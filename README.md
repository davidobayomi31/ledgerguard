# LedgerGuard

A payments API in **Java 21 + Spring Boot 4** that moves money the way banks do: a **double-entry ledger**, **idempotent** transfers that can be retried safely, and **row locking** so simultaneous requests can never overdraw an account.

![CI](https://github.com/davidobayomi31/ledgerguard/actions/workflows/ci.yml/badge.svg)

![LedgerGuard web app: accounts, transfers and a ledger statement](docs/dashboard.png)

> **Status:** Phase 2 of 4. The ledger and the fraud-review engine are done. Next up: async fraud checks and a cloud deployment (see [Roadmap](#roadmap)).

## Try it in 30 seconds

Start it (see [Run it](#run-it)), open **http://localhost:8080** and click **Try the demo, no sign-up**. You get a private sandbox with two accounts, a deposit, a payment from a second demo customer (Alex Chen), one transfer rejected for insufficient funds, and a $600 payment **held for fraud review**. Everything happens in one browser tab. Then:

1. Click **Send money** and pay Alex back, or move money between your own accounts. Both sides of a payment see who it came from and went to.
2. Under **People in this sandbox**, add more people (up to 7). Send money to anyone, then click **Switch** to see the payment arrive on their side.
3. Click **Retry this exact request**. The app resends the identical request with the same `Idempotency-Key`, and the server answers *replayed*: the money does not move twice.
4. Click an account to see its statement. Every line is a ledger entry, and the balance is their sum.
5. Click **Switch** on **Sam Rivera (fraud analyst)**. Approve or block the held $600, then switch back to see the result. (Or open **http://localhost:8080/?demo=analyst** to start there.)

![Fraud analyst view: review queue with risk score and reasons, and the audit log](docs/analyst.png)

The web app is plain HTML, CSS and JavaScript (`src/main/resources/static/`) calling the same REST API documented below. Developers can also use Swagger UI at `/swagger-ui.html`.

## What it does

- **Accounts:** customers open CAD accounts and view balances and statements.
- **Transfers:** send money between accounts with an `Idempotency-Key`. A transfer either **settles** or is **rejected** (for example `INSUFFICIENT_FUNDS`). Either way it is recorded.
- **Sandbox deposits:** add test money, simulating an incoming transfer from outside.
- **Fraud review:** every payment to another person is scored by rules. Risky ones are **held** (no money moves) until a fraud analyst **approves** or **blocks** them, and every decision lands in an append-only audit log.
- **Auth:** register and log in to get a JWT. Roles: `CUSTOMER`, `ANALYST`, `ADMIN`. Only analysts and admins can use the review queue.

## How it keeps money correct

| Problem | How LedgerGuard handles it | Where |
|---|---|---|
| Balances drifting or being edited | No balance column. A balance is the **sum of ledger entries**, and every transfer writes a debit and a matching credit. | `LedgerEntryRepository.balanceOf` |
| Half-written transfers | A Postgres **deferred constraint trigger** rejects any transaction whose entries don't sum to zero, even if the Java code has a bug. | `V1__ledger.sql` |
| Rewriting history | The ledger is **append-only**: the database rejects `UPDATE` and `DELETE` on entries. | `V1__ledger.sql` |
| Double charges when a client retries | `Idempotency-Key` header. A retry with the same key returns the original result (`200`, `Idempotent-Replayed: true`). The same key with a *different* request is refused (`422`). | `TransferService`, `RequestFingerprint` |
| Two requests spending the same money | `SELECT … FOR UPDATE` on the accounts, so transfers on one account run one at a time. | `AccountRepository.findByIdForUpdate` |
| Deadlocks (A→B and B→A at once) | Accounts are always locked in the **same order** (by id). | `TransferService.transfer` |
| Floating-point rounding | `BigDecimal` with exactly 2 decimals, and amounts sent in JSON as **strings** (`"40.25"`). | `Money` |
| Probing other people's accounts | Someone else's account returns `404`, not `403`. Login gives the same error for an unknown email and a wrong password. | `AccountService`, `AuthService` |

## How fraud review works

Each rule that fires adds points. **60 or more holds the payment.** No single signal is enough on its own: a first payment to someone new is normal, but a *large* first payment to someone new is what account-takeover fraud looks like.

| Signal | Points | Fires when |
|---|---|---|
| `LARGE_AMOUNT` | 35 | Amount is $500.00 or more |
| `NEW_PAYEE` | 35 | First payment of $200.00+ from this account to this recipient |
| `HIGH_VELOCITY` | 30 | 5+ payments from this account in the last 10 minutes |
| `DRAINS_ACCOUNT` | 25 | Sends 90%+ of the balance (and at least $100.00) |

Design decisions:

- **Rules are pure Java** (`FraudRules`): no database, so every boundary is unit-tested. `TransferService` gathers the facts while both accounts are locked, so the balance and recent payments can't change mid-decision.
- **Moving money between your own accounts is never scored.** There is no one to defraud.
- **A hold moves nothing:** a `HELD` transfer has no ledger entries. Approving re-checks the balance, because a hold doesn't reserve funds; if the customer spent the money meanwhile, the approval ends as `REJECTED`.
- **Two analysts can't both act on one transfer:** approve and block lock the transfer row (`SELECT … FOR UPDATE`); the second one gets `409 ALREADY_REVIEWED`.
- **The audit log is append-only in the database** (`V2__fraud_review.sql`), like the ledger: decisions can't be edited or deleted.
- **Each demo is a private sandbox.** Everyone created by one "Try the demo" click (customers and the analyst) shares a `sandbox_id`, and a sandbox analyst only sees, approves or blocks that sandbox's payments; anything else is `404`. Visitors trying the demo at the same time never see each other. An analyst with no sandbox sees every payment, as at a real bank (`V3__sandbox_scope.sql`).
- **Customers see the status, not the rules:** they get `HELD`/`BLOCKED`, while the score and signals are only on the analyst endpoints, so fraudsters can't tune around them.

## Tests

64 tests: JUnit 5 unit tests, plus integration tests that start the real app against a real Postgres using **Testcontainers**.

The concurrency tests are the most important ones:

- **50 simultaneous $10 transfers** from an account holding $100 → exactly 10 settle, 40 are rejected, and the balance ends at $0.00. This is repeated 5 rounds. With the row lock removed, the test fails, so it really detects the race.
- **20 simultaneous retries with one Idempotency-Key** → money moves exactly once.
- **50 transfers in opposite directions at once** → no deadlocks, and balances are unchanged.
- **10 analysts approve the same held payment at once** → exactly one succeeds, the other 9 get `409`, and the money moves once.
- **Ledger invariants:** the whole ledger sums to zero, entries can't be edited or deleted, and unbalanced entries are rejected at commit.
- **Fraud review:** every rule at its exact boundary, large + new payee is held with no ledger entries, customers get `403` on the queue, approve moves the money, block moves nothing, a transfer can be reviewed only once, approval after the money was spent is rejected, the audit log can't be edited, a demo analyst can't see or act on another sandbox's payments, customers never see the risk score or which rules fired, and the whole ledger still sums to zero after approvals, blocks and late rejections.

```bash
./mvnw verify          # needs Docker running (Testcontainers starts Postgres)
```
No Docker? Point the tests at any Postgres database instead:
```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/ledgerguard_test TEST_DATABASE_USERNAME=$USER ./mvnw verify
```

## Run it

**With Docker** (API + Postgres):
```bash
docker compose up --build
```
Then open http://localhost:8080 for the web app, or http://localhost:8080/swagger-ui.html for the API docs.

**Without Docker** (needs Java 21+ and a local Postgres):
```bash
createdb ledgerguard
DATABASE_URL=jdbc:postgresql://localhost:5432/ledgerguard DATABASE_USERNAME=$USER \
JWT_SECRET=any-string-of-at-least-32-characters ./mvnw spring-boot:run
```

| Variable | Meaning |
|---|---|
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://host:5432/db?sslmode=require` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Database login |
| `JWT_SECRET` | At least 32 characters; used to sign access tokens |
| `PORT` | HTTP port (default 8080) |

Flyway creates the schema on startup.

## Try it with curl

```bash
API=http://localhost:8080

# 1. Register and keep the token
TOKEN=$(curl -s -X POST $API/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"correct-horse-battery","fullName":"Alice"}' | jq -r .accessToken)

# 2. Open two accounts
A=$(curl -s -X POST $API/api/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"Chequing"}' | jq -r .id)
B=$(curl -s -X POST $API/api/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"Savings"}' | jq -r .id)

# 3. Add sandbox money
curl -s -X POST $API/api/accounts/$A/deposits -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"amount":"500.00"}'

# 4. Transfer, then send the exact same request again: the second one is a replay, not a second charge
KEY=$(uuidgen)
for i in 1 2; do
  curl -s -i -X POST $API/api/transfers -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
    -H 'Content-Type: application/json' -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":\"120.50\"}" | head -1
done
# HTTP/1.1 201
# HTTP/1.1 200   (Idempotent-Replayed: true)
```

## API

Full interactive docs are at `/swagger-ui.html`; the OpenAPI spec is at `/v3/api-docs`.

![LedgerGuard API docs (Swagger UI)](docs/swagger.png)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Create a customer and get a JWT |
| `POST` | `/api/auth/login` | Log in and get a JWT |
| `GET` | `/api/auth/me` | Current user |
| `POST` | `/api/accounts` | Open a CAD account |
| `GET` | `/api/accounts` | My accounts with balances |
| `GET` | `/api/accounts/{id}` | One account and its balance |
| `GET` | `/api/accounts/{id}/entries` | Statement (ledger entries, newest first, paginated) |
| `POST` | `/api/accounts/{id}/deposits` | Sandbox deposit (max 1,000.00), needs `Idempotency-Key` |
| `POST` | `/api/transfers` | Send money, needs `Idempotency-Key` |
| `GET` | `/api/transfers` | Recent transfers in or out of my accounts |
| `GET` | `/api/transfers/{id}` | One transfer |
| `GET` | `/api/reviews` | **Analyst:** held transfers with risk score and signals |
| `POST` | `/api/reviews/{id}/approve` | **Analyst:** release a held transfer (balance re-checked) |
| `POST` | `/api/reviews/{id}/block` | **Analyst:** stop a held transfer |
| `GET` | `/api/reviews/events` | **Analyst:** audit log, newest first |
| `POST` | `/api/sandbox/analysts` | Sandbox only: create an analyst login scoped to a `sandboxId` (off with `SANDBOX_ANALYST_SIGNUP=false`) |

Errors use [RFC 9457 problem details](https://www.rfc-editor.org/rfc/rfc9457) with a stable `code`:
```json
{ "status": 422, "detail": "This Idempotency-Key was already used for a different request.", "code": "IDEMPOTENCY_KEY_REUSED" }
```

## Tech stack

| Layer | Tech |
|---|---|
| Language / framework | Java 21, Spring Boot 4 (Web MVC, Data JPA, Security, Validation) |
| Database | PostgreSQL, Flyway migrations, Hibernate (schema validated against migrations) |
| Auth | Spring Security OAuth2 Resource Server, HS256 JWTs, BCrypt |
| API docs | springdoc-openapi (Swagger UI) |
| Tests | JUnit 5, AssertJ, Testcontainers |
| Delivery | Docker, Docker Compose, GitHub Actions |

## Roadmap

1. ✅ **Ledger core:** accounts, double-entry ledger, idempotent transfers, JWT auth, concurrency tests, Docker, CI
2. ✅ **Fraud engine:** rules (large amount, new payee, velocity, draining the account) produce a risk score. Risky transfers go to `HELD` and appear in an analyst review queue (approve or block), with every decision in an append-only audit log.
3. **Async + cloud:** fraud checks via AWS SQS. Deploy as a Docker image on AWS Lambda, with Postgres on Neon (all free tier).
4. **Dashboard + simulator:** richer analyst dashboard (filters, per-customer history) and a traffic generator that plants realistic fraud patterns. *The customer web app and a basic analyst view already exist.*
