package dev.obayomi.ledgerguard.transfer;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

	Optional<Transfer> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);

	/** Locks one transfer row, so two analysts can't review the same transfer at once. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Transfer t where t.id = :id")
	Optional<Transfer> findByIdForUpdate(@Param("id") UUID id);

	List<Transfer> findByStatusOrderByCreatedAt(TransferStatus status, Pageable pageable);

	/** Held payments made by customers in one demo sandbox. */
	@Query("""
			select t from Transfer t where t.status = dev.obayomi.ledgerguard.transfer.TransferStatus.HELD
			and t.initiatedBy in (select u.id from User u where u.sandboxId = :sandbox)
			order by t.createdAt""")
	List<Transfer> findHeldInSandbox(@Param("sandbox") UUID sandboxId, Pageable pageable);

	/** Has money from this account reached (or been waiting to reach) this recipient before? */
	boolean existsByFromAccountIdAndToAccountIdAndStatusIn(UUID from, UUID to, Collection<TransferStatus> statuses);

	long countByFromAccountIdAndTypeAndCreatedAtAfter(UUID from, TransferType type, Instant since);

	@Query("select t from Transfer t where t.fromAccountId in :ids or t.toAccountId in :ids order by t.createdAt desc")
	List<Transfer> findInvolving(@Param("ids") Collection<UUID> accountIds, Pageable pageable);
}
