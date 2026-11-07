package dev.obayomi.ledgerguard.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Interactive API docs at /swagger-ui.html, with an "Authorize" button for the JWT. */
@Configuration
@OpenAPIDefinition(
		info = @Info(title = "LedgerGuard API", version = "0.1",
				description = "Payments platform with a double-entry ledger and idempotent transfers."),
		security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class OpenApiConfig {
}
