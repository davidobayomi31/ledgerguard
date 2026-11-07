package dev.obayomi.ledgerguard.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfers")
public class Transfer {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "initiated_by", nullable = false, updatable = false)
	private UUID initiatedBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private TransferType type;

	@Column(name = "from_account_id", nullable = false, updatable = false)
	private UUID fromAccountId;

	@Column(name = "to_account_id", nullable = false, updatable = false)
	private UUID toAccountId;

	@Column(nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, updatable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransferStatus status;

	@Column(name = "failure_reason")
	private String failureReason;

	private String memo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Transfer() {}

	Transfer(UUID initiatedBy, String idempotencyKey, String requestFingerprint, TransferType type,
			UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency, String memo) {
		this.initiatedBy = initiatedBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.type = type;
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.amount = amount;
		this.currency = currency;
		this.memo = memo;
		this.createdAt = Instant.now();
	}

	void settle() {
		this.status = TransferStatus.SETTLED;
	}

	void reject(String reason) {
		this.status = TransferStatus.REJECTED;
		this.failureReason = reason;
	}

	public UUID getId() { return id; }
	public UUID getInitiatedBy() { return initiatedBy; }
	public String getIdempotencyKey() { return idempotencyKey; }
	public String getRequestFingerprint() { return requestFingerprint; }
	public TransferType getType() { return type; }
	public UUID getFromAccountId() { return fromAccountId; }
	public UUID getToAccountId() { return toAccountId; }
	public BigDecimal getAmount() { return amount; }
	public String getCurrency() { return currency; }
	public TransferStatus getStatus() { return status; }
	public String getFailureReason() { return failureReason; }
	public String getMemo() { return memo; }
	public Instant getCreatedAt() { return createdAt; }
}
