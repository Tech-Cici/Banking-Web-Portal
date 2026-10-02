package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A document the applicant said they were attaching — the NAME of one, not the file.
 *
 * <p>THE FILE IS NOT HERE AND WAS NEVER SENT. The wizard's Documents step collects file
 * names from the browser and there is no upload endpoint behind it. This class exists so
 * that the gap is recorded rather than invisible: an administrator can see what the
 * applicant believed they had attached and ask for whichever ones matter, which is
 * strictly better than a queue of applications with no indication of what paperwork is
 * supposed to exist.
 *
 * <p>{@link #received()} is false on every row this service can create. It is a stored
 * column rather than a constant so that when an upload endpoint does land, applications
 * submitted before it stay distinguishable from the ones after — and so no future code
 * path can read a promised document as a held one. A method returning a hardcoded false
 * would have to be found and changed by whoever adds upload; a column is already right.
 *
 * <p>The name is DISPLAY ONLY. It came from a public form, so it is a string a stranger
 * chose: it is never used to open, write or serve anything, and never joined onto a path.
 */
@Entity
@Table(name = "business_documents")
public class BusinessDocumentEntity {

    @Id private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "file_name", nullable = false, length = 260)
    private String fileName;

    @Column(nullable = false)
    private boolean received;

    protected BusinessDocumentEntity() {
        // JPA.
    }

    private BusinessDocumentEntity(UUID applicationId, String fileName) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.fileName = fileName;
        /*
         * False, and there is no constructor that can set it true.
         *
         * Whoever implements upload adds that path deliberately. Leaving a setter here
         * for a capability that does not exist is how a field ends up true because some
         * mapper copied it from a request body.
         */
        this.received = false;
    }

    /** A name the applicant listed. The file itself did not arrive. */
    public static BusinessDocumentEntity promised(UUID applicationId, String fileName) {
        return new BusinessDocumentEntity(applicationId, fileName);
    }

    public UUID id() {
        return id;
    }

    public UUID applicationId() {
        return applicationId;
    }

    public String fileName() {
        return fileName;
    }

    public boolean received() {
        return received;
    }
}
