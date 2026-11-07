package dev.obayomi.ledgerguard.transfer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

	Optional<Transfer> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);

	@Query("select t from Transfer t where t.fromAccountId in :ids or t.toAccountId in :ids order by t.createdAt desc")
	List<Transfer> findInvolving(@Param("ids") Collection<UUID> accountIds, Pageable pageable);
}
