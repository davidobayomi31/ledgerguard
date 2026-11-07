package dev.obayomi.ledgerguard.auth;

import dev.obayomi.ledgerguard.common.ApiException;
import dev.obayomi.ledgerguard.config.LedgerguardProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sandbox helpers for the public demo. Public sign-up only ever creates customers; this is the one way
 * to get an analyst login, and it can be switched off with SANDBOX_ANALYST_SIGNUP=false.
 */
@RestController
@RequestMapping("/api/sandbox")
class SandboxController {

	/** {@code sandboxId}: this analyst only reviews payments from customers in the same sandbox. */
	record AnalystRequest(@NotBlank @Size(max = 100) String fullName, java.util.UUID sandboxId) {}

	private final AuthService auth;
	private final AuthController authController;
	private final boolean enabled;

	SandboxController(AuthService auth, AuthController authController, LedgerguardProperties props) {
		this.auth = auth;
		this.authController = authController;
		this.enabled = props.sandbox().analystSignup();
	}

	@Operation(summary = "Sandbox only: create a fraud-analyst login and get an access token")
	@SecurityRequirements
	@PostMapping("/analysts")
	@ResponseStatus(HttpStatus.CREATED)
	AuthController.AuthResponse createAnalyst(@Valid @RequestBody AnalystRequest req) {
		if (!enabled) {
			throw ApiException.notFound("NOT_FOUND", "Not found.");
		}
		return authController.respond(auth.registerSandboxAnalyst(req.fullName(), req.sandboxId()));
	}
}
