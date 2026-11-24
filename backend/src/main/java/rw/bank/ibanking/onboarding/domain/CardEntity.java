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
 * A card the bank has issued, as a member of staff recorded it.
 *
 * <p>NOTHING HERE IS GENERATED. The masked number, the brand and the expiry date come off
 * the physical card and are typed in; this class stores them and nothing more. It has no
 * constructor that could invent a card number and no field that could hold a full one.
 *
 * <p>THE MASK ARRIVES ALREADY COMPOSED, from {@code CardService}, which is the only place
 * that turns four digits into a display string. Doing it here would put the formatting rule
 * next to the field and invite a later change to accept something longer.
 */
@Entity
@Table(name = "issued_cards")
public class CardEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "service_request_id")
    private UUID serviceRequestId;

    @Column(name = "masked_pan", nullable = false, length = 24)
    private String maskedPan;

    @Column(name = "brand", nullable = false, length = 32)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false, length = 16)
    private CardType cardType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CardStatus status;

    @Column(name = "expiry_month", nullable = false)
    private int expiryMonth;

    @Column(name = "expiry_year", nullable = false)
    private int expiryYear;

    @Column(name = "cardholder_name", nullable = false, length = 160)
    private String cardholderName;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "issued_by_name", nullable = false, length = 160)
    private String issuedByName;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    protected CardEntity() {
        // JPA.
    }

    private CardEntity(
            UUID customerId,
            UUID accountId,
            UUID serviceRequestId,
            String maskedPan,
            String brand,
            CardType cardType,
            int expiryMonth,
            int expiryYear,
            String cardholderName,
            String issuedByName) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.accountId = accountId;
        this.serviceRequestId = serviceRequestId;
        this.maskedPan = maskedPan;
        this.brand = brand;
        this.cardType = cardType;
        this.expiryMonth = expiryMonth;
        this.expiryYear = expiryYear;
        this.cardholderName = cardholderName;
        this.issuedByName = issuedByName;
        this.issuedAt = Instant.now();

        /*
         * PENDING_COLLECTION, not ACTIVE. The card exists and is waiting at a counter;
         * saying it is active before anybody has handed it over would be the portal
         * asserting the customer has something they do not yet have.
         */
        this.status = CardStatus.PENDING_COLLECTION;
    }

    public static CardEntity issued(
            UUID customerId,
            UUID accountId,
            UUID serviceRequestId,
            String maskedPan,
            String brand,
            CardType cardType,
            int expiryMonth,
            int expiryYear,
            String cardholderName,
            String issuedByName) {

        return new CardEntity(
                customerId,
                accountId,
                serviceRequestId,
                maskedPan,
                brand,
                cardType,
                expiryMonth,
                expiryYear,
                cardholderName,
                issuedByName);
    }

    /** Handed over at the counter. */
    public void activated() {
        if (status != CardStatus.PENDING_COLLECTION) return;
        this.status = CardStatus.ACTIVE;
        this.activatedAt = Instant.now();
    }

    /**
     * Stopped at the customer's request.
     *
     * <p>IDEMPOTENT BY DESIGN. Blocking a card that is already blocked is not an error --
     * the client sends an idempotency key for exactly this, and a second press of a Block
     * button under a slow connection must not produce a failure on a safety action.
     */
    public void blocked() {
        if (status == CardStatus.BLOCKED) return;
        this.status = CardStatus.BLOCKED;
        this.blockedAt = Instant.now();
    }

    public boolean isBlocked() {
        return status == CardStatus.BLOCKED;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID serviceRequestId() {
        return serviceRequestId;
    }

    public String maskedPan() {
        return maskedPan;
    }

    public String brand() {
        return brand;
    }

    public CardType cardType() {
        return cardType;
    }

    public CardStatus status() {
        return status;
    }

    public int expiryMonth() {
        return expiryMonth;
    }

    public int expiryYear() {
        return expiryYear;
    }

    public String cardholderName() {
        return cardholderName;
    }

    public Instant issuedAt() {
        return issuedAt;
    }
}
