package dev.obayomi.ledgerguard.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerguard")
public record LedgerguardProperties(Jwt jwt, Sandbox sandbox) {

	public record Jwt(String secret, String issuer, Duration ttl) {}

	public record Sandbox(BigDecimal maxDeposit) {}
}
