package dev.obayomi.ledgerguard.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.obayomi.ledgerguard.common.CurrentUser;
import dev.obayomi.ledgerguard.fraud.FraudRules;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fraud analysts' endpoints. SecurityConfig limits /api/reviews/** to the ANALYST and ADMIN roles. */
@RestController
@RequestMapping("/api/reviews")
class ReviewController {

	record DecisionRequest(@Size(max = 280) String note) {}

	record SignalView(String code, String description, int points) {}

	/** A held transfer as an analyst sees it: the transfer plus why the rules flagged it. */
	record ReviewItem(TransferController.TransferView transfer, int riskScore, List<SignalView> signals) {}

	record EventView(long id, String action, String actorName, String note, Instant createdAt, UUID transferId,
			String fromName, String toName, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
			String transferStatus) {}

	static final String SYSTEM_ACTOR = "Fraud rules (automatic)";

	private final TransferService transfers;
	private final TransferRepository transferRepository;
	private final PartyNames names;

	ReviewController(TransferService transfers, TransferRepository transferRepository, PartyNames names) {
		this.transfers = transfers;
		this.transferRepository = transferRepository;
		this.names = names;
	}

	@Operation(summary = "Review queue: transfers held by the fraud rules, oldest first")
	@GetMapping
	List<ReviewItem> queue(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
		List<Transfer> held = transfers.heldQueue(CurrentUser.from(jwt).id(), limit);
		Function<Transfer, TransferController.TransferView> view = names.viewer(held);
		return held.stream().map(t -> item(t, view)).toList();
	}

	@Operation(summary = "Approve a held transfer: money moves if the balance still covers it")
	@PostMapping("/{id}/approve")
	ReviewItem approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody(required = false) DecisionRequest req) {
		Transfer t = transfers.approve(CurrentUser.from(jwt).id(), id, noteOf(req));
		return item(t, names.viewer(List.of(t)));
	}

	@Operation(summary = "Block a held transfer: no money moves")
	@PostMapping("/{id}/block")
	ReviewItem block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody(required = false) DecisionRequest req) {
		Transfer t = transfers.block(CurrentUser.from(jwt).id(), id, noteOf(req));
		return item(t, names.viewer(List.of(t)));
	}

	@Operation(summary = "Audit log: every hold and review decision, newest first")
	@GetMapping("/events")
	List<EventView> auditLog(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
		List<ReviewEvent> recent = transfers.auditLog(CurrentUser.from(jwt).id(), limit);
		Map<UUID, Transfer> byId = transferRepository
			.findAllById(recent.stream().map(ReviewEvent::getTransferId).collect(Collectors.toSet()))
			.stream().collect(Collectors.toMap(Transfer::getId, t -> t));
		Map<UUID, String> parties = names.forTransfers(byId.values());
		Set<UUID> actorIds = new HashSet<>();
		recent.stream().map(ReviewEvent::getActorId).filter(Objects::nonNull).forEach(actorIds::add);
		Map<UUID, String> actors = names.userNames(actorIds);

		return recent.stream().map(e -> {
			Transfer t = byId.get(e.getTransferId());
			String actor = e.getActorId() == null ? SYSTEM_ACTOR : actors.getOrDefault(e.getActorId(), "Unknown");
			return new EventView(e.getId(), e.getAction().name(), actor, e.getNote(), e.getCreatedAt(), e.getTransferId(),
					parties.get(t.getFromAccountId()), parties.get(t.getToAccountId()), t.getAmount(), t.getStatus().name());
		}).toList();
	}

	private static ReviewItem item(Transfer t, Function<Transfer, TransferController.TransferView> view) {
		List<SignalView> signals = FraudRules.Assessment.parse(t.getRiskSignals()).stream()
			.map(s -> new SignalView(s.name(), s.description, s.points)).toList();
		return new ReviewItem(view.apply(t), t.getRiskScore(), signals);
	}

	private static String noteOf(DecisionRequest req) {
		if (req == null || req.note() == null || req.note().isBlank()) return null;
		return req.note().trim();
	}
}
