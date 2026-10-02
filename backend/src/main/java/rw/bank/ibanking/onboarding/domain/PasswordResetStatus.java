package rw.bank.ibanking.onboarding.domain;

/**
 * Where a customer's request for a new password has got to.
 *
 * <p>Three states and no more. There is deliberately no "IN PROGRESS": a manager either
 * has issued a temporary password or has not, and a state that means "somebody is looking
 * at it" is a state that gets left behind when that somebody goes home.
 */
public enum PasswordResetStatus {

    /** Waiting for a manager. The only state the queue screen shows. */
    PENDING,

    /**
     * A temporary password has been issued and emailed.
     *
     * <p>Named for what happened to the REQUEST, not to the password. Whether the customer
     * has used it is the customer's own {@code mustChangePassword} flag, and keeping the
     * two separate is what stops a stale queue row implying anything about live access.
     */
    FULFILLED,

    /**
     * A manager decided no password should be issued, and said why.
     *
     * <p>This is not an error state. A caller who cannot answer the branch's questions, or
     * a request the customer says they never made, is exactly what this flow exists to
     * catch — and it is the case where a self-service reset link would already have
     * emailed a working credential.
     */
    REFUSED
}
