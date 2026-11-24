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
    TRANSFER_RETURNED,

    /**
     * Money the bank lent, arriving in the account.
     *
     * <p>ITS OWN KIND RATHER THAN CASH, because this is the most consequential line on a
     * customer's statement: the moment they went into debt. "Cash" would tell them their
     * balance went up and nothing about why, and the entry it most resembles -- a deposit --
     * is the one it must never be confused with.
     *
     * <p>NO COUNTERPARTY. The other party is the bank itself, and naming the bank as a payee
     * on its own customer's statement would be inventing a party where there is a product.
     */
    LOAN_DISBURSEMENT,

    /**
     * The currency a customer gave up in a conversion.
     *
     * <p>NOT A TRANSFER, though a conversion is two entries between two accounts one customer
     * holds. A transfer moves one amount, so "sent RWF 100,000 to **** 0001" is a complete
     * line. A conversion moves two different amounts in two different currencies, and calling
     * either leg a transfer produces a statement line whose halves do not tie.
     */
    FX_SOLD,

    /** The currency they received. The description carries the rate they got. */
    FX_BOUGHT,

    /**
     * Money taken from an account to pay down a loan.
     *
     * <p>NOT A TRANSFER. The other side is a loan, which this portal does not hold as a
     * customer account, so there is no matching credit anywhere. Calling it a transfer would
     * promise one that a customer could go looking for and never find.
     */
    LOAN_REPAYMENT;

    /**
     * Whether this kind of movement has another party to name at all.
     *
     * <p>AN EXHAUSTIVE SWITCH, NOT {@code this != CASH}. That spelling answered "yes" for
     * every kind that had not been thought about yet, so adding LOAN_DISBURSEMENT silently
     * claimed the bank's own lending was a transfer from somebody. A switch over every
     * constant makes the next new kind a compile error instead, which is the only moment
     * anybody will be thinking about the answer.
     */
    public boolean hasCounterparty() {
        return switch (this) {
            /*
             * The bank itself is not a party to name on its own customer's statement. For a
             * conversion the other side is the bank's treasury, and printing the customer's
             * own name opposite their own account would invent a party where there is a
             * product.
             */
            case CASH, LOAN_DISBURSEMENT, LOAN_REPAYMENT, FX_SOLD, FX_BOUGHT -> false;
            case TRANSFER_OUT, TRANSFER_IN, TRANSFER_RETURNED -> true;
        };
    }
}
