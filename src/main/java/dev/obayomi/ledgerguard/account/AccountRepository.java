package dev.obayomi.ledgerguard.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

	List<Account> findByOwnerIdOrderByCreatedAt(UUID ownerId);

	/**
	 * SELECT ... FOR UPDATE: holds a row lock until the transaction ends, so two transfers
	 * touching the same account run one after the other instead of both reading the same balance.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
