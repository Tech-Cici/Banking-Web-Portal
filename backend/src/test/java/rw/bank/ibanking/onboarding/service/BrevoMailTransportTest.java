package rw.bank.ibanking.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rw.bank.ibanking.config.BrevoProperties;

/**
 * SENDING OVER HTTP INSTEAD OF SMTP, tested against a stub that speaks Brevo's protocol.
 *
 * <p>WHY THIS TRANSPORT EXISTS. Render's free web services do not allow outbound traffic to
 * SMTP ports, so the deployed service could not reach {@code smtp.gmail.com:587} whatever
 * credentials it was given: the first registration on the demo recorded
 * {@code delivered = false, failure = "The mail server rejected or could not be reached."}
 * and no setting could have fixed it. This posts over 443.
 *
 * <p>WHY A STUB SERVER RATHER THAN A MOCK. The three things most likely to be wrong here are
 * the header name Brevo expects, the shape of the JSON body, and which status codes mean
 * what. A mocked HTTP client proves none of them — it would happily assert that the code
 * sends whatever the code sends. A real server on a loopback port means the assertions are
 * about bytes that crossed a socket, and it needs no network and no Brevo account.
 *
 * <p>{@code com.sun.net.httpserver} is in the JDK, so this adds no dependency.
 *
 * <p>NOTE WHAT THE STUB CANNOT CATCH: that {@code api.brevo.com} really wants the header
 * spelled {@code api-key} and really answers 201. Those came from Brevo's own documentation,
 * and the stub asserts we send what that documentation describes. If Brevo changes, these
 * tests keep passing and the deployment breaks — which is the honest limit of testing against
 * a stub, and is why the startup check also warns about the unverified-sender case.
 */
@DisplayName("Sending through Brevo's HTTP API")
class BrevoMailTransportTest {

    private HttpServer server;
    private URI endpoint;

    /** What the stub saw, so the test can assert on the request rather than on the call. */
    private final AtomicReference<String> seenPath = new AtomicReference<>();

    private final AtomicReference<String> seenApiKey = new AtomicReference<>();

    private final AtomicReference<String> seenContentType = new AtomicReference<>();

    private final AtomicReference<String> seenBody = new AtomicReference<>();

    private final AtomicReference<String> seenMethod = new AtomicReference<>();

    private final AtomicInteger requestCount = new AtomicInteger();

    /** What the stub answers. Set per test. */
    private volatile int status = 201;

    private volatile String responseBody = "{\"messageId\":\"<stub@relay>\"}";

    private volatile Duration delay = Duration.ZERO;

