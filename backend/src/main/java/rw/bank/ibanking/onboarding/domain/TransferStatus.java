package rw.bank.ibanking.onboarding.domain;

/**
 * Where a transfer has got to.
 *
 * <p>THREE STATES AND NO FOURTH, deliberately. There is no SENT, no IN_PROGRESS and no
 * COMPLETED: the money is internal to this service, so the moment a manager approves it
 * the beneficiary's balance has already changed in the same database transaction. A state
 * meaning "approved and on its way" would describe a gap that does not exist here, and a
 * screen rendering it would be telling the customer something nothing can confirm.
 */
public enum TransferStatus {
    /**
     * Submitted, and the sender has ALREADY been debited.
     *
     * <p>The hold is a real ledger entry — see V12. The customer cannot spend this money
     * again while it waits, and their statement shows why it is gone.
     */
    PENDING_APPROVAL,

    /** A manager approved it. The beneficiary was credited in the same transaction. */
    APPROVED,

    /** A manager refused it, with a reason. The sender was credited back. */
    REJECTED
}
