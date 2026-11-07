package dev.obayomi.ledgerguard.auth;

import java.time.Instant;
import java.util.UUID;

import dev.obayomi.ledgerguard.common.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	record RegisterRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 12, max = 128) String password,
			@NotBlank @Size(max = 100) String fullName) {}

	record LoginRequest(@NotBlank String email, @NotBlank String password) {}

	record UserView(UUID id, String email, String fullName, Role role) {
		static UserView of(User u) {
			return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getRole());
		}
	}

	record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserView user) {}

	private final AuthService auth;
	private final TokenService tokens;

	AuthController(AuthService auth, TokenService tokens) {
		this.auth = auth;
		this.tokens = tokens;
	}

	@Operation(summary = "Create a customer account and get an access token")
	@SecurityRequirements
	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	AuthResponse register(@Valid @RequestBody RegisterRequest req) {
		return respond(auth.register(req.email(), req.password(), req.fullName()));
	}

	@Operation(summary = "Log in and get an access token")
	@SecurityRequirements
	@PostMapping("/login")
	AuthResponse login(@Valid @RequestBody LoginRequest req) {
		return respond(auth.authenticate(req.email(), req.password()));
	}

	@Operation(summary = "Who am I?")
	@GetMapping("/me")
	UserView me(@AuthenticationPrincipal Jwt jwt) {
		return UserView.of(auth.get(CurrentUser.from(jwt).id()));
	}

	private AuthResponse respond(User user) {
		TokenService.IssuedToken token = tokens.issue(user);
		return new AuthResponse(token.value(), "Bearer", token.expiresAt(), UserView.of(user));
	}
}
