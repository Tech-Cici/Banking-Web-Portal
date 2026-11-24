package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.common.time.BankZone;

/**
 * A repeating payment.
 *
 * <p>THE DESTINATION IS AN ACCOUNT ID, FIXED AT CREATION. It is never re-resolved on a run,
 * so nothing edited afterwards can redirect a payment that was already authorised against
 * the account it named -- the purpose the payee verification in V17 exists to serve.
 *
 * <p>IT IS NO LONGER LOOKED UP FROM AN ACCOUNT NUMBER. It used to be: the payee's number was
 * resolved to "the first account carrying it", and since V26 one customer number carries one
 * account per product, so "the first" was whichever row came back. Written onto the order
 * once, that guess then repeated every period for the life of the order. A sweep between the
 * customer's own accounts picks the destination by id, which removes the lookup instead of
 * correcting it.
 *
 * <p>THE SCHEDULE IS IN INSTANTS. V27 says why; the short version is that a period shorter
 * than a day has no date to be on. Date-shaped periods are start-of-day in {@link BankZone},
 * so "the 5th" is still exactly one moment.
 */
@Entity
@Table(name = "standing_orders")
public class StandingOrderEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    /** The saved payee, when the order pays one. Null for a sweep between the customer's
     * own accounts, where the destination is chosen by id and no payee is involved. */
    @Column(name = "beneficiary_id")
    private UUID beneficiaryId;

    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 140)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StandingOrderFrequency frequency;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StandingOrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_failure", length = 300)
    private String lastFailure;

    protected StandingOrderEntity() {
        // JPA.
    }

    private StandingOrderEntity(
            UUID customerId,
            UUID sourceAccountId,
            UUID beneficiaryId,
            UUID destinationAccountId,
            Money amount,
            String reference,
            StandingOrderFrequency frequency,
            Instant startAt,
            LocalDate endsOn) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.sourceAccountId = sourceAccountId;
        this.beneficiaryId = beneficiaryId;
        this.destinationAccountId = destinationAccountId;
        this.amountMinor = amount.minorUnits();
        this.currency = amount.currency();
        this.reference = reference;
        this.frequency = frequency;
        this.nextRunAt = startAt;
        this.endsOn = endsOn;
        this.status = StandingOrderStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public static StandingOrderEntity created(
            UUID customerId,
            UUID sourceAccountId,
            UUID beneficiaryId,
            UUID destinationAccountId,
            Money amount,
            String reference,
            StandingOrderFrequency frequency,
            Instant startAt,
            LocalDate endsOn) {

        return new StandingOrderEntity(
                customerId,
                sourceAccountId,
                beneficiaryId,
                destinationAccountId,
                amount,
                reference,
                frequency,
                startAt,
                endsOn);
    }

    /**
     * Records a successful occurrence and moves to the next.
     *
     * <p>ENDS WHEN THE NEXT DATE WOULD PASS THE END DATE, not when it equals it: an order
     * ending on the 31st should pay on the 31st.
     */
    public void paid(Instant occurrence) {
        this.lastRunAt = occurrence;
        this.lastFailure = null;
        advanceFrom(occurrence);
    }

    /**
     * Records an occurrence that could not be paid, and moves on.
     *
     * <p>DOES NOT RETRY. The period is gone and the customer is told; a payment that lands
     * whenever money happens to appear is a payment nobody chose the timing of.
     */
    public void failed(Instant occurrence, String reason) {
        this.lastFailure = reason;
        advanceFrom(occurrence);
    }

    /*
     * THE END DATE IS COMPARED ON THE BANK'S CALENDAR, not against the raw instant. ends_on
     * is a date -- "this stops on 31 December" -- so the question is which of the bank's days
     * the next occurrence lands on. Comparing an instant to a date in any other zone would
     * end an order a day early or late for whoever is reading it.
     */
    private void advanceFrom(Instant occurrence) {
        Instant next = frequency.next(occurrence);
        if (endsOn != null && BankZone.dateOf(next).isAfter(endsOn)) {
            this.status = StandingOrderStatus.ENDED;
        }
        this.nextRunAt = next;
    }

    /** Pauses an active order, or resumes a paused one. An ENDED order stays ended. */
    public void togglePause() {
        if (status == StandingOrderStatus.ACTIVE) {
            this.status = StandingOrderStatus.PAUSED;
        } else if (status == StandingOrderStatus.PAUSED) {
            this.status = StandingOrderStatus.ACTIVE;
        }
    }

    public void cancelled() {
        this.status = StandingOrderStatus.ENDED;
    }

    public boolean isActive() {
        return status == StandingOrderStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID sourceAccountId() {
        return sourceAccountId;
    }

    public UUID beneficiaryId() {
        return beneficiaryId;
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

    public StandingOrderFrequency frequency() {
        return frequency;
    }

    public Instant nextRunAt() {
        return nextRunAt;
    }

    public LocalDate endsOn() {
        return endsOn;
    }

    public StandingOrderStatus status() {
        return status;
    }

    public Instant lastRunAt() {
        return lastRunAt;
    }

    public String lastFailure() {
        return lastFailure;
    }
}
