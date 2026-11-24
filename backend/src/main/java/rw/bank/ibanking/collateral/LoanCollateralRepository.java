package rw.bank.ibanking.collateral;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** What was pledged against each loan application. */
public interface LoanCollateralRepository extends JpaRepository<LoanCollateralEntity, UUID> {

    List<LoanCollateralEntity> findByServiceRequestIdOrderByCreatedAtAsc(UUID serviceRequestId);
}
