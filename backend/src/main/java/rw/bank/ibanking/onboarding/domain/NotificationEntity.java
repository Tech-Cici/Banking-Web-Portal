package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Something the bank did that the customer should know about, readable in the portal.
 *
 * <p>WHAT THIS REPLACED: nothing, which was the fault. The dashboard had a notifications
 * panel, there was a full notifications page, and both were answered by the browser's own
 * mock from an array that starts empty on every page load. So the panel said "Nothing new."
 * permanently — including immediately after a member of staff produced a customer's card,
 * named the counter to collect it from, and emailed them about it.
 *
 * <p>A LIST THAT SAYS "NOTHING NEW" IS MAKING A CLAIM. The customer reads it as a record and
 * concludes that nothing happened; it had no means of checking. That is the same failure as
 * the fabricated sign-in location — a surface that looks like a control and answers from
 * nowhere — and it is why this table is written in the same transaction as the event.
 */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private NotificationKind kind;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Column(name = "link", length = 200)
    private String link;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected NotificationEntity() {
        // JPA.
    }

    private NotificationEntity(
            UUID customerId, NotificationKind kind, String title, String body, String link) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.link = link;
        this.createdAt = Instant.now();
    }

    /**
     * Records a notification.
     *
     * <p>THE LINK IS CHECKED HERE, not only by the constraint, so the failure arrives as an
     * argument error naming the problem rather than as a database violation inside somebody
     * else's transaction. A notification carries text the bank did not write — a staff
     * member's reason, a payee name the customer typed, a collection point — and an
     * absolute URL rendered from one is phishing with the bank's own domain behind it.
     */
    public static NotificationEntity raised(
            UUID customerId, NotificationKind kind, String title, String body, String link) {

        if (customerId == null || kind == null) {
            throw new IllegalArgumentException("A notification needs a customer and a kind.");
        }
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "A notification with no words in it is a row the customer cannot act on.");
        }

        String path = link == null || link.isBlank() ? null : link.trim();
        if (path != null && !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "A notification may only link to a path inside the portal, not to "
                            + path
                            + ". See V20.");
        }

        return new NotificationEntity(customerId, kind, title.trim(), body.trim(), path);
    }

    /**
     * Marks it read.
     *
     * <p>IDEMPOTENT, and it keeps the FIRST time. That time is the answer to "had the
     * customer seen this before they rang us", which is the only question this column is
     * ever asked; overwriting it on a second page load would destroy it.
     */
    public void read() {
        if (readAt == null) readAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public NotificationKind kind() {
        return kind;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public String link() {
        return link;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isRead() {
        return readAt != null;
    }
}
