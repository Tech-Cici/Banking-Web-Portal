package rw.bank.ibanking.onboarding.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.StandingOrderEntity;
import rw.bank.ibanking.onboarding.domain.StandingOrderStatus;

/** Repeating payments. */
public interface StandingOrderRepository extends JpaRepository<StandingOrderEntity, UUID> {

    /** One customer's own orders, newest first. */
    List<StandingOrderEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /**
     * Everything due, oldest first.
     *
     * <p>{@code lessThanEqual} rather than equal: a container that was asleep for two days
     * must pay what it missed, not skip it. Render's free tier sleeps, so "due today" would
     * quietly mean "due on the days the service happened to be awake".
     */
    List<StandingOrderEntity> findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            StandingOrderStatus status, Instant at);
}
