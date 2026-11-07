package dev.obayomi.ledgerguard.auth;

import dev.obayomi.ledgerguard.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;

	AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	/** Public sign-up always creates a CUSTOMER. Analysts and admins are promoted separately. */
	@Transactional
	public User register(String email, String password, String fullName) {
		String normalized = email.trim().toLowerCase();
		if (users.existsByEmailIgnoreCase(normalized)) {
			throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
		}
		return users.save(new User(normalized, passwordEncoder.encode(password), fullName.trim(), Role.CUSTOMER));
	}

	@Transactional(readOnly = true)
	public User authenticate(String email, String password) {
		// Same error for "no such user" and "wrong password", so attackers can't probe which emails exist.
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
