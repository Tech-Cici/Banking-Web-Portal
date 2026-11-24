package rw.bank.ibanking.onboarding.domain;

/**
 * Where a request for a card or a cheque book has got to.
 *
 * <p>FOUR STATES, NOT THE EIGHT THE FRONT END'S UNION LISTS. `ServiceRequestStatus` in
 * api.ts carries DRAFT, UNDER_REVIEW, ADDITIONAL_INFO_REQUIRED, APPROVED, REJECTED,
 * PROCESSING and COMPLETED — a speculative list written before anything implemented it,
 * and nothing ever produced any of them. These four are the ones the service actually
 * reaches, and the union is narrowed to match rather than left as a menu of states that
 * cannot occur.
 */
public enum ServiceRequestStatus {

    /**
     * Asked for, and nothing has happened yet. The state every request starts in.
     *
     * <p>Nothing moves this on but a member of staff. The customer is told that, because
     * a wait nobody has explained reads as a fault — and because the previous wording
     * promised a printing time the bank had never given.
     */
    SUBMITTED,

    /**
     * Produced, and waiting to be collected. The only state with a collection point.
     *
     * <p>Staff type the collection point when they set this, which is why the column is
     * null beforehand: the bank knows where the card is and the portal does not.
     */
    READY,

    /** Handed over. The audit record that somebody actually received it. */
    COLLECTED,

    /**
     * Approved and paid out. The ending a loan application gets.
     *
     * <p>NOT COLLECTED, and the difference is the whole point. READY and COLLECTED describe
     * an object sitting at a branch waiting for somebody to walk in -- a cheque book, a card.
     * A loan is not fetched: the money is credited to the account the customer named when
     * they applied, and the next thing they see is a bigger balance. Recording that as
     * "collected" would put a counter and a journey into an audit trail that had neither.
     *
     * <p>THERE IS A LOAN BEHIND EVERY ROW IN THIS STATE. Granting creates the loan record and
     * the disbursement in one transaction with this change, so a GRANTED application whose
     * loan does not exist is not a state the system can reach.
     */
    GRANTED,

    /** Staff declined it, with a reason the customer can read and act on. */
    DECLINED
}
