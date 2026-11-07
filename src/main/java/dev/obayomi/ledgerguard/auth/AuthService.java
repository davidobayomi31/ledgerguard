package dev.obayomi.ledgerguard.auth;

import dev.obayomi.ledgerguard.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	/** BCrypt only reads the first 72 bytes; Spring Security rejects anything longer with an exception. */
	static final int BCRYPT_MAX_BYTES = 72;

	private static boolean tooLongForBcrypt(String password) {
		return password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
	}

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;

	AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	/** Public sign-up always creates a CUSTOMER. Analysts and admins are promoted separately. */
	@Transactional
	public User register(String email, String password, String fullName) {
		return register(email, password, fullName, null);
	}

	/** {@code sandboxId}: optional, groups demo users so their analyst only sees them. */
	@Transactional
	public User register(String email, String password, String fullName, java.util.UUID sandboxId) {
		String normalized = email.trim().toLowerCase();
		if (tooLongForBcrypt(password)) {
			throw ApiException.badRequest("PASSWORD_TOO_LONG",
					"Password must be at most " + BCRYPT_MAX_BYTES + " bytes (about 72 letters).");
		}
		if (users.existsByEmailIgnoreCase(normalized)) {
			throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
		}
		return users.save(new User(normalized, passwordEncoder.encode(password), fullName.trim(), Role.CUSTOMER, sandboxId));
	}

	/** Sandbox only: an analyst with a random email and an unusable password; they log in with the returned token. */
	@Transactional
	public User registerSandboxAnalyst(String fullName, java.util.UUID sandboxId) {
		String email = "analyst-" + java.util.UUID.randomUUID() + "@sandbox.ledgerguard.dev";
		String unusable = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
		return users.save(new User(email, unusable, fullName.trim(), Role.ANALYST, sandboxId));
	}

	@Transactional(readOnly = true)
	public User authenticate(String email, String password) {
		// Same error for "no such user" and "wrong password", so attackers can't probe which emails exist.
		// A password too long for BCrypt can't belong to any account, so it is just a wrong password.
		if (tooLongForBcrypt(password)) {
			throw ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect.");
		}
		return users.findByEmailIgnoreCase(email.trim())
			.filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
			.orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect."));
	}

	@Transactional(readOnly = true)
	public User get(java.util.UUID id) {
		return users.findById(id)
			.orElseThrow(() -> ApiException.unauthorized("UNKNOWN_USER", "This account no longer exists."));
	}
}
