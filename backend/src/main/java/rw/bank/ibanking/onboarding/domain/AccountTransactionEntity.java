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

/**
 * One movement on an account.
 *
 * <p>THE LEDGER IS THE TRUTH. An account's balance is the sum of these, and
 * {@code customer_accounts.balance_minor} is a running total of them — including the
 * opening balance, which is a CREDIT like any other rather than a number set directly. A
 * balance that can change without an entry is a figure nobody can explain afterwards.
 *
 * <p>{@link #balanceAfter()} is stored rather than recomputed, so a statement can be read
 * without replaying history and so a drift between the two is findable.
 */
@Entity
@Table(name = "account_transactions")
public class AccountTransactionEntity {

    @Id private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "balance_after_minor", nullable = false)
    private long balanceAfterMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "performed_by", nullable = false, length = 160)
    private String performedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    /**
     * The other party's name AS IT WAS when this entry was posted, or null.
     *
     * <p>Null is the ordinary case and means this movement had no other party — a
     * deposit, a withdrawal, an opening balance. There is no placeholder, because a
     * placeholder in a name column reads as a fact and every screen then has to know to
     * hide it.
     *
     * <p>NEVER UPDATED. A statement records a moment. If this were refreshed from the
     * customer record, somebody changing their name would rewrite every statement they
     * had ever appeared on. See V13 for the longer argument.
     */
    @Column(name = "counterparty_name", length = 160)
    private String counterpartyName;

    /** The other party's MASK, e.g. {@code **** 7898}. Never the full number. */
    @Column(name = "counterparty_mask", length = 24)
    private String counterpartyMask;

    /**
     * What this movement was, as the server understands it.
     *
     * <p>Not derivable from the direction: a transfer arriving and a transfer being
     * refused are both a credit with somebody else's name on them. See {@link
     * MovementKind}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "movement_kind", nullable = false, length = 24)
    private MovementKind movementKind;

    protected AccountTransactionEntity() {
        // JPA.
    }

    private AccountTransactionEntity(
            UUID accountId,
            TransactionDirection direction,
            Money amount,
            Money balanceAfter,
            String description,
            String performedBy,
            String idempotencyKey,
            MovementKind movementKind,
            String counterpartyName,
            String counterpartyMask) {
        /*
         * BOTH OR NEITHER, checked here as well as by the database constraint. The
         * constraint is what actually guarantees it; this is what makes a caller who got
         * it wrong fail at the call site rather than in a Hibernate flush at the end of
         * the transaction, where the stack trace names nothing useful.
         */
        if ((counterpartyName == null) != (counterpartyMask == null)) {
            throw new IllegalArgumentException(
                    "A counterparty needs both a name and a mask, or neither.");
        }

        /*
         * AND THE KIND MUST AGREE WITH THEM. A transfer with nobody on the other side,
         * or cash with somebody, is a row whose two halves tell different stories — and
         * a screen reading one of them would be confidently wrong rather than merely
         * unhelpful.
         */
        if (movementKind.hasCounterparty() != (counterpartyName != null)) {
            throw new IllegalArgumentException(
                    "A " + movementKind + " movement disagrees with its counterparty.");
        }

        this.movementKind = movementKind;
        this.counterpartyName = counterpartyName;
        this.counterpartyMask = counterpartyMask;
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amount.minorUnits();
        this.balanceAfterMinor = balanceAfter.minorUnits();
        this.currency = amount.currency();
        this.description = description;
        this.performedBy = performedBy;
        this.createdAt = Instant.now();
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * A movement with no other party: cash over the counter, or an opening balance.
     *
     * <p>Kept as its own factory rather than made to pass two nulls, so that every call
     * site reads as a deliberate statement that there was nobody on the other end.
     */
    public static AccountTransactionEntity posted(
            UUID accountId,
            TransactionDirection direction,
            Money amount,
            Money balanceAfter,
            String description,
            String performedBy,
            String idempotencyKey) {
        return new AccountTransactionEntity(
                accountId, direction, amount, balanceAfter, description, performedBy,
                idempotencyKey, MovementKind.CASH, null, null);
    }

    /**
     * A movement between two accounts, remembering who the other one belonged to.
     *
     * @param counterpartyName the other party as they are named NOW, copied in and never
     *     refreshed
     * @param counterpartyMask their masked account number — the mask, never the number
     */
    public static AccountTransactionEntity postedBetween(
            UUID accountId,
            TransactionDirection direction,
            Money amount,
            Money balanceAfter,
            String description,
            String performedBy,
            String idempotencyKey,
            MovementKind movementKind,
            String counterpartyName,
            String counterpartyMask) {
        return new AccountTransactionEntity(
                accountId, direction, amount, balanceAfter, description, performedBy,
                idempotencyKey, movementKind, counterpartyName, counterpartyMask);
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public TransactionDirection direction() {
        return direction;
    }

    public Money amount() {
        return new Money(amountMinor, currency);
    }

    public Money balanceAfter() {
        return new Money(balanceAfterMinor, currency);
    }

    public String currency() {
        return currency;
    }

    public String description() {
        return description;
    }

    public String performedBy() {
        return performedBy;
    }

    /** The other party's name as it was, or null when this movement had no other party. */
    public String counterpartyName() {
        return counterpartyName;
    }

    /** The other party's mask, or null. Present exactly when the name is. */
    public String counterpartyMask() {
        return counterpartyMask;
    }

    /** Whether this movement had another party at all. */
    public boolean hasCounterparty() {
        return counterpartyName != null;
    }

    /** What this movement was. Never null. */
    public MovementKind movementKind() {
        return movementKind;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
