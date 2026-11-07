package dev.obayomi.ledgerguard.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.obayomi.ledgerguard.common.CurrentUser;
import dev.obayomi.ledgerguard.ledger.LedgerEntry;
import dev.obayomi.ledgerguard.transfer.TransferController;
import dev.obayomi.ledgerguard.transfer.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
class AccountController {

	record OpenAccountRequest(@NotBlank @Size(max = 60) String name) {}

	record DepositRequest(@NotNull BigDecimal amount) {}

	/** Money goes over the wire as a string ("10.00") so no client parses it as a float. */
	record AccountView(UUID id, String name, String currency,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance, Instant createdAt) {
		static AccountView of(AccountService.AccountWithBalance a) {
			return new AccountView(a.account().getId(), a.account().getName(), a.account().getCurrency(),
					a.balance(), a.account().getCreatedAt());
		}
	}

	record EntryView(long id, UUID transferId,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, Instant createdAt) {
		static EntryView of(LedgerEntry e) {
			return new EntryView(e.getId(), e.getTransferId(), e.getAmount(), e.getCreatedAt());
		}
	}

	record PageView<T>(List<T> items, int page, int size, long totalItems) {}

	private final AccountService accounts;
	private final TransferService transfers;

	AccountController(AccountService accounts, TransferService transfers) {
		this.accounts = accounts;
		this.transfers = transfers;
	}

	@Operation(summary = "Open a new CAD account")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	AccountView open(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenAccountRequest req) {
		return AccountView.of(accounts.open(CurrentUser.from(jwt).id(), req.name()));
	}

	@Operation(summary = "List my accounts with balances")
	@GetMapping
	List<AccountView> mine(@AuthenticationPrincipal Jwt jwt) {
		return accounts.listFor(CurrentUser.from(jwt).id()).stream().map(AccountView::of).toList();
	}

	@Operation(summary = "Get one account and its balance")
	@GetMapping("/{id}")
	AccountView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return AccountView.of(accounts.get(CurrentUser.from(jwt), id));
	}

	@Operation(summary = "Account statement: ledger entries, newest first")
	@GetMapping("/{id}/entries")
	PageView<EntryView> entries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		Page<LedgerEntry> result = accounts.statement(CurrentUser.from(jwt), id, page, size);
		return new PageView<>(result.getContent().stream().map(EntryView::of).toList(),
				result.getNumber(), result.getSize(), result.getTotalElements());
	}

	@Operation(summary = "Sandbox deposit: add test money (simulates an incoming transfer)")
	@PostMapping("/{id}/deposits")
	ResponseEntity<TransferController.TransferView> deposit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody DepositRequest req) {
		TransferService.Result result = transfers.deposit(CurrentUser.from(jwt).id(), idempotencyKey, id, req.amount());
		return TransferController.respond(result);
	}
}
