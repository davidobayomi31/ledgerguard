package dev.obayomi.ledgerguard.common;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

	@ParameterizedTest
	@ValueSource(strings = { "0.01", "1", "10.5", "10.50", "999999.99", "1000000", "25.000" })
	void acceptsValidAmountsAndNormalizesToTwoDecimals(String raw) {
		BigDecimal result = Money.requireValidAmount(new BigDecimal(raw));
		assertThat(result.scale()).isEqualTo(2);
		assertThat(result).isEqualByComparingTo(raw);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.00", "-1", "-0.01", "0.001", "10.555", "1000000.01" })
	void rejectsInvalidAmounts(String raw) {
		assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal(raw)))
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo("INVALID_AMOUNT");
	}

	@Test
	void rejectsMissingAmount() {
		assertThatThrownBy(() -> Money.requireValidAmount(null)).isInstanceOf(ApiException.class);
	}

	@Test
	void bigDecimalAvoidsFloatingPointErrors() {
		// The reason money is never a double: 0.1 + 0.2 != 0.3 in binary floating point.
		assertThat(0.1 + 0.2).isNotEqualTo(0.3);
		assertThat(new BigDecimal("0.1").add(new BigDecimal("0.2"))).isEqualByComparingTo("0.3");
	}
}
