package dev.obayomi.ledgerguard.fraud;

import java.math.BigDecimal;

import dev.obayomi.ledgerguard.fraud.FraudRules.Assessment;
import dev.obayomi.ledgerguard.fraud.FraudRules.Facts;
import dev.obayomi.ledgerguard.fraud.FraudRules.Signal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit tests: no Spring, no database. Each rule, its boundary, and how scores combine. */
class FraudRulesTest {

	private static Assessment assess(String amount, String balance, boolean firstToRecipient, long recent) {
		return FraudRules.assess(new Facts(new BigDecimal(amount), new BigDecimal(balance), firstToRecipient, recent));
	}

	@Test
	void anEverydayPaymentScoresZero() {
		Assessment a = assess("40.00", "1000.00", false, 0);
		assertThat(a.score()).isZero();
		assertThat(a.shouldHold()).isFalse();
	}

	@Test
	void largeAmountFiresAtExactly500() {
		assertThat(assess("499.99", "5000.00", false, 0).signals()).doesNotContain(Signal.LARGE_AMOUNT);
		assertThat(assess("500.00", "5000.00", false, 0).signals()).containsExactly(Signal.LARGE_AMOUNT);
	}

	@Test
	void newPayeeOnlyCountsFrom200() {
		assertThat(assess("199.99", "5000.00", true, 0).signals()).isEmpty();
		assertThat(assess("200.00", "5000.00", true, 0).signals()).containsExactly(Signal.NEW_PAYEE);
	}

	@Test
	void velocityFiresAtFiveRecentPayments() {
		assertThat(assess("10.00", "5000.00", false, 4).signals()).isEmpty();
		assertThat(assess("10.00", "5000.00", false, 5).signals()).containsExactly(Signal.HIGH_VELOCITY);
	}

	@Test
	void drainingNinetyPercentOfTheBalanceIsFlaggedButNotForTinyAmounts() {
		assertThat(assess("180.00", "200.00", false, 0).signals()).containsExactly(Signal.DRAINS_ACCOUNT);
		assertThat(assess("179.99", "200.00", false, 0).signals()).isEmpty();
		assertThat(assess("50.00", "50.00", false, 0).signals()).isEmpty(); // under $100: not worth flagging
	}

	@Test
	void oneSignalAloneNeverHoldsAPayment() {
		for (Assessment single : new Assessment[] {
				assess("500.00", "5000.00", false, 0), // large
				assess("200.00", "5000.00", true, 0), // new payee
				assess("10.00", "5000.00", false, 9), // velocity
				assess("180.00", "200.00", false, 0)}) { // drains
			assertThat(single.shouldHold()).as(single.signals().toString()).isFalse();
		}
	}

	@Test
	void largePaymentToSomeoneNewIsHeld() {
		Assessment a = assess("600.00", "1000.00", true, 0);
		assertThat(a.signals()).containsExactly(Signal.LARGE_AMOUNT, Signal.NEW_PAYEE);
		assertThat(a.score()).isEqualTo(70);
		assertThat(a.shouldHold()).isTrue();
	}

	@Test
	void signalCodesRoundTripThroughText() {
		Assessment a = assess("600.00", "600.00", true, 7);
		assertThat(Assessment.parse(a.signalCodes())).isEqualTo(a.signals());
		assertThat(Assessment.parse("")).isEmpty();
	}
}
