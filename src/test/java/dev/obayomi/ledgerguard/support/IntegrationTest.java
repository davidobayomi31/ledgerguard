package dev.obayomi.ledgerguard.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts the real app on a random port against a real Postgres.
 * By default that Postgres is a throwaway Docker container (Testcontainers).
 * Set TEST_DATABASE_URL to use an existing database instead (handy without Docker).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

	private static final String EXTERNAL_DB = System.getenv("TEST_DATABASE_URL");

	private static final PostgreSQLContainer POSTGRES = EXTERNAL_DB != null ? null : startPostgres();

	private static PostgreSQLContainer startPostgres() {
		PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
		container.start(); // shared by every test class; Testcontainers removes it when the JVM exits
		return container;
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		if (POSTGRES != null) {
			registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
			registry.add("spring.datasource.username", POSTGRES::getUsername);
			registry.add("spring.datasource.password", POSTGRES::getPassword);
		}
		else {
			registry.add("spring.datasource.url", () -> EXTERNAL_DB);
			registry.add("spring.datasource.username", () -> envOr("TEST_DATABASE_USERNAME", ""));
			registry.add("spring.datasource.password", () -> envOr("TEST_DATABASE_PASSWORD", ""));
		}
		registry.add("ledgerguard.jwt.secret", () -> "integration-test-secret-at-least-32-chars");
	}

	private static String envOr(String name, String fallback) {
		String value = System.getenv(name);
		return value != null ? value : fallback;
	}

	@LocalServerPort
	protected int port;

	protected ApiClient api() {
		return new ApiClient(port);
	}
}
