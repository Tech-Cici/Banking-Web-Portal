package rw.bank.ibanking.onboarding.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.AccountTransactionEntity;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.MovementKind;
import rw.bank.ibanking.onboarding.domain.TransactionDirection;
import rw.bank.ibanking.onboarding.repo.AccountTransactionRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;

/**
 * Money in and out of a customer's account.
 *
 * <p>This is the only code that moves a balance, and every movement it makes is paired with
 * a ledger entry in the same transaction. The balance is a running total of those entries
 * and nothing else — there is no path that changes one without the other.
 *
 * <p>FOUR PROPERTIES, each of which is a way real money goes missing:
 *
 * <ul>
 *   <li><b>Atomic.</b> The entry and the new balance commit together or not at all. Half of
 *       a deposit is a discrepancy nobody can reconstruct.
 *   <li><b>Serialised per account.</b> The row is locked for update, so two withdrawals
 *       arriving together cannot both read the old balance and both decide there is enough.
 *   <li><b>Idempotent.</b> A retry after a lost response returns the original entry rather
 *       than posting the money a second time.
 *   <li><b>Never negative.</b> There is no overdraft product, so a withdrawal that exceeds
 *       the balance is refused rather than lending money nobody approved.
 * </ul>
 */
@Service
public class AccountLedgerService {

    private static final Logger log = LoggerFactory.getLogger(AccountLedgerService.class);

    private final CustomerAccountRepository accounts;
    private final AccountTransactionRepository transactions;
    private final AccountAccess access;

