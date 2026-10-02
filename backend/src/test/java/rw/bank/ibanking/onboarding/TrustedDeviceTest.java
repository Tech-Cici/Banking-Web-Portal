package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import rw.bank.ibanking.onboarding.domain.TrustedDeviceEntity;
import rw.bank.ibanking.onboarding.repo.TrustedDeviceRepository;
import rw.bank.ibanking.onboarding.service.TrustedDevices;

/**
 * REMEMBERING A BROWSER WITHOUT GIVING UP THE SECOND FACTOR.
 *
 * <p>The emailed code was asked for on every sign-in, which made the portal tiring to
 * use; the obvious fix is to delete it, and that would make a stolen password enough to
 * reach somebody's money. So the code is asked for once per browser and that browser
 * then signs in on the password alone for thirty days.
 *
 * <p>THE TESTS THAT MATTER ARE THE REFUSALS. That a remembered browser skips the code is
 * the feature and it is easy to get right. What has to hold, and what a future change
 * could quietly break, is that a token which is forged, expired, revoked or somebody
 * else's does NOT skip it — each of those is a way of turning a second factor into
 * decoration, and each has its own test here.
 */
@DisplayName("Trusted devices")
class TrustedDeviceTest extends OnboardingIntegrationTest {

    private static final String MANAGER = "manager@zigama.local";
    private static final String STAFF_PASSWORD = "ZigamaStaff1";

    /** What signInAndSettle leaves every customer's password as. */
    private static final String PASSWORD = "ZigamaCustomer9!";

    /**
     * A distinct account number per customer.
     *
     * <p>Counted rather than fixed, because the service refuses a number already linked
     * to somebody else — correctly, since two customers holding one account number is
     * either a typo or a mistake about whose money it is. A shared constant made the
     * second customer in a test fail with 422 for a reason that had nothing to do with
     * what was being tested.
     */
    private static long nextAccountNumber = 4_001_111_1111L;

    private static String accountsBody() {
        return """
            {"accounts":[{"accountNumber":"%d","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"1000"}]}"""
                .formatted(nextAccountNumber++);
    }

    @Autowired private TrustedDeviceRepository devices;

    @Autowired private JdbcTemplate jdbc;

    /* ----------------------------------------------------------- fixtures */

    /** The challenge id out of a CHALLENGE_REQUIRED payload. */
    private String challengeIdFrom(String body) {
        return json.readTree(body).get("challenge").get("challengeId").asString();
    }

    /**
     * Winds a device past its expiry.
     *
     * <p>Written as SQL rather than through a setter on the entity, deliberately. A
     * setter that moved an expiry would be production code whose only caller is a test,
     * and it would be available to any future code path that wanted to extend a device's
     * trust quietly. The thirty-day constant is also left alone: shortening it for the
     * test would test a value that never ships.
     */
    private void expire(TrustedDeviceEntity device) {
        /*
         * BOTH DATES MOVE. The table's own constraint requires expires_at > created_at,
         * so dragging the expiry into the past on its own is refused — which is the
         * constraint doing exactly what it is for. Wound back to a device created forty
         * days ago and therefore expired ten days ago, which is what a real lapsed
         * device looks like.
         */
        Instant created = Instant.now().minus(java.time.Duration.ofDays(40));
        jdbc.update(
                "update trusted_devices set created_at = ?, expires_at = ? where id = ?",
                java.sql.Timestamp.from(created),
                java.sql.Timestamp.from(created.plus(TrustedDevices.TRUST_FOR)),
                device.id());
    }


    /** An active customer whose temporary password has already been replaced. */
    private String aSettledCustomer(String email) throws Exception {
        customerWithAccounts(email, accountsBody());
        return customers.findAll().stream()
                .filter(c -> c.email().equalsIgnoreCase(email))
                .findFirst()
                .orElseThrow()
                .id()
                .toString();
    }

