package dev.obayomi.ledgerguard.fraud;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scores a payment for fraud risk. Each rule that fires adds points; at {@link #HOLD_AT} or more the
 * payment is held for an analyst instead of moving money.
 *
 * <p>No single weak signal is enough on its own. A first payment to someone new is normal; a
 * first payment of a large amount to someone new is what account-takeover fraud looks like.
 *
 * <p>Pure logic with no database access: callers gather the facts, this decides.
 */
public final class FraudRules {

	public static final int HOLD_AT = 60;

	static final BigDecimal LARGE_AMOUNT = new BigDecimal("500.00");
	static final BigDecimal NEW_PAYEE_MIN = new BigDecimal("200.00");
	static final int VELOCITY_LIMIT = 5; // earlier payments from the same account in the window
	public static final int VELOCITY_WINDOW_MINUTES = 10;
	static final BigDecimal DRAIN_MIN = new BigDecimal("100.00");
	static final BigDecimal DRAIN_SHARE = new BigDecimal("0.90");

	public enum Signal {
		LARGE_AMOUNT(35, "Amount is $500.00 or more"),
		NEW_PAYEE(35, "First payment of $200.00+ to this recipient"),
		HIGH_VELOCITY(30, "5+ payments from this account in the last 10 minutes"),
		DRAINS_ACCOUNT(25, "Sends 90%+ of the account's balance");

		public final int points;
		public final String description;

		Signal(int points, String description) {
			this.points = points;
			this.description = description;
		}
	}

	/** The facts the rules need about one payment, gathered while the source account is locked. */
	public record Facts(BigDecimal amount, BigDecimal sourceBalance, boolean firstPaymentToRecipient,
			long recentPaymentsFromSource) {}

	public record Assessment(int score, List<Signal> signals) {
		public static final Assessment NONE = new Assessment(0, List.of());

		public boolean shouldHold() {
			return score >= HOLD_AT;
		}

		/** Stored on the transfer as text, e.g. "LARGE_AMOUNT,NEW_PAYEE". */
		public String signalCodes() {
			return String.join(",", signals.stream().map(Enum::name).toList());
		}

		public static List<Signal> parse(String codes) {
			if (codes == null || codes.isBlank()) return List.of();
			return Arrays.stream(codes.split(",")).map(Signal::valueOf).toList();
		}
	}

	private FraudRules() {}

	public static Assessment assess(Facts f) {
		List<Signal> fired = new ArrayList<>();
		if (f.amount().compareTo(LARGE_AMOUNT) >= 0) {
			fired.add(Signal.LARGE_AMOUNT);
		}
		if (f.firstPaymentToRecipient() && f.amount().compareTo(NEW_PAYEE_MIN) >= 0) {
			fired.add(Signal.NEW_PAYEE);
		}
		if (f.recentPaymentsFromSource() >= VELOCITY_LIMIT) {
			fired.add(Signal.HIGH_VELOCITY);
		}
		boolean drains = f.sourceBalance().signum() > 0
				&& f.amount().compareTo(DRAIN_MIN) >= 0
				&& f.amount().compareTo(f.sourceBalance().multiply(DRAIN_SHARE)) >= 0;
		if (drains) {
			fired.add(Signal.DRAINS_ACCOUNT);
		}
		int score = fired.stream().mapToInt(s -> s.points).sum();
		return new Assessment(score, List.copyOf(fired));
	}
}
