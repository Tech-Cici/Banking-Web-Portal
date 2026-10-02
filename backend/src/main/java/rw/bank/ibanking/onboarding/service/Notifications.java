package rw.bank.ibanking.onboarding.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.NotificationEntity;
import rw.bank.ibanking.onboarding.domain.NotificationKind;
import rw.bank.ibanking.onboarding.repo.NotificationRepository;

/**
 * THE ONE PLACE A NOTIFICATION IS WRITTEN, with the words the customer reads.
 *
 * <p>A METHOD PER EVENT rather than a general {@code notify(kind, title, body)}, on the same
 * reasoning as {@link EmailTemplates}: every sentence a customer sees lives in one file
 * somebody can read end to end and check for tone, for accidental promises, and for leaked
 * detail. A general method spreads that copy across eight call sites where nobody ever reads
 * it as a whole, and where the next one quietly says something the bank would not say.
 *
 * <p>WRITTEN IN THE CALLER'S TRANSACTION, deliberately. Each of these is invoked from the
 * service method that did the thing, next to the email, so a notification cannot survive an
 * event that rolled back and an event cannot half-tell the customer. That also means a kind
 * missing from V20's constraint fails the whole operation — which is why
 * NotificationKindsArePersistableTest exists; V5 is what that looks like when it is missed.
 *
 * <p>UNLIKE THE MAILER, A FAILURE HERE IS NOT SWALLOWED. {@code Mailer} deliberately never
 * fails the caller's work, because a mail server being down should not stop a manager
 * approving an account. This is a local insert in the same database as the event: if it
 * cannot be written, something is wrong that silence would hide.
 *
 * <p>NO SEVERITY ARGUMENT ANYWHERE. It comes from the kind — see {@link NotificationKind}.
 */
