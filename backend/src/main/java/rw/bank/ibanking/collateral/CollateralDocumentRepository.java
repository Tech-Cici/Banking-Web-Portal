package rw.bank.ibanking.collateral;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Documents uploaded as evidence of collateral. */
public interface CollateralDocumentRepository
        extends JpaRepository<CollateralDocumentEntity, UUID> {

    /**
     * One document, but only if it belongs to the customer asking.
     *
     * <p>THE OWNER IS IN THE QUERY, not checked after loading. A findById followed by an
     * if-statement is one forgotten if-statement away from serving somebody else's title deed,
     * and this is the method every read of a document goes through.
     */
    Optional<CollateralDocumentEntity> findByIdAndCustomerId(UUID id, UUID customerId);

    /**
     * The same bytes, already uploaded by the same customer and not yet attached to anything.
     *
     * <p>AN UPLOAD INTERRUPTED BY A LOST CONNECTION IS RETRIED BY THE BROWSER, and the second
     * attempt carries the same file. Without this, the customer's photograph is stored twice
     * and the second copy is the one they go on to use -- which is harmless until the sweep
     * deletes the first and somebody wonders where it went.
     */
    Optional<CollateralDocumentEntity> findFirstByCustomerIdAndSha256AndLinkedAtIsNull(
            UUID customerId, String sha256);

    /** Uploads that never became an application. Read by the sweep. */
    List<CollateralDocumentEntity> findByLinkedAtIsNullAndUploadedAtBefore(Instant cutoff);
}
