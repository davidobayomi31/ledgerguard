package dev.obayomi.ledgerguard;

import java.math.BigDecimal;
import java.util.UUID;

import dev.obayomi.ledgerguard.ledger.LedgerEntryRepository;
import dev.obayomi.ledgerguard.support.ApiClient;
import dev.obayomi.ledgerguard.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rules enforced by the database itself, so even a buggy release can't break them. */
class LedgerInvariantTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LedgerEntryRepository entries;

	@Test
	void theWholeLedgerAlwaysSumsToZero() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		String a = api.openAccount(alice);
		String b = api.openAccount(api.newCustomerToken());
		api.deposit(alice, a, "250.00");
		api.transfer(alice, UUID.randomUUID().toString(), a, b, "99.99");

		assertThat(entries.sumOfAllEntries()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void ledgerEntriesCannotBeEditedOrDeleted() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		api.deposit(alice, api.openAccount(alice), "10.00");

		assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = amount * 2"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
			.hasMessageContaining("append-only");
	}

	@Test
	void unbalancedEntriesAreRejectedAtCommit() {
		ApiClient api = api();
		String alice = api.newCustomerToken();
		api.deposit(alice, api.openAccount(alice), "10.00");
		UUID someTransfer = jdbc.queryForObject("SELECT id FROM transfers LIMIT 1", UUID.class);
		UUID someAccount = jdbc.queryForObject("SELECT account_id FROM ledger_entries LIMIT 1", UUID.class);

		// A single entry with no matching opposite side: money appearing from nowhere.
		assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, amount) VALUES (?, ?, 5.00)",
				someTransfer, someAccount))
			.hasMessageContaining("do not balance");
	}
}
