package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * A payee one customer has saved, and the record of the bank's decision about it.
 *
 * <p>THE FULL ACCOUNT NUMBER LIVES HERE AND LEAVES NOWHERE. The only accessor for it is
 * named {@link #destinationForPayment()} rather than {@code accountNumber()} — the same
 * device {@code CustomerAccountEntity.fullNumberForHolder()} uses — so that a reader can
 * see at the call site that it is the deliberate exception, and so that nobody assembling
 * a response reaches for it out of habit. Every view of a payee carries {@link
 * #maskedDestination()}.
 *
 * <p>WHY A SAVED PAYEE NEEDS A DECISION AT ALL. Adding one is the step a scam needs: the
 * script is "add this account and send the money now", and a payee that is instantly
 * payable is what makes it work. V17 records the whole trade, including why this is a staff
 * check rather than a timer.
 */
@Entity
@Table(name = "beneficiaries")
public class BeneficiaryEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type", nullable = false, length = 16)
    private BeneficiaryType beneficiaryType;

    @Column(name = "provider", nullable = false, length = 120)
    private String provider;

    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "masked_destination", nullable = false, length = 24)
    private String maskedDestination;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BeneficiaryStatus status;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by_name", length = 160)
    private String reviewedByName;

    @Column(name = "refused_reason", length = 300)
    private String refusedReason;

    @Column(name = "verified_holder_name", length = 160)
    private String verifiedHolderName;

    protected BeneficiaryEntity() {
        // JPA.
    }

    private BeneficiaryEntity(
            UUID customerId,
            String name,
            BeneficiaryType beneficiaryType,
            String provider,
            String accountNumber,
            String currency) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.name = name;
        this.beneficiaryType = beneficiaryType;
        this.provider = provider;
        this.accountNumber = accountNumber;
        this.maskedDestination = maskOf(accountNumber);
        this.currency = currency;
        this.status = BeneficiaryStatus.PENDING_VERIFICATION;
        this.addedAt = Instant.now();
    }

    /**
     * Saves a payee, unpayable.
     *
     * <p>There is no second factory that creates an ACTIVE one. A payee can only become
     * payable by passing through {@link #approved}, which requires a named reviewer — so
     * there is no code path, including a future one somebody adds in a hurry, that
     * produces a cleared payee with no decision behind it.
     */
    public static BeneficiaryEntity savedBy(
            UUID customerId,
            String name,
            BeneficiaryType beneficiaryType,
            String provider,
            String accountNumber,
            String currency) {

        return new BeneficiaryEntity(
                customerId, name, beneficiaryType, provider, accountNumber, currency);
    }

    /**
     * The last four digits, which is all any response ever shows.
     *
     * <p>Computed once at save time and stored, so that the mask in a response, a log line
     * and an audit row are the same string forever — including after this method's idea of
     * a mask changes.
     */
    private static String maskOf(String accountNumber) {
        String digits = accountNumber == null ? "" : accountNumber.trim();
        return digits.length() <= 4 ? "****" : "**** " + digits.substring(digits.length() - 4);
    }

    /**
     * Clears the payee for payment.
     *
     * @param holderName the name the bank held for this destination at the moment of the
     *     decision, or null where this service holds none. Stored as the evidence the
     *     decision rested on: re-resolving it later answers a different question, because
     *     an account can change hands between then and now.
     */
    public void approved(String reviewerName, String holderName) {
        requirePending(reviewerName);
        this.status = BeneficiaryStatus.ACTIVE;
        this.reviewedAt = Instant.now();
        this.reviewedByName = reviewerName;
        this.verifiedHolderName = holderName == null || holderName.isBlank() ? null : holderName;
    }

    /** Declines the payee, with the reason the customer is shown and emailed. */
    public void refused(String reviewerName, String reason) {
        requirePending(reviewerName);
        if (reason == null || reason.isBlank()) {
            /*
             * Mandatory, and V17's constraint says so too. The commonest refusal is "the
             * name does not match this account", which tells the customer exactly what to
             * do next; "the bank said no" tells them to telephone.
             */
            throw new BusinessRuleException(
                    "Give a reason for refusing this payee, so the customer knows what to put"
                            + " right.");
        }
        this.status = BeneficiaryStatus.REFUSED;
        this.reviewedAt = Instant.now();
        this.reviewedByName = reviewerName;
        this.refusedReason = reason.trim();
    }

    /**
     * Refuses a second decision on a payee that already has one.
     *
     * <p>THE RACE IS REAL AND IT IS NOT HARMLESS. Two staff members with the queue open
     * both press Approve; without this the second write silently replaces the first
     * reviewer's name, so the audit trail credits the decision to whoever happened to be
     * slower. Worse in the other order: a refusal landing on an approved payee would make
     * a payee the customer has already been told about unpayable again, with no record
     * that it was ever cleared.
     */
    private void requirePending(String reviewerName) {
        if (reviewerName == null || reviewerName.isBlank()) {
            throw new IllegalArgumentException("A reviewed payee must name the member of staff.");
        }
        if (status != BeneficiaryStatus.PENDING_VERIFICATION) {
            throw new BusinessRuleException(
                    "Another member of staff has already dealt with this payee. Refresh the page"
                            + " to see what happened.");
        }
    }

    /** Whether money may be sent to this payee. The only place that question is answered. */
    public boolean payable() {
        return status == BeneficiaryStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public String name() {
        return name;
    }

    public BeneficiaryType beneficiaryType() {
        return beneficiaryType;
    }

    public String provider() {
        return provider;
    }

    /**
     * THE FULL PAYMENT ADDRESS, FOR SENDING MONEY TO IT.
     *
     * <p>Public because Java has no way to say "this package and that service" — the
     * entity is in {@code domain} and the service that needs this is in {@code service} —
     * so, exactly as {@code CustomerAccountEntity.fullNumberForHolder()} does, the rule is
     * a convention made visible by the NAME rather than a compiler guarantee. A reader
     * seeing {@code destinationForPayment()} at a call site can tell what it is for;
     * nobody assembling a response body reaches for it by accident, which a plain {@code
     * accountNumber()} invites.
     *
     * <p>THE CALLER MUST HAVE CHECKED TWO THINGS, and nothing here can check either: that
     * the payee belongs to the customer being debited, and that {@link #payable()} is
     * true. {@code BeneficiaryService.destinationFor} is the only caller and does both.
     *
     * <p>Not for a log, not for a response, not for an error message.
     */
    public String destinationForPayment() {
        return accountNumber;
    }

    public String maskedDestination() {
        return maskedDestination;
    }

    public String currency() {
        return currency;
    }

    public BeneficiaryStatus status() {
        return status;
    }

    public Instant addedAt() {
        return addedAt;
    }

    public Instant reviewedAt() {
        return reviewedAt;
    }

    public String reviewedByName() {
        return reviewedByName;
    }

    public String refusedReason() {
        return refusedReason;
    }

    public String verifiedHolderName() {
        return verifiedHolderName;
    }
}
