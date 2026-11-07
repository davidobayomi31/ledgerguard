package dev.obayomi.ledgerguard.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerguard")
public record LedgerguardProperties(Jwt jwt, Sandbox sandbox) {

	public record Jwt(String secret, String issuer, Duration ttl) {}

	/**
	 * @param analystSignup lets anyone create a fraud-analyst login (for the public demo).
	 *                      A real deployment would turn this off and promote analysts by hand.
	 */
	public record Sandbox(BigDecimal maxDeposit, boolean analystSignup) {}
}
