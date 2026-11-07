package dev.obayomi.ledgerguard.transfer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.obayomi.ledgerguard.account.Account;
import dev.obayomi.ledgerguard.account.AccountKind;
import dev.obayomi.ledgerguard.account.AccountRepository;
import dev.obayomi.ledgerguard.auth.User;
import dev.obayomi.ledgerguard.auth.UserRepository;
import dev.obayomi.ledgerguard.common.ApiException;
import dev.obayomi.ledgerguard.common.CurrentUser;
import dev.obayomi.ledgerguard.common.Money;
import dev.obayomi.ledgerguard.config.LedgerguardProperties;
import dev.obayomi.ledgerguard.fraud.FraudRules;
import dev.obayomi.ledgerguard.ledger.LedgerEntry;
import dev.obayomi.ledgerguard.ledger.LedgerEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves money. Every path follows the same steps:
 * validate → return the earlier result if this Idempotency-Key was already used →
 * lock the accounts involved → check the balance → score fraud risk →
 * either write the transfer and its two ledger entries, or hold it for an analyst.
 */
@Service
public class TransferService {

	/** {@code replayed} is true when the same request was already processed and nothing new happened. */
	public record Result(Transfer transfer, boolean replayed) {}

	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

	private final TransferRepository transfers;
	private final AccountRepository accounts;
	private final LedgerEntryRepository entries;
	private final ReviewEventRepository reviewEvents;
	private final UserRepository users;
	private final BigDecimal maxDeposit;

	TransferService(TransferRepository transfers, AccountRepository accounts, LedgerEntryRepository entries,
			ReviewEventRepository reviewEvents, UserRepository users, LedgerguardProperties props) {
		this.transfers = transfers;
		this.accounts = accounts;
		this.entries = entries;
		this.reviewEvents = reviewEvents;
		this.users = users;
		this.maxDeposit = props.sandbox().maxDeposit();
	}

	@Transactional
	public Result transfer(UUID userId, String idempotencyKey, UUID fromId, UUID toId, BigDecimal rawAmount,
			String memo) {
		requireValidKey(idempotencyKey);
		BigDecimal amount = Money.requireValidAmount(rawAmount);
		if (fromId.equals(toId)) {
			throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination must be different accounts.");
		}
		String fingerprint = RequestFingerprint.of(TransferType.TRANSFER, fromId, toId, amount, memo);

		Optional<Transfer> earlier = transfers.findByInitiatedByAndIdempotencyKey(userId, idempotencyKey);
		if (earlier.isPresent()) {
			return replay(earlier.get(), fingerprint);
		}

		Account[] pair = lockPair(fromId, toId);
		Account from = pair[0];
		Account to = pair[1];

		if (from == null || !from.isOwnedBy(userId)) {
			throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Source account not found.");
		}
		if (to == null || to.getKind() != AccountKind.CUSTOMER) {
			throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Destination account not found.");
		}
		if (!from.getCurrency().equals(to.getCurrency())) {
			throw ApiException.unprocessable("CURRENCY_MISMATCH", "Both accounts must use the same currency.");
		}

		// Check again now that we hold the lock: a concurrent request with the same key
		// may have committed while we were waiting.
		earlier = transfers.findByInitiatedByAndIdempotencyKey(userId, idempotencyKey);
		if (earlier.isPresent()) {
			return replay(earlier.get(), fingerprint);
		}

		Transfer transfer = new Transfer(userId, idempotencyKey, fingerprint, TransferType.TRANSFER,
				from.getId(), to.getId(), amount, from.getCurrency(), memo);
		BigDecimal balance = entries.balanceOf(from.getId());
		if (balance.compareTo(amount) < 0) {
			transfer.reject("INSUFFICIENT_FUNDS");
			return new Result(transfers.save(transfer), false);
		}

		// Scored while both accounts are locked, so the facts (balance, recent payments) can't change underneath.
		FraudRules.Assessment risk = assessRisk(userId, from, to, amount, balance);
		transfer.recordRisk(risk.score(), risk.signalCodes());
		if (risk.shouldHold()) {
			transfer.hold();
			Transfer saved = transfers.saveAndFlush(transfer);
			reviewEvents.save(new ReviewEvent(saved.getId(), null, ReviewEvent.Action.HELD,
					"Risk score " + risk.score() + ": " + risk.signalCodes()));
			return new Result(saved, false);
		}
		return new Result(post(transfer), false);
	}

