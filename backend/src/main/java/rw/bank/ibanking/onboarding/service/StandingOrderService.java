package rw.bank.ibanking.onboarding.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.common.time.BankZone;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.AccountType;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.MovementKind;
import rw.bank.ibanking.onboarding.domain.StandingOrderEntity;
import rw.bank.ibanking.onboarding.domain.StandingOrderFrequency;
import rw.bank.ibanking.onboarding.domain.StandingOrderStatus;
import rw.bank.ibanking.onboarding.domain.TransactionDirection;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.StandingOrderRepository;

/**
 * Repeating payments: setting one up, and paying it when it falls due.
 *
 * <p>WHAT THEY MOVE MONEY BETWEEN. One of the customer's own accounts into another of
 * their own -- the current account into savings, which is the sweep this bank's members
 * actually keep. Both ends are account ids checked against the caller, so there is no
 * account number to resolve and nothing to guess; paying a saved payee is a separate
 * feature the schema still describes and V27 explains why it was left in place.
 *
 * <p>THESE PAY WITHOUT A MANAGER RELEASING THEM. Every other way money leaves an account in
 * this system waits in the approval queue. This one does not, and that was a deliberate
 * decision rather than an oversight -- the alternative, a due order creating a PENDING
 * transfer for the manager, was the other option on the table. V24 records the same thing
 * next to the schema.
 *
 * <p>WHAT REPLACES THE MISSING CONTROL IS IDEMPOTENCY. The ledger key for an occurrence is
 * derived from the order's id and the instant it was due, so a sweep that runs twice, a
 * container that wakes and catches up, and a hand-run during a demonstration all post once.
 * The ledger's own replay check is what enforces it; this class only has to name the key the
 * same way every time.
 *
 * <p>THE MONEY LEAVES THE CURRENT ACCOUNT. {@code create} refuses any other source, for the
 * same reason a one-off transfer does, and with more at stake: a one-off from the wrong
 * account is one visible mistake, while a standing order is that mistake repeating every
 * period with nobody watching.
 *
 * <p>A RUN THAT CANNOT BE FUNDED FAILS LOUDLY AND DOES NOT RETRY. The customer is notified,
 * the reason is stored, and the schedule moves to the next period. Retrying would land a
 * payment at whatever moment money happened to arrive, which is a payment nobody chose.
 */
@Service
public class StandingOrderService {

    private static final Logger log = LoggerFactory.getLogger(StandingOrderService.class);

    /** How many a customer may have running at once. */
    private static final int MAX_ACTIVE_PER_CUSTOMER = 20;

    private final StandingOrderRepository orders;
    private final CustomerAccountRepository accounts;
    private final AccountAccess access;
    private final AccountLedgerService ledger;
    private final Notifications notifications;

    /** Turns an account into the name on the other party's statement. */
    private final AccountHolders holders;

    StandingOrderService(
            StandingOrderRepository orders,
            CustomerAccountRepository accounts,
            AccountAccess access,
            AccountLedgerService ledger,
            Notifications notifications,
            AccountHolders holders) {
        this.orders = orders;
        this.accounts = accounts;
        this.access = access;
        this.ledger = ledger;
        this.notifications = notifications;
        this.holders = holders;
    }

    /**
     * What the customer asked for.
     *
     * <p>{@code startOn} IS A DATE AND MAY BE NULL. A monthly order starts on a day the
     * customer picks; a two-minute order has no day to pick and starts at once. The service
     * turns whichever applies into the instant the row stores.
     */
    public record NewOrder(
            UUID sourceAccountId,
            UUID destinationAccountId,
            Money amount,
            StandingOrderFrequency frequency,
            LocalDate startOn,
            LocalDate endsOn,
            String reference) {}

    /* ==================================================== the customer's side */

