package rw.bank.ibanking.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * The startup line is the whole point of {@link MailStartupCheck}, so the test asserts on
 * what it actually says. A test that only checked the context loads would pass with the
 * component silently doing nothing, which is the exact failure it exists to prevent.
 */
class MailStartupCheckTest {

    @Nested
    @DisplayName("mail off (the default)")
    @SpringBootTest
    @ActiveProfiles("test")
    @ExtendWith(OutputCaptureExtension.class)
    class Disabled {

        @Test
        @DisplayName("says so, and says how to turn it on")
        void announcesDisabled(CapturedOutput output) {
            assertThat(output).contains("Email is OFF");
            assertThat(output).contains("MAIL_ENABLED=true");
            // Never claims to be sending when it is not.
            assertThat(output).doesNotContain("Email is ON");
        }
    }

    @Nested
    @DisplayName("mail on with no credentials")
    @SpringBootTest(
            properties = {
                "ibanking.mail.enabled=true",
                "ibanking.mail.from=noreply@zigama.rw",
                "spring.mail.username=",
                "spring.mail.password="
            })
    @ActiveProfiles("test")
    @ExtendWith(OutputCaptureExtension.class)
    class EnabledWithoutCredentials {

        @Test
        @DisplayName("says every send will fail, and points at the App Password page")
        void announcesMissingCredentials(CapturedOutput output) {
            /*
             * The worst state to be quiet about: configured as if it works, sends nothing.
             * Without this line the first symptom is a customer who never got a code.
             */
            assertThat(output).contains("Every send will fail");
            assertThat(output).contains("myaccount.google.com/apppasswords");
        }

        @Test
        @DisplayName("does not print the password, blank or otherwise")
        void neverLogsTheSecret(CapturedOutput output) {
            assertThat(output).doesNotContain("spring.mail.password=");
        }
    }

    @Nested
    @DisplayName("mail on, sending from a consumer mailbox")
    @SpringBootTest(
            properties = {
                "ibanking.mail.enabled=true",
                "ibanking.mail.from=ciaramuzora@gmail.com",
                "spring.mail.username=ciaramuzora@gmail.com",
                "spring.mail.password=not-a-real-app-password"
            })
    @ActiveProfiles("test")
    @ExtendWith(OutputCaptureExtension.class)
    class EnabledWithConsumerSender {

        @Test
        @DisplayName("confirms it is on and names the sender")
        void announcesEnabled(CapturedOutput output) {
            assertThat(output).contains("Email is ON");
            assertThat(output).contains("ciaramuzora@gmail.com");
        }

        @Test
        @DisplayName("warns that the bank cannot be authenticated as the sender")
        void warnsAboutDeliverability(CapturedOutput output) {
            assertThat(output).contains("consumer mailbox");
            assertThat(output).contains("SPF, DKIM and DMARC");
        }

        @Test
        @DisplayName("never writes the SMTP password to the log")
        void neverLogsTheSecret(CapturedOutput output) {
            // An SMTP password in a log is a password in every backup and log aggregator
            // downstream of it.
            assertThat(output).doesNotContain("not-a-real-app-password");
        }
    }
}
