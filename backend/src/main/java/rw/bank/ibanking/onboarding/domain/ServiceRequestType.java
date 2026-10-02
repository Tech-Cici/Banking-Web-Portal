package rw.bank.ibanking.onboarding.domain;

/**
 * Which physical thing the customer has asked for.
 *
 * <p>Two values and one process behind them: a customer asks, staff produce it, somebody
 * collects it. They share a table and a queue for that reason — see V18.
 *
 * <p>The names are the front end's own vocabulary, copied rather than improved, because
 * this enum, a CHECK constraint in V18 and a TypeScript union are three copies of one
 * list. V16 exists because two copies of a list disagreed.
 */
public enum ServiceRequestType {

    /** A debit or prepaid card, linked to one account. */
    CARD,

    /** A cheque book, which only a current account can have. */
    CHEQUE_BOOK;

    /** What a customer and a member of staff both call it. */
    public String label() {
        return this == CARD ? "card" : "cheque book";
    }

    /** The prefix on the reference, so it says what it is when read out on the phone. */
    public String referencePrefix() {
        return this == CARD ? "CRD" : "CHQ";
    }
}
