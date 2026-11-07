package dev.obayomi.ledgerguard.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	/** An account's balance is derived from its entries, never stored. */
	@Query(value = "SELECT coalesce(sum(amount), 0) FROM ledger_entries WHERE account_id = :accountId", nativeQuery = true)
	BigDecimal balanceOf(@Param("accountId") UUID accountId);

	/** Double-entry invariant: across the whole ledger this must always be exactly zero. */
	@Query(value = "SELECT coalesce(sum(amount), 0) FROM ledger_entries", nativeQuery = true)
	BigDecimal sumOfAllEntries();

	Page<LedgerEntry> findByAccountIdOrderByIdDesc(UUID accountId, Pageable pageable);
}
