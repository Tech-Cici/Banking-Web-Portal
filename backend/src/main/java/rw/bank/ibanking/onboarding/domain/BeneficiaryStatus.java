package rw.bank.ibanking.onboarding.domain;

/**
 * Where a saved payee is in its review.
 *
 * <p>The names are the front end's, copied rather than improved: {@code
 * BeneficiaryStatus} here, a CHECK constraint in V17 and a TypeScript union in
 * banking.ts are three copies of one list, and the only thing worse than three copies is
 * three copies that disagree. V16 exists because two of them did.
 */
public enum BeneficiaryStatus {

    /**
     * Saved, and not payable. The state every payee starts in.
     *
     * <p>Nothing moves this on but a member of staff — there is no timer. The customer is
     * told a person is looking at it, because a wait nobody has explained reads as a
     * fault, and the previous wording ("a short cooling-off period") described a clock
     * that did not exist.
     */
    PENDING_VERIFICATION,

    /** Staff compared the details and cleared it. The only state money may be sent to. */
    ACTIVE,

    /**
     * Staff declined it, with a reason the customer can read and act on.
     *
     * <p>Kept rather than deleted. A refused payee is the record of a decision, and the
     * commonest refusal — the name does not match the account — is one the customer fixes
     * by adding the corrected number, which they cannot be told to do if the row is gone.
     */
    REFUSED
}