    /** Log output is checked for the key; collected by the handler's own thread. */
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/smtp/email", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email");
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        seenMethod.set(exchange.getRequestMethod());
        seenPath.set(exchange.getRequestURI().getPath());
        seenApiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
        seenContentType.set(exchange.getRequestHeaders().getFirst("content-type"));

        byte[] in = exchange.getRequestBody().readAllBytes();
        String body = new String(in, StandardCharsets.UTF_8);
        seenBody.set(body);
        synchronized (requests) {
            requests.add(body);
        }

        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private BrevoMailTransport transport(String apiKey, Duration timeout) {
        return new BrevoMailTransport(new BrevoProperties(true, apiKey, timeout), endpoint);
    }

    private BrevoMailTransport transport() {
        return transport("xkeysib-test-key", Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("posts to /v3/smtp/email with the api-key header Brevo documents")
    void sendsTheDocumentedRequest() throws Exception {
        transport().send("noreply@zigama.rw", "Zigama CSS", "holder@example.rw", "Your code", "123456 is your code");

        assertThat(seenMethod.get()).isEqualTo("POST");
        assertThat(seenPath.get()).isEqualTo("/v3/smtp/email");

        /*
         * NOT `Authorization`, NOT `X-API-Key`. Brevo's header is literally `api-key`, and
         * getting it wrong produces a 401 that looks like a bad key — sending somebody to
         * regenerate a key that was fine.
         */
        assertThat(seenApiKey.get()).isEqualTo("xkeysib-test-key");
        assertThat(seenContentType.get()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("sends the sender, recipient, subject and body in Brevo's shape")
    void sendsBrevosPayloadShape() throws Exception {
        transport().send("noreply@zigama.rw", "Zigama CSS", "holder@example.rw", "Your code", "123456 is your code");

        String body = seenBody.get();
        assertThat(body).contains("\"sender\"");
        assertThat(body).contains("\"email\":\"noreply@zigama.rw\"");
        assertThat(body).contains("\"name\":\"Zigama CSS\"");
        assertThat(body).contains("\"to\":[{\"email\":\"holder@example.rw\"}]");
        assertThat(body).contains("\"subject\":\"Your code\"");

        /*
         * textContent, NOT htmlContent. Every message this service sends is plain text
         * composed in the templates; handing it to an HTML field collapses the line breaks,
         * so a code on its own line ends up run together with the sentence above it.
         */
        assertThat(body).contains("\"textContent\":\"123456 is your code\"");
        assertThat(body).doesNotContain("htmlContent");
    }

    @Test
    @DisplayName("escapes a name that would otherwise break the JSON")
    void escapesAwkwardText() throws Exception {
        /*
         * A customer called O"Brien, or any body text containing a quote or a backslash,
         * would produce malformed JSON if this were string concatenation — a registration
         * that fails for one person and works for everyone else.
         */
        transport().send("noreply@zigama.rw", "Zigama \"CSS\"", "holder@example.rw", "Sub\\ject", "line1\nline2 \"quoted\"");

        String body = seenBody.get();
        assertThat(body).contains("\\\"CSS\\\"");
        assertThat(body).contains("Sub\\\\ject");
        assertThat(body).contains("line1\\nline2 \\\"quoted\\\"");

        // The real proof: it is still parseable.
        assertThatCode(() -> new tools.jackson.databind.ObjectMapper().readTree(body))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("treats 201 as sent")
    void acceptsCreated() {
        status = 201;
        assertThatCode(() -> transport().send("a@b.rw", "A", "c@d.rw", "s", "b"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a 400 says the sender may not be verified, which is the usual cause")
    void explainsBadRequest() {
        status = 400;
        responseBody = "{\"code\":\"invalid_parameter\",\"message\":\"sender is not valid\"}";

        assertThatThrownBy(() -> transport().send("a@b.rw", "A", "c@d.rw", "s", "b"))
                .isInstanceOf(MailTransportException.class)
                .hasMessageContaining("sender address may not be verified");
    }

    @Test
    @DisplayName("a 401 says the credentials were refused, not that the server was unreachable")
    void explainsUnauthorized() {
        status = 401;
        responseBody = "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}";

        assertThatThrownBy(() -> transport().send("a@b.rw", "A", "c@d.rw", "s", "b"))
                .hasMessageContaining("rejected our credentials");
    }

    @Test
    @DisplayName("a 429 says the sending limit was reached — the free plan is 300 a day")
    void explainsRateLimit() {
        status = 429;
        responseBody = "{\"code\":\"too_many_requests\"}";

        assertThatThrownBy(() -> transport().send("a@b.rw", "A", "c@d.rw", "s", "b"))
                .hasMessageContaining("sending limit");
    }

    @Test
    @DisplayName("never puts the API key or the provider's text in the stored failure")
    void failureMessageIsSafeToStore() {
        /*
         * THE MESSAGE GOES IN outbox.failure AND ONTO A STAFF SCREEN. Brevo echoes the
         * offending field, which for a rejected recipient is the customer's email address,
         * and the request carried the key. Neither belongs in a column somebody renders.
         */
        status = 400;
        responseBody =
                "{\"message\":\"recipient holder@example.rw rejected; key xkeysib-test-key\"}";

        assertThatThrownBy(
                        () ->
                                transport()
                                        .send(
                                                "a@b.rw",
                                                "A",
                                                "holder@example.rw",
                                                "s",
                                                "b"))
                .satisfies(
                        thrown -> {
                            assertThat(thrown.getMessage()).doesNotContain("xkeysib");
                            assertThat(thrown.getMessage()).doesNotContain("holder@example.rw");
                            assertThat(thrown.getMessage()).doesNotContain("rejected;");
                        });
    }

    @Test
    @DisplayName("gives up on a slow provider rather than holding the customer's request")
    void timesOut() {
        /*
         * A registration request holds a thread while this runs and the customer is watching
         * a spinner. Without a timeout, a provider that accepts the connection and never
         * answers holds it until the browser gives up — and the customer presses the button
         * again, which is how one code becomes three.
         */
        delay = Duration.ofSeconds(3);

        assertThatThrownBy(
                        () ->
                                transport("xkeysib-test-key", Duration.ofMillis(300))
                                        .send("a@b.rw", "A", "c@d.rw", "s", "b"))
                .hasMessageContaining("could not be reached");
    }

    @Test
    @DisplayName("does not retry, so one call is one email")
    void sendsExactlyOnce() {
        status = 500;

        assertThatThrownBy(() -> transport().send("a@b.rw", "A", "c@d.rw", "s", "b"))
                .hasMessageContaining("returned an error");

        /*
         * Mailer records the message regardless, so a retry here buys nothing and risks
         * sending twice — and a duplicated verification email is a customer typing the wrong
         * one of two valid-looking codes.
         */
        assertThat(requestCount.get()).isEqualTo(1);
    }
}
