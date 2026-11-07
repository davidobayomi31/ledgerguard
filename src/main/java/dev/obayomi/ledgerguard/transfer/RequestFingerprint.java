package dev.obayomi.ledgerguard.transfer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A hash of what a request asked for. If a client reuses an Idempotency-Key with a
 * different amount or recipient, the fingerprints differ and the request is refused
 * instead of silently returning the old result.
 */
final class RequestFingerprint {

	private RequestFingerprint() {}

	static String of(TransferType type, UUID from, UUID to, BigDecimal amount, String memo) {
		String canonical = String.join("|", type.name(), from.toString(), to.toString(),
				amount.stripTrailingZeros().toPlainString(), memo == null ? "" : memo);
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}
}
