package dev.obayomi.ledgerguard.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line in the double-entry ledger: a signed change to one account.
 * Negative = money leaving (debit), positive = money arriving (credit).
 * Rows are immutable; the database rejects UPDATE and DELETE.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "transfer_id", nullable = false, updatable = false)
	private UUID transferId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected LedgerEntry() {}

	public LedgerEntry(UUID transferId, UUID accountId, BigDecimal amount) {
		this.transferId = transferId;
		this.accountId = accountId;
		this.amount = amount;
		this.createdAt = Instant.now();
	}

	public Long getId() { return id; }
	public UUID getTransferId() { return transferId; }
	public UUID getAccountId() { return accountId; }
	public BigDecimal getAmount() { return amount; }
	public Instant getCreatedAt() { return createdAt; }
}
