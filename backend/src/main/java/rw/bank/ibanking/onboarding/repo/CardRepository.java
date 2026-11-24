package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.CardEntity;

/** Cards the bank has issued. */
public interface CardRepository extends JpaRepository<CardEntity, UUID> {

    /** One customer's own cards, newest first. */
    List<CardEntity> findByCustomerIdOrderByIssuedAtDesc(UUID customerId);

    /**
     * The card produced for a service request, if one was.
     *
     * <p>Backs the guard that marking a request ready twice cannot issue two cards. The
     * unique index in V23 is the backstop for the race this check cannot win on its own.
     */
    Optional<CardEntity> findByServiceRequestId(UUID serviceRequestId);
}
