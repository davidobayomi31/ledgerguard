package dev.obayomi.ledgerguard.transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.obayomi.ledgerguard.account.Account;
import dev.obayomi.ledgerguard.account.AccountKind;
import dev.obayomi.ledgerguard.account.AccountRepository;
import dev.obayomi.ledgerguard.common.ApiException;
import dev.obayomi.ledgerguard.common.CurrentUser;
import dev.obayomi.ledgerguard.common.Money;
import dev.obayomi.ledgerguard.config.LedgerguardProperties;
import dev.obayomi.ledgerguard.ledger.LedgerEntry;
import dev.obayomi.ledgerguard.ledger.LedgerEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves money. Every path follows the same steps:
 * validate → return the earlier result if this Idempotency-Key was already used →
 * lock the accounts involved → check the balance → write the transfer and its two ledger entries.
 */
@Service
public class TransferService {

	/** {@code replayed} is true when the same request was already processed and nothing new happened. */
	public record Result(Transfer transfer, boolean replayed) {}

	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

	private final TransferRepository transfers;
	private final AccountRepository accounts;
	private final LedgerEntryRepository entries;
	private final BigDecimal maxDeposit;

	TransferService(TransferRepository transfers, AccountRepository accounts, LedgerEntryRepository entries,
			LedgerguardProperties props) {
		this.transfers = transfers;
		this.accounts = accounts;
		this.entries = entries;
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

		// Always lock in the same global order (by id). If A→B and B→A ran at the same time and each
		// locked "its" source first, they would wait on each other forever: a deadlock.
		boolean fromFirst = fromId.compareTo(toId) < 0;
		Account first = lock(fromFirst ? fromId : toId);
		Account second = lock(fromFirst ? toId : fromId);
		Account from = fromFirst ? first : second;
		Account to = fromFirst ? second : first;

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
		if (entries.balanceOf(from.getId()).compareTo(amount) < 0) {
			transfer.reject("INSUFFICIENT_FUNDS");
			return new Result(transfers.save(transfer), false);
		}
		return new Result(post(transfer), false);
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
