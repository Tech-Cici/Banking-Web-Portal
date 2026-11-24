package rw.bank.ibanking.onboarding.domain;

/**
 * WHAT A NOTIFICATION IS ABOUT, and how loudly the portal should say it.
 *
 * <p>EVERY VALUE HERE IS AN EVENT A SIGNED-IN CUSTOMER CAN READ. That is the rule that
 * decides membership, and it excludes most of {@link OutboxKind}: an account being created,
 * approved, rejected or frozen, and a password being re-issued or refused, all happen at a
 * moment when the customer cannot sign in — usually that is the point of them. An in-app
 * notification for those would be unreadable by construction, and a table full of rows
 * nobody can see would make the product look more finished than it is. Email is the only
 * channel that reaches somebody locked out, which is why those are emails and nothing else.
 *
 * <p>THE SEVERITY LIVES HERE rather than being passed in at each call site, because it is a
 * property of the event and not of the moment. Two call sites that disagreed about how
 * loudly a refused payee should be announced would be a bug nobody noticed; one list cannot
 * disagree with itself.
 */
public enum NotificationKind {

    /** A card or cheque book is made and waiting at a named counter. */
    SERVICE_REQUEST_READY(NotificationSeverity.INFO),

    /**
     * Staff could not action a card or cheque book.
     *
     * <p>A WARNING, not INFO. The customer is usually the only party who can fix the reason,
     * and a decline rendered in the same grey as a routine message is a decline that waits
     * three weeks for somebody to telephone about it.
     */
    SERVICE_REQUEST_DECLINED(NotificationSeverity.WARNING),

    /** A saved payee has been checked and may now be paid. */
    BENEFICIARY_APPROVED(NotificationSeverity.INFO),

    /** A saved payee was refused, with the reviewer's reason. */
    BENEFICIARY_REFUSED(NotificationSeverity.WARNING),

    /**
     * A manager released money the customer had sent.
     *
     * <p>THE MOST VALUABLE ROW IN THIS TABLE, and until now the customer was told nothing at
     * all: {@code TransferService.approve} sends no email, so the only way to learn that
     * one's own money had moved was to keep reloading the dashboard's "Waiting for the bank"
     * panel.
     */
    TRANSFER_RELEASED(NotificationSeverity.INFO),

    /**
     * A manager refused a transfer and the money stayed put.
     *
     * <p>A WARNING for the same reason as a declined request, and more sharply: the customer
     * believes they have paid somebody. Somebody is expecting money that is not coming.
     */
    TRANSFER_REJECTED(NotificationSeverity.WARNING),

    /**
     * Money arrived from somebody else.
     *
     * <p>THE OTHER HALF OF {@link #TRANSFER_RELEASED}, and the half that was missing. The
     * sender was told their money had gone; the person it went to was told nothing, and
     * found out by opening the account and reading the ledger. "Has it arrived" is the
     * question a transfer actually raises, and it was the one event with no channel on the
     * receiving side.
     *
     * <p>INFO, not SECURITY. Receiving money is ordinary, and a credit rendered as loudly
     * as a password change would teach people to ignore the colour that matters.
     */
    TRANSFER_RECEIVED(NotificationSeverity.INFO),

    /** A standing order paid on its due date. */
    STANDING_ORDER_PAID(NotificationSeverity.INFO),

    /**
     * A standing order could not pay.
     *
     * <p>A WARNING, and arguably the most important row in this table. A standing order
     * executes with no manager releasing it, so the customer is the only check there is on
     * whether their rent went out. A missed payment rendered in the same grey as a routine
     * message is a missed payment nobody notices until the landlord calls.
     */
    STANDING_ORDER_FAILED(NotificationSeverity.WARNING),

    /**
     * The account's password was changed.
     *
     * <p>SECURITY, which the panel pins above everything else. This notification cannot
     * prevent the change — whoever made it already had the current password — so its whole
     * value is reaching somebody who did NOT make it, and it is worthless if it sits fourth
     * in a list under a cheque book.
     */
    /**
     * A loan application was approved and the money paid in.
     *
     * <p>IN THE PORTAL AS WELL AS BY EMAIL, for the reason recorded on the service-request
     * path: the email reaches an inbox, this reaches somebody who signs in without reading
     * it. For a loan that matters more than for a cheque book -- the balance has changed, and
     * a customer who cannot see why has been given money by a system that did not say so.
     */
    LOAN_GRANTED(NotificationSeverity.INFO),

    PASSWORD_CHANGED(NotificationSeverity.SECURITY),

    /**
     * A freeze has been lifted.
     *
     * <p>THE ONLY ACCOUNT-STATUS EVENT HERE, and it is here precisely because it is the only
     * one the customer can read: they can sign in again afterwards. The freeze itself cannot
     * be notified in-app, because a frozen customer is refused entry.
     */
    ACCOUNT_UNFROZEN(NotificationSeverity.INFO);

    private final NotificationSeverity severity;

    NotificationKind(NotificationSeverity severity) {
        this.severity = severity;
    }

    public NotificationSeverity severity() {
        return severity;
    }
}
