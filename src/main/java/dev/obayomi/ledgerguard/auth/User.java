package dev.obayomi.ledgerguard.auth;

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
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** Set for demo users only: everyone from one "Try the demo" click shares it. See V3__sandbox_scope.sql. */
	@Column(name = "sandbox_id", updatable = false)
	private UUID sandboxId;

	protected User() {}

	public User(String email, String passwordHash, String fullName, Role role) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.fullName = fullName;
		this.role = role;
		this.createdAt = Instant.now();
	}

	public User(String email, String passwordHash, String fullName, Role role, UUID sandboxId) {
		this(email, passwordHash, fullName, role);
		this.sandboxId = sandboxId;
	}

	public UUID getId() { return id; }
	public String getEmail() { return email; }
	public String getPasswordHash() { return passwordHash; }
	public String getFullName() { return fullName; }
	public Role getRole() { return role; }
	public Instant getCreatedAt() { return createdAt; }
	public UUID getSandboxId() { return sandboxId; }
}
