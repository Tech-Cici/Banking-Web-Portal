package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.CustomerStatus;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    Optional<CustomerEntity> findByEmailIgnoreCase(String email);

    List<CustomerEntity> findByStatusOrderByCreatedAtDesc(CustomerStatus status);

    List<CustomerEntity> findAllByOrderByCreatedAtDesc();

    boolean existsByEmailIgnoreCase(String email);

    long count();

    /* --------------------------------------------------- counts for the overview */

    long countByStatus(CustomerStatus status);

    /**
     * Approved, but the temporary password has not been replaced yet.
     *
     * <p>Worth its own count on the staff overview: these are accounts whose credential
     * a member of staff has seen and handed over, and which nobody has taken ownership
     * of. A number that stops falling is a queue of live passwords sitting on desks.
     */
    long countByStatusAndMustChangePasswordTrue(CustomerStatus status);
}
