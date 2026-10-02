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

    MailStartupCheck(OutboundMailProperties mail, MailProperties smtp) {
        this.mail = mail;
        this.smtp = smtp;
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

        log.info("Email is ON, sending as {} <{}> via {}", mail.fromName(), mail.from(), smtp.getHost());

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
