package rw.bank.ibanking.onboarding.domain;

/** Where a standing order is. */
public enum StandingOrderStatus {

    /** Due dates will be paid. */
    ACTIVE,

    /** Suspended by the customer. Due dates pass without payment and without failure. */
    PAUSED,

    /** Finished: cancelled by the customer, or past its end date. Never pays again. */
    ENDED
}
