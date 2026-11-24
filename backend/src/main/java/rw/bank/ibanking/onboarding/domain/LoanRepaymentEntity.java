package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import rw.bank.ibanking.common.money.Money;

/**
 * One payment against a loan, and how it was applied.
 *
 * <p>THE SPLIT IS THE POINT. The ledger already records that money left the account; what it
 * cannot say is which loan it paid and how much of it was interest. That depends on what was
 * outstanding at that moment, so it is unrecoverable afterwards and is written down when it
 * is known. It is also the question customers actually ask: "I paid 110,000, why did the
 * balance only drop by 94,000?"
 *
 * <p>interest + capital = amount, enforced by V34 as well as computed here. It is the
 * sentence the row exists to make.
 */
@Entity
@Table(name = "loan_repayments")
public class LoanRepaymentEntity {

    @Id private UUID id;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "interest_minor", nullable = false)
    private long interestMinor;

    @Column(name = "capital_minor", nullable = false)
    private long capitalMinor;

    @Column(name = "outstanding_after_minor", nullable = false)
    private long outstandingAfterMinor;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    protected LoanRepaymentEntity() {
        // JPA.
    }

    private LoanRepaymentEntity(
            UUID loanId,
            UUID customerId,
            UUID fromAccountId,
            Money amount,
            Money interest,
            Money capital,
            Money outstandingAfter) {

        this.id = UUID.randomUUID();
        this.loanId = loanId;
        this.customerId = customerId;
        this.fromAccountId = fromAccountId;
        this.currency = amount.currency();
        this.amountMinor = amount.minorUnits();
        this.interestMinor = interest.minorUnits();
        this.capitalMinor = capital.minorUnits();
        this.outstandingAfterMinor = outstandingAfter.minorUnits();
        this.paidAt = Instant.now();
    }

    public static LoanRepaymentEntity applied(
            UUID loanId,
            UUID customerId,
            UUID fromAccountId,
            Money amount,
            Money interest,
            Money capital,
            Money outstandingAfter) {

        return new LoanRepaymentEntity(
                loanId, customerId, fromAccountId, amount, interest, capital, outstandingAfter);
    }

    public UUID id() {
        return id;
    }

    public UUID loanId() {
        return loanId;
    }

    public UUID fromAccountId() {
        return fromAccountId;
    }

    public Money amount() {
        return new Money(amountMinor, currency);
    }

    /** The part that was the price of borrowing, not a reduction in what was borrowed. */
    public Money interest() {
        return new Money(interestMinor, currency);
    }

    public Money capital() {
        return new Money(capitalMinor, currency);
    }

    public Money outstandingAfter() {
        return new Money(outstandingAfterMinor, currency);
    }

    public Instant paidAt() {
        return paidAt;
    }
}
