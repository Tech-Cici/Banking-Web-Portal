package rw.bank.ibanking.statements;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Statements customers have taken. */
public interface StatementRepository extends JpaRepository<StatementEntity, UUID> {

    /** One customer's own, newest first. */
    List<StatementEntity> findByCustomerIdOrderByRequestedAtDesc(UUID customerId);
}
