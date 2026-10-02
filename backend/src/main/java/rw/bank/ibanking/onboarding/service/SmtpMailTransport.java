package rw.bank.ibanking.onboarding.service;

import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends over SMTP, through whatever relay {@code spring.mail.*} points at.
 *
 * <p>This is the code that used to live inside {@link Mailer}, moved out unchanged in
 * behaviour so that a second transport could exist beside it. The one thing it no longer does
 * is write to the outbox — that stayed in {@code Mailer}, where it belongs, so both transports
 * get the same recording and neither can forget it.
 *
 * <p>IT WILL NOT WORK ON EVERY HOST. Render's free web services block outbound SMTP ports
 * outright, which is what {@link BrevoMailTransport} exists for. This remains the right
 * transport for development and for any host that permits port 587.
 *
 * <p>{@code JavaMailSender} is obtained through an {@link ObjectProvider} because Boot only
 * autoconfigures it when {@code spring.mail.host} is set. That lets the service start, and
 * every non-mail endpoint work, on a machine with no SMTP configured at all.
 */
class SmtpMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

    private final ObjectProvider<JavaMailSender> mailSender;

    SmtpMailTransport(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String description() {
        return "SMTP";
    }

    @Override
    public void send(String fromEmail, String fromName, String to, String subject, String body)
            throws MailTransportException {

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.error("Mail is on but no mail sender is configured (spring.mail.host is unset).");
            throw new MailTransportException("No mail sender configured");
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);

        } catch (MailException | jakarta.mail.MessagingException | UnsupportedEncodingException e) {
            /*
             * The exception's own message is not passed on. It can carry the SMTP dialogue,
             * which for an authentication failure includes the username — and a provider's
             * text is not something to put in a column that a support screen renders.
             */
            log.error("Failed to send over SMTP", e);
            throw new MailTransportException("The mail server rejected or could not be reached.");
        }
    }
}
