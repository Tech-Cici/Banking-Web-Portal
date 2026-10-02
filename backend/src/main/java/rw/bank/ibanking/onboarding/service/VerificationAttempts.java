package rw.bank.ibanking.onboarding.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.onboarding.repo.EmailVerificationRepository;

/**
 * Records a wrong code, in a transaction that survives the rejection.
 *
 * <p>This exists because of a real bug, and the bug is worth keeping described because the
 * broken version looked completely correct.
 *
 * <p>The increment used to happen inside the verifying method, which is
 * {@code @Transactional} and throws to reject the attempt. Throwing rolls the transaction
 * back — including the increment. So the counter never advanced: every wrong code came back
 * "4 attempts left", forever, and a six-digit code with a ten-minute life could be walked
 * through all million combinations. The attempt limit was decorative, and an integration
 * test is what noticed, because nothing about the code read as wrong.
 *
 * <p>A separate bean, not a method on the caller: {@code REQUIRES_NEW} is applied by
 * Spring's proxy, and a call from one method of a bean to another on the same instance
 * never goes through the proxy — so self-invocation would have silently kept the old
 * behaviour, which is the same failure wearing a different hat.
 */
@Service
class VerificationAttempts {

    private final EmailVerificationRepository verifications;

    VerificationAttempts(EmailVerificationRepository verifications) {
        this.verifications = verifications;
    }

    /**
     * Counts one failed attempt and commits it.
     *
     * @return attempts remaining, never negative
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int recordFailure(UUID verificationId) {
        var verification = verifications.findById(verificationId).orElse(null);
        if (verification == null) return 0;

        verification.recordFailedAttempt();
        verifications.save(verification);

        return Math.max(0, rw.bank.ibanking.onboarding.domain.EmailVerificationEntity.MAX_ATTEMPTS
                - verification.attempts());
    }
}
