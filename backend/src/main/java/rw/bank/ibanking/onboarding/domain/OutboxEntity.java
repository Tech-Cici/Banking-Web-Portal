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
 * A record of a message the bank sent, or tried to.
 *
 * <p>`delivered` is the field that earns this table its place. Recording that a message
 * was composed is not the same as recording that it left the building, and conflating the
 * two is how a bank comes to believe it notified a customer who never heard anything. When
 * someone says "I was never told my account was ready", this is the row that answers it.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxKind kind;

    @Column(nullable = false)
    private String sender;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(nullable = false)
    private boolean delivered;

    /** A safe reason, when it failed. Never a stack trace. */
    @Column private String failure;

    protected OutboxEntity() {
        // JPA.
    }

    private OutboxEntity(
            OutboxKind kind,
            String sender,
            String recipient,
            String subject,
            String body,
            boolean delivered,
            String failure) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.sentAt = Instant.now();
        this.delivered = delivered;
        this.failure = failure;
    }

    public static OutboxEntity sent(
            OutboxKind kind, String sender, String recipient, String subject, String body) {
        return new OutboxEntity(kind, sender, recipient, subject, body, true, null);
    }

    /** Composed but not delivered — sending is off, or it failed. */
    public static OutboxEntity notSent(
            OutboxKind kind,
            String sender,
            String recipient,
            String subject,
            String body,
            String reason) {
        return new OutboxEntity(kind, sender, recipient, subject, body, false, reason);
    }

    public UUID id() {
        return id;
    }

    public OutboxKind kind() {
        return kind;
    }

    public String sender() {
        return sender;
    }

    public String recipient() {
        return recipient;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public boolean delivered() {
        return delivered;
    }

    public String failure() {
        return failure;
    }
}
