package dev.obayomi.ledgerguard.transfer;

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

/** One line in the fraud audit log. The database rejects edits and deletes (see V2__fraud_review.sql). */
@Entity
@Table(name = "review_events")
public class ReviewEvent {

	public enum Action { HELD, APPROVED, BLOCKED, REJECTED_ON_APPROVAL }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "transfer_id", nullable = false, updatable = false)
	private UUID transferId;

	/** Null when the system acted (the fraud rules holding a transfer). */
	@Column(name = "actor_id", updatable = false)
	private UUID actorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Action action;

	@Column(updatable = false)
	private String note;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ReviewEvent() {}

	ReviewEvent(UUID transferId, UUID actorId, Action action, String note) {
		this.transferId = transferId;
		this.actorId = actorId;
		this.action = action;
		this.note = note;
		this.createdAt = Instant.now();
	}

	public Long getId() { return id; }
	public UUID getTransferId() { return transferId; }
	public UUID getActorId() { return actorId; }
	public Action getAction() { return action; }
	public String getNote() { return note; }
	public Instant getCreatedAt() { return createdAt; }
}
