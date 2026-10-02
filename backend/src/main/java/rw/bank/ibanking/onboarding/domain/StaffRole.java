package rw.bank.ibanking.onboarding.domain;

/**
 * The two bank-side jobs, deliberately disjoint.
 *
 * <p>An ADMIN creates accounts; a MANAGER releases them. Nobody holds both, which is what
 * makes the four-eyes rule mean anything: issuing working credentials for a customer who
 * does not exist is the most valuable thing an insider can do, so it takes two people.
 */
public enum StaffRole {
    ADMIN,
    MANAGER
}
