package dev.obayomi.ledgerguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.obayomi.ledgerguard.support.ApiClient;
import dev.obayomi.ledgerguard.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests that matter most for a payments system: what happens when many requests
 * hit the same account at the same moment.
 */
class ConcurrencyTest extends IntegrationTest {

	@Test
	void fiftySimultaneousTransfersCannotOverdrawAnAccount() throws Exception {
		ApiClient api = api();
		// Races are probabilistic, so run the scenario several times. Verified: with the row
		// lock removed, this test fails (the account goes negative or an extra transfer settles).
		for (int round = 1; round <= 5; round++) {
			String alice = api.newCustomerToken();
			String a = api.openAccount(alice);
			String b = api.openAccount(api.newCustomerToken());
			api.deposit(alice, a, "100.00");

			// 50 transfers of $10 from an account holding $100: exactly 10 can succeed.
			List<ApiClient.Response> results = runAtOnce(50,
					i -> api.transfer(alice, UUID.randomUUID().toString(), a, b, "10.00"));

			long settled = results.stream().filter(r -> "SETTLED".equals(r.get("status"))).count();
			long rejected = results.stream().filter(r -> "REJECTED".equals(r.get("status"))).count();
			assertThat(settled).as("settled in round %d", round).isEqualTo(10);
			assertThat(rejected).as("rejected in round %d", round).isEqualTo(40);
			assertThat(api.balance(alice, a)).as("balance in round %d", round).isEqualByComparingTo("0.00");
		}
	}

	@Test
	void twentySimultaneousRetriesWithOneKeyMoveMoneyOnce() throws Exception {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());
		api.deposit(alice, a, "100.00");
		String key = UUID.randomUUID().toString();

		List<ApiClient.Response> results = runAtOnce(20, i -> api.transfer(alice, key, a, b, "5.00"));

		assertThat(results).extracting(ApiClient.Response::status).containsOnly(201, 200);
		assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
		assertThat(results).extracting(r -> r.get("id")).containsOnly(results.get(0).get("id"));
		assertThat(api.balance(alice, a)).isEqualByComparingTo("95.00");
	}

	@Test
	void transfersInOppositeDirectionsDoNotDeadlock() throws Exception {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String bob = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(bob);
		api.deposit(alice, a, "100.00");
		api.deposit(bob, b, "100.00");

		// Half go A→B, half go B→A. Without ordered locking, Postgres would detect deadlocks and abort some.
		List<ApiClient.Response> results = runAtOnce(50, i -> i % 2 == 0
				? api.transfer(alice, UUID.randomUUID().toString(), a, b, "1.00")
				: api.transfer(bob, UUID.randomUUID().toString(), b, a, "1.00"));

		assertThat(results).extracting(r -> r.get("status")).containsOnly("SETTLED");
		assertThat(api.balance(alice, a)).isEqualByComparingTo("100.00");
		assertThat(api.balance(bob, b)).isEqualByComparingTo("100.00");
	}

	@Test
	void analystsApprovingTheSameHeldPaymentAtOnceMoveTheMoneyOnlyOnce() throws Exception {
		ApiClient api = api();
		// $500 to someone new is held (large + new payee). Alice has enough to cover it twice,
		// so without the row lock on the transfer, a double approval would visibly move $1,000.
		for (int round = 1; round <= 3; round++) {
			String alice = api.newCustomerToken();
			String bob = api.newCustomerToken();
			String a = api.openAccount(alice);
			String b = api.openAccount(bob);
			api.deposit(alice, a, "1000.00");
			api.deposit(alice, a, "1000.00");
			String held = api.transfer(alice, UUID.randomUUID().toString(), a, b, "500.00").get("id");
			List<String> analysts = new ArrayList<>();
			for (int i = 0; i < 10; i++) analysts.add(api.newAnalystToken());

			List<ApiClient.Response> results = runAtOnce(10,
					i -> api.post("/api/reviews/" + held + "/approve", analysts.get(i), null, Map.of()));

			assertThat(results).filteredOn(r -> r.status() == 200).as("round %d", round).hasSize(1);
			assertThat(results).filteredOn(r -> r.status() == 409).as("round %d", round).hasSize(9);
			assertThat(api.balance(alice, a)).as("round %d", round).isEqualByComparingTo("1500.00");
			assertThat(api.balance(bob, b)).as("round %d", round).isEqualByComparingTo("500.00");
			assertThat(api.get("/api/transfers/" + held, alice).get("status")).isEqualTo("SETTLED");
		}
	}

	interface Call {
		ApiClient.Response run(int i) throws Exception;
	}

	/** Releases all requests at the same instant to maximise contention. */
	private static List<ApiClient.Response> runAtOnce(int count, Call call) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<ApiClient.Response>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int n = i;
				Callable<ApiClient.Response> task = () -> {
					go.await();
					return call.run(n);
				};
				futures.add(pool.submit(task));
			}
			go.countDown();
			List<ApiClient.Response> results = new ArrayList<>();
			for (Future<ApiClient.Response> f : futures) {
				results.add(f.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}
}
