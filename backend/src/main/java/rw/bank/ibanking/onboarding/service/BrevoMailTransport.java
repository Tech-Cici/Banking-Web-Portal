package rw.bank.ibanking.onboarding.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rw.bank.ibanking.config.BrevoProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends through Brevo's transactional email API over HTTPS.
 *
 * <p>WHY NOT SMTP. Render's free web services do not allow outbound traffic to SMTP ports, so
 * a connection to port 587 — Gmail's, Brevo's own relay, anyone's — cannot leave the
 * container. This posts to {@code https://api.brevo.com/v3/smtp/email} on 443, which is not
 * blocked. The endpoint path contains the word "smtp"; the transport is not SMTP.
 *
 * <p>THE SENDER MUST BE VERIFIED IN BREVO FIRST. Brevo refuses to send from an address that
 * is not a verified sender or on an authenticated domain, so {@code ibanking.mail.from} has to
 * match something registered in the Brevo account. That failure arrives as a 400 with the
 * reason in the body, which is logged and not stored.
 *
 * <p>NO RETRY, deliberately. {@link Mailer} already records the message before and regardless
 * of the outcome, so a failed send loses nothing — the body is in the outbox and the code is
 * readable from the staff message log. Retrying inside a request that a customer is waiting on
 * trades a known failure for a longer wait and a risk of sending twice.
 *
 * <p>THE API KEY NEVER APPEARS ANYWHERE but the request header. Not in a log line, not in an
 * exception, not in a failure row. An exception message from here is stored in
 * {@code outbox.failure} and rendered on a staff screen.
 */
class BrevoMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(BrevoMailTransport.class);

    /** Brevo's transactional send endpoint. */
    private static final URI ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");

    /** Brevo's own header name for the key. Not {@code Authorization}, not {@code X-API-Key}. */
    private static final String API_KEY_HEADER = "api-key";

    private final BrevoProperties settings;
    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;

    BrevoMailTransport(BrevoProperties settings) {
        this(settings, ENDPOINT);
    }

    /**
     * The endpoint is injectable for tests only.
     *
     * <p>A test that posted to the real Brevo would need a real key, would send real email, and
     * would fail in CI without network. Pointing this at a local stub server is what makes it
     * possible to assert on the header name, the payload shape and the handling of each status
     * code — the three things most likely to be wrong and least likely to be noticed.
     */
    BrevoMailTransport(BrevoProperties settings, URI endpoint) {
        this.settings = settings;
        this.endpoint = endpoint;
        this.json = new ObjectMapper();
        this.http =
                HttpClient.newBuilder()
                        /*
                         * The CONNECT timeout, separate from the per-request timeout below.
                         * Without it a DNS or routing problem hangs for the OS default, which
                         * on Linux is over two minutes — far longer than the request the
                         * customer is waiting on.
                         */
                        .connectTimeout(Duration.ofSeconds(5))
                        /*
                         * Brevo answers 301 to http:// and we only ever send https://, so a
                         * redirect here would mean something unexpected. Not following one
                         * keeps the key from being replayed to a host we did not choose.
                         */
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public String description() {
        return "Brevo HTTP API (" + ENDPOINT.getHost() + ")";
    }

    @Override
    public void send(String fromEmail, String fromName, String to, String subject, String body)
            throws MailTransportException {

        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .header(API_KEY_HEADER, settings.apiKey())
                        .header("content-type", "application/json")
                        .header("accept", "application/json")
                        .timeout(settings.timeout())
                        .POST(HttpRequest.BodyPublishers.ofString(payload(fromEmail, fromName, to, subject, body)))
                        .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());

        } catch (IOException e) {
            /*
             * The network did not carry it: DNS, TLS, a timeout, a blocked egress. The
             * exception's own text can name the host and port, which is fine in a log and not
             * something to put on a staff screen.
             */
            log.error("Brevo send failed before a response: {}", e.toString());
            throw new MailTransportException("The email service could not be reached.");

        } catch (InterruptedException e) {
            /*
             * Restore the flag. Swallowing an interrupt leaves a thread that cannot be shut
             * down, which on a web container means a deploy that hangs rather than restarts.
             */
            Thread.currentThread().interrupt();
            log.error("Brevo send was interrupted");
            throw new MailTransportException("Sending was interrupted.");
        }

        if (response.statusCode() == 201) {
            return;
        }

        /*
         * THE STATUS CODES WORTH TELLING APART, because they need different people to fix
         * them and a single "it failed" sends the wrong person looking.
         *
         * The response BODY is logged and never stored: Brevo echoes the offending field,
         * which for a rejected recipient means the customer's address, and a staff screen is
         * not where that belongs.
         */
        log.error(
                "Brevo refused the send: HTTP {} {}",
                response.statusCode(),
                response.body() == null ? "" : response.body());

        throw new MailTransportException(
                switch (response.statusCode()) {
                    case 400 ->
                            "The email service rejected the message. The sender address may not"
                                    + " be verified with the provider.";
                    case 401 -> "The email service rejected our credentials.";
                    case 402, 429 -> "The email service's sending limit has been reached.";
                    default -> "The email service returned an error.";
                });
    }

    /**
     * Brevo's request body.
     *
     * <p>BUILT WITH JACKSON rather than string concatenation, which is not fussiness: a
     * customer's name goes in here, and a name containing a quote or a backslash would
     * produce malformed JSON — a registration that fails for one person and works for
     * everybody else, which is the worst shape of bug to find.
     *
     * <p>{@code textContent}, not {@code htmlContent}. Every message this service sends is
     * plain text composed in the templates, and handing text to an HTML field would collapse
     * its line breaks and interpret any {@code <} in it.
     */
    private String payload(
            String fromEmail, String fromName, String to, String subject, String body) {

        ObjectNode root = json.createObjectNode();

        ObjectNode sender = root.putObject("sender");
        sender.put("email", fromEmail);
        sender.put("name", fromName);

        root.putArray("to").addObject().put("email", to);

        root.put("subject", subject);
        root.put("textContent", body);

        return root.toString();
    }
}
