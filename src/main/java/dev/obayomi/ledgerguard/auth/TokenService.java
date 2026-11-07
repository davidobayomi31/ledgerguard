package dev.obayomi.ledgerguard.auth;

import java.time.Instant;

import dev.obayomi.ledgerguard.config.LedgerguardProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues signed access tokens. The subject is the user id; the role claim drives authorization. */
@Service
public class TokenService {

	public record IssuedToken(String value, Instant expiresAt) {}

	private final JwtEncoder encoder;
	private final LedgerguardProperties.Jwt config;

	TokenService(JwtEncoder encoder, LedgerguardProperties props) {
		this.encoder = encoder;
		this.config = props.jwt();
	}

	public IssuedToken issue(User user) {
		Instant now = Instant.now();
		Instant expiresAt = now.plus(config.ttl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(config.issuer())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.subject(user.getId().toString())
			.claim("role", user.getRole().name())
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, expiresAt);
	}
}