    @Transactional(readOnly = true)
    public List<StandingOrderEntity> forCustomer(UUID customerId) {
        return orders.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    @Transactional(readOnly = true)
    public StandingOrderEntity one(UUID customerId, UUID orderId) {
        return ownedOrRefuse(customerId, orderId);
    }

    /**
     * Sets one up.
     *
     * <p>THE PAYEE'S ACCOUNT IS RESOLVED HERE AND FROZEN ON THE ROW. Looking it up on every
     * run would mean a payee edited next month silently redirects this payment -- the exact
     * thing payee verification exists to prevent. The order is authorised against an
     * account, not against a name that can be pointed somewhere else later.
     */
    @Transactional
    public StandingOrderEntity create(UUID customerId, NewOrder request) {
        CustomerAccountEntity source = heldOrRefuse(customerId, request.sourceAccountId());
        CustomerAccountEntity destination =
                heldOrRefuse(customerId, request.destinationAccountId());

        if (destination.id().equals(source.id())) {
            throw new BusinessRuleException("A standing order cannot pay the account it comes from.");
        }

        /*
         * THE MONEY LEAVES THE CURRENT ACCOUNT, the same rule a one-off transfer follows.
         * The bank's other products are savings -- compulsory, voluntary, fixed deposit,
         * education -- and they are where money is kept rather than where it is paid from.
         *
         * IT MATTERS MORE HERE THAN ON A TRANSFER. A one-off from the wrong account is one
         * mistake the customer sees immediately; a standing order is that mistake repeating
         * every period with nobody watching, which is exactly the kind of thing that is only
         * noticed after a savings product has been drained.
         */
        if (source.accountType() != AccountType.CURRENT) {
            throw new BusinessRuleException(
                    "A standing order pays from your current account. Choose that as the account"
                            + " the money comes from.");
        }

        if (!source.isActive()) {
            throw new BusinessRuleException("That account cannot be used for payments right now.");
        }
        if (!destination.isActive()) {
            throw new BusinessRuleException("That account cannot receive payments right now.");
        }

        /*
         * CURRENCY IS NOT CONVERTED HERE, ever. An exchange rate is the bank's to set and
         * this service has none; a standing order that quietly converted would be inventing
         * one every period.
         */
        if (!source.currency().equals(request.amount().currency())) {
            throw new BusinessRuleException(
                    "The amount must be in " + source.currency() + ", the account's own currency.");
        }
        if (!destination.currency().equals(request.amount().currency())) {
            throw new BusinessRuleException(
                    "That account is held in "
                            + destination.currency()
                            + ", which this service cannot convert into.");
        }

        if (request.amount().minorUnits() <= 0) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }
        if (request.reference() == null || request.reference().isBlank()) {
            throw new BusinessRuleException("Give a reference so you can recognise this later.");
        }

        Instant startAt = firstOccurrence(request);

        if (request.endsOn() != null
                && request.endsOn().isBefore(BankZone.dateOf(startAt))) {
            throw new BusinessRuleException("The end date is before the first payment.");
        }

        long active =
                orders.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                        .filter(StandingOrderEntity::isActive)
                        .count();
        if (active >= MAX_ACTIVE_PER_CUSTOMER) {
            throw new BusinessRuleException(
                    "You already have " + MAX_ACTIVE_PER_CUSTOMER + " standing orders running.");
        }

        StandingOrderEntity order =
                StandingOrderEntity.created(
                        customerId,
                        source.id(),
                        /* No payee: this pays an account the same customer already holds. */
                        null,
                        destination.id(),
                        request.amount(),
                        request.reference().trim(),
                        request.frequency(),
                        startAt,
                        request.endsOn());

        StandingOrderEntity saved = orders.save(order);
        log.info(
                "Standing order {} created for customer {}, first payment {}",
                saved.id(),
                customerId,
                saved.nextRunAt());
        return saved;
    }

