package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import rw.bank.ibanking.onboarding.domain.TransferEntity;
import rw.bank.ibanking.onboarding.domain.TransferStatus;

/** Transfer instructions and their decisions. */
public interface TransferRepository extends JpaRepository<TransferEntity, UUID> {

    /** A retry with the same key is the same instruction, not a second one. */
    Optional<TransferEntity> findByIdempotencyKey(String idempotencyKey);

    /** The manager's queue: everything still waiting, oldest first. */
    List<TransferEntity> findByStatusOrderBySubmittedAtAsc(TransferStatus status, Limit limit);

    /**
     * How many transfers are waiting for a manager.
     *
     * <p>FOR THE OVERVIEW, and counted in the database rather than by loading the queue and
     * taking its size — the queue screen asks for a bounded page, so its size is the page
     * and not the backlog. A manager told "12 waiting" when there are 200 has been given a
     * worse number than none.
     */
    long countByStatus(TransferStatus status);

    /**
     * Everything touching these accounts, sent or received, newest first.
     *
     * <p>One query over both sides rather than two, because the customer's list is a
     * single chronological thing: a transfer they sent and one they received on the same
     * day belong next to each other, and merging two ordered lists in Java would only
     * reproduce the ORDER BY less reliably.
     */
    @Query(
            "select t from TransferEntity t"
                    + " where t.sourceAccountId in :accountIds"
                    + " or t.destinationAccountId in :accountIds"
                    + " order by t.submittedAt desc")
    List<TransferEntity> findTouching(List<UUID> accountIds, Limit limit);

    /**
     * What is currently in flight out of these accounts.
     *
     * <p>The sum of these is the difference between what the accounts hold and what the
     * books should hold — see the conservation invariant in V12. Used by the dashboard to
     * explain a balance that has already dropped, and by the tests to assert nothing is
     * lost in flight.
     */
    List<TransferEntity> findBySourceAccountIdInAndStatus(
            List<UUID> accountIds, TransferStatus status);
}
