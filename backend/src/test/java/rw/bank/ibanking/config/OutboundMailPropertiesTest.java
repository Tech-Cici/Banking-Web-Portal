package rw.bank.ibanking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The mail configuration is bound so that misconfiguration is loud. These pin the cases
 * where staying quiet would be worse than failing to start.
 */
class OutboundMailPropertiesTest {

    private static OutboundMailProperties of(boolean enabled, String from, Duration validity) {
        return new OutboundMailProperties(enabled, from, null, validity);
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("sending is off unless something turns it on")
        void disabledByDefault() {
            // Adding the mail dependency must not change behaviour on a machine with no
            // SMTP configured. This is that promise, as a test.
            assertThat(of(false, "", null).enabled()).isFalse();
        }

        @Test
        @DisplayName("code validity defaults to ten minutes")
        void codeValidityDefault() {
            assertThat(of(false, "", null).codeValidity()).isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        @DisplayName("a blank display name falls back to the bank's name")
        void fromNameDefault() {
            assertThat(new OutboundMailProperties(false, "a@b.rw", "  ", null).fromName())
                    .isEqualTo("Ciara's demo");
        }
    }

    @Nested
    @DisplayName("refuses to start")
    class Refusals {

        @Test
        @DisplayName("when sending is on but no sender address is set")
        void enabledWithoutFrom() {
            /*
             * Mail with no From address fails every receiving filter. Better to fail at
             * startup than at the moment a customer is waiting for a code.
             */
            assertThatThrownBy(() -> of(true, "  ", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ibanking.mail.from");
        }

        @Test
        @DisplayName("when a code would never expire")
        void nonPositiveValidity() {
            assertThatThrownBy(() -> of(false, "a@b.rw", Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("code-validity");

            assertThatThrownBy(() -> of(false, "a@b.rw", Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("when a code would outlive the hour")
        void excessiveValidity() {
            // A code valid for a day is not a second factor, it is a password sitting in
            // an inbox. The cap is deliberately low.
            assertThatThrownBy(() -> of(false, "a@b.rw", Duration.ofHours(24)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("standing credential");
        }

        @Test
        @DisplayName("but accepts an hour exactly")
        void boundaryAccepted() {
            assertThat(of(false, "a@b.rw", Duration.ofHours(1)).codeValidity())
                    .isEqualTo(Duration.ofHours(1));
        }
    }

    @Nested
    @DisplayName("sender domain")
    class SenderDomain {

        @Test
        @DisplayName("a consumer mailbox is not a domain the bank can authenticate as")
        void consumerDomainsAreFlagged() {
            // The current development sender. SPF/DKIM/DMARC for gmail.com belong to
            // Google, so this address can never be authenticated as the bank's.
            assertThat(of(false, "ciaramuzora@gmail.com", null).hasOwnedSenderDomain()).isFalse();

            for (String address :
                    new String[] {
                        "x@googlemail.com", "x@YAHOO.com", "x@outlook.com", "x@icloud.com"
                    }) {
                assertThat(of(false, address, null).hasOwnedSenderDomain())
                        .as(address)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a domain the bank controls is fine")
        void ownedDomainAccepted() {
            assertThat(of(false, "noreply@zigama.rw", null).hasOwnedSenderDomain()).isTrue();
        }

        @Test
        @DisplayName("a malformed address is not treated as owned")
        void malformedIsNotOwned() {
            // Fail closed: an address with no domain cannot be vouched for.
            assertThat(of(false, "not-an-address", null).hasOwnedSenderDomain()).isFalse();
            assertThat(of(false, "", null).hasOwnedSenderDomain()).isFalse();
        }
    }
}
