package rw.bank.ibanking.onboarding.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.BeneficiaryEntity;
import rw.bank.ibanking.onboarding.domain.BeneficiaryStatus;
import rw.bank.ibanking.onboarding.domain.BeneficiaryType;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.repo.BeneficiaryRepository;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;

/**
 * SAVED PAYEES, FROM THE CUSTOMER ADDING ONE TO A MEMBER OF STAFF CLEARING IT.
 *
 * <p>WHAT THIS REPLACED. The Beneficiaries screen was served entirely by the browser's
 * mock. Adding a payee produced a row marked {@code PENDING_VERIFICATION} and nothing
 * anywhere ever moved it on: no screen, no endpoint, no timer. The customer was told the
 * payee was "in its cooling-off period", which named a clock that did not exist, and the
 * payee stayed unusable for ever.
 *
 * <p>THE GATE IS ENFORCED HERE, which is the point of {@link #destinationFor}. The old
 * arrangement checked the status in the TRANSFER SCREEN'S dropdown — and only on the
 * standing-order screen, not the transfer one, so an unverified payee was selectable and
 * payable on the single screen that moves money. A control a customer's browser applies is
 * not a control. The transfer path now asks this service for a destination and this service
 * refuses unless the payee is the caller's own and {@link BeneficiaryEntity#payable()}.
 *
 * <p>THE REVIEW IS A COMPARISON, NOT A SIGN-OFF. {@link #review} puts the name the customer
 * typed beside the name the bank holds for that account number and says which of four
 * things it is — a match, a partial match, a mismatch, or a comparison that could not be
 * made. A member of staff handed only what the customer typed has nothing to check it
 * against, and an approval screen that cannot verify anything costs the customer a wait and
 * buys nothing.
 *
 * <p>NOTHING HERE APPROVES ANYTHING BY ITSELF. A perfect name match does not clear a payee:
 * the verdict is shown to a person and the person decides. Auto-clearing on a match would
 * mean somebody who knows the account holder's name — which is exactly what a payee's own
 * paperwork tells them — never reaches the queue at all.
 */
