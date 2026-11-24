package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * A customer has asked the bank for a card or a cheque book.
 *
 * <p>WHAT THIS REPLACED. The request used to exist only in the browser's mock store, which
 * starts empty on every page load — so the reference the customer was shown stopped
 * existing when they refreshed, and no member of staff could ever see it. V18 records the
 * whole trade.
 *
 * <p>THE COLLECTION POINT IS NOT THE CUSTOMER'S CHOICE, and that is the design rather than
 * an omission. The old screen offered a dropdown of six branch names that the front end had
 * invented, and told the customer to take their ID to the one they picked. Here the field
 * stays null until a member of staff types it in {@link #markedReady}, because the bank is
 * the only party that knows where the thing actually is.
 */
@Entity
@Table(name = "service_requests")
public class ServiceRequestEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "reference", nullable = false, length = 24)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 16)
    private ServiceRequestType requestType;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "details", nullable = false, length = 200)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ServiceRequestStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "collection_point", length = 120)
    private String collectionPoint;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "ready_by_name", length = 160)
    private String readyByName;

    /*
     * WHEN THIS REQUEST REACHED AN ENDING SOMEBODY PERFORMED, and who performed it. Handed
     * over, or granted -- V31 renamed the pair from collected_* once a loan could end a
     * request without anybody collecting anything. A decline keeps its own columns because it
     * carries a reason the other endings do not.
     */
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "finished_by_name", length = 160)
    private String finishedByName;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "declined_by_name", length = 160)
    private String declinedByName;

    /*
     * THE LOAN APPLICATION'S TERMS, AS FIGURES.
     *
     * NULL FOR EVERY OTHER KIND OF REQUEST, and the database says so: V29 carries a
     * constraint tying these four to LOAN_APPLICATION in both directions, so a cheque book
     * cannot acquire an amount and a live loan application cannot lack one.
     *
     * WHY THEY ARE NOT IN `details`. They were, as English, inside a sentence built for a
     * person to read. Granting the loan then meant parsing that sentence or retyping it, and
     * both settle how much money to lend by a route that can be wrong silently. `details`
     * still carries the customer's own words for the purpose, which no column replaces.
     *
     * WHAT THE CUSTOMER ASKED FOR, NOT WHAT THE BANK DECIDED. Staff may grant a different
     * amount or term; that lives on the loan, not here.
     */
    @Column(name = "loan_product", length = 32)
    private String loanProduct;

    @Column(name = "loan_amount_minor")
    private Long loanAmountMinor;

    @Column(name = "loan_currency", length = 3)
    private String loanCurrency;

    @Column(name = "loan_months")
    private Integer loanMonths;

    @Column(name = "decline_reason", length = 300)
    private String declineReason;

    protected ServiceRequestEntity() {
        // JPA.
    }

    private ServiceRequestEntity(
            UUID customerId, ServiceRequestType requestType, UUID accountId, String details) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.requestType = requestType;
        this.accountId = accountId;
        this.details = details;
        this.status = ServiceRequestStatus.SUBMITTED;
        this.submittedAt = Instant.now();
        this.reference = referenceFrom(requestType, this.id);
    }

    /** Records the request. Nothing is produced and nothing is promised. */
    public static ServiceRequestEntity raisedBy(
            UUID customerId, ServiceRequestType requestType, UUID accountId, String details) {

        return new ServiceRequestEntity(customerId, requestType, accountId, details);
    }

    /**
     * A loan application, which carries terms as well as a sentence.
     *
     * <p>A SEPARATE FACTORY rather than four more parameters on {@link #raisedBy}. Every
     * other request type would have to pass four nulls, and a call site passing nulls is one
     * that can pass them in the wrong order. This way the only way to build a loan
     * application is the way that includes its figures.
     */
    public static ServiceRequestEntity loanApplied(
            UUID customerId,
            UUID accountId,
            String details,
            String product,
            Money amount,
            int months) {

        ServiceRequestEntity request =
                new ServiceRequestEntity(
                        customerId, ServiceRequestType.LOAN_APPLICATION, accountId, details);

        request.loanProduct = product;
        request.loanAmountMinor = amount.minorUnits();
        request.loanCurrency = amount.currency();
        request.loanMonths = months;

        return request;
    }

    /** What the customer asked to borrow, for a loan application. */
    public Optional<Money> loanAmount() {
        return loanAmountMinor == null || loanCurrency == null
                ? Optional.empty()
                : Optional.of(new Money(loanAmountMinor, loanCurrency));
    }

    /** The product code the customer asked for. */
    public Optional<String> loanProduct() {
        return Optional.ofNullable(loanProduct);
    }

    /** The term in months the customer asked for. */
    public Optional<Integer> loanMonths() {
        return Optional.ofNullable(loanMonths);
    }

    /**
     * WHAT THE CUSTOMER QUOTES ON THE TELEPHONE.
     *
     * <p>DERIVED FROM THE ROW'S OWN ID, not from a count of rows. {@code
     * OnboardingService.nextCustomerNumber} builds its value from {@code customers.count()
     * + 1}, which two concurrent requests read identically and which then fails on a
     * UNIQUE index as a 500 that looks like nothing in particular. An id cannot collide.
     *
     * <p>THE ALPHABET LEAVES OUT WHAT PEOPLE MISHEAR — no O or 0, no I or 1, no S or 5.
     * This string's whole job is to survive being read aloud down a bad line and typed
     * into a staff screen by somebody else.
     *
     * <p>THE SIGN BIT IS MASKED OFF RATHER THAN RUN THROUGH {@code Math.abs}, which is not
     * the stylistic choice it looks like. {@code Math.abs(Long.MIN_VALUE)} returns
     * {@code Long.MIN_VALUE} — negation overflows — so the remainder below would be
     * negative and {@code charAt} would throw. One id in 2^64 lands on it, which is to say
     * never, and also that the failure would be a 500 on a customer's request with nothing
     * in the log to suggest why. Clearing the bit cannot overflow and has no edge case.
     */
    /* Package-private, not private, so ServiceRequestReferenceTest can hand it the one
       id that used to make it throw. A guard nothing can reach is a guard nobody trusts. */
    static String referenceFrom(ServiceRequestType type, UUID id) {
        String alphabet = "ABCDEFGHJKLMNPQRTUVWXYZ2346789";
        long bits = (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) & Long.MAX_VALUE;

        StringBuilder suffix = new StringBuilder(6);
        for (int position = 0; position < 6; position++) {
            suffix.append(alphabet.charAt((int) (bits % alphabet.length())));
            bits /= alphabet.length();
        }
        return type.referencePrefix() + "-" + suffix;
    }

    /**
     * Marks it produced and waiting to be collected.
     *
     * @param collectionPoint where the customer should go, in the staff member's own words.
     *     Required, and the database says so too: a request that is ready with no
     *     collection point emails somebody an instruction to collect their card from
     *     nowhere.
     */
    public void markedReady(String staffName, String collectionPoint) {
        requireStatus(ServiceRequestStatus.SUBMITTED, staffName, "ready");

        if (collectionPoint == null || collectionPoint.isBlank()) {
            throw new BusinessRuleException(
                    "Say where the customer should collect it. The portal has no branch list"
                            + " of its own and must not guess one.");
        }

        this.status = ServiceRequestStatus.READY;
        this.collectionPoint = collectionPoint.trim();
        this.readyAt = Instant.now();
        this.readyByName = staffName;
    }

    /**
     * Marks it handed over.
     *
     * <p>Only from READY, which is not pedantry: a request that goes straight from
     * SUBMITTED to COLLECTED is a record that something was handed over before it was
     * made, and it would leave the collection point and the ready timestamp null against a
     * database constraint that requires them.
     */
    /**
     * Approved and paid out.
     *
     * <p>FROM SUBMITTED, NOT FROM READY. A loan has no "ready" step: there is nothing to make
     * and nothing to put on a counter, so the staff action goes straight from the application
     * to the money being in the account. Requiring READY first would mean inventing a stage
     * that exists for cheque books and has no meaning here.
     *
     * <p>IT SHARES finished_at WITH A COLLECTION. The pair records when a request reached an
     * ending somebody performed and who performed it, which is one fact; a granted_at beside
     * it would leave four columns of which two are ever filled, and every screen asking "who
     * finished this?" would have to ask twice. The status says which kind of ending it was.
     * V31 renamed the columns, which were called collected_* when every ending was a
     * collection.
     */
    public void granted(String staffName) {
        requireStatus(ServiceRequestStatus.SUBMITTED, staffName, "granted");

        if (requestType != ServiceRequestType.LOAN_APPLICATION) {
            throw new BusinessRuleException(
                    "A " + requestType.label() + " cannot be granted. Only a loan can.");
        }

        this.status = ServiceRequestStatus.GRANTED;
        this.finishedAt = Instant.now();
        this.finishedByName = staffName;
    }

    public void markedCollected(String staffName) {
        requireStatus(ServiceRequestStatus.READY, staffName, "collected");
        this.status = ServiceRequestStatus.COLLECTED;
        this.finishedAt = Instant.now();
        this.finishedByName = staffName;
    }

    /** Declines it, with the reason the customer is shown and emailed. */
    public void declined(String staffName, String reason) {
        requireStatus(ServiceRequestStatus.SUBMITTED, staffName, "declined");

        if (reason == null || reason.isBlank()) {
            /*
             * Mandatory, and V18's constraint says so too. "The bank said no" is the
             * answer that guarantees a telephone call; a reason is often something the
             * customer can put right themselves.
             */
            throw new BusinessRuleException(
                    "Give a reason for declining this, so the customer knows what to do next.");
        }

        this.status = ServiceRequestStatus.DECLINED;
        this.declinedAt = Instant.now();
        this.declinedByName = staffName;
        this.declineReason = reason.trim();
    }

    /**
     * Refuses a transition from the wrong state, and names both.
     *
     * <p>THE RACE IS REAL. Two staff members with the queue open both press "Mark ready";
     * without this the second write replaces the first one's name and collection point, so
     * the audit row credits whoever was slower and the customer may have been emailed two
     * different branches. Refusing the second makes it a visible conflict instead.
     */
    private void requireStatus(ServiceRequestStatus required, String staffName, String action) {
        if (staffName == null || staffName.isBlank()) {
            throw new IllegalArgumentException(
                    "A request marked " + action + " must name the member of staff.");
        }
        if (status != required) {
            throw new BusinessRuleException(
                    "This request is already "
                            + status.name().toLowerCase(java.util.Locale.ROOT)
                            + ", so it cannot be marked "
                            + action
                            + ". Refresh the page to see what happened.");
        }
    }

    /** Whether this request is still waiting for somebody at the bank. */
    public boolean open() {
        return status == ServiceRequestStatus.SUBMITTED || status == ServiceRequestStatus.READY;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public String reference() {
        return reference;
    }

    public ServiceRequestType requestType() {
        return requestType;
    }

    public UUID accountId() {
        return accountId;
    }

    public String details() {
        return details;
    }

    public ServiceRequestStatus status() {
        return status;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public String collectionPoint() {
        return collectionPoint;
    }

    public Instant readyAt() {
        return readyAt;
    }

    public String readyByName() {
        return readyByName;
    }

    /** When this was handed over or granted. Null while open, and null on a decline. */
    public Instant finishedAt() {
        return finishedAt;
    }

    /** Who handed it over or granted it. */
    public String finishedByName() {
        return finishedByName;
    }

    public String declineReason() {
        return declineReason;
    }
}
