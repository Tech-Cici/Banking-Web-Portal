package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * An account the customer holds at the bank, as the administrator entered it.
 *
 * <p>ENTERED, NOT OPENED, AND NOT VERIFIED HERE. The accounts already exist at Zigama. An
 * administrator reads them from the bank's own records — which this service has no view of
 * — and types them in when creating the login. Nothing in this class checks that the
 * account exists or belongs to this person: {@link #assignedBy()} is the record that a
 * named member of staff checked, in the same way the approving manager's name is the record
 * that the identity was checked.
 *
 * <p>THE BALANCE IS HELD HERE, and this comment used to say the opposite. It said the money
 * was core banking's and that a figure kept here would be a second, unreconciled copy. The
 * bank decided otherwise: an administrator enters an opening balance and the customer
 * deposits and withdraws in this portal. The risk the old comment named has not gone away —
 * if core banking also holds these balances the two WILL diverge, and nothing here
 * reconciles them. That is recorded in docs/OPEN-ITEMS.md as the bank's decision rather
 * than as a gap to close in code.
 *
 * <p>What the decision buys is a rule this class must keep: the balance is the SUM OF THE
 * LEDGER and nothing else. It moves only through {@link #credit} and {@link #debit}, each
 * of which {@code AccountLedgerService} pairs with a row in account_transactions in the same
 * database transaction. There is deliberately no setter — a balance that can be assigned
 * without an entry is a number nobody can account for afterwards.
 *
 * <p>THE FULL NUMBER TRAVELS TO ONE PLACE ONLY: its own holder, through
 * {@link #fullNumberForHolder()}. Everything else — the logs, the staff screens, any list,
 * anybody else's view of this account — reads {@link #maskedNumber()}.
 *
 * <p>That is a correction rather than the original design. The rule used to be that the
 * number never left this class at all, which is right for a log and wrong for the customer:
 * their own account number is on their passbook, and it is what they must give somebody in
 * order to be paid. Refusing to show it made receiving money impossible while the transfer
 * form was telling senders to type it in full.
 *
 * <p>So if you are about to widen {@link #accountNumber()}, the thing you want is still
 * almost certainly the mask — or, if the reader genuinely is the holder,
 * {@link #fullNumberForHolder()} behind an entitlement check.
 */
@Entity
@Table(name = "customer_accounts")
public class CustomerAccountEntity {

    /** Digits kept visible. Matches the portal's `**** 4582` form. */
    private static final int VISIBLE_DIGITS = 4;

    @Id private UUID id;

    /**
     * WHO THE ACCOUNT WAS ENTERED AGAINST — which is not always who may see it.
     *
     * <p>For a personal account this is the holder, and it is what grants access. For a
     * company account it records the login the administrator was creating at the time,
     * and grants nothing: access to a company account runs through
     * {@link CorporateMembershipEntity}, which is why a colleague added later sees the
     * same account without a second row being created for them.
     */
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /**
     * Set when the account belongs to a COMPANY rather than to the person above.
     *
     * <p>Null for a personal account. A company's money is not the finance director's
     * money: hang it off a person and removing that person orphans the accounts, while a
     * second signatory needs their own copy of each one — two records of one balance.
     */
    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "masked_number", nullable = false, length = 24)
    private String maskedNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 40)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "assigned_by", nullable = false, length = 160)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "removed_by", length = 160)
    private String removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removal_reason", length = 500)
    private String removalReason;

    /**
     * The balance, in minor units.
     *
     * <p>A RUNNING TOTAL OF THE LEDGER, not an independent figure. Every change goes
     * through {@link #credit} or {@link #debit}, each of which is paired with a row in
     * account_transactions — including the opening balance. A setter that moved this
     * without an entry would produce a number nobody could explain afterwards, so there
     * is not one.
     */
    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    /**
     * When the balance last moved. Null until it first does — see {@link #balanceAsOf()}.
     */
    @Column(name = "balance_as_of")
    private Instant balanceAsOf;

    protected CustomerAccountEntity() {
        // JPA.
    }

    private CustomerAccountEntity(
            UUID customerId,
            UUID companyId,
            String accountNumber,
            AccountType accountType,
            String currency,
            String assignedBy) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.companyId = companyId;
        this.accountNumber = accountNumber.trim();
        this.maskedNumber = mask(this.accountNumber);
        this.accountType = accountType;
        /*
         * Upper-cased here rather than trusted from the caller. ISO 4217 codes are
         * upper-case and the column is CHAR(3); a lower-case "usd" from one client would
         * otherwise sit beside "USD" as a separate value in every report that groups by it.
         */
        this.currency = currency.toUpperCase(Locale.ROOT);
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }

    /** An account held by a person, in their own name. */
    public static CustomerAccountEntity assigned(
            UUID customerId,
            String accountNumber,
            AccountType accountType,
            String currency,
            String assignedBy) {
        return new CustomerAccountEntity(
                customerId, null, accountNumber, accountType, currency, assignedBy);
    }

    /**
     * An account held by a COMPANY.
     *
     * <p>A separate factory rather than a nullable argument on the one above, so that
     * neither can be produced by accident: a caller that forgot to pass a company id
     * would create a company's account as one person's private holding, and every
     * visibility rule downstream would then be working from the wrong owner.
     */
    public static CustomerAccountEntity assignedToCompany(
            UUID enteredAgainstCustomerId,
            UUID companyId,
            String accountNumber,
            AccountType accountType,
            String currency,
            String assignedBy) {
        return new CustomerAccountEntity(
                enteredAgainstCustomerId,
                companyId,
                accountNumber,
                accountType,
                currency,
                assignedBy);
    }

    /** The portal's mask, applied once at entry. */
    static String mask(String accountNumber) {
        String digits = accountNumber.replaceAll("\\D", "");
        String tail =
                digits.length() <= VISIBLE_DIGITS
                        ? digits
                        : digits.substring(digits.length() - VISIBLE_DIGITS);
        return "**** " + tail;
    }

    /** Whether the customer can still see this account. */
    public boolean isActive() {
        return removedAt == null;
    }

    /**
     * Takes the account off the customer's profile, keeping the row.
     *
     * <p>Deleting would erase the fact that somebody once connected this customer to this
     * account, which is the first thing anyone would want to look up if it turns out to
     * have been the wrong number.
     */
    public void remove(String by, String reason) {
        if (!isActive()) {
            throw new IllegalStateException("That account has already been removed.");
        }
        this.removedBy = by;
        this.removedAt = Instant.now();
        this.removalReason = reason;
    }

    /** The balance, as money rather than a bare number. */
    public Money balance() {
        return new Money(balanceMinor, currency);
    }

    /**
     * When this balance was last true.
     *
     * <p>Falls back to when the account was entered, which is the honest answer for an
     * account that has had no movement: the figure has been what it is since then.
     */
    public Instant balanceAsOf() {
        return balanceAsOf == null ? assignedAt : balanceAsOf;
    }

    /**
     * Adds money.
     *
     * <p>ONLY {@code AccountLedgerService} MAY CALL THIS. It is public because Java has
     * no way to say "this package and that service" — the entity and the service live in
     * different packages — so the rule is a convention with two backstops rather than a
     * compiler guarantee: the service is the only caller, and the column carries a CHECK
     * constraint that a balance may not go negative however it is reached.
     *
     * <p>The rule it protects: every balance change is paired with a ledger entry, in the
     * same transaction. A call to this without one produces a number nobody can explain.
     */
    public void credit(Money amount) {
        requireSameCurrency(amount);
        this.balanceMinor = Math.addExact(balanceMinor, amount.minorUnits());
        this.balanceAsOf = Instant.now();
    }

    /**
     * Removes money, refusing to go below zero.
     *
     * <p>There is no overdraft product, so an account that could go negative would be
     * lending money nobody approved. Checked here as well as by a CHECK constraint on the
     * column — this one produces a sentence a customer can act on, the constraint catches
     * any path that forgets to.
     */
    public void debit(Money amount) {
        requireSameCurrency(amount);
        if (amount.minorUnits() > balanceMinor) {
            throw new BusinessRuleException(
                    "There is not enough in that account to cover this withdrawal.");
        }
        this.balanceMinor = Math.subtractExact(balanceMinor, amount.minorUnits());
        this.balanceAsOf = Instant.now();
    }

    private void requireSameCurrency(Money amount) {
        if (!amount.currency().equals(currency)) {
            throw new BusinessRuleException(
                    "That amount is in " + amount.currency() + " and the account is in "
                            + currency + ".");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    /** The company that holds this account, or null for a personal one. */
    public UUID companyId() {
        return companyId;
    }

    /** Whether this account belongs to a company rather than to an individual. */
    public boolean isCompanyAccount() {
        return companyId != null;
    }

    /**
     * THE FULL NUMBER, for matching an account inside this package.
     *
     * <p>Package-private. Not for logging, and not for a response body — with the one
     * exception below, which is its own method so that the exception is visible at every
     * call site rather than inferred from this one.
     */
    String accountNumber() {
        return accountNumber;
    }

    /**
     * THE FULL NUMBER, FOR ITS OWN HOLDER TO READ.
     *
     * <p>WHY THIS EXISTS, because the rule above was over-applied and it broke something.
     * The mask exists so a full account number never reaches a log, an aggregator, a
     * screenshot or somebody else's screen. A customer's own number is none of those: it
     * is printed on their passbook and their statement, and it is the thing they have to
     * give somebody in order to BE PAID.
     *
     * <p>The portal refused to show it anywhere, while the transfer form told the sender
     * to type "the full number, not the masked one". Between them, a customer could not
     * receive money: to be paid they had to read out a number their own bank would not
     * show them.
     *
     * <p>So this is a separate, differently-named accessor rather than a widened
     * {@link #accountNumber()}: a reader seeing {@code fullNumberForHolder()} at a call
     * site can tell it is the deliberate exception, and a reader seeing
     * {@code accountNumber()} still knows it must not travel. The CALLER remains
     * responsible for proving the caller is the holder — {@code AccountAccess.mayAct}
     * does that, and nothing here can check it.
     */
    public String fullNumberForHolder() {
        return accountNumber;
    }

    public String maskedNumber() {
        return maskedNumber;
    }

    public AccountType accountType() {
        return accountType;
    }

    public String currency() {
        return currency;
    }

    public String assignedBy() {
        return assignedBy;
    }

    public Instant assignedAt() {
        return assignedAt;
    }

    public String removedBy() {
        return removedBy;
    }

    public Instant removedAt() {
        return removedAt;
    }

    public String removalReason() {
        return removalReason;
    }
}