@Service
public class BeneficiaryService {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryService.class);

    /**
     * How many payees one customer may have waiting at once.
     *
     * <p>This is QUEUE PROTECTION rather than a product limit. Without it one customer can
     * put a thousand rows in front of the bank's staff, which hides everybody else's payee
     * behind theirs — a denial of service aimed at other customers, mounted with no special
     * access. Ten is far more than a person adds in a sitting.
     */
    private static final int MAX_PENDING_PER_CUSTOMER = 10;

    /** How many payees one customer may keep in total, refused ones aside. */
    private static final int MAX_SAVED_PER_CUSTOMER = 50;

    /** The statuses that occupy a destination: everything except a refusal. */
    private static final List<BeneficiaryStatus> LIVE_STATUSES =
            List.of(BeneficiaryStatus.PENDING_VERIFICATION, BeneficiaryStatus.ACTIVE);

    private final BeneficiaryRepository beneficiaries;
    private final CustomerRepository customers;
    private final AccountHolders holders;
    private final Mailer mailer;
    private final Notifications notifications;

    BeneficiaryService(
            BeneficiaryRepository beneficiaries,
            CustomerRepository customers,
            AccountHolders holders,
            Mailer mailer,
            Notifications notifications) {
        this.beneficiaries = beneficiaries;
        this.customers = customers;
        this.holders = holders;
        this.mailer = mailer;
        this.notifications = notifications;
    }

    /* ==================================================== the customer's side */

    /** What the customer filled in. */
    public record NewBeneficiary(
            String name,
            BeneficiaryType beneficiaryType,
            String provider,
            String accountNumber,
            String currency) {}

    /** One customer's own payees, newest first. */
    @Transactional(readOnly = true)
    public List<BeneficiaryEntity> forCustomer(UUID customerId) {
        return beneficiaries.findByCustomerIdOrderByAddedAtDesc(customerId);
    }

    /**
     * Saves a payee, unpayable, and puts it in front of the bank's staff.
     *
     * <p>THE DUPLICATE CHECK IS NOT TIDINESS. Two rows for the same destination mean two
     * reviews of the same decision, which can disagree — one approved and one refused for
     * the same account — and a customer picking from a dropdown with the destination twice
     * has no way to tell which is the usable one.
     */
    @Transactional
    public BeneficiaryEntity add(UUID customerId, NewBeneficiary details) {
        CustomerEntity customer = requireCustomer(customerId);

        String name = trimmed(details.name());
        String accountNumber = stripped(details.accountNumber());
        BeneficiaryType type = details.beneficiaryType();

        if (name.isEmpty()) {
            throw new BusinessRuleException("Give the payee a name, so you can recognise them.");
        }
        if (accountNumber.length() < 4) {
            throw new BusinessRuleException(
                    "Enter the full account or mobile number you want to pay.");
        }
        if (type == null) {
            throw new BusinessRuleException("Choose what kind of payee this is.");
        }

        /*
         * A CUSTOMER MAY NOT SAVE THEIR OWN ACCOUNT AS A PAYEE. Moving money between your
         * own accounts is its own screen, needs no review, and a payee row for it would put
         * the customer's own account into a staff queue to be approved.
         */
        if (ownAccountNumbers(customerId).contains(accountNumber)) {
            throw new BusinessRuleException(
                    "That is one of your own accounts. Use “Move money between my"
                            + " accounts” instead — it does not need a payee.");
        }

        Optional<BeneficiaryEntity> existing =
                beneficiaries.findFirstByCustomerIdAndAccountNumberAndStatusIn(
                        customerId, accountNumber, LIVE_STATUSES);

        if (existing.isPresent()) {
            BeneficiaryEntity already = existing.get();
            throw new BusinessRuleException(
                    already.payable()
                            ? "You already have a payee for that number: "
                                    + already.name()
                                    + " ("
                                    + already.maskedDestination()
                                    + ")."
                            : "You have already added that number as "
                                    + already.name()
                                    + ", and it is still being checked.");
        }

        enforceCaps(customerId);

        BeneficiaryEntity saved =
                beneficiaries.save(
                        BeneficiaryEntity.savedBy(
                                customerId,
                                name,
                                type,
                                trimmed(details.provider()),
                                accountNumber,
                                currencyOf(details.currency())));

        /*
         * Logged with the MASK, never the number. A log of full account numbers is the
         * harvest the mask exists to prevent, made permanent and shipped to every log
         * aggregator the bank uses.
         */
        log.info(
                "Customer {} saved payee {} ({}); it is waiting for a member of staff",
                customer.customerNumber(),
                saved.id(),
                saved.maskedDestination());

        return saved;
    }

    private void enforceCaps(UUID customerId) {
        long pending =
                beneficiaries.countByCustomerIdAndStatusIn(
                        customerId, List.of(BeneficiaryStatus.PENDING_VERIFICATION));
        if (pending >= MAX_PENDING_PER_CUSTOMER) {
            throw new BusinessRuleException(
                    "You already have "
                            + MAX_PENDING_PER_CUSTOMER
                            + " payees waiting to be checked. Once some of those are done you"
                            + " can add more.");
        }

        long saved = beneficiaries.countByCustomerIdAndStatusIn(customerId, LIVE_STATUSES);
        if (saved >= MAX_SAVED_PER_CUSTOMER) {
            throw new BusinessRuleException(
                    "You have reached the limit of "
                            + MAX_SAVED_PER_CUSTOMER
                            + " saved payees. Remove one you no longer pay to add another.");
        }
    }

    /**
     * Forgets a payee.
     *
     * <p>Scoped to the caller's own, and the same answer whether the payee is somebody
     * else's or does not exist. A different answer for the two would let anybody signed in
     * confirm a payee id belongs to another customer.
     */
    @Transactional
    public void remove(UUID customerId, UUID beneficiaryId) {
        BeneficiaryEntity payee = requireOwn(customerId, beneficiaryId);
        beneficiaries.delete(payee);
        log.info("Customer {} removed payee {}", customerId, beneficiaryId);
    }

    /**
     * THE FULL DESTINATION, FOR A TRANSFER, AND THE ONE PLACE THE GATE IS ENFORCED.
     *
     * <p>Two things are checked here and nowhere else: that the payee belongs to the
     * customer whose account is about to be debited, and that staff have cleared it. Both
     * were previously "checked" by a filter on a dropdown, which is to say not checked —
     * the transfer screen's filter looked only at the payee's TYPE, so an unverified payee
     * was payable, which is precisely the "add this account and send the money now" script
     * the review exists to interrupt.
     *
     * <p>The refusal says which refusal it is only where that is safe. A payee that is not
     * the caller's gets the same answer as one that does not exist; a payee that IS theirs
     * and is still waiting is told so plainly, because they already know they added it and
     * the alternative is a customer re-adding a payee that is sitting in a queue.
     */
    @Transactional(readOnly = true)
    public String destinationFor(UUID customerId, UUID beneficiaryId) {
        BeneficiaryEntity payee = requireOwn(customerId, beneficiaryId);

        if (!payee.payable()) {
            throw new BusinessRuleException(
                    payee.status() == BeneficiaryStatus.REFUSED
                            ? "We were not able to approve that payee, so it cannot be paid."
                                    + " There is a reason on your payee list."
                            : "That payee is still being checked by the bank, so it cannot be"
                                    + " paid yet. You will be emailed when it is done.");
        }

        return payee.destinationForPayment();
    }

    /* ======================================================= the staff's side */

    /** How the name the customer typed compares with the name the bank holds. */
    public enum NameCheck {

        /** The same name, allowing for case, punctuation and the order of the words. */
        MATCH,

        /**
         * One name is contained in the other — "Teta Eliana" against "Teta Eliana
         * Muzora".
         *
         * <p>ITS OWN VERDICT RATHER THAN A MATCH OR A MISMATCH, because it is both the
         * commonest innocent case and a usable disguise. Customers write the short form of
         * a name they know; somebody adding a stranger's account may also know one of
         * their names. A reviewer should see that this is what happened and decide.
         */
        PARTIAL,

        /** Two different names. The commonest sign of a mistyped digit. */
        MISMATCH,

        /**
         * The bank holds no name for this destination, so no comparison was made.
         *
         * <p>DIFFERENT FROM A MISMATCH, and the screen must not blur them. Payees at
         * another bank, abroad, or on a mobile wallet are held by an institution this
         * service cannot ask. A blank where the comparison belongs reads as a pass.
         */
        UNAVAILABLE
    }

    /** A payee, with everything a reviewer needs to decide about it. */
    public record Review(
            BeneficiaryEntity beneficiary,
            CustomerEntity customer,
            String heldName,
            NameCheck nameCheck) {}

    /** Everything waiting, oldest first. */
    @Transactional(readOnly = true)
    public List<Review> waiting() {
        return beneficiaries
                .findByStatusOrderByAddedAtAsc(BeneficiaryStatus.PENDING_VERIFICATION)
                .stream()
                .map(this::review)
                .toList();
    }

    @Transactional(readOnly = true)
    public long waitingCount() {
        return beneficiaries.countByStatus(BeneficiaryStatus.PENDING_VERIFICATION);
    }

    /**
     * Resolves the holder and compares the names.
     *
     * <p>ONE LOOKUP PER ROW, which is worth stating rather than hiding. The queue is the
     * payees waiting at one moment at one bank, and it is capped per customer above, so
     * this is a handful of indexed reads; if the queue ever becomes long enough for that to
     * matter, the fix is one query joining accounts, not caching a name whose whole value
     * is being current. It is recorded in frontend/docs/OPEN-ITEMS.md.
     */
    @Transactional(readOnly = true)
    public Review review(BeneficiaryEntity payee) {
        CustomerEntity customer = requireCustomer(payee.customerId());

        if (!payee.beneficiaryType().heldAtThisBank()) {
            return new Review(payee, customer, null, NameCheck.UNAVAILABLE);
        }

        Optional<String> held = holders.nameOfAccountNumber(payee.destinationForPayment());
        return held.map(name -> new Review(payee, customer, name, compare(payee.name(), name)))
                /*
                 * An INTERNAL payee whose number matches no account here. Not a mismatch:
                 * there is nothing to mismatch against. The reviewer is told the bank holds
                 * no such account, which is a refusal reason in itself.
                 */
                .orElseGet(() -> new Review(payee, customer, null, NameCheck.UNAVAILABLE));
    }

    /**
     * Compares two names as a person would.
     *
     * <p>Case, punctuation and the ORDER of the words are all ignored, because "MUZORA
     * Teta Eliana", "Teta Eliana Muzora" and "teta eliana muzora" are one person written
     * three ways, and a comparison that called those a mismatch would train reviewers to
     * approve through the warning — which is worse than having no warning.
     */
    static NameCheck compare(String typed, String held) {
        Set<String> typedWords = words(typed);
        Set<String> heldWords = words(held);

        if (typedWords.isEmpty() || heldWords.isEmpty()) return NameCheck.UNAVAILABLE;
        if (typedWords.equals(heldWords)) return NameCheck.MATCH;
        if (heldWords.containsAll(typedWords) || typedWords.containsAll(heldWords)) {
            return NameCheck.PARTIAL;
        }
        return NameCheck.MISMATCH;
    }

    /**
     * A name as a set of comparable words.
     *
     * <p>A {@link TreeSet} so the comparison does not depend on the order they were
     * written in, and anything that is not a letter or a digit is a separator — hyphens,
     * apostrophes, full stops and double spaces all appear in real names and none of them
     * distinguishes two people.
     */
    private static Set<String> words(String name) {
        Set<String> words = new TreeSet<>();
        if (name == null) return words;
        for (String word : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank()) words.add(word);
        }
        return words;
    }

    /**
     * Clears a payee for payment and tells the customer.
     *
     * <p>The holder name is resolved AGAIN here rather than taken from the queue the
     * reviewer was looking at, and stored on the row. The queue may have been on screen for
     * an hour; what is recorded as the evidence has to be what was true when the decision
     * was made, and a stale name in an audit trail is worse than none because it reads as
     * confirmation.
     */
    @Transactional
    public BeneficiaryEntity approve(UUID beneficiaryId, StaffEntity reviewer) {
        BeneficiaryEntity payee = findPayee(beneficiaryId);
        Review fresh = review(payee);

        payee.approved(reviewer.fullName(), fresh.heldName());
        beneficiaries.save(payee);

        mailer.send(
                OutboxKind.BENEFICIARY_APPROVED,
                fresh.customer().email(),
                EmailTemplates.BENEFICIARY_APPROVED_SUBJECT,
                EmailTemplates.beneficiaryApproved(
                        fresh.customer().fullName(), payee.name(), payee.maskedDestination()));

        /*
         * AND IN THE PORTAL. The email already carries the "if you did not add this payee"
         * warning, and so does this: a payee appearing on an account that the customer did
         * not save is the clearest single sign that somebody else has their password, and
         * the in-app list is where a customer who does not read their email will see it.
         */
        notifications.beneficiaryApproved(
                fresh.customer().id(), payee.name(), payee.maskedDestination());

        log.info(
                "Payee {} ({}) for customer {} was approved by {} (name check: {})",
                payee.id(),
                payee.maskedDestination(),
                fresh.customer().customerNumber(),
                reviewer.email(),
                fresh.nameCheck());

        return payee;
    }

    /** Declines a payee, with the reason the customer is shown and emailed. */
    @Transactional
    public BeneficiaryEntity refuse(UUID beneficiaryId, String reason, StaffEntity reviewer) {
        BeneficiaryEntity payee = findPayee(beneficiaryId);
        CustomerEntity customer = requireCustomer(payee.customerId());

        // Throws if the reason is missing, or if another member of staff got here first.
        payee.refused(reviewer.fullName(), reason);
        beneficiaries.save(payee);

        mailer.send(
                OutboxKind.BENEFICIARY_REFUSED,
                customer.email(),
                EmailTemplates.BENEFICIARY_REFUSED_SUBJECT,
                EmailTemplates.beneficiaryRefused(
                        customer.fullName(),
                        payee.name(),
                        payee.maskedDestination(),
                        payee.refusedReason()));

        notifications.beneficiaryRefused(customer.id(), payee.name(), payee.refusedReason());

        log.info(
                "Payee {} ({}) for customer {} was refused by {}",
                payee.id(),
                payee.maskedDestination(),
                customer.customerNumber(),
                reviewer.email());

        return payee;
    }

    /* ============================================================== plumbing */

    private BeneficiaryEntity findPayee(UUID beneficiaryId) {
        return beneficiaries
                .findById(beneficiaryId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find that payee. It may already have been"
                                                + " dealt with."));
    }

    private BeneficiaryEntity requireOwn(UUID customerId, UUID beneficiaryId) {
        return beneficiaries
                .findById(beneficiaryId)
                .filter(payee -> payee.customerId().equals(customerId))
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find that payee on your list."));
    }

    private CustomerEntity requireCustomer(UUID customerId) {
        return customers
                .findById(customerId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find the customer this payee belongs to."));
    }

    /** The caller's own account numbers, so they cannot save one as a payee. */
    private Set<String> ownAccountNumbers(UUID customerId) {
        Set<String> numbers = new TreeSet<>();
        for (var account : holders.accountsOf(customerId)) {
            numbers.add(account.fullNumberForHolder());
        }
        return numbers;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** Spaces removed as well as trimmed: people write account numbers in groups. */
    private static String stripped(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static String currencyOf(String value) {
        String currency = trimmed(value).toUpperCase(Locale.ROOT);
        return currency.length() == 3 ? currency : "RWF";
    }
}