    /** POSTs a sign-in, optionally carrying a device cookie, and returns the response. */
    private MockHttpServletResponse signIn(String email, String deviceToken) throws Exception {
        var request =
                post("/api/v1/auth/login")
                        .session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"identifier":"%s","password":"%s"}"""
                                        .formatted(email, PASSWORD));

        if (deviceToken != null) {
            request = request.cookie(new Cookie(TrustedDevices.COOKIE, deviceToken));
        }

        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    }

    /** The sign-in code the bank just emailed. */
    private String emailedCode(String email) {
        var mail = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).get(0);
        var matcher = Pattern.compile("Your sign-in code is (\\d{6})").matcher(mail.body());
        assertThat(matcher.find()).as("a sign-in code should have been emailed").isTrue();
        return matcher.group(1);
    }

    /**
     * Signs in the long way — password, then the emailed code — and returns the device
     * token the bank set on the way through.
     */
    private String signInWithTheCodeAndKeepTheDevice(String email) throws Exception {
        return signInWithTheCodeAndKeepTheDevice(email, null);
    }

    /** The same, starting from a cookie this browser already carries. */
    private String signInWithTheCodeAndKeepTheDevice(String email, String existingCookie)
            throws Exception {
        var login = signIn(email, existingCookie);
        assertThat(login.getContentAsString()).contains("CHALLENGE_REQUIRED");

        String challengeId = challengeIdFrom(login.getContentAsString());

        var verifyRequest =
                post("/api/v1/auth/verify")
                        .session(new MockHttpSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"challengeId":"%s","code":"%s"}"""
                                        .formatted(challengeId, emailedCode(email)));

        /*
         * THE COOKIE GOES WITH THE VERIFY TOO, not only with the login. The server merges
         * the new token into whatever the browser already carries, so a verify that
         * arrived without the cookie would return a value holding only the newest person
         * — which is the single-token behaviour this replaced.
         */
        if (existingCookie != null) {
            verifyRequest = verifyRequest.cookie(new Cookie(TrustedDevices.COOKIE, existingCookie));
        }

        var verified =
                mvc.perform(verifyRequest)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.outcome").value("COMPLETE"))
                        .andReturn()
                        .getResponse();

        Cookie cookie = verified.getCookie(TrustedDevices.COOKIE);
        assertThat(cookie).as("completing the code step should remember the browser").isNotNull();
        return cookie.getValue();
    }

    /* -------------------------------------------------------------- tests */

    @Test
    @DisplayName("THE CODE IS ASKED FOR ONCE, and the same browser is not asked again")
    void aRememberedBrowserSkipsTheCode() throws Exception {
        String email = "remembered@example.rw";
        aSettledCustomer(email);

        String token = signInWithTheCodeAndKeepTheDevice(email);

        /*
         * The second sign-in, same browser, same token. COMPLETE rather than
         * CHALLENGE_REQUIRED, and no challenge in the payload — the session exists
         * already.
         */
        var again = signIn(email, token);
        assertThat(again.getContentAsString()).contains("COMPLETE");
        assertThat(again.getContentAsString()).doesNotContain("challengeId");
    }

    @Test
    @DisplayName("TWO PEOPLE ON ONE BROWSER ARE BOTH REMEMBERED, and neither evicts the other")
    void oneBrowserRemembersMoreThanOneCustomer() throws Exception {
        String first = "shared-one@example.rw";
        String second = "shared-two@example.rw";
        aSettledCustomer(first);
        aSettledCustomer(second);

        /*
         * THE BUG THIS TEST EXISTS FOR, found in a real log rather than by reading the
         * code.
         *
         * The cookie held ONE token. Signing in as the second person overwrote the first
         * person's, so the next sign-in as the first person presented somebody else's
         * token, was correctly refused, and asked for a code — which then overwrote the
         * second person's. Alternating between two accounts on one browser asked for a
         * code EVERY time, which is precisely what remembering the browser was built to
         * stop, and it looked from the outside as though the feature had never shipped.
         */
        String cookie = signInWithTheCodeAndKeepTheDevice(first);
        cookie = signInWithTheCodeAndKeepTheDevice(second, cookie);

        /* Both, in either order, without a code. */
        assertThat(signIn(first, cookie).getContentAsString()).contains("COMPLETE");
        assertThat(signIn(second, cookie).getContentAsString()).contains("COMPLETE");
        assertThat(signIn(first, cookie).getContentAsString()).contains("COMPLETE");
    }

    @Test
    @DisplayName("SIGNING IN AGAIN REPLACES YOUR OWN TOKEN rather than stacking another")
    void aSecondSignInReplacesTheSameCustomersToken() throws Exception {
        String email = "rotating@example.rw";
        String customerId = aSettledCustomer(email);

        String first = signInWithTheCodeAndKeepTheDevice(email);

        /*
         * The trust has to lapse before a second code is possible — a browser that is
         * still trusted is not asked again, which is the whole feature. Winding it past
         * its expiry is how a customer reaches a second code without waiting a month.
         *
         * (This test was first written as two sign-ins in a row and failed on its own
         * helper: the second sign-in returned COMPLETE, because the browser was already
         * trusted. The helper was right and the test was wrong.)
         */
        expire(devices.findByCustomerId(java.util.UUID.fromString(customerId)).get(0));

        String second = signInWithTheCodeAndKeepTheDevice(email, first);

        /*
         * Two rows, ONE of them live. The lapsed device is revoked as it is dropped from
         * the cookie rather than left lying about: keeping it would let one person's
         * repeated sign-ins fill the capped list and evict everybody else who shares the
         * machine.
         */
        var all = devices.findByCustomerId(java.util.UUID.fromString(customerId));
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(device -> !device.isRevoked()).count()).isEqualTo(1);

        /* And the new cookie works, while the old token no longer does. */
        assertThat(signIn(email, second).getContentAsString()).contains("COMPLETE");
        assertThat(signIn(email, first).getContentAsString()).contains("CHALLENGE_REQUIRED");
    }

    @Test
    @DisplayName("A DIFFERENT BROWSER IS STILL ASKED, because it has proved nothing")
    void anUnknownBrowserStillGetsTheCode() throws Exception {
        String email = "unknown-browser@example.rw";
        aSettledCustomer(email);
        signInWithTheCodeAndKeepTheDevice(email);

        /* No cookie: a different machine, or the same one after clearing its data. */
        assertThat(signIn(email, null).getContentAsString()).contains("CHALLENGE_REQUIRED");
    }

    @Test
    @DisplayName("A MADE-UP TOKEN IS NOT A DEVICE, and guessing one is hopeless")
    void aForgedTokenIsRefused() throws Exception {
        String email = "forged@example.rw";
        aSettledCustomer(email);
        signInWithTheCodeAndKeepTheDevice(email);

        /*
         * The shape is right — 64 hex characters, exactly what a real token looks like —
         * and it is refused, because what is stored is a hash of 256 random bits and
         * there is nothing here to guess. A test with an obviously invalid value like
         * "x" would pass against an implementation that only checked the length.
         */
        String wellFormedButWrong = "f".repeat(64);
        assertThat(signIn(email, wellFormedButWrong).getContentAsString())
                .contains("CHALLENGE_REQUIRED");
    }

    @Test
    @DisplayName("SOMEBODY ELSE'S DEVICE TOKEN DOES NOT WORK ON YOUR ACCOUNT")
    void oneCustomersTokenDoesNotTrustAnother() throws Exception {
        String mine = "mine@example.rw";
        String theirs = "theirs@example.rw";
        aSettledCustomer(mine);
        aSettledCustomer(theirs);

        String myToken = signInWithTheCodeAndKeepTheDevice(mine);

        /*
         * THE ONE THAT WOULD BE WORST TO GET WRONG. A token looked up by hash alone and
         * not compared against whoever is signing in would let anybody with one valid
         * device cookie skip the second factor on EVERY account whose password they knew.
         * A single shared browser would be enough to make the emailed code decorative.
         */
        assertThat(signIn(theirs, myToken).getContentAsString()).contains("CHALLENGE_REQUIRED");
    }

    @Test
    @DisplayName("TRUST LAPSES, and an expired browser is asked for a code again")
    void anExpiredTokenIsRefused() throws Exception {
        String email = "expired@example.rw";
        String customerId = aSettledCustomer(email);
        String token = signInWithTheCodeAndKeepTheDevice(email);

        /*
         * Wound past its expiry rather than waited out. The thirty days are the policy
         * being tested; sleeping for them is not an option and shortening the constant
         * for the test would test a different constant than the one that ships.
         */
        TrustedDeviceEntity device =
                devices.findByCustomerId(java.util.UUID.fromString(customerId)).get(0);
        assertThat(device.expiresAt()).isAfter(Instant.now());
        expire(device);

        assertThat(signIn(email, token).getContentAsString()).contains("CHALLENGE_REQUIRED");
    }

    @Test
    @DisplayName("FREEZING AN ACCOUNT WITHDRAWS ITS BROWSERS, and restoring does not give them back")
    void freezingRevokesEveryTrustedBrowser() throws Exception {
        String email = "frozen@example.rw";
        String customerId = aSettledCustomer(email);
        String token = signInWithTheCodeAndKeepTheDevice(email);

        mvc.perform(
                        post("/api/v1/admin/customers/{id}/freeze", customerId)
                                .with(httpBasic(MANAGER, STAFF_PASSWORD))
                                .with(csrf())
                                .header("Idempotency-Key", "freeze-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reason":"Card reported stolen"}"""))
                .andExpect(status().isOk());

        mvc.perform(
                        post("/api/v1/admin/customers/{id}/unfreeze", customerId)
                                .with(httpBasic(MANAGER, STAFF_PASSWORD))
                                .with(csrf())
                                .header("Idempotency-Key", "unfreeze-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reason":"Identity confirmed at the branch"}"""))
                .andExpect(status().isOk());

        /*
         * THE POINT OF REVOKING ON FREEZE. While frozen, signing in is refused anyway, so
         * this changes nothing then. It changes what the freeze meant AFTERWARDS: if the
         * freeze happened because somebody else had the password and the machine,
         * restoring the account without this would hand the account straight back to them
         * with no code in the way.
         */
        assertThat(signIn(email, token).getContentAsString()).contains("CHALLENGE_REQUIRED");

        var revoked = devices.findByCustomerId(java.util.UUID.fromString(customerId)).get(0);
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.revokedReason()).contains("Card reported stolen");
    }

    @Test
    @DisplayName("THE COOKIE CANNOT BE READ BY SCRIPT, and is scoped to signing in")
    void theDeviceCookieIsProtected() throws Exception {
        String email = "cookie@example.rw";
        aSettledCustomer(email);

        var login = signIn(email, null);
        String challengeId = challengeIdFrom(login.getContentAsString());

        var verified =
                mvc.perform(
                                post("/api/v1/auth/verify")
                                        .session(new MockHttpSession())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"challengeId":"%s","code":"%s"}"""
                                                        .formatted(challengeId, emailedCode(email))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();

        Cookie cookie = verified.getCookie(TrustedDevices.COOKIE);

        /*
         * HttpOnly is the flag that matters most. This token stands in for a code emailed
         * to the customer, so a cross-site-scripting bug able to read it would defeat the
         * second factor for thirty days — far worse than stealing a session, which ends
         * when they sign out.
         */
        assertThat(cookie.isHttpOnly()).as("script must not be able to read it").isTrue();
        assertThat(cookie.getPath())
                .as("only sent while signing in, not with every balance request")
                .isEqualTo("/api/v1/auth");
        assertThat(cookie.getMaxAge()).isEqualTo((int) TrustedDevices.TRUST_FOR.toSeconds());
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");

        /* And the token itself must not be sitting in the response body as well. */
        assertThat(verified.getContentAsString()).doesNotContain(cookie.getValue());
    }
}
