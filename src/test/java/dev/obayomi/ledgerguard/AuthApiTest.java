package dev.obayomi.ledgerguard;

import java.util.Map;
import java.util.UUID;

import dev.obayomi.ledgerguard.support.ApiClient;
import dev.obayomi.ledgerguard.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiTest extends IntegrationTest {

	@Test
	void registerLoginAndCallAProtectedEndpoint() {
		ApiClient api = api();
		String email = "ada-" + UUID.randomUUID() + "@example.com";

		ApiClient.Response registered = api.post("/api/auth/register", null, null,
				Map.of("email", email, "password", "a-very-long-password", "fullName", "Ada Lovelace"));
		assertThat(registered.status()).isEqualTo(201);
		assertThat(registered.get("tokenType")).isEqualTo("Bearer");

		ApiClient.Response login = api.post("/api/auth/login", null, null,
				Map.of("email", email.toUpperCase(), "password", "a-very-long-password"));
		assertThat(login.status()).isEqualTo(200);

		ApiClient.Response me = api.get("/api/auth/me", login.get("accessToken"));
		assertThat(me.status()).isEqualTo(200);
		assertThat(me.get("email")).isEqualTo(email);
		assertThat(me.get("role")).isEqualTo("CUSTOMER");
	}

	@Test
	void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
		ApiClient api = api();
		String email = "bob-" + UUID.randomUUID() + "@example.com";
		api.post("/api/auth/register", null, null, Map.of("email", email, "password", "a-very-long-password", "fullName", "Bob"));

		ApiClient.Response wrongPassword = api.post("/api/auth/login", null, null, Map.of("email", email, "password", "nope-nope-nope"));
		ApiClient.Response unknownEmail = api.post("/api/auth/login", null, null, Map.of("email", "x" + email, "password", "nope-nope-nope"));

		assertThat(wrongPassword.status()).isEqualTo(401);
		assertThat(unknownEmail.status()).isEqualTo(401);
		assertThat(wrongPassword.get("detail")).isEqualTo(unknownEmail.get("detail"));
	}

	@Test
	void duplicateEmailIsRejected() {
		ApiClient api = api();
		String email = "dup-" + UUID.randomUUID() + "@example.com";
		Map<String, String> body = Map.of("email", email, "password", "a-very-long-password", "fullName", "Dup");
		assertThat(api.post("/api/auth/register", null, null, body).status()).isEqualTo(201);
		ApiClient.Response again = api.post("/api/auth/register", null, null, body);
		assertThat(again.status()).isEqualTo(409);
		assertThat(again.get("code")).isEqualTo("EMAIL_TAKEN");
	}

	@Test
	void requestsWithoutATokenAreRejected() {
		assertThat(api().get("/api/accounts", null).status()).isEqualTo(401);
		assertThat(api().get("/api/accounts", "not-a-real-token").status()).isEqualTo(401);
	}

	@Test
	void shortPasswordsFailValidation() {
		ApiClient.Response r = api().post("/api/auth/register", null, null,
				Map.of("email", "short-" + UUID.randomUUID() + "@example.com", "password", "short", "fullName", "S"));
		assertThat(r.status()).isEqualTo(400);
	}
}
