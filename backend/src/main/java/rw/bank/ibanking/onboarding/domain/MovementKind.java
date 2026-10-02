package rw.bank.ibanking.onboarding.domain;

/**
 * WHAT A LEDGER ENTRY WAS, named by the server so no screen has to guess.
 *
 * <p>The direction alone cannot tell these apart. A transfer arriving and a transfer
 * being refused are both a CREDIT with another party's name on them, and wording a line
 * from the direction turns a failed payment into "you received 4,000 from Ciara" — a
 * receipt for money that never moved, shown to the customer whose payment just failed.
 *
 * <p>Deliberately not inferred from the description either. Deciding what a screen says
 * by matching on display text is how a copy edit becomes a bug in the meaning of a
 * statement line.
 */
public enum MovementKind {

    /** Cash over the counter, or an opening balance. The customer and the bank. */
    CASH,

    /** Money left this account towards somebody else. */
    TRANSFER_OUT,

    /** Money arrived from somebody else. */
    TRANSFER_IN,

    /**
     * A transfer out of this account was refused and the money came back.
     *
     * <p>A CREDIT, like {@link #TRANSFER_IN}, and nothing like it: the other party on
     * this entry is the payee who was never paid.
     */
    TRANSFER_RETURNED;

    /** Whether this kind of movement has another party to name at all. */
    public boolean hasCounterparty() {
        return this != CASH;
    }
}
