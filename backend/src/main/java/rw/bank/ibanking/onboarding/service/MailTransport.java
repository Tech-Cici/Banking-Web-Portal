package rw.bank.ibanking.onboarding.service;

/**
 * The one thing {@link Mailer} needs from whatever actually carries a message out.
 *
 * <p>WHY THIS EXISTS. {@code Mailer} spoke directly to {@code JavaMailSender}, which means
 * SMTP, which means a TCP connection to port 587. Render's free web services do not allow
 * outbound traffic to SMTP ports at all — so on that platform the send could never succeed
 * whatever the credentials were, and the outbox recorded a row reading "The mail server
 * rejected or could not be reached." for every verification code. An HTTP mail API gets out
 * over 443 like any other request.
 *
 * <p>So there are two ways to send and the deployment picks one. The interface is small on
 * purpose: everything that makes {@code Mailer} careful — recording every message whether or
 * not it left, keeping "composed" and "delivered" apart, never failing the caller's business
 * decision over a mail problem — stays in {@code Mailer} and is not reimplemented per
 * transport. A transport's only job is to hand one message to one provider and say whether
 * that worked.
 *
 * <p>THE EXCEPTION IS PART OF THE CONTRACT. A failure is reported as
 * {@link MailTransportException} carrying a message safe to store in the {@code outbox.failure}
 * column, which a staff screen renders. Provider text is not safe: an SMTP authentication
 * failure quotes the username back, and an API error body can echo the request. So a
 * transport logs the detail and throws something plain.
 */
interface MailTransport {

    /**
     * What this transport is, for the one startup log line that says how mail will be sent.
     *
     * <p>Worth a method rather than a class-name check: "Brevo HTTP API" in a log tells
     * somebody reading it why SMTP settings are being ignored.
     */
    String description();

    /**
     * Sends one message, or throws.
     *
     * @throws MailTransportException when the message did not go. The message on it is
     *     stored and shown to staff, so it must name the problem without quoting the
     *     provider, the credentials, or the recipient.
     */
    void send(String fromEmail, String fromName, String to, String subject, String body)
            throws MailTransportException;
}
