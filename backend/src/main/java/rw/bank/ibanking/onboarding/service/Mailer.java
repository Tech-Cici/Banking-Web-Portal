package rw.bank.ibanking.onboarding.service;

import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.config.OutboundMailProperties;
import rw.bank.ibanking.onboarding.domain.OutboxEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.repo.OutboxRepository;

/**
 * The one place this service sends email.
 *
 * <p>Three behaviours are deliberate and worth not undoing.
 *
 * <p><b>Every message is recorded, whether or not it was sent.</b> The outbox row carries
 * `delivered`, so "we composed it" and "it left the building" are never conflated. That
 * distinction is what answers a customer who says nobody ever told them their account was
 * ready.
 *
 * <p><b>A send failure never fails the caller's work.</b> If a manager approves an account
 * and Gmail is unreachable, the approval still stands — it is a decision about a customer's
 * account, not an email. Rolling it back because SMTP blinked would mean the customer's
 * account silently reverts to pending and the manager believes they released it. So the
 * failure is recorded and surfaced, and the business decision holds.
 *
 * <p><b>It runs in its own transaction.</b> {@code REQUIRES_NEW} so the outbox row survives
 * even when the caller's transaction later rolls back: knowing an email went out to a
 * customer is exactly the fact you must not lose when something else fails.
 */
@Service
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final OutboundMailProperties settings;
    private final OutboxRepository outbox;

    /**
     * Obtained lazily.
     *
     * <p>{@code JavaMailSender} is only autoconfigured when {@code spring.mail.host} is
     * set. An {@code ObjectProvider} lets the service start, and every non-mail endpoint
     * work, on a machine with no SMTP configured at all — rather than failing to start
     * because a bean is missing.
     */
    private final ObjectProvider<JavaMailSender> mailSender;

    Mailer(
            OutboundMailProperties settings,
            OutboxRepository outbox,
            ObjectProvider<JavaMailSender> mailSender) {
        this.settings = settings;
        this.outbox = outbox;
        this.mailSender = mailSender;
    }

    /**
     * Records the message, and sends it when sending is on.
     *
     * @return the outbox row, whose {@code delivered()} says what actually happened
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxEntity send(OutboxKind kind, String to, String subject, String body) {
        if (!settings.enabled()) {
            /*
             * Not an error, and not silence either. The row exists, marked undelivered,
             * so the registration flow can be walked end to end with no mail server and
             * nobody is misled into thinking a customer was contacted.
             */
            log.info("Mail is off; recording {} to {} without sending", kind, to);
            return outbox.save(
                    OutboxEntity.notSent(
                            kind, settings.from(), to, subject, body, "Sending is disabled"));
        }

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.error(
                    "Mail is on but no mail sender is configured (spring.mail.host is unset)."
                            + " Recording {} to {} as undelivered.",
                    kind,
                    to);
            return outbox.save(
                    OutboxEntity.notSent(
                            kind, settings.from(), to, subject, body, "No mail sender configured"));
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(settings.from(), settings.fromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);

            log.info("Sent {} to {}", kind, to);
            return outbox.save(
                    OutboxEntity.sent(kind, settings.from(), to, subject, body));

        } catch (MailException | jakarta.mail.MessagingException | UnsupportedEncodingException e) {
            /*
             * The exception's own message is not stored. It can carry the SMTP dialogue,
             * which for an authentication failure includes the username — and a provider's
             * text is not something to put in a column that a support screen renders.
             */
            log.error("Failed to send {} to {}", kind, to, e);
            return outbox.save(
                    OutboxEntity.notSent(
                            kind,
                            settings.from(),
                            to,
                            subject,
                            body,
                            "The mail server rejected or could not be reached."));
        }
    }
}
