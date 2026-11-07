package dev.obayomi.ledgerguard;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.obayomi.ledgerguard.support.ApiClient;
import dev.obayomi.ledgerguard.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 2: risky transfers are held, analysts approve or block them, and every decision is logged. */
class FraudReviewApiTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	/** Alice has $1,000 and sends $600 to Bob for the first time: large + new payee = held. */
	private record Setup(ApiClient api, String alice, String a, String bob, String b, String heldId) {}

	private Setup heldPayment() {
		ApiClient api = api();
		String alice = api.newCustomerToken("Alice Martin");
		String bob = api.newCustomerToken("Bob Osei");
		String a = api.openAccount(alice);
		String b = api.openAccount(bob);
		api.deposit(alice, a, "1000.00");
		ApiClient.Response r = api.transfer(alice, UUID.randomUUID().toString(), a, b, "600.00");
		assertThat(r.status()).isEqualTo(201);
		assertThat(r.get("status")).isEqualTo("HELD");
		return new Setup(api, alice, a, bob, b, r.get("id"));
	}

	private static Map<String, Object> decide(ApiClient api, String analyst, String id, String action, String note) {
		ApiClient.Response r = api.post("/api/reviews/" + id + "/" + action, analyst, null, Map.of("note", note));
		assertThat(r.status()).as(action + " " + r).isEqualTo(200);
		return r.json();
	}

	@Test
	void aRiskyTransferIsHeldAndMovesNoMoney() {
		Setup s = heldPayment();
		assertThat(s.api().balance(s.alice(), s.a())).isEqualByComparingTo("1000.00");
		assertThat(s.api().balance(s.bob(), s.b())).isEqualByComparingTo("0.00");
		// No ledger entries exist for a held transfer.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?::uuid", Long.class,
				s.heldId())).isZero();
	}

	@Test
	void movingMoneyBetweenYourOwnAccountsIsNeverHeld() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String chequing = api.openAccount(alice);
		String savings = api.openAccount(alice);
		api.deposit(alice, chequing, "1000.00");
		ApiClient.Response r = api.transfer(alice, UUID.randomUUID().toString(), chequing, savings, "950.00");
		assertThat(r.get("status")).isEqualTo("SETTLED");
	}

	@Test
	void customersCannotSeeOrUseTheReviewQueue() {
		Setup s = heldPayment();
		assertThat(s.api().get("/api/reviews", s.alice()).status()).isEqualTo(403);
		assertThat(s.api().post("/api/reviews/" + s.heldId() + "/approve", s.alice(), null, Map.of()).status()).isEqualTo(403);
	}

	@Test
	void analystsSeeWhyATransferWasHeld() {
		// Its own sandbox, so the queue holds just this payment however many earlier runs left in the database.
		ApiClient api = api();
		UUID sandbox = UUID.randomUUID();
		String heldId = heldPaymentIn(api, sandbox);
		String analyst = api.newAnalystToken(sandbox);

		List<Map<String, Object>> queue = api.get("/api/reviews", analyst).list();
		assertThat(queue).hasSize(1);
		Map<String, Object> item = queue.get(0);
		assertThat(((Map<?, ?>) item.get("transfer")).get("id")).isEqualTo(heldId);
		assertThat(item.get("riskScore")).isEqualTo(70);
		List<Object> codes = ((List<?>) item.get("signals")).stream().map(sig -> ((Map<?, ?>) sig).get("code")).map(Object.class::cast).toList();
		assertThat(codes).containsExactly("LARGE_AMOUNT", "NEW_PAYEE");
		assertThat(((Map<?, ?>) item.get("transfer")).get("fromName")).isEqualTo("Demo visitor");
	}

	@Test
	void approvingMovesTheMoneyAndIsLogged() {
		Setup s = heldPayment();
		String analyst = s.api().newAnalystToken();

		Map<String, Object> result = decide(s.api(), analyst, s.heldId(), "approve", "Called Alice, confirmed");
		assertThat(((Map<?, ?>) result.get("transfer")).get("status")).isEqualTo("SETTLED");
		assertThat(s.api().balance(s.alice(), s.a())).isEqualByComparingTo("400.00");
		assertThat(s.api().balance(s.bob(), s.b())).isEqualByComparingTo("600.00");

		List<Map<String, Object>> log = s.api().get("/api/reviews/events?limit=200", analyst).list().stream()
			.filter(e -> s.heldId().equals(e.get("transferId"))).toList();
		assertThat(log).extracting(e -> e.get("action")).containsExactly("APPROVED", "HELD"); // newest first
		assertThat(log.get(0).get("actorName")).isEqualTo("Sam Rivera");
		assertThat(log.get(0).get("note")).isEqualTo("Called Alice, confirmed");
		assertThat(log.get(1).get("actorName")).isEqualTo("Fraud rules (automatic)");
	}

	@Test
	void blockingMovesNothing() {
		Setup s = heldPayment();
		String analyst = s.api().newAnalystToken();

		Map<String, Object> result = decide(s.api(), analyst, s.heldId(), "block", "Account takeover pattern");
		assertThat(((Map<?, ?>) result.get("transfer")).get("status")).isEqualTo("BLOCKED");
		assertThat(s.api().balance(s.alice(), s.a())).isEqualByComparingTo("1000.00");
		assertThat(s.api().balance(s.bob(), s.b())).isEqualByComparingTo("0.00");
	}

	@Test
	void aTransferCanOnlyBeReviewedOnce() {
		Setup s = heldPayment();
		String analyst = s.api().newAnalystToken();
		decide(s.api(), analyst, s.heldId(), "block", "No");

		ApiClient.Response again = s.api().post("/api/reviews/" + s.heldId() + "/approve", analyst, null, Map.of());
		assertThat(again.status()).isEqualTo(409);
		assertThat(again.get("code")).isEqualTo("ALREADY_REVIEWED");
		assertThat(s.api().balance(s.bob(), s.b())).isEqualByComparingTo("0.00");
	}

	@Test
	void approvalChecksTheBalanceAgainBecauseAHoldDoesNotReserveMoney() {
		Setup s = heldPayment();
		// While the $600 waits, Alice moves $900 to her own savings (allowed, and never held).
		String savings = s.api().openAccount(s.alice());
		s.api().transfer(s.alice(), UUID.randomUUID().toString(), s.a(), savings, "900.00");

		Map<String, Object> result = decide(s.api(), s.api().newAnalystToken(), s.heldId(), "approve", "Looks fine");
		Map<?, ?> transfer = (Map<?, ?>) result.get("transfer");
		assertThat(transfer.get("status")).isEqualTo("REJECTED");
		assertThat(transfer.get("failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(s.api().balance(s.alice(), s.a())).isEqualByComparingTo("100.00");
	}

	@Test
	void retryingAHeldTransferReplaysItInsteadOfCreatingAnother() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());
		api.deposit(alice, a, "1000.00");
		String key = UUID.randomUUID().toString();

		ApiClient.Response first = api.transfer(alice, key, a, b, "700.00");
		ApiClient.Response retry = api.transfer(alice, key, a, b, "700.00");
		assertThat(first.get("status")).isEqualTo("HELD");
		assertThat(retry.status()).isEqualTo(200);
		assertThat(retry.get("id")).isEqualTo(first.get("id"));
	}

	@Test
	void manyQuickPaymentsPlusANewPayeeAreHeld() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());
		String c = api.openAccount(api.newCustomerToken());
		api.deposit(alice, a, "1000.00");
		for (int i = 0; i < 5; i++) {
			assertThat(api.transfer(alice, UUID.randomUUID().toString(), a, b, "10.00").get("status")).isEqualTo("SETTLED");
		}
		// Sixth payment in minutes, $250 to someone new: velocity (30) + new payee (35) = 65.
		assertThat(api.transfer(alice, UUID.randomUUID().toString(), a, c, "250.00").get("status")).isEqualTo("HELD");
	}

	@Test
	void customersNeverSeeTheRiskScoreOrWhichRulesFired() {
		Setup s = heldPayment();
		// The transfer as the customer sees it: right after sending, by id, and in their list.
		List<Map<String, Object>> views = List.of(
				s.api().get("/api/transfers/" + s.heldId(), s.alice()).json(),
				s.api().get("/api/transfers", s.alice()).list().get(0));
		for (Map<String, Object> view : views) {
			assertThat(view.get("status")).isEqualTo("HELD");
			assertThat(view).doesNotContainKeys("riskScore", "riskSignals", "signals", "reviewNote", "reviewedBy");
		}
		assertThat(s.api().transfer(s.alice(), UUID.randomUUID().toString(), s.a(), s.b(), "650.00").json())
			.doesNotContainKeys("riskScore", "riskSignals", "signals");
	}

	@Test
	void fraudReviewNeverCreatesOrLosesMoney() {
		String analyst = api().newAnalystToken();
		Setup approved = heldPayment();
		decide(approved.api(), analyst, approved.heldId(), "approve", "ok");
		Setup blocked = heldPayment();
		decide(blocked.api(), analyst, blocked.heldId(), "block", "no");
		Setup spent = heldPayment();
		String savings = spent.api().openAccount(spent.alice());
		spent.api().transfer(spent.alice(), UUID.randomUUID().toString(), spent.a(), savings, "900.00");
		decide(spent.api(), analyst, spent.heldId(), "approve", "too late");

		// Every debit has a matching credit, across every account in the system.
		assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", java.math.BigDecimal.class))
			.isEqualByComparingTo("0");
		// Only the approved payment wrote ledger entries; blocked and rejected-on-approval wrote none.
		for (Setup s : List.of(approved, blocked, spent)) {
			long expected = s == approved ? 2 : 0;
			assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?::uuid", Long.class,
					s.heldId())).isEqualTo(expected);
		}
		assertThat(approved.api().balance(approved.alice(), approved.a())).isEqualByComparingTo("400.00");
		assertThat(blocked.api().balance(blocked.alice(), blocked.a())).isEqualByComparingTo("1000.00");
		assertThat(spent.api().balance(spent.alice(), spent.a())).isEqualByComparingTo("100.00");
	}

	@Test
	void eachDemoSandboxOnlySeesAndReviewsItsOwnPayments() {
		ApiClient api = api();
		UUID sandboxA = UUID.randomUUID();
		UUID sandboxB = UUID.randomUUID();
		String heldA = heldPaymentIn(api, sandboxA);
		String heldB = heldPaymentIn(api, sandboxB);
		String analystA = api.newAnalystToken(sandboxA);

		List<Object> queueA = api.get("/api/reviews", analystA).list().stream()
			.map(i -> ((Map<?, ?>) i.get("transfer")).get("id")).map(Object.class::cast).toList();
		assertThat(queueA).containsExactly(heldA);

		// Sandbox A's analyst can't act on sandbox B's payment, and can't even tell it exists.
		ApiClient.Response crossApprove = api.post("/api/reviews/" + heldB + "/approve", analystA, null, Map.of());
		assertThat(crossApprove.status()).isEqualTo(404);
		ApiClient.Response crossBlock = api.post("/api/reviews/" + heldB + "/block", analystA, null, Map.of());
		assertThat(crossBlock.status()).isEqualTo(404);

		assertThat(api.get("/api/reviews/events?limit=200", analystA).list())
			.allSatisfy(e -> assertThat(e.get("transferId")).isEqualTo(heldA));

		// An analyst with no sandbox (a real bank's) can review any sandbox's payment.
		String bankAnalyst = api.newAnalystToken();
		assertThat(api.post("/api/reviews/" + heldB + "/block", bankAnalyst, null, Map.of()).status()).isEqualTo(200);
	}

	private static String heldPaymentIn(ApiClient api, UUID sandbox) {
		String alice = api.newCustomerToken("Demo visitor", sandbox);
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken("Alex Chen", sandbox));
		api.deposit(alice, a, "1000.00");
		ApiClient.Response r = api.transfer(alice, UUID.randomUUID().toString(), a, b, "600.00");
		assertThat(r.get("status")).isEqualTo("HELD");
		return r.get("id");
	}

	@Test
	void theAuditLogCannotBeEditedOrDeleted() {
		heldPayment();
		assertThatThrownBy(() -> jdbc.update("UPDATE review_events SET note = 'covered up'"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM review_events"))
			.hasMessageContaining("append-only");
	}
}
