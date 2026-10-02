package rw.bank.ibanking.onboarding.service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * The bank's temporary passwords: how they are made and how long they last.
 *
 * <p>EXTRACTED SO THERE IS ONE OF THEM. This lived as three private members of {@link
 * OnboardingService} and was needed in a second place the moment a manager could re-issue
 * a password for a customer who had forgotten theirs. Copying a credential generator is
 * how two parts of a bank end up disagreeing about how strong a password is — and the copy
 * is always the one that drifts, because nobody thinks of it as the real one.
 */
final class TemporaryPasswords {

    /**
     * No I, L, O, 0 or 1.
     *
     * <p>This password is read aloud or written on a slip and handed over in a branch, so
     * the characters people confuse are removed. A customer who mistypes an issued password
     * three times and gets locked out is a branch visit that need not have happened.
     */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * How long an emailed temporary password stays usable.
     *
     * <p>Three days: long enough for somebody who checks their email at the weekend, short
     * enough that a mailbox breached later yields nothing. This is the control that bounds
     * the cost of sending a credential by email at all — without it the password sits in
     * an inbox for as long as the inbox exists.
     */
    static final Duration VALIDITY = Duration.ofHours(72);

    private TemporaryPasswords() {}

    /**
     * A temporary password: four groups of four, e.g. {@code K7M4-PQ29-XTVB-3HRD}.
     *
     * <p>Grouped because it is dictated or copied from a slip of paper. Twelve characters
     * from a 31-symbol alphabet is about 59 bits, which is ample for something that must be
     * replaced on first use anyway.
     */
    static String generate() {
        StringBuilder password = new StringBuilder(19);
        for (int group = 0; group < 4; group++) {
            if (group > 0) password.append('-');
            for (int i = 0; i < 4; i++) {
                password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return password.toString();
    }
}
