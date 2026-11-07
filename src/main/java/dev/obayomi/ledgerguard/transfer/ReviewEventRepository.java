package dev.obayomi.ledgerguard.transfer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewEventRepository extends JpaRepository<ReviewEvent, Long> {

	List<ReviewEvent> findAllByOrderByIdDesc(Pageable pageable);

	List<ReviewEvent> findByTransferIdOrderById(UUID transferId);

	/** The audit log limited to payments made by customers in one demo sandbox. */
	@Query("""
			select e from ReviewEvent e where e.transferId in (
				select t.id from Transfer t where t.initiatedBy in (
					select u.id from User u where u.sandboxId = :sandbox))
			order by e.id desc""")
	List<ReviewEvent> findInSandbox(@Param("sandbox") UUID sandboxId, Pageable pageable);
}
