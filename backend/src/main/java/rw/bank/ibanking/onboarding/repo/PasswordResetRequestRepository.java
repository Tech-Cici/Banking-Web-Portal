package rw.bank.ibanking.onboarding.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.PasswordResetRequestEntity;
import rw.bank.ibanking.onboarding.domain.PasswordResetStatus;

/** Customers who have asked for a new password. */
public interface PasswordResetRequestRepository
        extends JpaRepository<PasswordResetRequestEntity, UUID> {

    /**
     * The queue screen: everything still waiting, oldest first.
     *
     * <p>Oldest first because this is a queue and not a feed. Newest first would quietly
     * bury the customer who has been locked out longest under the ones who asked most
     * recently.
     */
    List<PasswordResetRequestEntity> findByStatusOrderByRequestedAtAsc(PasswordResetStatus status);

    /**
     * Whether this customer already has one waiting.
     *
     * <p>This is what keeps "one pending request per customer" true. It would be a partial
     * unique index if the test suite's H2 supported one — see V15 — so the rule lives here,
     * and a duplicate ask is answered the same as the first without filling the queue with
     * one person's repeated clicks.
     */
    Optional<PasswordResetRequestEntity> findFirstByCustomerIdAndStatus(
            UUID customerId, PasswordResetStatus status);

    long countByStatus(PasswordResetStatus status);

    /**
     * How many times this customer has asked recently, for the rate limit.
     *
     * <p>COUNTED IN THE DATABASE. The first version of this loaded every settled request,
     * filtered by customer in Java and counted the result — fine with a handful of rows and
     * a table scan per click once a bank has a year of them.
     */
    long countByCustomerIdAndRequestedAtAfter(UUID customerId, Instant since);
}
