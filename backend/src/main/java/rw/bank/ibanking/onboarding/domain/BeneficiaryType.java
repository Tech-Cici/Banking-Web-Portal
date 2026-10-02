package rw.bank.ibanking.onboarding.domain;

/**
 * Which rails a payment to this payee would take.
 *
 * <p>ONLY {@link #INTERNAL} IS PAYABLE TODAY, and saying so here is the point of the
 * javadoc. This service holds its own ledger and has no connection to any other
 * institution, so the outbound transfer screens show a page explaining that rather than a
 * form that cannot work. The other three values exist because a customer may save a payee
 * before the rail opens, and because the front end has always offered them.
 *
 * <p>INTERNAL is also the only value the bank can NAME-CHECK: resolving who holds an
 * account number is a question about this bank's own accounts. For the rest there is
 * nothing to compare the customer's typing against, which the review screen states
 * instead of leaving blank.
 */
public enum BeneficiaryType {

    /** An account at Zigama CSS. Payable, and name-checkable. */
    INTERNAL,

    /** Another bank in Rwanda. */
    DOMESTIC,

    /** A bank outside Rwanda. */
    INTERNATIONAL,

    /** A mobile money wallet. */
    WALLET;

    /**
     * Whether the bank can look up who holds this destination.
     *
     * <p>Asked by the review service so that a missing holder name is reported as
     * "unavailable" rather than "no match" — the difference between a comparison that
     * could not be made and one that failed is the whole value of showing it.
     */
    public boolean heldAtThisBank() {
        return this == INTERNAL;
    }
}
