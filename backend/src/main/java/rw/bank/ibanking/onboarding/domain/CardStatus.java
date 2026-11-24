package rw.bank.ibanking.onboarding.domain;

/**
 * Where a card is in its life.
 *
 * <p>STORED, NEVER INFERRED. In particular EXPIRED is set by a member of staff when the
 * bank says the card has expired, and is not derived from the expiry month and year. The
 * difference matters: arithmetic on this service's clock telling a customer their card is
 * dead is this portal making a statement about the customer's property that the bank has
 * not made.
 */
public enum CardStatus {

    /** Produced and waiting at a counter. The request that produced it is READY. */
    PENDING_COLLECTION,

    /** Handed over and usable. */
    ACTIVE,

    /** Stopped at the customer's request. */
    BLOCKED,

    /** The bank has said it is no longer valid. */
    EXPIRED
}
