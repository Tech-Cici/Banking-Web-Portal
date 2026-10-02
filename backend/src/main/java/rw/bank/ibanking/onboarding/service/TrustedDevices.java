package rw.bank.ibanking.onboarding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.TrustedDeviceEntity;
import rw.bank.ibanking.onboarding.repo.TrustedDeviceRepository;

/**
 * WHETHER A BROWSER HAS ALREADY PROVED ITSELF, and for how much longer.
 *
 * <p>Signing in takes a password and then a code emailed to the customer. Asked for on
 * every sign-in, the code made the portal tiring to use; deleted, it would make a stolen
 * password enough to reach somebody's money. This is the middle: the code is asked for
 * once per browser, and that browser then signs in on the password alone for thirty days.
 *
 * <p>WHAT THIS DOES AND DOES NOT PROTECT AGAINST, stated plainly because a security
 * control whose limits are not written down gets relied on for things it does not do.
 *
 * <ul>
 *   <li>A LEAKED PASSWORD, on an attacker's own machine: still stopped. Their browser has
 *       never been trusted, so they get the code step, and the code goes to the
 *       customer's email rather than to them.
 *   <li>A SHARED OR STOLEN COMPUTER, already trusted, whose password is also known:
 *       NOT stopped. That is the trade being made, and it is the same trade every bank
 *       that offers "remember this device" makes.
 *   <li>A STOLEN COOKIE: not stopped for thirty days, and the cookie is therefore
 *       HttpOnly and Secure so that script on the page cannot read it and a plain-HTTP
 *       request cannot carry it. Rotating the token on each use would shorten the window
 *       for a copied cookie; it is not done here because two tabs signing in at once
 *       would race, and it is recorded in docs/OPEN-ITEMS.md rather than quietly
 *       omitted.
 * </ul>
 *
 * <p>MORE THAN ONE CUSTOMER PER BROWSER, which the first version of this got wrong.
 *
 * <p>It held a single token in the cookie. One browser used by two people — a shared
 * family laptop, or a developer testing two accounts — then behaved worse than having no
 * feature at all: signing in as the second person replaced the first person's token, so
 * the next sign-in as the first person presented a token belonging to somebody else, was
 * correctly refused, and asked for a code. Alternating between two accounts meant the
 * code was asked for EVERY time, which is exactly the thing this was built to stop.
 *
 * <p>So the cookie holds a short LIST of tokens and a sign-in tries each of them. The
 * list is capped, and the tokens carry no customer id — a cookie that named its accounts
 * would tell anybody who read it who shares this computer.
 */
@Service
public class TrustedDevices {

    private static final Logger log = LoggerFactory.getLogger(TrustedDevices.class);

    /**
     * The name of the cookie holding the token.
     *
     * <p>Distinct from the session cookie, and deliberately outlives it: the session ends
     * when the customer signs out, and the trust in the browser does not.
     */
    public static final String COOKIE = "ZIGAMA_DEVICE";

    /**
     * Thirty days, as the customer was told.
     *
     * <p>Long enough that a customer signing in weekly is never asked again; short enough
     * that a browser somebody stopped using — an old laptop, a machine in an internet
     * café — stops being trusted within a month without anybody having to remember to
     * revoke it.
     */
    public static final Duration TRUST_FOR = Duration.ofDays(30);

    /**
     * 256 bits from {@link SecureRandom}.
     *
     * <p>The entropy is the whole defence here. This token stands in for a one-time code
     * emailed to the customer, so it must be unguessable in a way a six-digit code never
     * is: there is no attempt counter behind it and no short expiry to fall back on.
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * How many browsers-worth of trust one cookie may carry.
     *
     * <p>Five, because a shared machine has a handful of users and a cookie is not a
     * database. Past that the oldest is dropped, which asks that person for a code next
     * time — the correct failure, and the only one available once the list is full.
     */
    private static final int MAX_REMEMBERED = 5;

    /**
     * Separates tokens in the cookie value.
     *
     * <p>A full stop, because tokens are hex and cannot contain one. A comma or a
     * semicolon would need quoting — those are cookie syntax, and a value that has to be
     * escaped is a value that eventually is not.
     */
    private static final String SEPARATOR = ".";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TrustedDeviceRepository devices;

