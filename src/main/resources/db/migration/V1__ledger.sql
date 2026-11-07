-- LedgerGuard schema, version 1: users, accounts, transfers and a double-entry ledger.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    full_name     TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('CUSTOMER', 'ANALYST', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_key ON users (lower(email));

-- CUSTOMER accounts belong to a user. SYSTEM accounts belong to the platform
-- (e.g. the funding account that sandbox deposits come from).
CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    owner_id   UUID REFERENCES users (id),
    kind       TEXT        NOT NULL CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    name       TEXT        NOT NULL,
    currency   VARCHAR(3)  NOT NULL DEFAULT 'CAD' CHECK (currency ~ '^[A-Z]{3}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'CUSTOMER') = (owner_id IS NOT NULL))
);
CREATE INDEX accounts_owner_idx ON accounts (owner_id);

-- One row per money movement request. (initiated_by, idempotency_key) is unique,
-- so retrying the same request can never move money twice.
CREATE TABLE transfers (
    id                  UUID PRIMARY KEY,
    initiated_by        UUID          NOT NULL REFERENCES users (id),
    idempotency_key     TEXT          NOT NULL,
    request_fingerprint TEXT          NOT NULL,
    type                TEXT          NOT NULL CHECK (type IN ('TRANSFER', 'DEPOSIT')),
    from_account_id     UUID          NOT NULL REFERENCES accounts (id),
    to_account_id       UUID          NOT NULL REFERENCES accounts (id),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status              TEXT          NOT NULL CHECK (status IN ('SETTLED', 'REJECTED')),
    failure_reason      TEXT,
    memo                TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (from_account_id <> to_account_id),
    UNIQUE (initiated_by, idempotency_key)
);
CREATE INDEX transfers_from_idx ON transfers (from_account_id, created_at DESC);
CREATE INDEX transfers_to_idx ON transfers (to_account_id, created_at DESC);

-- The ledger. A balance is never stored or edited: it is the sum of an account's entries.
-- Every settled transfer writes one negative (debit) and one positive (credit) entry.
CREATE TABLE ledger_entries (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id UUID           NOT NULL REFERENCES transfers (id),
    account_id  UUID           NOT NULL REFERENCES accounts (id),
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount <> 0),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id);
CREATE INDEX ledger_entries_transfer_idx ON ledger_entries (transfer_id);

-- Rule 1: the ledger is append-only. History can be corrected only by new entries.
CREATE FUNCTION forbid_ledger_changes() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END
$$;
CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_changes();

-- Rule 2: every transfer's entries must sum to zero. Checked at COMMIT, so a
-- half-written transfer can never be saved, even if the application has a bug.
CREATE FUNCTION check_transfer_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (SELECT sum(amount) FROM ledger_entries WHERE transfer_id = NEW.transfer_id) <> 0 THEN
        RAISE EXCEPTION 'ledger entries for transfer % do not balance', NEW.transfer_id;
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transfer_balanced();

INSERT INTO accounts (id, owner_id, kind, name, currency)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM', 'Sandbox funding', 'CAD');
