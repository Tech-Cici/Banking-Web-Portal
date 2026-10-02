package rw.bank.ibanking.onboarding.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.onboarding.domain.LoginChallengeEntity;
import rw.bank.ibanking.onboarding.repo.LoginChallengeRepository;

/**
 * Records a wrong sign-in code, in a transaction that survives the rejection.
 *
 * <p>The same shape as {@link VerificationAttempts}, and for the same reason: the verifying
 * method throws to reject the attempt, and throwing rolls back the transaction it runs in —
 * including the increment. Incrementing inline would leave the limit decorative and the
 * code brute-forceable, which is exactly the bug that got shipped once already on the
 * registration side.
 *
 * <p>A separate bean rather than a method on the caller, because {@code REQUIRES_NEW} is
 * applied by Spring's proxy and a self-invocation never goes through it.
 */
@Service
class LoginAttempts {

    private final LoginChallengeRepository challenges;

    LoginAttempts(LoginChallengeRepository challenges) {
        this.challenges = challenges;
    }

    /** @return attempts remaining, never negative */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int recordFailure(UUID challengeId) {
        var challenge = challenges.findById(challengeId).orElse(null);
        if (challenge == null) return 0;

        challenge.recordFailedAttempt();
        challenges.save(challenge);

        return Math.max(0, LoginChallengeEntity.MAX_ATTEMPTS - challenge.attempts());
    }
}