@Service
public class Notifications {

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);

    /**
     * How many a customer is shown.
     *
     * <p>Fifty, bounded in the query. The dashboard panel renders four of them and the full
     * page shows the rest; a customer of five years has thousands and no interest in the
     * oldest, because what this list answers is "has anything happened that I did not know
     * about", which the recent end answers or nothing does.
     */
    private static final int SHOWN = 50;

    private final NotificationRepository notifications;

    Notifications(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /* ==================================================== the customer's side */

    @Transactional(readOnly = true)
    public List<NotificationEntity> forCustomer(UUID customerId) {
        return notifications.findByCustomerIdOrderByCreatedAtDesc(customerId, Limit.of(SHOWN));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID customerId) {
        return notifications.countByCustomerIdAndReadAtIsNull(customerId);
    }

    /**
     * Marks one read.
     *
     * <p>THE CUSTOMER IS CHECKED AGAINST THE ROW rather than taken from it. Marking by id
     * alone would let anybody with a session mark somebody else's security notification read
     * — which is not a theoretical annoyance: the whole value of the PASSWORD_CHANGED row is
     * that it is still unread and at the top when its owner next signs in. A row that is not
     * theirs reports as not found, which is also all they should learn about it.
     */
    @Transactional
    public NotificationEntity markRead(UUID customerId, UUID notificationId) {
        NotificationEntity found =
                notifications
                        .findById(notificationId)
                        .filter(row -> row.customerId().equals(customerId))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that notification."));

        found.read();
        return notifications.save(found);
    }

    /** Marks everything unread as read, and says how many that was. */
    @Transactional
    public int markAllRead(UUID customerId) {
        List<NotificationEntity> unread = notifications.findByCustomerIdAndReadAtIsNull(customerId);
        for (NotificationEntity row : unread) {
            row.read();
            notifications.save(row);
        }
        return unread.size();
    }

    /* ======================================================== the events */

    /**
     * A card or cheque book is ready.
     *
     * <p>THE COLLECTION POINT IS IN THE BODY because it is the only fact here the customer
     * cannot work out for themselves, and it is the one the email leads with. Note that it
     * is a staff member's own words — there is no branch list in this portal, deliberately —
     * so it is text the bank typed rather than a value this code chose.
     */
    @Transactional
    public void serviceRequestReady(
            UUID customerId, String what, String reference, String collectionPoint, String link) {

        raise(
                customerId,
                NotificationKind.SERVICE_REQUEST_READY,
                "Your " + what + " is ready to collect",
                "Reference "
                        + reference
                        + ". Collect it at "
                        + collectionPoint
                        + ", and bring photo identification — we cannot hand it to anybody"
                        + " else.",
                link);
    }

    /** Staff declined a card or cheque book, in their own words. */
    @Transactional
    public void serviceRequestDeclined(
            UUID customerId, String what, String reference, String reason, String link) {

        raise(
                customerId,
                NotificationKind.SERVICE_REQUEST_DECLINED,
                "We could not action your " + what,
                "Reference " + reference + ". " + reason,
                link);
    }

    /** A saved payee may now be paid. */
    @Transactional
    public void beneficiaryApproved(UUID customerId, String payeeName, String maskedDestination) {
        raise(
                customerId,
                NotificationKind.BENEFICIARY_APPROVED,
                "You can now pay " + payeeName,
                "We have checked the payee you saved for account "
                        + maskedDestination
                        + ". If you did not add this payee, call the bank straight away.",
                "/beneficiaries");
    }

    /** A saved payee was refused, with the reviewer's reason. */
    @Transactional
    public void beneficiaryRefused(UUID customerId, String payeeName, String reason) {
        raise(
                customerId,
                NotificationKind.BENEFICIARY_REFUSED,
                "We could not approve " + payeeName,
                reason + " Nothing has been sent and no money has left your account.",
                "/beneficiaries");
    }

    /**
     * A manager released the customer's money.
     *
     * <p>THE FIRST TIME THE CUSTOMER IS TOLD THIS AT ALL. {@code TransferService.approve}
     * sends no email, so until now the only way to learn that one's own money had actually
     * moved was to keep reloading the dashboard's "Waiting for the bank" panel.
     */
    @Transactional
    public void transferReleased(
            UUID customerId, Money amount, String destinationMask, String reference) {

        raise(
                customerId,
                NotificationKind.TRANSFER_RELEASED,
                "Your transfer has been sent",
                display(amount)
                        + " to "
                        + destinationMask
                        + (reference == null || reference.isBlank() ? "" : " (" + reference + ")")
                        + " has been released and has left your account.",
                "/transfers");
    }

    /**
     * A manager refused the transfer.
     *
     * <p>SAYS THE MONEY DID NOT MOVE, in those words. The customer believes they have paid
     * somebody and somebody is expecting money; a message that only says "rejected" leaves
     * them unsure whether to send it again, which is how a payment gets made twice.
     */
    @Transactional
    public void transferRejected(
            UUID customerId, Money amount, String destinationMask, String reason) {

        raise(
                customerId,
                NotificationKind.TRANSFER_REJECTED,
                "Your transfer was not sent",
                display(amount)
                        + " to "
                        + destinationMask
                        + " has not been sent and the money is back in your account. "
                        + reason,
                "/transfers");
    }

    /**
     * The password changed.
     *
     * <p>NO LINK TO A RESET, AND NO LINK AT ALL EXCEPT THE SECURITY PAGE. A notification
     * saying "if this was not you, click here" trains people to click precisely the link a
     * phishing copy will supply — which is the same reasoning the emails are written under.
     * The instruction is to telephone.
     */
    @Transactional
    public void passwordChanged(UUID customerId, int browsersSignedOut) {
        String browsers =
                browsersSignedOut == 0
                        ? ""
                        : browsersSignedOut == 1
                                ? " One browser that could sign in without an emailed code has"
                                        + " been signed out."
                                : " "
                                        + browsersSignedOut
                                        + " browsers that could sign in without an emailed code"
                                        + " have been signed out.";

        raise(
                customerId,
                NotificationKind.PASSWORD_CHANGED,
                "Your password was changed",
                "If this was not you, call the number printed on the back of your card"
                        + " immediately."
                        + browsers,
                "/security");
    }

    /** A freeze was lifted. */
    @Transactional
    public void accountUnfrozen(UUID customerId) {
        raise(
                customerId,
                NotificationKind.ACCOUNT_UNFROZEN,
                "Your account is active again",
                "The hold on your account has been lifted and you can use the portal as"
                        + " normal.",
                "/accounts");
    }

    /* ============================================================== plumbing */

    /**
     * An amount as a customer reads it: "RWF 12,000".
     *
     * <p>FORMATTED HERE, where the rest of the copy is, and NOT with any arithmetic on it —
     * {@code toPlainString} has already done the only conversion there is, from minor units,
     * and this adds separators to the integer part of that string. Nothing in this class may
     * compute, round or restate a figure: the amount a notification quotes has to be the
     * amount that moved, and the one place it exists is the transfer row.
     *
     * <p>Grouping is normally the client's job, and is done here only because this sentence
     * is composed on the server. "RWF 12000" in a message about somebody's money is the kind
     * of thing that gets misread by a factor of ten.
     */
    private static String display(Money amount) {
        String plain = amount.toPlainString();

        int point = plain.indexOf('.');
        String whole = point < 0 ? plain : plain.substring(0, point);
        String rest = point < 0 ? "" : plain.substring(point);

        boolean negative = whole.startsWith("-");
        String digits = negative ? whole.substring(1) : whole;

        StringBuilder grouped = new StringBuilder();
        for (int index = 0; index < digits.length(); index++) {
            if (index > 0 && (digits.length() - index) % 3 == 0) grouped.append(',');
            grouped.append(digits.charAt(index));
        }

        return amount.currency() + " " + (negative ? "-" : "") + grouped + rest;
    }

    private void raise(
            UUID customerId, NotificationKind kind, String title, String body, String link) {

        notifications.save(NotificationEntity.raised(customerId, kind, title, body, link));

        /*
         * THE KIND AND THE CUSTOMER, never the body. A body carries a payee name, an
         * amount, a collection point and a staff member's free text; writing it to the log
         * would copy customer detail into a file with a quite different audience and
         * retention from the database row it came from.
         */
        log.info("Notified customer {} of {}", customerId, kind);
    }
}
