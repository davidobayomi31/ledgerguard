package dev.obayomi.ledgerguard.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import dev.obayomi.ledgerguard.common.ApiException;
import dev.obayomi.ledgerguard.common.CurrentUser;
import dev.obayomi.ledgerguard.ledger.LedgerEntry;
import dev.obayomi.ledgerguard.ledger.LedgerEntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

	public record AccountWithBalance(Account account, BigDecimal balance) {}

	private final AccountRepository accounts;
	private final LedgerEntryRepository entries;

	AccountService(AccountRepository accounts, LedgerEntryRepository entries) {
		this.accounts = accounts;
		this.entries = entries;
	}

	@Transactional
	public AccountWithBalance open(UUID ownerId, String name) {
		Account account = accounts.save(new Account(ownerId, name.trim(), "CAD"));
		return new AccountWithBalance(account, BigDecimal.ZERO.setScale(2));
	}

	@Transactional(readOnly = true)
	public List<AccountWithBalance> listFor(UUID ownerId) {
		return accounts.findByOwnerIdOrderByCreatedAt(ownerId).stream()
			.map(a -> new AccountWithBalance(a, entries.balanceOf(a.getId())))
			.toList();
	}

	@Transactional(readOnly = true)
	public AccountWithBalance get(CurrentUser user, UUID accountId) {
		Account account = visibleAccount(user, accountId);
		return new AccountWithBalance(account, entries.balanceOf(account.getId()));
	}

	@Transactional(readOnly = true)
	public Page<LedgerEntry> statement(CurrentUser user, UUID accountId, int page, int size) {
		visibleAccount(user, accountId);
		return entries.findByAccountIdOrderByIdDesc(accountId, PageRequest.of(page, Math.min(size, 100)));
	}

	/** Someone else's account answers 404, not 403, so account ids can't be probed. */
	private Account visibleAccount(CurrentUser user, UUID accountId) {
		return accounts.findById(accountId)
			.filter(a -> a.isOwnedBy(user.id()) || (user.canViewAllAccounts() && a.getKind() == AccountKind.CUSTOMER))
			.orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
	}
}
