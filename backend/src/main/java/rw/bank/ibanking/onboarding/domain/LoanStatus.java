package rw.bank.ibanking.onboarding.domain;

/**
 * Where a loan stands, as the bank states it.
 *
 * <p>STORED, NEVER INFERRED. In particular IN_ARREARS is set by the bank, not derived by
 * comparing {@code nextDueDate} with this service's clock. Whether somebody has missed a
 * payment depends on when money actually arrived, which lives in the core banking system;
 * a portal that worked it out from a date would eventually tell a customer they were behind
 * on a loan they had paid.
 */
public enum LoanStatus {
    ACTIVE,
    IN_ARREARS,
    SETTLED
}
