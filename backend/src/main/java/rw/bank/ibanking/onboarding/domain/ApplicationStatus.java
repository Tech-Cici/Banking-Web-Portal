package rw.bank.ibanking.onboarding.domain;

/**
 * Where a registration has got to.
 *
 * <p>Strictly forward: SUBMITTED to ACCOUNT_CREATED to APPROVED or REJECTED. There is no
 * transition back, because each step has already had an effect somebody relied on — a
 * temporary password was handed over, or a customer was emailed.
 */
public enum ApplicationStatus {
    /** Registered. Nothing exists yet; this is a request, not an account. */
    SUBMITTED,
    /** An administrator created the login. Not usable until a manager approves. */
    ACCOUNT_CREATED,
    /** A manager released it. The customer can sign in and has been emailed. */
    APPROVED,
    /** Turned down, with a reason. */
    REJECTED
}
