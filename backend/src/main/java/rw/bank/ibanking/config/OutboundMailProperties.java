package rw.bank.ibanking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound email settings, bound from {@code ibanking.mail.*}.
 *
 * <p>Bound rather than read ad hoc so that a typo in the YAML fails at startup instead of
 * silently leaving a value at its default. A misspelled {@code from-name} is cosmetic; a
 * misspelled {@code enabled} means a deployment that believes it is sending verification
 * codes and is not, which nobody notices until customers report that registration never
 * completes.
 *
 * <p>Note what is NOT here: the SMTP host, port, username and password. Those bind to
 * Spring's own {@code spring.mail.*} and are consumed by the mail sender Boot builds. The
 * password is never read by application code except to ask whether it is blank — nothing
 * needs its value, and a getter for it is the first step towards a log line containing it.
 *
 * <p>The name is {@code OutboundMailProperties} rather than {@code MailProperties} because
 * Spring Boot already has a class by the latter name for {@code spring.mail.*}. Two
 * same-named configuration records in one codebase is an import away from binding the
 * wrong one.
 *
 * @param enabled whether the service may actually send. False means messages are recorded
 *     and logged instead, which is the correct default: adding the mail dependency must not
 *     change behaviour on a machine with no SMTP configured.
 * @param from the address customers see in the From field
 * @param fromName the display name shown beside that address
 * @param codeValidity how long a registration verification code stays usable
 */
@ConfigurationProperties(prefix = "ibanking.mail")
public record OutboundMailProperties(boolean enabled, String from, String fromName, Duration codeValidity) {

    /** Ten minutes. Long enough to find the email, short enough that a forwarded one is dead. */
    private static final Duration DEFAULT_CODE_VALIDITY = Duration.ofMinutes(10);

    /** Beyond this a code stops being a second factor and becomes a standing credential. */
    private static final Duration MAX_CODE_VALIDITY = Duration.ofHours(1);

    public OutboundMailProperties {
        from = from == null ? "" : from.trim();
        fromName = fromName == null || fromName.isBlank() ? "Ciara's demo" : fromName.trim();
        codeValidity = codeValidity == null ? DEFAULT_CODE_VALIDITY : codeValidity;

        /*
         * Sending with no From address would produce mail that fails every receiving
         * filter, so it is a configuration error rather than something to discover at
         * the moment a customer is waiting for a code. Only checked when enabled: an
         * environment with mail off has nothing to configure.
         */
        if (enabled && from.isEmpty()) {
            throw new IllegalArgumentException(
                    "ibanking.mail.from must be set when ibanking.mail.enabled is true.");
        }

        if (codeValidity.isNegative() || codeValidity.isZero()) {
            throw new IllegalArgumentException(
                    "ibanking.mail.code-validity must be positive; got " + codeValidity);
        }

        if (codeValidity.compareTo(MAX_CODE_VALIDITY) > 0) {
            throw new IllegalArgumentException(
                    "ibanking.mail.code-validity must not exceed "
                            + MAX_CODE_VALIDITY
                            + "; got "
                            + codeValidity
                            + ". A verification code that lives longer than an hour is a"
                            + " standing credential sitting in an inbox.");
        }
    }

    /** True when the address is one this bank can authenticate as its own. */
    public boolean hasOwnedSenderDomain() {
        /*
         * SPF, DKIM and DMARC records live in the sending domain's DNS. For a consumer
         * mailbox that DNS belongs to the provider, so the bank's mail can never be
         * cryptographically attributed to the bank — every receiver has to treat it as
         * unauthenticated mail claiming to be from a bank, which is what a phishing run
         * looks like.
         *
         * Used to warn at startup rather than to refuse. Development legitimately runs on
         * a personal mailbox; a deployment silently doing so is what this is for.
         */
        String domain = from.contains("@") ? from.substring(from.indexOf('@') + 1) : "";
        return !domain.isEmpty() && !CONSUMER_MAIL_DOMAINS.contains(domain.toLowerCase());
    }

    private static final java.util.Set<String> CONSUMER_MAIL_DOMAINS =
            java.util.Set.of(
                    "gmail.com",
                    "googlemail.com",
                    "yahoo.com",
                    "outlook.com",
                    "hotmail.com",
                    "live.com",
                    "icloud.com",
                    "me.com",
                    "aol.com",
                    "proton.me",
                    "protonmail.com",
                    "yandex.com",
                    "zoho.com");
}
