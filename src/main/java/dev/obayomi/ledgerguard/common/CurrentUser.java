package dev.obayomi.ledgerguard.common;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads who is calling from their verified access token. */
public record CurrentUser(UUID id, String role) {

	public static CurrentUser from(Jwt jwt) {
		return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"));
	}

	/** Analysts and admins may look at any account, e.g. to investigate fraud. */
	public boolean canViewAllAccounts() {
		return "ANALYST".equals(role) || "ADMIN".equals(role);
	}
}
