package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.NotificationEntity;

/** A customer's in-app notifications. */
public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    /**
     * Newest first, bounded by the caller.
     *
     * <p>BOUNDED IN THE QUERY, not after loading everything — the same point
     * PasswordResetRequestRepository makes about its rate limit. A customer of five years
     * has thousands of rows and the panel wants four of them.
     */
    List<NotificationEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Limit limit);

    /** Everything still unread, for the mark-all action. */
    List<NotificationEntity> findByCustomerIdAndReadAtIsNull(UUID customerId);

    long countByCustomerIdAndReadAtIsNull(UUID customerId);
}
