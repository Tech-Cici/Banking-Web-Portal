package rw.bank.ibanking.onboarding.domain;

/**
 * Which physical thing the customer has asked for.
 *
 * <p>THREE VALUES NOW, AND NOT ALL OF THEM ARE COLLECTED. A card and a cheque book are
 * objects somebody picks up; a loan application is not, and the ready-to-collect step that
 * suits the first two would send a customer to a counter for money that arrives in their
 * account. {@code isCollected()} is where that difference lives.
 *
 * <p>The names are the front end's own vocabulary, copied rather than improved, because
 * this enum, a CHECK constraint in V18 and a TypeScript union are three copies of one
 * list. V16 exists because two copies of a list disagreed; V25 extends the constraint.
 */
public enum ServiceRequestType {

    /** A debit or prepaid card, linked to one account. */
    CARD,

    /** A cheque book, which only a current account can have. */
    CHEQUE_BOOK,

    /** Asking the bank for a loan. Nothing is produced and nothing is collected. */
    LOAN_APPLICATION;

    /** What a customer and a member of staff both call it. */
    public String label() {
        return switch (this) {
            case CARD -> "card";
            case CHEQUE_BOOK -> "cheque book";
            case LOAN_APPLICATION -> "loan application";
        };
    }

    /** The prefix on the reference, so it says what it is when read out on the phone. */
    public String referencePrefix() {
        return switch (this) {
            case CARD -> "CRD";
            case CHEQUE_BOOK -> "CHQ";
            case LOAN_APPLICATION -> "LNA";
        };
    }

    /**
     * Whether somebody physically collects the thing.
     *
     * <p>Guards the collection point: V18 requires one on a READY request, and "collect
     * your loan at Nyarugenge" is exactly the kind of invented instruction that migration
     * exists to stop.
     */
    public boolean isCollected() {
        return this != LOAN_APPLICATION;
    }
}