    TrustedDevices(TrustedDeviceRepository devices) {
        this.devices = devices;
    }

    /**
     * Trusts the browser that has just completed the code step.
     *
     * @return the token to put in the customer's cookie. Returned rather than stored,
     *     because only its hash is kept — this is the one moment the token exists
     *     server-side, and nothing may log it.
     */
    @Transactional
    public String rememberIn(UUID customerId, String existingCookie, String userAgent) {
        /*
         * THE NEW TOKEN GOES IN FRONT OF WHATEVER WAS THERE, and this customer's previous
         * token comes out.
         *
         * Keeping the old one would let one person's repeated sign-ins fill the list and
         * evict everybody else from the shared machine. Revoking it as it is dropped is
         * rotation, got for free and safely: it only happens immediately after a fresh
         * emailed code, so there is no race with a concurrent sign-in to lose.
         */
        String fresh = mint(customerId, userAgent);

        List<String> kept = new ArrayList<>();
        kept.add(fresh);

        for (String existing : split(existingCookie)) {
            if (kept.size() >= MAX_REMEMBERED) break;

            var found = devices.findByTokenHash(hash(existing));
            if (found.isEmpty()) continue;

            if (found.get().customerId().equals(customerId)) {
                found.get().revoke("Replaced by a newer sign-in from the same browser");
                devices.save(found.get());
                continue;
            }

            /* Somebody else's, and still theirs to keep. */
            kept.add(existing);
        }

        return String.join(SEPARATOR, kept);
    }

    /**
     * Whether any token in this cookie belongs to the customer signing in.
     *
     * <p>Tried in turn rather than assuming the cookie holds one. On a shared browser the
     * customer signing in now may be the third of four remembered there, and stopping at
     * the first token would ask everybody except the most recent person for a code.
     */
    @Transactional
    public boolean trustsAny(UUID customerId, String cookieValue) {
        for (String token : split(cookieValue)) {
            if (trusts(customerId, token)) return true;
        }
        return false;
    }

    /** Splits a cookie value into tokens, tolerating an absent or malformed one. */
    private static List<String> split(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        for (String piece : cookieValue.split("\\.")) {
            if (!piece.isBlank()) tokens.add(piece.trim());
        }
        return tokens;
    }