    /**
     * When the first occurrence falls.
     *
     * <p>A DATE-SHAPED PERIOD STARTS AT THE BEGINNING OF THE DAY THE CUSTOMER CHOSE, in the
     * bank's zone, so that "the 5th" is the same moment whatever zone the server runs in.
     *
     * <p>A SUB-DAILY PERIOD STARTS NOW, because there is no day to choose. The first sweep
     * that comes along pays it, which is the point of a two-minute order: you set it up and
     * watch it happen rather than reading that it will happen later.
     */
    private Instant firstOccurrence(NewOrder request) {
        if (request.frequency().isSubDaily()) {
            /*
             * TO THE SECOND. A payment schedule has no use for microseconds, and carrying
             * them means every occurrence derived from this one carries them too -- into log
             * lines, into the time shown to the customer, and into anything built from the
             * instant. Truncating once here keeps the whole chain clean.
             */
            return Instant.now().truncatedTo(ChronoUnit.SECONDS);
        }

        LocalDate startOn = request.startOn();
        if (startOn == null) {
            throw new BusinessRuleException("Choose when the first payment goes out.");
        }
        if (startOn.isBefore(BankZone.today())) {
            throw new BusinessRuleException("The first payment date cannot be in the past.");
        }
        return BankZone.startOfDay(startOn);
    }

    @Transactional
    public StandingOrderEntity togglePause(UUID customerId, UUID orderId) {
        StandingOrderEntity order = ownedOrRefuse(customerId, orderId);
        order.togglePause();
        return orders.save(order);
    }

    @Transactional
    public void cancel(UUID customerId, UUID orderId) {
        StandingOrderEntity order = ownedOrRefuse(customerId, orderId);
        order.cancelled();
        orders.save(order);
        log.info("Standing order {} cancelled by customer {}", orderId, customerId);
    }

    /* ========================================================= the sweep */

