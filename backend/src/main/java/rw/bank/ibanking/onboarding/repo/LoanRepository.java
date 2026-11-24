package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.LoanEntity;

/** Loans the bank has granted. */
public interface LoanRepository extends JpaRepository<LoanEntity, UUID> {

    List<LoanEntity> findByCustomerIdOrderByRecordedAtDesc(UUID customerId);

    /** Backs the guard that one application cannot become two debts. */
    Optional<LoanEntity> findByServiceRequestId(UUID serviceRequestId);
}