    @Transactional
    String mint(UUID customerId, String userAgent) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);

        /*
         * The label is taken HERE, at the one moment trust is granted, and never updated
         * afterwards — the thing the customer is identifying is the browser they trusted,
         * and it is the row they will later want to revoke. Null when the client sent
         * nothing recognisable; see DeviceDescription on why that is not defaulted.
         */
        devices.save(
                TrustedDeviceEntity.trusted(
                        customerId, hash(token), TRUST_FOR, DeviceDescription.from(userAgent)));

        /*
         * The CUSTOMER is logged, never the token and never its hash. A log line carrying
         * either would turn the log into a list of working credentials.
         */
        log.info("Trusted a new browser for customer {} for {} days", customerId, TRUST_FOR.toDays());

        return token;
    }

    /**
     * Whether this token lets this customer skip the code step.
     *
     * <p>THE CUSTOMER IS COMPARED, and that comparison is the point of the method. A
     * token is found by its hash alone and then checked against whoever is signing in, so
     * presenting customer A's cookie while signing in as customer B is found, refused and
     * logged — rather than silently missing, which would look exactly like an ordinary
     * first visit.
     */
    @Transactional
    boolean trusts(UUID customerId, String token) {
        if (token == null || token.isBlank()) return false;

        Optional<TrustedDeviceEntity> found = devices.findByTokenHash(hash(token));
        if (found.isEmpty()) return false;

        TrustedDeviceEntity device = found.get();

        if (!device.customerId().equals(customerId)) {
            /*
             * A device token belonging to somebody else. No ordinary browser does this:
             * either two accounts share a machine and one cookie has been sent for the
             * wrong sign-in, or somebody is trying a token they came by. Worth a warning
             * either way, and worth refusing in both.
             */
            log.warn(
                    "A device token issued to customer {} was presented for customer {}; refused",
                    device.customerId(),
                    customerId);
            return false;
        }

        if (!device.usable(Instant.now())) {
            log.info(
                    "A {} device token was presented by customer {}; the code will be required",
                    device.isRevoked() ? "revoked" : "expired",
                    customerId);
            return false;
        }

        device.used();
        devices.save(device);
        return true;
    }

    /**
     * This customer's browsers, newest first, revoked ones included.
     *
     * <p>REVOKED ONES ARE KEPT IN THE LIST, which V14's own comment argues for: "this
     * browser was trusted and then it was not" is the history somebody investigating an
     * unauthorised sign-in needs, and a row that vanishes answers nothing. The screen
     * labels them rather than hiding them, so a customer who revoked something can see
     * that it took.
     */
    @Transactional(readOnly = true)
    public List<TrustedDeviceEntity> listFor(UUID customerId) {
        var all = new ArrayList<>(devices.findByCustomerId(customerId));
        all.sort(java.util.Comparator.comparing(TrustedDeviceEntity::createdAt).reversed());
        return all;
    }

    /**
     * Whether this id is the browser the request came from.
     *
     * <p>COMPARED BY TOKEN HASH, from the cookie, never by anything the client could
     * assert. The screen needs this so it can stop somebody revoking the browser they are
     * sitting at — which would work, and would then ask them for an emailed code they may
     * be unable to receive, having just locked themselves out of their own machine.
     */
    @Transactional(readOnly = true)
    public boolean isCurrent(UUID deviceId, String cookieValue) {
        for (String token : split(cookieValue)) {
            var found = devices.findByTokenHash(hash(token));
            if (found.isPresent() && found.get().id().equals(deviceId)) return true;
        }
        return false;
    }

    /**
     * Withdraws trust from one browser, at the customer's own request.
     *
     * <p>THE CUSTOMER IS CHECKED AGAINST THE ROW, not taken from it. Revoking by id alone
     * would let anybody with a session end trust in a browser belonging to somebody else —
     * a denial of service against another customer, delivered by changing a UUID in a URL.
     * A row that is not theirs is reported as not found, which is also all they should
     * learn about it.
     *
     * @return the revoked device, for the response.
     */
    @Transactional
    public TrustedDeviceEntity revokeOne(UUID customerId, UUID deviceId, String reason) {
        TrustedDeviceEntity device =
                devices
                        .findById(deviceId)
                        .filter(found -> found.customerId().equals(customerId))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that browser on your"
                                                        + " account. It may already have been"
                                                        + " removed."));

        /* Idempotent in the entity, which keeps the FIRST revocation's time and reason. */
        device.revoke(reason);
        devices.save(device);

        log.info("Customer {} revoked trust in one of their browsers", customerId);
        return device;
    }

    /**
     * Withdraws trust from every browser this customer has.
     *
     * <p>Called when an account is frozen. Freezing means "this access may not be theirs
     * any more", and a browser that could still sign in on the password alone would be
     * the one part of that access the freeze had not touched. Signing in is refused for a
     * frozen customer anyway; this is what makes the freeze still mean something after
     * the account is restored.
     *
     * @return how many were revoked, for the log line at the call site.
     */
    @Transactional
    public int revokeAll(UUID customerId, String reason) {
        var all = devices.findByCustomerId(customerId);
        int revoked = 0;

        for (TrustedDeviceEntity device : all) {
            if (device.isRevoked()) continue;
            device.revoke(reason);
            devices.save(device);
            revoked++;
        }

        if (revoked > 0) {
            log.info("Revoked {} trusted browser(s) for customer {}: {}", revoked, customerId, reason);
        }
        return revoked;
    }

    /**
     * SHA-256, hex encoded.
     *
     * <p>Not BCrypt, and for the opposite reason to a password. BCrypt's cost buys
     * resistance to offline guessing of a low-entropy secret; this token is 256 random
     * bits, so there is nothing to guess and no reason to pay that cost on every sign-in.
     * The hash is here so that a database dump hands out no working tokens.
     */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandated for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
