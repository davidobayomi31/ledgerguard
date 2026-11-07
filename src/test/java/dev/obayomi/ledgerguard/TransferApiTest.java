package dev.obayomi.ledgerguard;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.obayomi.ledgerguard.support.ApiClient;
import dev.obayomi.ledgerguard.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiTest extends IntegrationTest {

	@Test
	void moneyMovesAndBothBalancesUpdate() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String bob = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(bob);
		api.deposit(alice, a, "100.00");

		ApiClient.Response r = api.transfer(alice, UUID.randomUUID().toString(), a, b, "40.25");

		assertThat(r.status()).isEqualTo(201);
		assertThat(r.get("status")).isEqualTo("SETTLED");
		assertThat(r.get("amount")).isEqualTo("40.25"); // money is a string in JSON, never a float
		assertThat(r.headers().getLocation()).isNotNull();
		assertThat(api.balance(alice, a)).isEqualByComparingTo("59.75");
		assertThat(api.balance(bob, b)).isEqualByComparingTo("40.25");

		// The statement shows the debit as a negative ledger entry.
		ApiClient.Response statement = api.get("/api/accounts/" + a + "/entries", alice);
		assertThat(statement.json().get("totalItems")).isEqualTo(2);
	}

	@Test
	void bothSidesSeeWhoTheMoneyCameFromAndWentTo() {
		ApiClient api = api();
		String alice = api.newCustomerToken("Alice Martin");
		String bob = api.newCustomerToken("Bob Osei");
		String a = api.openAccount(alice);
		String b = api.openAccount(bob);
		api.deposit(alice, a, "50.00");
		api.transfer(alice, UUID.randomUUID().toString(), a, b, "20.00");

		// Bob, the recipient, sees who paid him.
		Map<String, Object> received = api.get("/api/transfers", bob).list().get(0);
		assertThat(received.get("fromName")).isEqualTo("Alice Martin");
		assertThat(received.get("toName")).isEqualTo("Bob Osei");

		// Alice's sandbox deposit shows the platform as the sender, not a person.
		Map<String, Object> deposit = api.get("/api/transfers", alice).list().get(1);
		assertThat(deposit.get("type")).isEqualTo("DEPOSIT");
		assertThat(deposit.get("fromName")).isEqualTo("LedgerGuard sandbox");
	}

	@Test
	void retryingADepositWithTheSameKeyAddsTheMoneyOnce() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String key = UUID.randomUUID().toString();
		String path = "/api/accounts/" + a + "/deposits";

		ApiClient.Response first = api.post(path, alice, key, Map.of("amount", "100.00"));
		ApiClient.Response retry = api.post(path, alice, key, Map.of("amount", "100.00"));

		assertThat(first.status()).isEqualTo(201);
		assertThat(retry.status()).isEqualTo(200);
		assertThat(retry.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
		assertThat(retry.get("id")).isEqualTo(first.get("id"));
		assertThat(api.balance(alice, a)).isEqualByComparingTo("100.00");
	}

	@Test
	void onlyThePeopleInvolvedCanSeeATransfer() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String bob = api.newCustomerToken();
		String mallory = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(bob);
		api.deposit(alice, a, "50.00");
		String id = api.transfer(alice, UUID.randomUUID().toString(), a, b, "20.00").get("id");

		assertThat(api.get("/api/transfers/" + id, alice).status()).isEqualTo(200); // sender
		assertThat(api.get("/api/transfers/" + id, bob).status()).isEqualTo(200); // recipient
		// Anyone else gets 404, not 403, so guessing ids can't even confirm a transfer exists.
		ApiClient.Response snoop = api.get("/api/transfers/" + id, mallory);
		assertThat(snoop.status()).isEqualTo(404);
		assertThat(snoop.get("code")).isEqualTo("TRANSFER_NOT_FOUND");
		assertThat(api.get("/api/transfers/" + UUID.randomUUID(), mallory).status()).isEqualTo(404);
	}

	@Test
	void theStatementPagesThroughEntriesNewestFirst() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		api.deposit(alice, a, "10.00");
		api.deposit(alice, a, "20.00");
		api.deposit(alice, a, "30.00");
		String path = "/api/accounts/" + a + "/entries?size=2&page=";

		Map<String, Object> page0 = api.get(path + 0, alice).json();
		Map<String, Object> page1 = api.get(path + 1, alice).json();

		assertThat(page0.get("totalItems")).isEqualTo(3);
		assertThat(amounts(page0)).containsExactly("30.00", "20.00");
		assertThat(amounts(page1)).containsExactly("10.00");
		assertThat(amounts(api.get(path + 2, alice).json())).isEmpty();
	}

	private static List<Object> amounts(Map<String, Object> page) {
		return ((List<?>) page.get("items")).stream().map(e -> ((Map<?, ?>) e).get("amount")).map(Object.class::cast).toList();
	}

	@Test
	void insufficientFundsIsRecordedAsRejectedAndMovesNothing() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());
		api.deposit(alice, a, "10.00");

		ApiClient.Response r = api.transfer(alice, UUID.randomUUID().toString(), a, b, "10.01");

		assertThat(r.status()).isEqualTo(201);
		assertThat(r.get("status")).isEqualTo("REJECTED");
		assertThat(r.get("failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(api.balance(alice, a)).isEqualByComparingTo("10.00");
	}

	@Test
	void retryingWithTheSameIdempotencyKeyDoesNotChargeTwice() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());
		api.deposit(alice, a, "100.00");
		String key = UUID.randomUUID().toString();

		ApiClient.Response first = api.transfer(alice, key, a, b, "30.00");
		ApiClient.Response retry = api.transfer(alice, key, a, b, "30.00");

		assertThat(first.status()).isEqualTo(201);
		assertThat(retry.status()).isEqualTo(200);
		assertThat(retry.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
		assertThat(retry.get("id")).isEqualTo(first.get("id"));
		assertThat(api.balance(alice, a)).isEqualByComparingTo("70.00");
	}

	@Test
	void reusingAnIdempotencyKeyForADifferentRequestIsRefused() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());
		api.deposit(alice, a, "100.00");
		String key = UUID.randomUUID().toString();

		api.transfer(alice, key, a, b, "30.00");
		ApiClient.Response different = api.transfer(alice, key, a, b, "31.00");

		assertThat(different.status()).isEqualTo(422);
		assertThat(different.get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
		assertThat(api.balance(alice, a)).isEqualByComparingTo("70.00");
	}

	@Test
	void cannotSendFromSomeoneElsesAccountOrSeeIt() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String mallory = api.newCustomerToken();
		String a = api.openAccount(alice);
		String m = api.openAccount(mallory);
		api.deposit(alice, a, "100.00");

		ApiClient.Response steal = api.transfer(mallory, UUID.randomUUID().toString(), a, m, "50.00");
		assertThat(steal.status()).isEqualTo(404);
		assertThat(api.get("/api/accounts/" + a, mallory).status()).isEqualTo(404);
		assertThat(api.balance(alice, a)).isEqualByComparingTo("100.00");
	}

	@Test
	void invalidRequestsAreRejectedWithProblemDetails() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());

		assertThat(api.transfer(alice, UUID.randomUUID().toString(), a, b, "-5").get("code")).isEqualTo("INVALID_AMOUNT");
		assertThat(api.transfer(alice, UUID.randomUUID().toString(), a, b, "1.001").get("code")).isEqualTo("INVALID_AMOUNT");
		assertThat(api.transfer(alice, UUID.randomUUID().toString(), a, a, "1.00").get("code")).isEqualTo("SAME_ACCOUNT");
		assertThat(api.transfer(alice, "short", a, b, "1.00").get("code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
		assertThat(api.transfer(alice, null, a, b, "1.00").status()).isEqualTo(400);
		assertThat(api.post("/api/accounts/" + a + "/deposits", alice, UUID.randomUUID().toString(),
				Map.of("amount", "5000.00")).get("code")).isEqualTo("DEPOSIT_LIMIT");
	}
}
