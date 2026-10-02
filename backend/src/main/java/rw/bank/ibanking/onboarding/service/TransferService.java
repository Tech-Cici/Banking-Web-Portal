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
import rw.bank.ibanking.onboarding.domain.CompanyEntity;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.domain.MovementKind;
import rw.bank.ibanking.onboarding.domain.TransactionDirection;
import rw.bank.ibanking.onboarding.domain.TransferEntity;
import rw.bank.ibanking.onboarding.domain.TransferStatus;
import rw.bank.ibanking.onboarding.repo.CompanyRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.TransferRepository;

/**
 * MOVING MONEY BETWEEN ACCOUNTS IN THIS SERVICE, held until a manager decides.
 *
 * <p>Three operations and one invariant. A customer submits, which debits them at once; a
 * manager approves, which credits the beneficiary; or a manager rejects, which gives the
 * money back. Nothing else changes a transfer.
 *
 * <p>THE INVARIANT, stated so a future change can be checked against it:
 *
 * <pre>
 *     sum(every account balance) + sum(every pending transfer) = constant
 * </pre>
 *
 * <p>Between submission and a decision the money is in flight: it has left the sender and
 * not arrived anywhere, so the accounts alone are short by the pending total. A bank holds
 * that difference in a suspense account; this service has none, so the invariant above is
 * asserted in {@code TransferTest} instead. A code path that loses money in flight fails a
 * test rather than turning up in a reconciliation nobody runs.
 *
 * <p>WHY THE SENDER IS DEBITED AT SUBMISSION rather than at approval. Held only at
 * approval, a customer could submit their whole balance five times and a manager could
 * approve all five — four failing AFTER a manager had said yes, which is the worst moment
 * for a payment to fail. Debiting first makes "never negative" true at the only point
 * where it can be enforced against the customer rather than against the manager.
 *
 * <p>ORDER OF LOCKS. When both accounts must be locked, they are locked in a fixed order
 * by id. Two transfers in opposite directions between the same pair of accounts, arriving
 * together, would otherwise each hold the lock the other needs and deadlock. Sorting by
 * id is arbitrary but consistent, which is all a deadlock needs.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    /** How many rows a queue or list hands back at once. */
    private static final int PAGE = 100;

    private final TransferRepository transfers;
    private final CustomerAccountRepository accounts;
    private final AccountLedgerService ledger;
    private final AccountAccess access;
    private final CustomerRepository customers;
    private final CompanyRepository companies;
    private final BeneficiaryLookups lookups;

    /** Who holds an account, shared with the payee review queue. */
    private final AccountHolders holders;

    private final Notifications notifications;

    TransferService(
            TransferRepository transfers,
            CustomerAccountRepository accounts,
            AccountLedgerService ledger,
            AccountAccess access,
            CustomerRepository customers,
            CompanyRepository companies,
            BeneficiaryLookups lookups,
            AccountHolders holders,
            Notifications notifications) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.access = access;
        this.customers = customers;
        this.companies = companies;
        this.lookups = lookups;
        this.holders = holders;
        this.notifications = notifications;
    }

    /**
     * What the customer asked for, before it is a transfer.
     *
     * <p>THE DESTINATION ARRIVES ONE OF TWO WAYS, and exactly one must be set.
     *
     * <ul>
     *   <li>{@code destinationAccountNumber} — they typed somebody else's full number.
     *   <li>{@code destinationAccountId} — they picked one of their OWN accounts from a
     *       list.
     * </ul>
     *
     * <p>The second exists because the client is never sent a full account number: every
     * account response carries only {@code **** 4582}. So a "move money between my
     * accounts" picker has no number to send, and a number-only endpoint made the most
     * ordinary transfer in the product impossible — current account to savings account,
     * same person.
     *
     * <p>The id path is not a shortcut around the number path. An id is only accepted
     * for an account this customer may already act on, checked the same way the source
     * is; anybody else's account is reachable only by knowing its number.
     */
    public record Instruction(
            UUID sourceAccountId,
            String destinationAccountNumber,
            UUID destinationAccountId,
            String amount,
            String reference,
            String idempotencyKey) {}

    /* ----------------------------------------------------- the customer's side */

    /** Who holds an account number, so a mistyped digit is caught before the money moves. */
    public record Beneficiary(String holderName, String maskedNumber, String currency) {}

    /**
     * Resolves a beneficiary from the full account number the customer typed.
     *
     * <p>THIS IS AN ORACLE, AND IT IS BOUNDED RATHER THAN AVOIDED. Answering "who holds
     * this number" catches the mistake that cannot be undone — money sent to a stranger,
     * which no manager reviewing a queue can spot, because they have no idea who the
     * sender meant to pay. Asked at scale, the same answer turns a list of account
     * numbers into a list of the people behind them.
     *
     * <p>So it is allowed and limited: {@link BeneficiaryLookups} caps one customer at
     * twenty lookups an hour, which is far above what paying people requires and far
     * below what walking a number range requires. The caller must already be a signed-in
     * customer, must supply the FULL number — masks are not unique and are not accepted —
     * and every lookup is logged against them.
     *
     * <p>Returns a NAME, A MASK AND A CURRENCY. Not the account id, and not the number the
     * caller supplied: nothing in the answer can be replayed as a destination by somebody
     * who did not already have the number.
     */
    @Transactional(readOnly = true)
    public Beneficiary resolve(UUID customerId, String accountNumber) {
        // Counted BEFORE the lookup, so a miss costs the same allowance as a hit.
        // Charging only for hits would let somebody probe freely until they found one.
        lookups.record(customerId);

        CustomerAccountEntity found =
                destinationOrRefuse(
                        customerId,
                        new Instruction(null, accountNumber, null, null, null, null));

        /*
         * Logged with the MASK, never the number the caller typed. A log of full account
         * numbers somebody probed for is the harvest this method worries about, made
         * permanent and shipped to every aggregator.
         */
        log.info("Customer {} resolved beneficiary {}", customerId, found.maskedNumber());

        return new Beneficiary(
                holderNameOf(found), found.maskedNumber(), found.currency());
    }

    /**
     * Submits a transfer and debits the sender.
     *
     * <p>Everything that can refuse it refuses here, before a manager's time is spent:
     * entitlement to the source account, the destination existing, both being in the same
     * currency, and the sender having the money.
     */
    @Transactional
    public TransferEntity submit(UUID customerId, Instruction instruction) {

        /*
         * IDEMPOTENCY FIRST, before anything is read or locked — the same order the cash
         * ledger uses. The case: the customer taps Send, the money is held, and the
         * response is lost. The app retries with the same key. Without this the sender is
         * debited twice and the second instruction sits in a manager's queue looking
         * every bit as legitimate as the first.
         */
        var replay = transfers.findByIdempotencyKey(instruction.idempotencyKey());
        if (replay.isPresent()) {
            log.info("Replayed transfer submission for an existing key");
            return replay.get();
        }

        CustomerAccountEntity destination = destinationOrRefuse(customerId, instruction);

        /*
         * The source, locked. Two transfers out of one account arriving together would
         * otherwise both read the old balance and both decide there is enough.
         */
        CustomerAccountEntity source = sourceOrRefuse(customerId, instruction.sourceAccountId());

        if (source.id().equals(destination.id())) {
            throw new BusinessRuleException(
                    "That is the same account. Choose a different one to send to.");
        }

        /*
         * There is deliberately no separate "may this account be debited" check here.
         * `debitAllowed` in the API response is simply the account being active, and
         * AccountAccess.mayAct already refuses an inactive one — so a second check would
         * be a second place the same rule is written, which is the arrangement that class
         * exists to end.
         */

        /*
         * SAME CURRENCY ONLY. A cross-currency transfer needs an exchange rate, and this
         * service has none — inventing one would be deciding what somebody's money is
         * worth. Refused with a sentence that says what to do instead.
         */
        if (!source.currency().equals(destination.currency())) {
            throw new BusinessRuleException(
                    "That account is in "
                            + source.currency()
                            + " and the one you are sending to is in "
                            + destination.currency()
                            + ". Foreign exchange is not available in the portal yet.");
        }

        // Parsed against the ACCOUNT's currency, never one the caller supplied.
        Money amount = Money.parse(instruction.amount(), source.currency());

        if (amount.isZero()) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }

        /*
         * THE DEBIT IS THE HOLD. Throws if it would take the account below zero, which is
         * where a customer is told they do not have the money — rather than a manager
         * being told, later, about a payment they have already approved.
         */
        AccountTransactionEntity debit =
                ledger.postAgainst(
                        source,
                        TransactionDirection.DEBIT,
                        amount,
                        "Sent, awaiting approval",
                        performerFor(customerId, source),
                        "transfer-out-" + instruction.idempotencyKey(),
                        /*
                         * THE PAYEE, NAMED. The description used to carry the
                         * payee's masked number and nothing else, so a statement
                         * said money had gone to "**** 7898" and left the
                         * customer to remember who that was. The name now lives
                         * in its own column and the description says only what
                         * happened, which is what each of them is for.
                         */
                        MovementKind.TRANSFER_OUT,
                        holderNameOf(destination),
                        destination.maskedNumber());

        TransferEntity transfer =
                transfers.save(
                        TransferEntity.held(
                                source.id(),
                                customerId,
                                destination.id(),
                                amount,
                                instruction.reference(),
                                instruction.idempotencyKey(),
                                debit.id()));

        /*
         * Masks only. A transfer log line naming both full account numbers is a map of
         * who pays whom, in every aggregator and backup thereafter.
         */
        log.info(
                "Transfer {} submitted: {} from {} to {}, awaiting approval",
                transfer.id(),
                amount.minorUnits(),
                source.maskedNumber(),
                destination.maskedNumber());

        return transfer;
    }

    /** Everything this customer's accounts have sent or received. */
    @Transactional(readOnly = true)
    public List<TransferEntity> forCustomer(UUID customerId) {
        List<UUID> reachable =
                access.accountsFor(customerId).stream().map(CustomerAccountEntity::id).toList();

        return reachable.isEmpty()
                ? List.of()
                : transfers.findTouching(reachable, Limit.of(PAGE));
    }

    /**
     * What is currently held out of this customer's accounts.
     *
     * <p>The dashboard needs this to explain a balance that has already dropped. Without
     * it the customer sees money gone with nothing saying where, which is the complaint
     * that arrives by telephone.
     */
    @Transactional(readOnly = true)
    public List<TransferEntity> pendingFor(UUID customerId) {
        List<UUID> reachable =
                access.accountsFor(customerId).stream().map(CustomerAccountEntity::id).toList();

        return reachable.isEmpty()
                ? List.of()
                : transfers.findBySourceAccountIdInAndStatus(
                        reachable, TransferStatus.PENDING_APPROVAL);
    }

    /* ------------------------------------------------------- the manager's side */

    /** Everything waiting on a decision, oldest first — a queue is worked from the front. */
    @Transactional(readOnly = true)
    public List<TransferEntity> queue() {
        return transfers.findByStatusOrderBySubmittedAtAsc(
                TransferStatus.PENDING_APPROVAL, Limit.of(PAGE));
    }

    /**
     * Releases a transfer: the beneficiary is credited, in this transaction.
     *
     * <p>There is no gap between approving and arriving, which is why {@code TransferStatus}
     * has no SENT state. If this commits, the money is there.
     */
    @Transactional
    public TransferEntity approve(UUID transferId, StaffEntity manager) {
        TransferEntity transfer = pendingOrRefuse(transferId);

        CustomerAccountEntity destination =
                accounts
                        .findByIdForUpdate(transfer.destinationAccountId())
                        .orElseThrow(
                                () ->
                                        new BusinessRuleException(
                                                "The account this was being sent to is no longer"
                                                        + " there. Refuse the transfer so the money"
                                                        + " goes back."));

        /*
         * A beneficiary account removed while the transfer waited. Crediting it would put
         * money somewhere the customer can no longer see, so the honest answer is to
         * refuse the approval and tell the manager to reject it instead — which returns
         * the money rather than stranding it.
         */
        if (!destination.isActive()) {
            throw new BusinessRuleException(
                    "The account this was being sent to has been removed. Refuse the transfer"
                            + " so the money goes back to the sender.");
        }

        /*
         * The sender, read only so the beneficiary's statement can name them. NO LOCK:
         * nothing about this row is being changed, and a second pessimistic lock would
         * add an ordering constraint between the two accounts for no benefit. Lock
         * ordering is the one thing this service must get right to stop two opposite
         * transfers deadlocking against each other.
         */
        Counterparty sender = counterpartyOf(transfer.sourceAccountId());

        AccountTransactionEntity credit =
                ledger.postAgainst(
                        destination,
                        TransactionDirection.CREDIT,
                        transfer.amount(),
                        "Received — " + transfer.reference(),
                        manager.fullName(),
                        /*
                         * Derived from the transfer id, so a retried or double-clicked
                         * approval can never credit the beneficiary a second time. The
                         * entity refuses a second decision as well; this is the backstop
                         * that holds even if that check is ever loosened.
                         */
                        "transfer-in-" + transfer.id(),
                        /*
                         * THE SENDER, NAMED, which is the whole point of this
                         * line for the person receiving the money: "you received
                         * 5,000 from Teta Eliana" rather than "a credit arrived".
                         * Read from the source account rather than from the
                         * manager who approved it — the manager released the
                         * payment, they did not make it.
                         */
                        MovementKind.TRANSFER_IN,
                        sender.name(),
                        sender.mask());

        transfer.approve(manager.fullName(), credit.id());
        transfers.save(transfer);

        /*
         * THE SENDER IS FINALLY TOLD.
         *
         * <p>Until now nothing told them at all: this method sends no email, so the only
         * way to learn that one's own money had actually left was to keep reloading the
         * dashboard's "Waiting for the bank" panel until the row disappeared from it. It is
         * the single most important thing in this service to notify about, and it was the
         * one event with no channel.
         */
        notifyTheSender(
                transfer.sourceAccountId(),
                owner ->
                        notifications.transferReleased(
                                owner,
                                transfer.amount(),
                                destination.maskedNumber(),
                                transfer.reference()));

        log.info(
                "Transfer {} approved by {}: {} delivered to {}",
                transfer.id(),
                manager.email(),
                transfer.amount().minorUnits(),
                destination.maskedNumber());

        return transfer;
    }

    /**
     * Refuses a transfer and gives the money back.
     *
     * <p>The reversal is its own CREDIT rather than an edit to the original debit. An
     * entry that can be unwritten is a ledger nobody can audit: the customer's statement
     * should show the money leaving and coming back, with the reason, not show nothing
     * having happened.
     */
    @Transactional
    public TransferEntity reject(UUID transferId, String reason, StaffEntity manager) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Give a reason for refusing this transfer.");
        }

        TransferEntity transfer = pendingOrRefuse(transferId);

        CustomerAccountEntity source =
                accounts
                        .findByIdForUpdate(transfer.sourceAccountId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "The source account of a pending transfer is"
                                                        + " missing; its money cannot be returned."));

        /*
         * Credited back even if the account has since been removed from the profile. The
         * money is the customer's and belongs on the row it came from; leaving it in
         * flight because the account was taken off a screen would lose it outright.
         */
        /* The payee who did not get it, read without a lock for the same reason. */
        Counterparty payee = counterpartyOf(transfer.destinationAccountId());

        AccountTransactionEntity reversal =
                ledger.postAgainst(
                        source,
                        TransactionDirection.CREDIT,
                        transfer.amount(),
                        "Refused — money returned",
                        manager.fullName(),
                        "transfer-reversal-" + transfer.id(),
                        /*
                         * STILL THE INTENDED PAYEE, not the sender themselves.
                         * The money came back to its own account, so naming the
                         * account holder here would produce "returned from
                         * yourself". What the customer needs to see is which
                         * payment failed.
                         */
                        MovementKind.TRANSFER_RETURNED,
                        payee.name(),
                        payee.mask());

        transfer.reject(manager.fullName(), reason, reversal.id());
        transfers.save(transfer);

        /*
         * SAYS THE MONEY CAME BACK, in those words. The customer believes they have paid
         * somebody, and somebody is expecting money that is not coming; a message that only
         * says "refused" leaves them unsure whether to send it again, which is how a
         * payment gets made twice.
         */
        notifyTheSender(
                transfer.sourceAccountId(),
                owner ->
                        notifications.transferRejected(
                                owner, transfer.amount(), payee.mask(), reason));

        log.warn(
                "Transfer {} refused by {}: {} returned to {} ({})",
                transfer.id(),
                manager.email(),
                transfer.amount().minorUnits(),
                source.maskedNumber(),
                reason);

        return transfer;
    }

    /* -------------------------------------------------------------- internals */

    /**
     * Notifies whoever owns the account the money left, if that is one person.
     *
     * <p>A COMPANY ACCOUNT GETS NOTHING, and that is a gap stated rather than guessed at. A
     * corporate account's {@code customerId} is null — it belongs to a company with several
     * members in different roles — so there is no single inbox for "your transfer was
     * released", and picking one of the members would be inventing a recipient. Who on a
     * corporate account should be told about a released payment is part of the corporate
     * approvals work; docs/OPEN-ITEMS.md records it.
     *
     * <p>READ WITHOUT A LOCK. Nothing about this row is being changed, and taking a second
     * pessimistic lock here would add an ordering constraint between the two accounts — the
     * one thing this service has to get right to stop two opposite transfers deadlocking.
     */
    private void notifyTheSender(UUID sourceAccountId, java.util.function.Consumer<UUID> notify) {
        accounts
                .findById(sourceAccountId)
                .map(CustomerAccountEntity::customerId)
                .ifPresent(notify);
    }

    private TransferEntity pendingOrRefuse(UUID transferId) {
        TransferEntity transfer =
                transfers
                        .findById(transferId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that transfer."));

        if (!transfer.isPending()) {
            throw new BusinessRuleException(
                    "That transfer has already been decided by "
                            + transfer.decidedBy()
                            + " and cannot be decided again.");
        }
        return transfer;
    }

    /**
     * The destination: one of this customer's own accounts by id, or anybody's by full
     * account number.
     *
     * <p>Exactly one of the two, and BOTH is refused rather than one silently winning. A
     * client that sent a picked account and a typed number together has a bug, and
     * guessing which the person meant is guessing where their money goes.
     */
    private CustomerAccountEntity destinationOrRefuse(UUID customerId, Instruction instruction) {
        String number = instruction.destinationAccountNumber();
        boolean hasNumber = number != null && !number.isBlank();
        boolean hasId = instruction.destinationAccountId() != null;

        if (hasNumber && hasId) {
            throw new BusinessRuleException(
                    "Choose one of your accounts or type an account number, not both.");
        }

        if (hasId) {
            /*
             * ONE OF THEIR OWN, and checked exactly as the source is. Without mayAct here
             * an account id — which appears in URLs all over the portal — would be enough
             * to push money into somebody else's account, and the id path would be the
             * way around the number the typed path requires.
             *
             * It is a harmless direction to get wrong by accident and a useful one to get
             * wrong on purpose: paying money INTO an account is how somebody confirms it
             * exists.
             */
            return accounts
                    .findById(instruction.destinationAccountId())
                    .filter(found -> access.mayAct(customerId, found))
                    .orElseThrow(
                            () ->
                                    new ResourceNotFoundException(
                                            "We could not find the account you are sending to."));
        }

        if (!hasNumber) {
            throw new BusinessRuleException(
                    "Choose one of your accounts, or type the account number you are sending"
                            + " to.");
        }

        /*
         * Masks are not accepted and could not be: they are not unique, so a mask would
         * name several accounts and the service would be choosing whose money arrives.
         */
        return accounts.findByAccountNumberAndRemovedAtIsNull(number.trim()).stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                /*
                                 * The same answer whether the number is wrong or the
                                 * account was removed. Distinguishing them would tell
                                 * somebody probing numbers which ones used to exist.
                                 */
                                new ResourceNotFoundException(
                                        "We could not find an account with that number."));
    }

    /**
     * The account the money leaves, locked, or a refusal that says which refusal it is.
     *
     * <p>WHY THIS IS NOT ONE {@code filter(mayAct)}. It was, and the three reasons a send
     * can be refused arrived as one 404 with one sentence: the id names no account, the
     * account is somebody else's, or it is the customer's own and has been closed. The
     * customer picked this account from a list this service produced moments earlier, so
     * the third case is the likely one — and "we could not find that, it may not be yours
     * to view" is the least useful of the three things it could have said. Support cannot
     * act on it and neither can the person holding the phone.
     *
     * <p>THE AMBIGUITY IS KEPT WHERE IT EARNS SOMETHING. An id that names no account and
     * an id that names somebody else's get the identical 404, because a different answer
     * for the second would let anybody with an account id confirm that it exists. What is
     * split out is the case where the account IS the caller's, which tells them nothing
     * they did not already know.
     */
    private CustomerAccountEntity sourceOrRefuse(UUID customerId, UUID sourceAccountId) {
        var found = accounts.findByIdForUpdate(sourceAccountId);

        if (found.isEmpty()) {
            /*
             * NO SUCH ROW. Logged with both ids because this refusal is supposed to be
             * unreachable from the portal: the "From" menu is filled from this same
             * table, so an id that is not in it came from somewhere other than the menu
             * — a stale list the browser kept, a cached response, or a different client.
             * Without the id in the log there is no way to tell which.
             *
             * ACCOUNT IDS, NOT ACCOUNT NUMBERS. An id is a random UUID and names nothing
             * on its own; it is the number that must stay out of logs.
             */
            log.warn(
                    "Transfer refused: no account row for source id {} (customer {})",
                    sourceAccountId,
                    customerId);
            throw new ResourceNotFoundException(
                    "We could not find the account you are sending from.");
        }

        CustomerAccountEntity source = found.get();

        if (access.mayAct(customerId, source)) return source;

        /*
         * FOUND BUT NOT PERMITTED. The three fields the decision rests on, so a refusal
         * can be matched to the one that caused it rather than guessed at.
         */
        log.warn(
                "Transfer refused: source {} is not usable by customer {};"
                        + " row belongs to {}, company {}, removed {}",
                sourceAccountId,
                customerId,
                source.customerId(),
                source.companyId(),
                source.isActive() ? "no" : "yes");

        /*
         * Not permitted. If it is theirs and merely closed, say so; otherwise fall
         * through to the same 404 an unknown id gets.
         *
         * `isActive` is the removal flag, so a closed account reaches here having passed
         * the ownership half of mayAct. The ownership test is repeated rather than
         * inferred, because inferring it from "mayAct said no and the account is
         * removed" would also be true of somebody ELSE'S removed account — and naming
         * that one as closed would confirm it exists.
         */
        boolean theirs =
                source.isCompanyAccount()
                        ? access.companiesFor(customerId).contains(source.companyId())
                        : source.customerId().equals(customerId);

        if (theirs && !source.isActive()) {
            throw new BusinessRuleException(
                    "That account has been closed, so money cannot be sent from it. Refresh the"
                            + " page to see the accounts you can use.");
        }

        throw new ResourceNotFoundException("We could not find the account you are sending from.");
    }

    /**
     * Whose name goes on the ledger entry.
     *
     * <p>THE ACTUAL CUSTOMER, and this used to be the literal string "Account holder".
     * That placeholder went into {@code performed_by} on every transfer debit — a field
     * whose entire job is to say who moved the money. An audit trail that answers
     * "Account holder" to that question is one that cannot be audited, and it read as
     * real because every other entry in the table carries a genuine name.
     *
     * <p>A company account's entries name the company, because the statement belongs to
     * the company; which member instructed it is on the transfer row.
     */
    /**
     * The holder's name, for the sender to check against who they meant to pay.
     *
     * <p>Distinct from {@link #performerFor}, which names whoever MOVED money on an
     * account. The two were one method and that was the bug: reusing the performer helper
     * here meant the name check answered "Account holder" — a verification that verified
     * nothing while looking like a safety net.
     */
    /**
     * The other party on a transfer, as a statement will remember them.
     *
     * <p>A name and a mask travel together, because an entry requires both or neither
     * and fetching them as two separate lookups is how one of them ends up null.
     */
    private record Counterparty(String name, String mask) {}

    /**
     * Looks up the account on the other side, for the entry's own record of it.
     *
     * <p>The fallback is unreachable: both sides of a transfer are foreign keys and
     * cannot vanish. It is here so that an impossible case degrades to one unhelpful
     * statement line rather than failing a money movement that has already succeeded
     * everywhere else in the transaction.
     */
    private Counterparty counterpartyOf(UUID accountId) {
        return accounts
                .findById(accountId)
                .map(account -> new Counterparty(holderNameOf(account), account.maskedNumber()))
                .orElseGet(() -> new Counterparty("An account at this bank", "****"));
    }

    /**
     * Delegated to {@link AccountHolders}, which is where this logic now lives.
     *
     * <p>IT WAS A PRIVATE HELPER HERE and is now shared, because the payee review queue
     * needs the same answer. Kept as a one-line method rather than inlined at its two call
     * sites so that the name still reads at those sites, and so there is one place to look
     * when the naming rule for company accounts next changes.
     */
    private String holderNameOf(CustomerAccountEntity account) {
        return holders.nameOf(account);
    }

    private String performerFor(UUID customerId, CustomerAccountEntity account) {
        if (account.isCompanyAccount()) {
            return companies
                    .findById(account.companyId())
                    .map(CompanyEntity::name)
                    .orElse("Company account");
        }
        return customers.findById(customerId).map(CustomerEntity::fullName).orElse("Customer");
    }
}
