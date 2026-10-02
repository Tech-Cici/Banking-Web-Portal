package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * One customer's instruction to move money to another account in this service, and the
 * manager's decision on it.
 *
 * <p>THE INSTRUCTION, NOT THE MONEY. The money is in {@code account_transactions} and
 * nowhere else; this row records who asked for what, who decided, and which entries the
 * decision produced. The two are linked in both directions so that reconciling a transfer
 * against the ledger never has to match on amount and timestamp — which stops working the
 * moment two identical transfers land in the same second.
 *
 * <p>THE SENDER IS ALREADY DEBITED WHILE THIS IS PENDING. That is the design, argued at
 * length in V12: a hold that is not a ledger entry means the balance and the entries
 * disagree by design, and the customer's own statement cannot show why their money is
 * unavailable. So {@link #debitEntryId} is set from the moment the transfer exists.
 *
 * <p>Every transition is a method here rather than a setter, and each one refuses to run
 * twice. A double-clicked Approve must not credit the beneficiary twice, and the check
 * that stops it belongs with the state it protects rather than in whichever service
 * remembered to look.
 */
@Entity
@Table(name = "transfers")
public class TransferEntity {

    @Id private UUID id;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    /**
     * The customer who gave the instruction.
     *
     * <p>Separate from the account, because for a company account they are not the same
     * thing: the account belongs to the company and the instruction came from one named
     * member of it. That is the first thing anybody reviewing a company's payments needs.
     */
    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 140)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    /** The manager's NAME, so the audit survives their staff row being removed. */
    @Column(name = "decided_by", length = 160)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "debit_entry_id")
    private UUID debitEntryId;

    @Column(name = "credit_entry_id")
    private UUID creditEntryId;

    @Column(name = "reversal_entry_id")
    private UUID reversalEntryId;

    protected TransferEntity() {
        // JPA.
    }

    private TransferEntity(
            UUID sourceAccountId,
            UUID submittedBy,
            UUID destinationAccountId,
            Money amount,
            String reference,
            String idempotencyKey,
            UUID debitEntryId) {
        this.id = UUID.randomUUID();
        this.sourceAccountId = sourceAccountId;
        this.submittedBy = submittedBy;
        this.destinationAccountId = destinationAccountId;
        this.amountMinor = amount.minorUnits();
        this.currency = amount.currency();
        this.reference = reference;
        this.status = TransferStatus.PENDING_APPROVAL;
        this.submittedAt = Instant.now();
        this.idempotencyKey = idempotencyKey;
        this.debitEntryId = debitEntryId;
    }

    /**
     * Records a transfer whose sender has ALREADY been debited.
     *
     * @param debitEntryId the entry that took the money. Required, not nullable: a
     *     pending transfer with no debit behind it is money the customer can still spend
     *     while a manager is being asked to release it, which is how the same balance
     *     gets promised to two people.
     */
    public static TransferEntity held(
            UUID sourceAccountId,
            UUID submittedBy,
            UUID destinationAccountId,
            Money amount,
            String reference,
            String idempotencyKey,
            UUID debitEntryId) {

        if (debitEntryId == null) {
            throw new IllegalArgumentException(
                    "A transfer must be recorded with the entry that debited the sender.");
        }
        return new TransferEntity(
                sourceAccountId,
                submittedBy,
                destinationAccountId,
                amount,
                reference,
                idempotencyKey,
                debitEntryId);
    }

    public boolean isPending() {
        return status == TransferStatus.PENDING_APPROVAL;
    }

    /**
     * The manager released it, and the beneficiary has been credited.
     *
     * @param creditEntryId the entry that delivered the money. Required for the same
     *     reason the debit is: a transfer marked APPROVED with no credit behind it is one
     *     a screen reports as arrived while the beneficiary's balance disagrees.
     */
    public void approve(String manager, UUID creditEntryId) {
        requirePending();
        if (creditEntryId == null) {
            throw new IllegalArgumentException(
                    "A transfer cannot be approved without the entry that credited the"
                            + " beneficiary.");
        }
        this.status = TransferStatus.APPROVED;
        this.decidedBy = manager;
        this.decidedAt = Instant.now();
        this.creditEntryId = creditEntryId;
    }

    /**
     * The manager refused it, and the sender has been credited back.
     *
     * @param reason required. A refused payment with no stated reason is one nobody can
     *     explain to the customer who is about to telephone about it.
     */
    public void reject(String manager, String reason, UUID reversalEntryId) {
        requirePending();
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Give a reason for refusing this transfer.");
        }
        if (reversalEntryId == null) {
            throw new IllegalArgumentException(
                    "A transfer cannot be rejected without the entry that returned the money.");
        }
        this.status = TransferStatus.REJECTED;
        this.decidedBy = manager;
        this.decidedAt = Instant.now();
        this.rejectionReason = reason;
        this.reversalEntryId = reversalEntryId;
    }

    /**
     * Refuses a second decision.
     *
     * <p>THE CASE THIS SERVES IS A DOUBLE-CLICKED APPROVE, which is not hypothetical on a
     * queue screen. Without it the second call credits the beneficiary again and the money
     * arrives twice, from a hold that only ever covered it once.
     */
    private void requirePending() {
        if (!isPending()) {
            throw new BusinessRuleException(
                    "That transfer has already been decided by "
                            + decidedBy
                            + " and cannot be decided again.");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID sourceAccountId() {
        return sourceAccountId;
    }

    public UUID submittedBy() {
        return submittedBy;
    }

    public UUID destinationAccountId() {
        return destinationAccountId;
    }

    public Money amount() {
        return new Money(amountMinor, currency);
    }

    public String reference() {
        return reference;
    }

    public TransferStatus status() {
        return status;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public String decidedBy() {
        return decidedBy;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    public UUID debitEntryId() {
        return debitEntryId;
    }

    public UUID creditEntryId() {
        return creditEntryId;
    }

    public UUID reversalEntryId() {
        return reversalEntryId;
    }
}
