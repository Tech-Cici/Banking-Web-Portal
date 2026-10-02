package rw.bank.ibanking.onboarding.domain;

/** Whether a customer may sign in, and why not. */
public enum CustomerStatus {
    /** Created by an administrator, awaiting a manager. Sign-in is refused. */
    PENDING_APPROVAL,
    ACTIVE,
    REJECTED,
    SUSPENDED
}
