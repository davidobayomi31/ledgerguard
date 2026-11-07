package dev.obayomi.ledgerguard;

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
