package rw.bank.ibanking.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two words a customer uses to recognise their own browser.
 *
 * <p>IN THE SERVICE PACKAGE because {@code DeviceDescription} is package-private and should
 * stay that way — it exists for two call sites in this package and widening it to make it
 * testable would be the tail wagging the dog. Same move as BeneficiaryNameCheckTest.
 *
 * <p>WHAT THESE GUARD. Not accuracy — nobody needs this to be right about Opera 73 versus
 * Opera 74. Two things: that the ORDER of the checks is right, because every Chromium
 * browser says "Chrome" and Chrome says "Safari", so one transposition labels most of the
 * world's browsers "Safari on Windows"; and that an unknown header returns NOTHING rather
 * than a plausible default, which is the failure mode this whole batch exists to remove.
 */
class DeviceDescriptionTest {

    @ParameterizedTest(name = "{1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // Chrome on Windows. Says "Safari" and "AppleWebKit"; is neither.
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/120.0.0.0 Safari/537.36|Chrome on Windows",
                // Edge. Says "Chrome" AND "Safari" before it says "Edg".
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0|Edge on Windows",
                // Real Safari, which must not be caught by the Chrome check.
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML,"
                        + " like Gecko) Version/17.0 Safari/605.1.15|Safari on Mac",
                // An iPhone. Its agent contains "like Mac OS X" — testing Mac first calls
                // every iPhone a Mac, which is the platform ordering bug.
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1|Safari on"
                        + " iPhone",
                // Android says "Linux" too. "Chrome on Linux" is not what anybody calls
                // their phone.
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/120.0.0.0 Mobile Safari/537.36|Chrome on Android",
                "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101"
                        + " Firefox/121.0|Firefox on Linux",
                // Firefox on iOS is "FxiOS" and contains no "firefox" at all.
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) FxiOS/121.0 Mobile/15E148 Safari/605.1.15|Firefox"
                        + " on iPhone",
            })
    @DisplayName("names the browser and the machine")
    void describesRealAgents(String userAgent, String expected) {
        assertThat(DeviceDescription.from(userAgent)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "curl/8.4.0", "PostmanRuntime/7.36.0", "-"})
    @DisplayName("says nothing rather than guessing")
    void returnsNullWhenItCannotTell(String userAgent) {
        /*
         * THE ASSERTION THIS WHOLE BATCH IS ABOUT. The alternative — "Unknown browser", or
         * worse a default like "Chrome on Windows" — is how `sign_ins.location` came to
         * claim "Kigali, Rwanda" for every sign-in ever recorded. A null here makes the
         * screens render nothing, and nothing prompts a question where a wrong answer
         * closes one.
         */
        assertThat(DeviceDescription.from(userAgent)).isNull();
    }

    @Test
    @DisplayName("names the machine alone when the browser is unrecognised")
    void fallsBackToThePlatform() {
        /* Half an answer is still true, and "a browser on Windows" is recognisable to
           somebody who has one Windows machine. It must not be dressed up as Chrome. */
        assertThat(DeviceDescription.from("SomeNewBrowser/2.0 (Windows NT 10.0)"))
                .isEqualTo("A browser on Windows");
    }

    @Test
    @DisplayName("is bounded, whatever the client sends")
    void ignoresAnAbsurdlyLongHeader() {
        /*
         * The header is client-supplied and unbounded. This is not a performance claim —
         * nothing here is quadratic — it is that the worst case should be known. The
         * padding is placed FIRST so that a cap read from the wrong end would produce
         * "Chrome on Windows" and fail this.
         */
        String padded = "x".repeat(5_000) + " Windows Chrome";

        assertThat(DeviceDescription.from(padded)).isNull();
    }
}
