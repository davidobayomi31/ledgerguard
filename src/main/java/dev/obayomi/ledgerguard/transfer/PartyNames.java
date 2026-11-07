package dev.obayomi.ledgerguard.transfer;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.obayomi.ledgerguard.account.Account;
import dev.obayomi.ledgerguard.account.AccountKind;
import dev.obayomi.ledgerguard.account.AccountRepository;
import dev.obayomi.ledgerguard.auth.User;
import dev.obayomi.ledgerguard.auth.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is on each side of a transfer, as the other party would see it on a bank statement:
 * the account holder's full name, or "LedgerGuard sandbox" for the platform's own accounts.
 * Only names are exposed, never another customer's email or balance.
 */
@Component
public class PartyNames {

	static final String PLATFORM = "LedgerGuard sandbox";

	private final AccountRepository accounts;
	private final UserRepository users;

	PartyNames(AccountRepository accounts, UserRepository users) {
		this.accounts = accounts;
		this.users = users;
	}

	/** Account id → display name, looked up in two queries however many transfers there are. */
	@Transactional(readOnly = true)
	public Map<UUID, String> forTransfers(Collection<Transfer> transfers) {
		Set<UUID> accountIds = new HashSet<>();
		for (Transfer t : transfers) {
			accountIds.add(t.getFromAccountId());
			accountIds.add(t.getToAccountId());
		}
		List<Account> found = accounts.findAllById(accountIds);
		Set<UUID> ownerIds = found.stream().map(Account::getOwnerId).filter(id -> id != null).collect(Collectors.toSet());
		Map<UUID, String> ownerNames = users.findAllById(ownerIds).stream()
			.collect(Collectors.toMap(User::getId, User::getFullName));

		Map<UUID, String> names = new HashMap<>();
		for (Account a : found) {
			names.put(a.getId(), a.getKind() == AccountKind.SYSTEM ? PLATFORM : ownerNames.getOrDefault(a.getOwnerId(), "Unknown"));
		}
		return names;
	}

	/** User id → full name, for showing who made a review decision. */
	@Transactional(readOnly = true)
	public Map<UUID, String> userNames(Collection<UUID> userIds) {
		return users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getFullName));
	}

	@Transactional(readOnly = true)
	public Function<Transfer, TransferController.TransferView> viewer(Collection<Transfer> transfers) {
		Map<UUID, String> names = forTransfers(transfers);
		return t -> TransferController.TransferView.of(t, names);
	}
}
