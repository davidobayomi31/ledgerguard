package dev.obayomi.ledgerguard.transfer;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();

	@Test
	void sameRequestSameFingerprintEvenIfAmountIsWrittenDifferently() {
		assertThat(RequestFingerprint.of(TransferType.TRANSFER, a, b, new BigDecimal("10.5"), "rent"))
			.isEqualTo(RequestFingerprint.of(TransferType.TRANSFER, a, b, new BigDecimal("10.50"), "rent"));
	}

	@Test
	void anyChangeToTheRequestChangesTheFingerprint() {
		String base = RequestFingerprint.of(TransferType.TRANSFER, a, b, new BigDecimal("10.00"), "rent");
		assertThat(RequestFingerprint.of(TransferType.TRANSFER, a, b, new BigDecimal("10.01"), "rent")).isNotEqualTo(base);
		assertThat(RequestFingerprint.of(TransferType.TRANSFER, b, a, new BigDecimal("10.00"), "rent")).isNotEqualTo(base);
		assertThat(RequestFingerprint.of(TransferType.TRANSFER, a, b, new BigDecimal("10.00"), "food")).isNotEqualTo(base);
		assertThat(RequestFingerprint.of(TransferType.DEPOSIT, a, b, new BigDecimal("10.00"), "rent")).isNotEqualTo(base);
	}
}