    AccountLedgerService(
            CustomerAccountRepository accounts,
            AccountTransactionRepository transactions,
            AccountAccess access) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.access = access;
    }

    /** Money in. */
    @Transactional
    public AccountTransactionEntity deposit(
            UUID customerId,
            UUID accountId,
            String amount,
            String description,
            String performedBy,
            String idempotencyKey) {

        return post(
                customerId,
                accountId,
                TransactionDirection.CREDIT,
                amount,
                description,
                performedBy,
                idempotencyKey);
    }

    /** Money out. */
    @Transactional
    public AccountTransactionEntity withdraw(
            UUID customerId,
            UUID accountId,
            String amount,
            String description,
            String performedBy,
            String idempotencyKey) {

        return post(
                customerId,
                accountId,
                TransactionDirection.DEBIT,
                amount,
                description,
                performedBy,
                idempotencyKey);
    }

    /**
     * The opening balance an administrator entered.
     *
     * <p>A CREDIT like any other, deliberately. It would be simpler to set the balance
     * directly, and that is exactly the thing worth refusing: a starting figure with no
     * entry behind it is a number nobody can account for, and the sum of the ledger would
     * no longer equal the balance from the very first day.
     *
     * <p>Not customer-facing — this is called while the login is being created.
     */
    @Transactional
    public void openWith(
            CustomerAccountEntity account, Money opening, String staffName, String idempotencyKey) {

        if (opening.isZero()) {
            // Nothing happened; an entry saying so would be noise on every statement.
            return;
        }

        account.credit(opening);
        accounts.save(account);

        transactions.save(
                AccountTransactionEntity.posted(
                        account.id(),
                        TransactionDirection.CREDIT,
                        opening,
                        account.balance(),
                        "Opening balance",
                        staffName,
                        idempotencyKey));

        /*
         * The MASK, never the number, and the amount because an opening balance is a
         * figure somebody will want to trace back to who entered it.
         */
        log.info(
                "Opening balance {} {} on {} entered by {}",
                opening.toPlainString(),
                opening.currency(),
                account.maskedNumber(),
                staffName);
    }

    @Transactional(readOnly = true)
    public List<AccountTransactionEntity> statement(UUID accountId, int limit) {
        return transactions.findByAccountIdOrderByCreatedAtDesc(accountId, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<AccountTransactionEntity> recentAcross(List<UUID> accountIds, int limit) {
        if (accountIds.isEmpty()) return List.of();
        return transactions.findByAccountIdInOrderByCreatedAtDesc(accountIds, Limit.of(limit));
    }

    /* ------------------------------------------------------------ the core */

    /**
     * Posts one entry against an account the CALLER has already loaded and locked.
     *
     * <p>FOR TRANSFERS, AND THE ENTITLEMENT IS THE CALLER'S JOB. Every other way into
     * this class checks {@link AccountAccess#mayAct} first, and this one cannot: half of
     * a transfer is a credit to the BENEFICIARY, who is not the person instructing it and
     * would fail that check by definition. A beneficiary who had to be entitled to the
     * payment could never be paid.
     *
     * <p>So the rule moves one level up: {@code TransferService} checks the customer
     * against the SOURCE account, and the destination is reachable only because the
     * customer supplied its full account number. Nothing else may call this — a second
     * caller is a second place where "may this person move this money" is decided, which
     * is the arrangement {@link AccountAccess} exists to prevent.
     *
     * <p>Package-private for that reason, and it takes an entity rather than an id so it
     * cannot be called without the caller having loaded the row under a lock.
     */
    @Transactional
    AccountTransactionEntity postAgainst(
            CustomerAccountEntity account,
            TransactionDirection direction,
            Money amount,
            String description,
            String performedBy,
            String idempotencyKey,
            MovementKind movementKind,
            String counterpartyName,
            String counterpartyMask) {

        /*
         * The same replay check the customer-facing path makes. A retried approval must
         * return the entry it already made rather than crediting the beneficiary twice.
         */
        var existing = transactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();

        if (direction == TransactionDirection.CREDIT) {
            account.credit(amount);
        } else {
            // Throws if it would go below zero. See CustomerAccountEntity.debit.
            account.debit(amount);
        }
        accounts.save(account);

        AccountTransactionEntity entry =
                AccountTransactionEntity.postedBetween(
                        account.id(),
                        direction,
                        amount,
                        account.balance(),
                        description,
                        performedBy,
                        idempotencyKey,
                        /*
                         * WHAT IT WAS, AND WHO WAS ON THE OTHER END. Every movement that
                         * reaches this method is one half of a transfer, so there is
                         * always another party — which is exactly what the statement
                         * could not say before.
                         */
                        movementKind,
                        counterpartyName,
                        counterpartyMask);
        transactions.save(entry);

        log.info(
                "{} of {} on account {} ({})",
                direction,
                amount.minorUnits(),
                account.maskedNumber(),
                description);

        return entry;
    }

    private AccountTransactionEntity post(
            UUID customerId,
            UUID accountId,
            TransactionDirection direction,
            String rawAmount,
            String description,
            String performedBy,
            String idempotencyKey) {

        /*
         * IDEMPOTENCY FIRST, before anything is read or locked.
         *
         * The case this serves: the customer taps deposit, the money moves, and the
         * response is lost on a bad connection. The app retries with the same key. Without
         * this check the money is deposited twice and only a reconciliation weeks later
         * would find it.
         *
         * The replayed entry is returned unchanged rather than re-posted, so the client
         * sees the same result it would have seen the first time.
         */
        var existing = transactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            AccountTransactionEntity replay = existing.get();
            if (!replay.accountId().equals(accountId)) {
                /*
                 * The same key against a different account is not a retry; it is a client
                 * reusing a key it should have replaced. Failing loudly beats guessing
                 * which of the two requests was meant.
                 */
                throw new BusinessRuleException(
                        "That request has already been used for a different account.");
            }
            log.info("Replayed {} on account {} for an existing key", direction, accountId);
            return replay;
        }

        /*
         * Locked for update. Two withdrawals arriving together would otherwise both read
         * the old balance, both decide there is enough, and between them take out more
         * than the account held.
         */
        /*
         * WHOSE MONEY IT IS is decided by AccountAccess, not here. This used to compare
         * `customer_id` inline, which was the entire rule while a personal account was
         * the only kind. A company's accounts are reached through membership, and a
         * comparison on `customer_id` would have let the contact the accounts happened to
         * be entered against keep moving the company's money after their access was
         * revoked — while every colleague who was granted access could move none of it.
         *
         * `mayAct` also covers the removed-account case the second filter used to, so
         * there is one place to read and one place to change.
         */
        CustomerAccountEntity account =
                accounts.findByIdForUpdate(accountId)
                        .filter(found -> access.mayAct(customerId, found))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that account."));

        // Parsed against the ACCOUNT's currency, not one the caller supplied.
        Money amount = Money.parse(rawAmount, account.currency());

        if (amount.isZero()) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }

        if (direction == TransactionDirection.CREDIT) {
            account.credit(amount);
        } else {
            // Throws if it would go below zero. See CustomerAccountEntity.debit.
            account.debit(amount);
        }

        accounts.save(account);

        AccountTransactionEntity entry =
                AccountTransactionEntity.posted(
                        account.id(),
                        direction,
                        amount,
                        account.balance(),
                        description,
                        performedBy,
                        idempotencyKey);
        transactions.save(entry);

        /*
         * Amount and mask, never the account number. A statement line in a log is fine; a
         * full account number in a log aggregator is not.
         */
        log.info(
                "{} of {} {} on {}, balance now {}",
                direction,
                amount.toPlainString(),
                amount.currency(),
                account.maskedNumber(),
                account.balance().toPlainString());

        return entry;
    }
}
