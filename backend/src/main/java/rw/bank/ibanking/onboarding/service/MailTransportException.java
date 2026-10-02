package rw.bank.ibanking.onboarding.service;

/**
 * A send that did not happen, described in words safe to store and show.
 *
 * <p>CHECKED, deliberately. The whole design of {@link Mailer} rests on a failed send being
 * an ordinary expected outcome rather than an exception that escapes: a manager's approval
 * must stand even when the notification does not go. An unchecked exception would make it
 * possible to forget that, and the compiler would say nothing.
 *
 * <p>ITS MESSAGE IS STORED. It lands in {@code outbox.failure} and is rendered on a staff
 * screen, so it must not carry provider text. An SMTP rejection quotes the username back; an
 * HTTP API's error body can echo the request, including the recipient. Transports log the
 * real detail at ERROR and construct this with something plain.
 *
 * <p>IT DOES NOT TAKE A CAUSE on purpose. A cause invites {@code getCause().getMessage()}
 * appearing in the stored text later, which is exactly the leak this type exists to prevent.
 * The cause belongs in the log, where the transport puts it.
 */
final class MailTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    MailTransportException(String safeMessage) {
        super(safeMessage);
    }
}
