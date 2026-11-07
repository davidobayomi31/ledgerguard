package dev.obayomi.ledgerguard.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** A tiny HTTP client for tests that never throws on 4xx/5xx, so tests can assert on errors. */
public final class ApiClient {

	public record Response(int status, Object body, HttpHeaders headers) {

		@SuppressWarnings("unchecked")
		public Map<String, Object> json() {
			return (Map<String, Object>) body;
		}

		@SuppressWarnings("unchecked")
		public List<Map<String, Object>> list() {
			return (List<Map<String, Object>>) body;
		}

		public String get(String field) {
			Object value = json().get(field);
			return value == null ? null : value.toString();
		}
	}

	private static final ParameterizedTypeReference<Object> ANY = new ParameterizedTypeReference<>() {};

	private final RestClient http;

	ApiClient(int port) {
		this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
	}

	public Response post(String path, String token, String idempotencyKey, Object body) {
		RestClient.RequestBodySpec request = http.post().uri(path);
		if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
		return wrap(request.body(body).retrieve().onStatus(s -> true, (req, res) -> {}).toEntity(ANY));
	}

	public Response get(String path, String token) {
		RestClient.RequestHeadersSpec<?> request = http.get().uri(path);
		if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		return wrap(request.retrieve().onStatus(s -> true, (req, res) -> {}).toEntity(ANY));
	}

	private static Response wrap(ResponseEntity<Object> entity) {
		return new Response(entity.getStatusCode().value(), entity.getBody(), entity.getHeaders());
	}

	// ---- helpers for common setup ----

	public String newCustomerToken() {
		return newCustomerToken("Test User");
	}

	public String newCustomerToken(String fullName) {
		return newCustomerToken(fullName, null);
	}

	/** A customer in a demo sandbox (null = an ordinary customer). */
	public String newCustomerToken(String fullName, UUID sandboxId) {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		Map<String, Object> body = new java.util.HashMap<>(
				Map.of("email", email, "password", "correct-horse-battery", "fullName", fullName));
		if (sandboxId != null) body.put("sandboxId", sandboxId.toString());
		Response r = post("/api/auth/register", null, null, body);
		if (r.status() != 201) throw new IllegalStateException("register failed: " + r);
		return r.get("accessToken");
	}

	/** A fraud analyst, created through the sandbox endpoint. */
	public String newAnalystToken() {
		return newAnalystToken(null);
	}

	/** An analyst scoped to one demo sandbox (null = sees every payment). */
	public String newAnalystToken(UUID sandboxId) {
		Map<String, Object> body = new java.util.HashMap<>(Map.of("fullName", "Sam Rivera"));
		if (sandboxId != null) body.put("sandboxId", sandboxId.toString());
		Response r = post("/api/sandbox/analysts", null, null, body);
		if (r.status() != 201) throw new IllegalStateException("analyst signup failed: " + r);
		return r.get("accessToken");
	}

	public String openAccount(String token) {
		Response r = post("/api/accounts", token, null, Map.of("name", "Chequing"));
		if (r.status() != 201) throw new IllegalStateException("open account failed: " + r);
		return r.get("id");
	}

	public void deposit(String token, String accountId, String amount) {
		Response r = post("/api/accounts/" + accountId + "/deposits", token, UUID.randomUUID().toString(),
				Map.of("amount", amount));
		if (r.status() != 201) throw new IllegalStateException("deposit failed: " + r);
	}

	public Response transfer(String token, String key, String from, String to, String amount) {
		return post("/api/transfers", token, key, Map.of("fromAccountId", from, "toAccountId", to, "amount", amount));
	}

	public BigDecimal balance(String token, String accountId) {
		return new BigDecimal(get("/api/accounts/" + accountId, token).get("balance"));
	}
}
