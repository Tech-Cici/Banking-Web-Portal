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

    /** Staff declined it, with a reason the customer can read and act on. */
    DECLINED
}
