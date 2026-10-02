package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.repo.OutboxRepository;
import rw.bank.ibanking.onboarding.service.Mailer;

/**
 * Whether a message actually goes out, and what happens when it cannot.
 *
 * <p>The mail sender is mocked rather than replaced with a real SMTP server: what is under
 * test is this service's behaviour — the message it builds, the row it writes, and what it
 * does with a failure. Whether Spring can hold an SMTP conversation is Spring's concern and
 * is not worth a test here.
 */
class MailerTest {

    @Nested
    @DisplayName("sending switched off (the default)")
    @SpringBootTest(properties = "management.health.mail.enabled=false")
    @ActiveProfiles("test")
    class Disabled {

        @Autowired Mailer mailer;
        @Autowired OutboxRepository outbox;
        @MockitoBean JavaMailSender mailSender;

        @BeforeEach
        void clear() {
            outbox.deleteAll();
        }

        @Test
        @DisplayName("records the message and does not pretend it was delivered")
        void recordsWithoutSending() {
            var row =
                    mailer.send(
                            OutboxKind.ACCOUNT_APPROVED, "someone@example.rw", "Subject", "Body");

            assertThat(row.delivered()).isFalse();
            assertThat(row.failure()).contains("disabled");
            // The distinction that matters: composed is not the same as sent.
            verifyNoInteractions(mailSender);
        }
    }

    @Nested
    @DisplayName("sending switched on")
    @SpringBootTest(
            properties = {
                "ibanking.mail.enabled=true",
                "ibanking.mail.from=noreply@zigama.rw",
                "ibanking.mail.from-name=Zigama CSS",
                "spring.mail.host=localhost",
                "spring.mail.username=noreply@zigama.rw",
                "spring.mail.password=irrelevant-for-this-test",
                // See application.yml: the mail health indicator opens an SMTP connection
                // per probe, and it also clashes with a mocked JavaMailSender here.
                "management.health.mail.enabled=false"
            })
    @ActiveProfiles("test")
    class Enabled {

        @Autowired Mailer mailer;
        @Autowired OutboxRepository outbox;
        @MockitoBean JavaMailSender mailSender;

        @BeforeEach
        void clear() {
            outbox.deleteAll();
            // The real implementation is what builds a MimeMessage; the mock only
            // intercepts the send, so the message under inspection is a genuine one.
            when(mailSender.createMimeMessage())
                    .thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());
        }

        @Test
        @DisplayName("builds a real message with the configured sender and marks it delivered")
        void sends() throws Exception {
            var row =
                    mailer.send(
                            OutboxKind.EMAIL_VERIFICATION,
                            "applicant@example.rw",
                            "Your Zigama CSS verification code",
                            "Your verification code is 123456.");

            ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
            verify(mailSender).send(captor.capture());
            MimeMessage sent = captor.getValue();

            assertThat(sent.getFrom()[0].toString()).contains("noreply@zigama.rw");
            assertThat(sent.getFrom()[0].toString()).contains("Zigama CSS");
            assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("applicant@example.rw");
            assertThat(sent.getSubject()).isEqualTo("Your Zigama CSS verification code");
            assertThat(sent.getContent().toString()).contains("123456");

            assertThat(row.delivered()).isTrue();
            assertThat(row.failure()).isNull();
        }

        @Test
        @DisplayName("a refused send is recorded as undelivered, without the SMTP detail")
        void failureIsRecordedSafely() {
            /*
             * The provider's own text can carry the SMTP dialogue — which for an
             * authentication failure includes the username — so it must not reach a
             * column a support screen renders.
             */
            doThrow(
                            new MailSendException(
                                    "535-5.7.8 Username and Password not accepted for"
                                            + " noreply@zigama.rw"))
                    .when(mailSender)
                    .send(any(MimeMessage.class));

            var row =
                    mailer.send(
                            OutboxKind.ACCOUNT_APPROVED, "someone@example.rw", "Subject", "Body");

            assertThat(row.delivered()).isFalse();
            assertThat(row.failure()).doesNotContain("535");
            assertThat(row.failure()).doesNotContain("Username and Password");
            assertThat(row.failure()).doesNotContain("noreply@zigama.rw");
            assertThat(row.failure()).contains("rejected or could not be reached");
        }

        @Test
        @DisplayName("a send failure does not throw, so it cannot undo the caller's decision")
        void failureDoesNotPropagate() {
            doThrow(new MailSendException("unreachable")).when(mailSender).send(any(MimeMessage.class));

            /*
             * This is the important one. If a manager approves an account and Gmail is
             * unreachable, the approval must still stand — it is a decision about a
             * customer's account, not an email. Throwing here would roll the approval
             * back while the manager believes they released it.
             */
            var row = mailer.send(OutboxKind.ACCOUNT_APPROVED, "x@example.rw", "S", "B");
            assertThat(row.delivered()).isFalse();
            assertThat(row.body()).isEqualTo("B");
        }
    }
}
