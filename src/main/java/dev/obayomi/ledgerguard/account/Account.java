package dev.obayomi.ledgerguard.account;

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

/** An account has no balance column on purpose: see LedgerEntryRepository#balanceOf. */
@Entity
@Table(name = "accounts")
public class Account {

	/** Platform account that sandbox deposits are funded from. Seeded by Flyway. */
	public static final UUID SANDBOX_FUNDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "owner_id", updatable = false)
	private UUID ownerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AccountKind kind;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Account() {}

	public Account(UUID ownerId, String name, String currency) {
		this.ownerId = ownerId;
		this.kind = AccountKind.CUSTOMER;
		this.name = name;
		this.currency = currency;
		this.createdAt = Instant.now();
	}

	public boolean isOwnedBy(UUID userId) {
		return kind == AccountKind.CUSTOMER && userId.equals(ownerId);
	}

	public UUID getId() { return id; }
	public UUID getOwnerId() { return ownerId; }
	public AccountKind getKind() { return kind; }
	public String getName() { return name; }
	public String getCurrency() { return currency; }
	public Instant getCreatedAt() { return createdAt; }
}
