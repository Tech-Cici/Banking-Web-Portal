package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * A loan the bank has granted, as a member of staff recorded it.
 *
 * <p>NOTHING HERE IS CALCULATED. The principal, the outstanding balance, the installment
 * and the rate were all typed in from the bank's own systems. This class stores them and
 * hands them back; it has no method that derives one figure from another, and should not
 * grow one — a second opinion about what a customer owes is worse than no opinion.
 */
@Entity
@Table(name = "loans")
public class LoanEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "service_request_id")
    private UUID serviceRequestId;

    @Column(nullable = false, length = 32)
    private String product;

    @Column(nullable = false, length = 24)
    private String reference;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "principal_minor", nullable = false)
    private long principalMinor;

    @Column(name = "outstanding_minor", nullable = false)
    private long outstandingMinor;

    @Column(name = "installment_minor", nullable = false)
    private long installmentMinor;

    @Column(name = "interest_rate_percent", nullable = false)
    private BigDecimal interestRatePercent;

    @Column(name = "next_due_date", nullable = false)
    private LocalDate nextDueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LoanStatus status;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by_name", nullable = false, length = 160)
    private String recordedByName;

    protected LoanEntity() {
        // JPA.
    }

    private LoanEntity(
            UUID customerId,
            UUID accountId,
            UUID serviceRequestId,
            String product,
            Money principal,
            Money outstanding,
            Money installment,
            BigDecimal interestRatePercent,
            LocalDate nextDueDate,
            LoanStatus status,
            String recordedByName) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.accountId = accountId;
        this.serviceRequestId = serviceRequestId;
        this.product = product;
        this.currency = principal.currency();
        this.principalMinor = principal.minorUnits();
        this.outstandingMinor = outstanding.minorUnits();
        this.installmentMinor = installment.minorUnits();
        this.interestRatePercent = interestRatePercent;
        this.nextDueDate = nextDueDate;
        this.status = status;
        this.recordedAt = Instant.now();
        this.recordedByName = recordedByName;

        /* Short, and derived from the id so two concurrent recordings cannot collide —
           the same reasoning as the service-request reference in V18. */
        this.reference = "LN-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    public static LoanEntity recorded(
            UUID customerId,
            UUID accountId,
            UUID serviceRequestId,
            String product,
            Money principal,
            Money outstanding,
            Money installment,
            BigDecimal interestRatePercent,
            LocalDate nextDueDate,
            LoanStatus status,
            String recordedByName) {

        return new LoanEntity(
                customerId,
                accountId,
                serviceRequestId,
                product,
                principal,
                outstanding,
                installment,
                interestRatePercent,
                nextDueDate,
                status,
                recordedByName);
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

    public String product() {
        return product;
    }

    public String reference() {
        return reference;
    }

    public String currency() {
        return currency;
    }

    public Money principal() {
        return new Money(principalMinor, currency);
    }

    public Money outstanding() {
        return new Money(outstandingMinor, currency);
    }

    public Money installment() {
        return new Money(installmentMinor, currency);
    }

    public BigDecimal interestRatePercent() {
        return interestRatePercent;
    }

    public LocalDate nextDueDate() {
        return nextDueDate;
    }

    /**
     * Applies the capital part of a repayment.
     *
     * <p>CAPITAL ONLY. Interest is not a reduction in what was borrowed -- it is the price of
     * having borrowed it -- so a payment that is all interest leaves the outstanding figure
     * exactly where it was. That is correct and it is also the thing customers query, which
     * is why the repayment row records the split.
     *
     * <p>SETTLED AT ZERO, and the status is set here rather than by the caller: the
     * relationship between "owes nothing" and "is settled" is a property of the loan, and
     * V25's constraint already refuses one without the other.
     *
     * <p>IT REFUSES TO OVERPAY rather than clamping. Clamping would silently take more money
     * than the debt and return nothing, which is a worse answer than a sentence saying how
     * much is actually left.
     */
    public void repaidCapital(Money capital) {
        if (capital.minorUnits() < 0) {
            throw new BusinessRuleException("A repayment cannot be negative.");
        }
        if (capital.minorUnits() > outstandingMinor) {
            throw new BusinessRuleException(
                    "That is more than is left on this loan. The balance is "
                            + outstanding().toPlainString()
                            + " "
                            + currency
                            + ".");
        }

        this.outstandingMinor -= capital.minorUnits();

        if (outstandingMinor == 0) {
            this.status = LoanStatus.SETTLED;
        }
    }

    /** Moves the next instalment on a month, once one has been paid in full. */
    public void nextInstalmentDue() {
        this.nextDueDate = nextDueDate.plusMonths(1);
    }

    public LoanStatus status() {
        return status;
    }
}
