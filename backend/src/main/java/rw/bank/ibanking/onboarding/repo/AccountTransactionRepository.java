package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.AccountTransactionEntity;

public interface AccountTransactionRepository
        extends JpaRepository<AccountTransactionEntity, UUID> {

    /** Newest first — a statement reads backwards from now. */
    List<AccountTransactionEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Limit limit);

    List<AccountTransactionEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /**
     * The entry a retry is asking about.
     *
     * <p>This is what stops a lost response becoming a double payment: the second request
     * carries the same key, finds the first entry, and returns it instead of posting again.
     */
    Optional<AccountTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    List<AccountTransactionEntity> findByAccountIdInOrderByCreatedAtDesc(
            List<UUID> accountIds, Limit limit);
}
