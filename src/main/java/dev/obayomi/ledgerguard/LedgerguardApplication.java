package dev.obayomi.ledgerguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerguardApplication {

	public static void main(String[] args) {
		SpringApplication.run(LedgerguardApplication.class, args);
	}

}