    /**
     * The ids of everything active and due at or before {@code now}, oldest first.
     *
     * <p>THE LOOP IS NOT HERE, deliberately: see {@link StandingOrderRunner}. Calling
     * {@link #runOne} from this class would never cross the transaction proxy, and the
     * per-order isolation it is annotated for would silently not happen.
     */
    @Transactional(readOnly = true)
    public List<UUID> dueBy(Instant now) {
        return orders
                .findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
                        StandingOrderStatus.ACTIVE, now)
                .stream()
                .map(StandingOrderEntity::id)
                .toList();
    }

    /**
     * One occurrence, in its own transaction.
     *
     * <p>THE IDEMPOTENCY KEY IS THE WHOLE SAFETY ARGUMENT. It names the order and the exact
     * instant the occurrence was due, so a second sweep over the same occurrence hands back
     * the entries already posted rather than posting again. Without a manager in the loop
     * this is the only thing standing between a retry and paying twice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runOne(UUID orderId) {
        StandingOrderEntity order =
                orders.findById(orderId)
                        .orElseThrow(() -> new ResourceNotFoundException("Standing order gone."));

        if (!order.isActive()) return;

        Instant occurrence = order.nextRunAt();
        Money amount = order.amount();

        CustomerAccountEntity source = accounts.findByIdForUpdate(order.sourceAccountId()).orElse(null);
        CustomerAccountEntity destination =
                accounts.findById(order.destinationAccountId()).orElse(null);

        /*
         * THESE TWO THROW RATHER THAN RECORD, because a failure recorded in this
         * transaction would roll back with it. StandingOrderRunner catches the refusal and
         * writes it in a transaction of its own.
         */
        if (source == null || !source.isActive()) {
            throw new BusinessRuleException("The account it pays from is no longer available.");
        }
        if (destination == null || !destination.isActive()) {
            throw new BusinessRuleException("The payee's account is no longer available.");
        }

        /*
         * NO BALANCE CHECK HERE. CustomerAccountEntity.debit already owns that rule and
         * refuses a withdrawal that would go below zero, with the sentence the customer
         * should read. A second copy in this class would be a second rule to keep in step
         * with the first, and the two would eventually disagree about whose money moved.
         */

        /*
         * THE OCCURRENCE GOES INTO THE KEY AS EPOCH SECONDS, not as its ISO text.
         *
         * account_transactions.idempotency_key is VARCHAR(80), and this key has to fit with
         * room to spare or the insert is rejected by the database rather than by any rule in
         * this class. Instant.toString() carries microseconds -- "2026-10-15T13:47:12.345678Z"
         * -- which makes the key 83 characters. Epoch seconds makes it 66, exactly the length
         * the date-based key had when this worked.
         *
         * THE FAILURE THIS CAUSED IS WORTH REMEMBERING, because it was invisible. An
         * over-long value throws DataIntegrityViolationException, which is not a
         * BusinessRuleException, so the runner caught it in its last-resort branch, logged
         * it, and left the order exactly as it found it. No advance, no recorded failure,
         * status still ACTIVE -- an order that looked healthy on screen and retried every
         * sweep forever. Anything derived from stored state and written to a sized column
         * needs its length to be a decision rather than an accident.
         *
         * SECONDS ARE ENOUGH TO TELL TWO OCCURRENCES APART. The shortest period this
         * supports is two minutes.
         */
        String key = "standing-order-" + order.id() + "-" + occurrence.getEpochSecond();
        String actor = "Standing order " + order.reference();

        ledger.postAgainst(
                source,
                TransactionDirection.DEBIT,
                amount,
                "Standing order — " + order.reference(),
                /* THE ORDER ITSELF, never a staff name. Nobody approved this; writing a
                   person into the audit record would be a lie in the one place that must
                   not contain one. */
                actor,
                key + "-out",
                MovementKind.TRANSFER_OUT,
                holderNameOf(destination),
                destination.maskedNumber());

        ledger.postAgainst(
                destination,
                TransactionDirection.CREDIT,
                amount,
                "Standing order — " + order.reference(),
                actor,
                key + "-in",
                MovementKind.TRANSFER_IN,
                holderNameOf(source),
                source.maskedNumber());

        order.paid(occurrence);
        orders.save(order);

        notifications.standingOrderPaid(
                order.customerId(), amount, destination.maskedNumber(), order.reference());

        /* The recipient is told too, exactly as an approved transfer tells them. */
        if (!destination.customerId().equals(source.customerId())) {
            notifications.transferReceived(
                    destination.customerId(),
                    amount,
                    holderNameOf(source),
                    source.maskedNumber(),
                    order.reference());
        }

        log.info("Standing order {} paid {} for {}", order.id(), amount.minorUnits(), occurrence);
    }

    /* ============================================================== plumbing */

    /**
     * Records an occurrence that could not be paid, and tells the customer.
     *
     * <p>ITS OWN TRANSACTION, because the one that tried to pay is already rollback-only by
     * the time anybody knows it failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID orderId, String reason) {
        orders.findById(orderId)
                .ifPresent(
                        order -> {
                            Instant occurrence = order.nextRunAt();
                            order.failed(occurrence, reason);
                            orders.save(order);

                            notifications.standingOrderFailed(
                                    order.customerId(), order.amount(), order.reference(), reason);

                            log.warn(
                                    "Standing order {} could not pay {}: {}",
                                    order.id(),
                                    occurrence,
                                    reason);
                        });
    }

    /**
     * The name the other party sees on their statement.
     *
     * <p>A NAME, NOT A MASK. "Received from Teta Eliana" is what the credit is for; a
     * second copy of the account mask in the name column would tell the customer nothing
     * the mask column does not already say.
     */
    private String holderNameOf(CustomerAccountEntity account) {
        return holders.nameOf(account);
    }

    /**
     * One of the caller's own accounts, or a refusal that does not say which.
     *
     * <p>BOTH ENDS GO THROUGH THIS, source and destination alike. A standing order between
     * the customer's own accounts is two account ids arriving from a browser, and an id the
     * caller does not hold must be refused on the way in rather than trusted because it came
     * from a form the client rendered.
     *
     * <p>MISSING AND NOT-YOURS GIVE THE SAME ANSWER, deliberately. Account ids are UUIDs, so
     * guessing one is not a realistic attack, but a different message for "exists but is
     * somebody else's" is still an oracle, and there is nothing to gain by building one.
     */
    private CustomerAccountEntity heldOrRefuse(UUID customerId, UUID accountId) {
        CustomerAccountEntity account =
                accounts.findById(accountId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that account on your profile."));

        if (!access.mayAct(customerId, account)) {
            throw new ResourceNotFoundException("We could not find that account on your profile.");
        }
        return account;
    }

    private StandingOrderEntity ownedOrRefuse(UUID customerId, UUID orderId) {
        return orders.findById(orderId)
                .filter(row -> row.customerId().equals(customerId))
                .orElseThrow(
                        () -> new ResourceNotFoundException("We could not find that standing order."));
    }
}
