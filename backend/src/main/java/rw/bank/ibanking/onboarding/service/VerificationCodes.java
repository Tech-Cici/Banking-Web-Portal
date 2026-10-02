package rw.bank.ibanking.onboarding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generating and hashing verification codes.
 *
 * <p>Separated from the service that uses it so the two properties below can be tested on
 * their own, without a database or a mail server in the way.
 */
final class VerificationCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DIGITS = 6;
    private static final int BOUND = 1_000_000;

    private VerificationCodes() {}

    /**
     * A six-digit code.
     *
     * <p>{@code SecureRandom}, not {@code Math.random} or {@code Random}. A predictable
     * code is no code at all: {@code java.util.Random} is a linear congruential generator
     * whose entire future output follows from two observed values, and an attacker can
     * observe their own codes as often as they like by starting registrations.
     *
     * <p>Zero-padded, so 42 becomes "000042" and every code really has six digits. Without
     * the padding, a fifth of all codes would be shorter and therefore easier to guess.
     */
    static String generate() {
        return String.format("%0" + DIGITS + "d", RANDOM.nextInt(BOUND));
    }

    /**
     * SHA-256, hex encoded.
     *
     * <p>Deliberately NOT BCrypt, which is the right choice for passwords and the wrong one
     * here. BCrypt is slow by design, and the code is checked on a hot path while a
     * customer waits; more importantly its cost buys resistance to offline brute force of a
     * *high-entropy* secret, and a six-digit code has 20 bits — an attacker with the hash
     * cracks it in under a second whatever the algorithm. What actually protects this code
     * is the attempt counter and the ten-minute expiry, not the hash. The hash is here so a
     * database dump does not hand out live codes, and SHA-256 does that job.
     */
    static String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Compares in constant time.
     *
     * <p>{@code String.equals} returns as soon as two characters differ, so the time it
     * takes leaks how much of the hash was right. That is a remote timing attack, and while
     * it is a stretch over a network it costs nothing to close.
     */
    static boolean matches(String candidateCode, String storedHash) {
        return MessageDigest.isEqual(
                hash(candidateCode).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** A single-use token, exchanged for the right to submit the registration. */
    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
