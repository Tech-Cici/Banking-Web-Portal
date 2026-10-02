package rw.bank.ibanking.onboarding.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import rw.bank.ibanking.config.BrevoProperties;

/**
 * Chooses which way mail leaves, once, at startup.
 *
 * <p>ONE FLAG DECIDES IT: {@code ibanking.mail.brevo.enabled}. Not a string naming a
 * transport, because a misspelled {@code "brevoo"} would have to either throw or fall back
 * silently, and a silent fallback to a transport the host blocks is the failure this whole
 * change exists to fix.
 *
 * <p>The default is SMTP, so adding Brevo changed nothing for anyone who does not switch it
 * on — development keeps working as it did.
 *
 * <p>A SEPARATE LOG LINE IS NOT NEEDED HERE. {@code MailStartupCheck} already reports what
 * mail will do at startup and now names the transport, so saying it twice would just be
 * noise. The line below is at DEBUG for the case where a bean is being chased.
 */
@Configuration
class MailTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MailTransportConfiguration.class);

    @Bean
    MailTransport mailTransport(
            BrevoProperties brevo, ObjectProvider<JavaMailSender> mailSender) {

        if (brevo.enabled()) {
            log.debug("Mail transport: Brevo HTTP API");
            return new BrevoMailTransport(brevo);
        }

        log.debug("Mail transport: SMTP");
        return new SmtpMailTransport(mailSender);
    }
}
