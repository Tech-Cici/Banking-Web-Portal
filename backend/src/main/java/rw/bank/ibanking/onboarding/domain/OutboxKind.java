package rw.bank.ibanking.onboarding.domain;

/** Which message this was. Matches the frontend's own vocabulary. */
public enum OutboxKind {
    EMAIL_VERIFICATION,
    ACCOUNT_CREATED,
    ACCOUNT_APPROVED,
    ACCOUNT_REJECTED,

    /** A manager stopped the customer using the service. */
    ACCOUNT_FROZEN,

    /** A manager gave it back. */
    ACCOUNT_UNFROZEN,

    /**
     * A manager re-issued a temporary password, because the customer had forgotten theirs.
     *
     * <p>Its own kind rather than reusing ACCOUNT_APPROVED. The staff outbox screen is the
     * record of what the bank sent somebody, and two different events that both put a
     * working credential in an inbox must be told apart there — "approved" and "password
     * re-issued" answer different questions when a customer disputes a sign-in.
     */
    PASSWORD_REISSUED,

    /** A manager refused a password request, with a reason. */
    PASSWORD_REQUEST_REFUSED,

    /**
     * Staff checked a payee the customer saved, and it can now be paid.
     *
     * <p>Its own kind because this is the message that closes a wait the customer was put
     * in, and because it is the bank's only chance to tell somebody a payee appeared on
     * their account that they did not add — the clearest sign another person has their
     * password. The staff outbox screen has to be able to find it.
     */
    BENEFICIARY_APPROVED,

    /** Staff declined a payee, with the reason the customer is shown. */
    BENEFICIARY_REFUSED,

    /**
     * A card or cheque book has been produced and is waiting to be collected.
     *
     * <p>THIS EMAIL IS WHAT MAKES THE WAIT TOLERABLE, and it is the only place the bank
     * states a collection point — the portal has no branch list of its own and the screens
     * no longer claim one. The message carries what a member of staff typed and nothing
     * else.
     */
    /**
     * The customer changed their own password, and we are telling them so.
     *
     * <p>ITS OWN KIND BECAUSE IT IS THE DETECTION MECHANISM. This message cannot prevent
     * anything — whoever made the change already had the current password — so its entire
     * value is that a customer whose password was changed BY SOMEBODY ELSE finds out. A
     * kind of its own is what makes "did we tell them" answerable from the sent-messages
     * screen when that is the question being asked.
     *
     * <p>Distinct from PASSWORD_REISSUED, which is a member of staff handing out a
     * temporary password, and from PASSWORD_REQUEST_REFUSED. Those are things the bank did
     * to the account; this is something the customer did to it.
     */
    PASSWORD_CHANGED,

    SERVICE_REQUEST_READY,

    /** Staff declined a card or cheque book request, with a reason. */
    SERVICE_REQUEST_DECLINED
}