	/** Moving money between your own accounts is never scored: there is no one to defraud. */
	private FraudRules.Assessment assessRisk(UUID userId, Account from, Account to, BigDecimal amount,
			BigDecimal balance) {
		if (to.isOwnedBy(userId)) {
			return FraudRules.Assessment.NONE;
		}
		boolean paidBefore = transfers.existsByFromAccountIdAndToAccountIdAndStatusIn(from.getId(), to.getId(),
				List.of(TransferStatus.SETTLED, TransferStatus.HELD));
		long recent = transfers.countByFromAccountIdAndTypeAndCreatedAtAfter(from.getId(), TransferType.TRANSFER,
				Instant.now().minus(Duration.ofMinutes(FraudRules.VELOCITY_WINDOW_MINUTES)));
		return FraudRules.assess(new FraudRules.Facts(amount, balance, !paidBefore, recent));
	}

	// ── Analyst review ───────────────────────────────────────────────

	/** A demo analyst sees only their own sandbox; any other analyst sees every held payment. */
	@Transactional(readOnly = true)
	public List<Transfer> heldQueue(UUID analystId, int limit) {
		PageRequest page = PageRequest.of(0, Math.min(limit, 100));
		UUID sandbox = sandboxOf(analystId);
		return sandbox == null
				? transfers.findByStatusOrderByCreatedAt(TransferStatus.HELD, page)
				: transfers.findHeldInSandbox(sandbox, page);
	}

	@Transactional(readOnly = true)
	public List<ReviewEvent> auditLog(UUID analystId, int limit) {
		PageRequest page = PageRequest.of(0, Math.min(limit, 200));
		UUID sandbox = sandboxOf(analystId);
		return sandbox == null ? reviewEvents.findAllByOrderByIdDesc(page) : reviewEvents.findInSandbox(sandbox, page);
	}

	private UUID sandboxOf(UUID userId) {
		return users.findById(userId).map(User::getSandboxId).orElse(null);
	}

	/**
	 * Releases a held transfer. The balance is checked again, because the customer may have spent the
	 * money while the transfer waited (a hold does not reserve funds).
	 */
	@Transactional
	public Transfer approve(UUID analystId, UUID transferId, String note) {
		Transfer transfer = lockHeld(analystId, transferId);
		Account[] pair = lockPair(transfer.getFromAccountId(), transfer.getToAccountId());
		transfer.markReviewed(analystId, note);
		if (entries.balanceOf(pair[0].getId()).compareTo(transfer.getAmount()) < 0) {
			transfer.reject("INSUFFICIENT_FUNDS");
			reviewEvents.save(new ReviewEvent(transfer.getId(), analystId, ReviewEvent.Action.REJECTED_ON_APPROVAL, note));
			return transfers.save(transfer);
		}
		Transfer settled = post(transfer);
		reviewEvents.save(new ReviewEvent(transfer.getId(), analystId, ReviewEvent.Action.APPROVED, note));
		return settled;
	}

	@Transactional
	public Transfer block(UUID analystId, UUID transferId, String note) {
		Transfer transfer = lockHeld(analystId, transferId);
		transfer.markReviewed(analystId, note);
		transfer.block();
		reviewEvents.save(new ReviewEvent(transfer.getId(), analystId, ReviewEvent.Action.BLOCKED, note));
		return transfers.save(transfer);
	}

	/** Row lock on the transfer: if two analysts click at once, the second waits, then sees it is no longer HELD. */
	private Transfer lockHeld(UUID analystId, UUID transferId) {
		Transfer transfer = transfers.findByIdForUpdate(transferId)
			.orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found."));
		// A demo analyst can't act on another sandbox's payment; 404 so they can't even confirm it exists.
		UUID sandbox = sandboxOf(analystId);
		if (sandbox != null && !sandbox.equals(sandboxOf(transfer.getInitiatedBy()))) {
			throw ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found.");
		}
		if (transfer.getStatus() != TransferStatus.HELD) {
			throw ApiException.conflict("ALREADY_REVIEWED", "This transfer is not waiting for review.");
		}
		return transfer;
	}

