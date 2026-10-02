package rw.bank.ibanking.onboarding.service;

import java.util.Locale;

/**
 * "CHROME ON WINDOWS", from a User-Agent header.
 *
 * <p>WHAT THIS IS FOR. A customer looking at a list of their own sign-ins, or their own
 * trusted browsers, needs to be able to tell the rows apart — otherwise "revoke the one
 * that is not me" is not an action anybody can take. The question this answers is "which
 * of these is the laptop I lost", and a browser-and-platform pair is the most this service
 * can say truthfully without asking a third party anything.
 *
 * <p>WHY NOT A USER-AGENT PARSING LIBRARY. Those exist to tell a thousand browsers apart
 * for analytics, and they carry a fingerprint database that needs updating to stay
 * accurate. Nothing here depends on precision: a row labelled "Firefox on Windows" does
 * its job if the customer recognises it, and an unrecognised agent must degrade to saying
 * so rather than to a wrong guess. Eight string checks and an honest null are the right
 * size for that.
 *
 * <p>DELIBERATELY COARSE, AND THAT IS A PRIVACY PROPERTY, not a limitation. The raw
 * User-Agent carries version numbers and build strings that make a browser individually
 * identifiable; stored against every sign-in it is a fingerprint of the customer's
 * machines. Two words are enough for recognition and not enough to single anybody out.
 *
 * <p>NEVER FABRICATES. An absent, blank or unrecognised header returns {@code null}, the
 * column is nullable, and the screens show nothing rather than a default. That is the whole
 * lesson of the string this class exists alongside — see {@link
 * rw.bank.ibanking.onboarding.domain.SignInMethod} for the hard-coded "Kigali, Rwanda" that
 * every sign-in used to claim.
 */
final class DeviceDescription {

    /**
     * How much of a header to look at.
     *
     * <p>A User-Agent is client-supplied and unbounded. Nothing below is quadratic, so
     * this is not about cost — it is that a megabyte of header has no business being
     * walked at all, and a cap means the worst case is known rather than argued about.
     */
    private static final int MAX_CONSIDERED = 400;

    private DeviceDescription() {}

    /**
     * A short, human description of the browser, or null when the header does not say.
     *
     * @param userAgent the raw header. May be null: a non-browser client, or a browser
     *     configured not to send one, is perfectly entitled to omit it.
     */
    static String from(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return null;

        String agent =
                userAgent.length() > MAX_CONSIDERED
                        ? userAgent.substring(0, MAX_CONSIDERED)
                        : userAgent;
        String lower = agent.toLowerCase(Locale.ROOT);

        String browser = browserIn(lower);
        String platform = platformIn(lower);

        if (browser == null && platform == null) return null;
        if (browser == null) return "A browser on " + platform;
        if (platform == null) return browser;
        return browser + " on " + platform;
    }

    /**
     * ORDER MATTERS HERE, and getting it wrong is the classic User-Agent mistake.
     *
     * <p>Every Chromium browser says "Chrome" somewhere, and Chrome itself says "Safari"
     * for historical reasons. So the specific names are tested before the general ones:
     * Edge before Chrome, Chrome before Safari. Reversing any pair labels most of the
     * world's browsers "Safari on Windows", which a customer would correctly fail to
     * recognise as their own.
     */
    private static String browserIn(String lower) {
        if (lower.contains("edg/") || lower.contains("edge")) return "Edge";
        if (lower.contains("opr/") || lower.contains("opera")) return "Opera";
        if (lower.contains("samsungbrowser")) return "Samsung Internet";
        if (lower.contains("firefox") || lower.contains("fxios")) return "Firefox";
        if (lower.contains("chrome") || lower.contains("crios")) return "Chrome";
        if (lower.contains("safari")) return "Safari";
        return null;
    }

    /**
     * The machine, not its version.
     *
     * <p>iPhone and iPad before the general Apple checks: an iPhone's agent contains "like
     * Mac OS X", so testing for Mac first calls every iPhone a Mac. Android before Linux
     * for the same reason — an Android agent says "Linux" too, and "Firefox on Linux" is
     * not what a customer calls their phone.
     */
    private static String platformIn(String lower) {
        if (lower.contains("iphone")) return "iPhone";
        if (lower.contains("ipad")) return "iPad";
        if (lower.contains("android")) return "Android";
        if (lower.contains("windows")) return "Windows";
        if (lower.contains("mac os") || lower.contains("macintosh")) return "Mac";
        if (lower.contains("cros")) return "ChromeOS";
        if (lower.contains("linux") || lower.contains("x11")) return "Linux";
        return null;
    }
}
