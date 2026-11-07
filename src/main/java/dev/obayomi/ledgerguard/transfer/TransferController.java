package dev.obayomi.ledgerguard.transfer;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.obayomi.ledgerguard.common.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

	record TransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId, @NotNull BigDecimal amount,
			@Size(max = 140) String memo) {}

	public record TransferView(UUID id, TransferType type, UUID fromAccountId, UUID toAccountId,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currency,
			TransferStatus status, String failureReason, String memo, Instant createdAt) {
		static TransferView of(Transfer t) {
			return new TransferView(t.getId(), t.getType(), t.getFromAccountId(), t.getToAccountId(), t.getAmount(),
					t.getCurrency(), t.getStatus(), t.getFailureReason(), t.getMemo(), t.getCreatedAt());
		}
	}

	private final TransferService transfers;

	TransferController(TransferService transfers) {
		this.transfers = transfers;
	}

	@Operation(summary = "Send money between accounts",
			description = "201 = new transfer (check status: SETTLED or REJECTED). 200 + Idempotent-Replayed: true = "
					+ "this Idempotency-Key was already used for the same request, nothing new happened.")
	@PostMapping
	ResponseEntity<TransferView> create(@AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "Unique per payment attempt; reuse it when retrying. A UUID works well.")
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody TransferRequest req) {
		TransferService.Result result = transfers.transfer(CurrentUser.from(jwt).id(), idempotencyKey,
				req.fromAccountId(), req.toAccountId(), req.amount(), req.memo());
		return respond(result);
	}

	@Operation(summary = "Get one transfer")
	@GetMapping("/{id}")
	TransferView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return TransferView.of(transfers.get(CurrentUser.from(jwt), id));
	}

	@Operation(summary = "Recent transfers in or out of my accounts")
	@GetMapping
	List<TransferView> recent(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
		return transfers.recentFor(CurrentUser.from(jwt).id(), limit).stream().map(TransferView::of).toList();
	}

	public static ResponseEntity<TransferView> respond(TransferService.Result result) {
		TransferView body = TransferView.of(result.transfer());
		if (result.replayed()) {
			return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
		}
		return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/transfers/" + body.id())).body(body);
	}
}
