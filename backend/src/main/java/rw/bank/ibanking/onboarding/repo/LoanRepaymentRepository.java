package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.LoanRepaymentEntity;

/** Payments made against loans. */
public interface LoanRepaymentRepository extends JpaRepository<LoanRepaymentEntity, UUID> {

    /** One loan's history, oldest first: the order a statement reads. */
    List<LoanRepaymentEntity> findByLoanIdOrderByPaidAtAsc(UUID loanId);
}
