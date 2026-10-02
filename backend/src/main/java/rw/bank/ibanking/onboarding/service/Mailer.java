package rw.bank.ibanking.onboarding.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * and the email provider is unreachable, the approval still stands — it is a decision about a customer's
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
     * Whatever actually carries a message out — SMTP, or Brevo's HTTP API.
     *
     * <p>THIS USED TO BE A {@code JavaMailSender} DIRECTLY, and that was the bug: SMTP was
     * not a detail, it was the only option, and Render's free web services block outbound
     * SMTP ports. Every verification code was recorded undelivered on that host and no
     * configuration could fix it. Which transport is in here is decided once, at startup,
     * by {@code MailTransportConfiguration}.
     */
    private final MailTransport transport;

    Mailer(OutboundMailProperties settings, OutboxRepository outbox, MailTransport transport) {
        this.settings = settings;
        this.outbox = outbox;
        this.transport = transport;
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

        try {
            transport.send(settings.from(), settings.fromName(), to, subject, body);

            log.info("Sent {} to {} over {}", kind, to, transport.description());
            return outbox.save(OutboxEntity.sent(kind, settings.from(), to, subject, body));

        } catch (MailTransportException e) {
            /*
             * THE MESSAGE ON THE EXCEPTION IS WHAT GETS STORED, and that is safe by the
             * type's own contract: a transport logs the provider's text and throws
             * something plain, because this column is rendered on a staff screen. The old
             * code hard-coded one SMTP-shaped sentence here, which would have read "the
             * mail server rejected or could not be reached" for an HTTP API that answered
             * 401 — true in spirit and useless for finding the problem.
             */
            log.error("Failed to send {} to {} over {}", kind, to, transport.description());
            return outbox.save(
                    OutboxEntity.notSent(
                            kind, settings.from(), to, subject, body, e.getMessage()));
        }
    }
}
