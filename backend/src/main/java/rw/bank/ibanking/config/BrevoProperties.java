package rw.bank.ibanking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Brevo's HTTP mail API, bound from {@code ibanking.mail.brevo.*}.
 *
 * <p>SEPARATE FROM {@link OutboundMailProperties} rather than two more fields on it, because
 * the two answer different questions. That record says whether mail is on and who it comes
 * from — true of every transport. This one says use Brevo instead of SMTP, and holds the one
 * credential that needs. Keeping them apart also meant adding this changed no existing
 * constructor call.
 *
 * <p>WHY BREVO AT ALL. Render's free web services do not allow outbound traffic to SMTP
 * ports, so {@code smtp.gmail.com:587} is unreachable from that container regardless of
 * credentials — every verification code was recorded undelivered. Brevo's transactional
 * endpoint is ordinary HTTPS on 443, which is not blocked. Brevo also offers an SMTP relay;
 * that would be blocked exactly as Gmail's is, so this must be the HTTP API.
 *
 * @param enabled whether to send through Brevo rather than SMTP. False by default so that
 *     adding this changes nothing: development keeps using SMTP, or the local mailbox.
 * @param apiKey the {@code xkeysib-…} key, sent in the {@code api-key} header. Never logged,
 *     never returned, never put in an exception message — only ever asked whether it is
 *     blank.
 * @param timeout how long to wait on the API before giving up. Short: a customer is sitting
 *     on a "we sent you a code" screen while this call is in flight.
 */
@ConfigurationProperties(prefix = "ibanking.mail.brevo")
public record BrevoProperties(boolean enabled, String apiKey, Duration timeout) {

    /**
     * Ten seconds.
     *
     * <p>Chosen against the thing on the other side of it: a registration request holds a
     * thread while this runs, and the customer is watching a spinner. A send that has not
     * answered in ten seconds is better recorded as failed — the row keeps the body, so the
     * code is still readable from the staff message log — than held while the browser times
     * out and the customer presses the button again.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Beyond this the request outlives the patience of whoever is waiting on it. */
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    public BrevoProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;

        /*
         * A missing key is a configuration error worth failing on rather than discovering
         * when a customer is waiting. Only checked when Brevo is actually selected — an
         * environment on SMTP has no key to supply.
         *
         * The VALUE is not examined beyond being non-blank. Checking that it starts with
         * "xkeysib-" would reject a perfectly good key the day Brevo changes its prefix,
         * and a wrong key already announces itself clearly: the API answers 401.
         */
        if (enabled && apiKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "ibanking.mail.brevo.api-key must be set when ibanking.mail.brevo.enabled"
                            + " is true. Create one at https://app.brevo.com under SMTP & API →"
                            + " API keys and supply it as BREVO_API_KEY.");
        }

        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "ibanking.mail.brevo.timeout must be positive; got " + timeout);
        }

        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "ibanking.mail.brevo.timeout must not exceed "
                            + MAX_TIMEOUT
                            + "; got "
                            + timeout
                            + ". A customer is waiting on a request that holds this call.");
        }
    }

    /** True when a key has been supplied. Never exposes the key itself. */
    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }
}