	/**
	 * Locks both accounts and returns them as [from, to]. Always locks in the same global order (by id):
	 * if A→B and B→A ran at the same time and each locked "its" source first, they would wait on each
	 * other forever (a deadlock).
	 */
	private Account[] lockPair(UUID fromId, UUID toId) {
		boolean fromFirst = fromId.compareTo(toId) < 0;
		Account first = lock(fromFirst ? fromId : toId);
		Account second = lock(fromFirst ? toId : fromId);
		return fromFirst ? new Account[] {first, second} : new Account[] {second, first};
	}

	/** Sandbox only: adds test money from the platform's funding account (which may go negative). */
	@Transactional
	public Result deposit(UUID userId, String idempotencyKey, UUID accountId, BigDecimal rawAmount) {
		requireValidKey(idempotencyKey);
		BigDecimal amount = Money.requireValidAmount(rawAmount);
		if (amount.compareTo(maxDeposit) > 0) {
			throw ApiException.unprocessable("DEPOSIT_LIMIT",
					"Sandbox deposits are limited to " + maxDeposit.toPlainString() + " each.");
		}
		String fingerprint = RequestFingerprint.of(TransferType.DEPOSIT, Account.SANDBOX_FUNDING_ID, accountId,
				amount, null);

		Optional<Transfer> earlier = transfers.findByInitiatedByAndIdempotencyKey(userId, idempotencyKey);
		if (earlier.isPresent()) {
			return replay(earlier.get(), fingerprint);
		}
		Account to = lock(accountId);
		if (to == null || !to.isOwnedBy(userId)) {
			throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found.");
		}
		earlier = transfers.findByInitiatedByAndIdempotencyKey(userId, idempotencyKey);
		if (earlier.isPresent()) {
			return replay(earlier.get(), fingerprint);
		}
		Transfer deposit = new Transfer(userId, idempotencyKey, fingerprint, TransferType.DEPOSIT,
				Account.SANDBOX_FUNDING_ID, to.getId(), amount, to.getCurrency(), "Sandbox deposit");
		return new Result(post(deposit), false);
	}

	@Transactional(readOnly = true)
	public Transfer get(CurrentUser user, UUID transferId) {
		return transfers.findById(transferId)
			.filter(t -> user.canViewAllAccounts() || t.getInitiatedBy().equals(user.id()) || ownsEither(user.id(), t))
			.orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found."));
	}

	@Transactional(readOnly = true)
	public List<Transfer> recentFor(UUID userId, int limit) {
		List<UUID> mine = accounts.findByOwnerIdOrderByCreatedAt(userId).stream().map(Account::getId).toList();
		if (mine.isEmpty()) {
			return List.of();
		}
		return transfers.findInvolving(mine, PageRequest.of(0, Math.min(limit, 100)));
	}

	/** Writes a settled transfer and its debit + credit. The database re-checks that they sum to zero. */
	private Transfer post(Transfer transfer) {
		transfer.settle();
		Transfer saved = transfers.saveAndFlush(transfer); // flush so the entries' foreign key can see it
		entries.save(new LedgerEntry(saved.getId(), saved.getFromAccountId(), saved.getAmount().negate()));
		entries.save(new LedgerEntry(saved.getId(), saved.getToAccountId(), saved.getAmount()));
		return saved;
	}

	private Account lock(UUID accountId) {
		return accounts.findByIdForUpdate(accountId).orElse(null);
	}

	private Result replay(Transfer earlier, String fingerprint) {
		if (!earlier.getRequestFingerprint().equals(fingerprint)) {
			throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
					"This Idempotency-Key was already used for a different request.");
		}
		return new Result(earlier, true);
	}

	private boolean ownsEither(UUID userId, Transfer t) {
		return accounts.findById(t.getFromAccountId()).map(a -> a.isOwnedBy(userId)).orElse(false)
				|| accounts.findById(t.getToAccountId()).map(a -> a.isOwnedBy(userId)).orElse(false);
	}

	private static void requireValidKey(String key) {
		if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
			throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
					"Idempotency-Key must be 8-100 characters: letters, digits, '.', '_', ':' or '-'. A UUID works well.");
		}
	}
}
