package rw.bank.ibanking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says, once at startup, what the mail configuration will actually do.
 *
 * <p>The failure this exists to prevent is silence. Mail is off by default and every value
 * has a default, so a service can start perfectly happily in a state where no customer will
 * ever receive a verification code — and the first symptom is a support call weeks later
 * about registrations that never complete. One line in the startup log is cheap insurance.
 *
 * <p>It warns; it does not refuse. Refusing to start over a mail setting would take an
 * otherwise healthy API offline, and every other endpoint works without SMTP.
 */
@Component
class MailStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(MailStartupCheck.class);

    private final OutboundMailProperties mail;
    private final MailProperties smtp;
    private final BrevoProperties brevo;

    MailStartupCheck(OutboundMailProperties mail, MailProperties smtp, BrevoProperties brevo) {
        this.mail = mail;
        this.smtp = smtp;
        this.brevo = brevo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        if (!mail.enabled()) {
            /*
             * This used to say codes "will be logged". Nothing logs them — deliberately,
             * because a verification code in a log file is a credential in every backup
             * downstream of it. Saying so anyway sent a developer looking in the wrong
             * place, so the message now names the right one.
             */
            log.info(
                    "Email is OFF (ibanking.mail.enabled=false). Verification codes and approval"
                            + " notices are recorded at GET /api/v1/admin/outbox (staff"
                            + " credentials required) instead of being sent. Set MAIL_ENABLED=true"
                            + " and supply MAIL_PASSWORD to send for real.");
            return;
        }

        /*
         * TWO TRANSPORTS NOW, and each has its own way of being half-configured. Reporting
         * SMTP credentials on a deployment that sends over HTTP would send somebody to
         * check a password that is not used.
         */
        if (brevo.enabled()) {
            /*
             * Only ever asked whether it is blank — see BrevoProperties. An API key in a log
             * file is a key in every backup downstream of it. BrevoProperties already
             * refuses to bind without one, so reaching here blank should be impossible; the
             * line stays because "should be impossible" is how the SMTP case started.
             */
            if (!brevo.hasApiKey()) {
                log.error(
                        "Email is ON over Brevo but BREVO_API_KEY is empty. Every send will"
                                + " fail. Create a key at https://app.brevo.com under SMTP & API"
                                + " → API keys.");
                return;
            }

            log.info(
                    "Email is ON, sending as {} <{}> via the Brevo HTTP API."
                            + " SMTP settings are ignored.",
                    mail.fromName(),
                    mail.from());

            /*
             * THE MISTAKE THIS CATCHES. Brevo refuses to send from an address that is not a
             * verified sender in the account, and the failure arrives as a 400 that reads
             * like a malformed request. Saying it here, at startup, is cheaper than finding
             * it when a customer is waiting for a code.
             */
            log.warn(
                    "Brevo will refuse any message whose sender is not verified in the account."
                            + " Confirm {} is listed under Senders, domains & dedicated IPs.",
                    mail.from());

        } else {
            /*
             * Only ever asked whether it is blank. The value is not logged, not returned, and
             * not held anywhere — an SMTP password in a log file is a password in every backup
             * and every log aggregator downstream of it.
             */
            boolean credentialsMissing =
                    isBlank(smtp.getUsername()) || isBlank(smtp.getPassword());

            if (credentialsMissing) {
                log.error(
                        "Email is ON but SMTP credentials are incomplete (MAIL_USERNAME and/or"
                                + " MAIL_PASSWORD are empty). Every send will fail. For Gmail,"
                                + " MAIL_PASSWORD must be a 16-character App Password from"
                                + " https://myaccount.google.com/apppasswords (the account password"
                                + " is not accepted).");
                return;
            }

            log.info(
                    "Email is ON, sending as {} <{}> via {}",
                    mail.fromName(),
                    mail.from(),
                    smtp.getHost());

            /*
             * SMTP DOES NOT WORK EVERYWHERE, and the host that does not allow it does not say
             * so — the send simply fails as though the relay were down. Render's free web
             * services block outbound SMTP ports outright, which cost an afternoon to find.
             */
            log.info(
                    "If every send fails with a connection error, check whether the host allows"
                            + " outbound SMTP. Some platforms block those ports entirely; set"
                            + " BREVO_ENABLED=true to send over HTTPS instead.");
        }

        if (!mail.hasOwnedSenderDomain()) {
            log.warn(
                    "Sender {} is a consumer mailbox. SPF, DKIM and DMARC for that domain belong"
                            + " to the provider, not to this bank, so outgoing mail cannot be"
                            + " authenticated as the bank's: expect spam filtering, and note that"
                            + " customers are being taught to trust bank mail from a personal"
                            + " address. Acceptable in development only; production needs an"
                            + " address on a domain the bank controls.",
                    mail.from());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
