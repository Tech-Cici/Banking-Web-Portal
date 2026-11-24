package rw.bank.ibanking.onboarding.repo;

import java.time.Instant;
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

    /**
     * One account's movements inside a period, oldest first.
     *
     * <p>ASCENDING, unlike everything else here, because a statement is read downwards from
     * the opening balance. A descending list would show a running balance that counts
     * backwards.
     *
     * <p>BOTH ENDS ARE INSTANTS, and the caller is responsible for turning the customer's
     * dates into them in the bank's own zone. A date compared against a timestamp in the
     * server's zone silently drops the first two hours of every Kigali day.
     */
    List<AccountTransactionEntity> findByAccountIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            UUID accountId, Instant from, Instant to);

    /**
     * The last movement on an account before {@code before}, if there was one.
     *
     * <p>THIS IS HOW THE OPENING BALANCE IS FOUND, and it is read from that entry's own
     * balance_after column rather than recomputed by summing history. Summing would mean a
     * statement could disagree with the ledger while both were internally consistent, which
     * is the hardest kind of disagreement to notice.
     */
    Optional<AccountTransactionEntity> findFirstByAccountIdAndCreatedAtLessThanOrderByCreatedAtDesc(
            UUID accountId, Instant before);

    /**
     * How many movements a period holds, without reading any of them.
     *
     * <p>THIS IS WHAT MAKES A SIZE LIMIT A LIMIT. Checking the size by fetching the rows and
     * counting them has already done the allocation the limit exists to prevent: a statement
     * refused for being too large would be refused only after every one of its entities was
     * loaded into memory. Counting in the database costs one indexed scan and returns a
     * number.
     */
    long countByAccountIdAndCreatedAtBetween(UUID accountId, Instant from, Instant to);
}
