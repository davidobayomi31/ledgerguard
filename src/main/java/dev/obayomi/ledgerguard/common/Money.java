package dev.obayomi.ledgerguard.common;

import java.math.BigDecimal;

/**
 * Rules for amounts of money. Money is always {@link BigDecimal} with exactly two
 * decimal places, never {@code double}: 0.1 + 0.2 is 0.30000000000000004 in floating point.
 */
public final class Money {

	public static final int SCALE = 2;
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");

	private Money() {}

	/** Validates a client-supplied amount and returns it normalized to two decimal places. */
	public static BigDecimal requireValidAmount(BigDecimal amount) {
		if (amount == null) {
			throw ApiException.badRequest("INVALID_AMOUNT", "Amount is required.");
		}
		if (amount.signum() <= 0) {
			throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be greater than zero.");
		}
		if (amount.stripTrailingZeros().scale() > SCALE) {
			throw ApiException.badRequest("INVALID_AMOUNT", "Amount can have at most 2 decimal places.");
		}
		if (amount.compareTo(MAX_AMOUNT) > 0) {
			throw ApiException.badRequest("INVALID_AMOUNT", "Amount cannot exceed 1,000,000.00.");
		}
		// Exact: the scale check above guarantees no rounding happens here.
		return amount.setScale(SCALE);
	}
}
