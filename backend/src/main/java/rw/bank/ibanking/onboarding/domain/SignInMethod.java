package rw.bank.ibanking.onboarding.domain;

/**
 * HOW SOMEBODY GOT IN.
 *
 * <p>WHAT THIS REPLACED, because the replacement is the point. {@code sign_ins} used to
 * carry a {@code location} column, and all four call sites in {@code CustomerAuthService}
 * passed the same string literal: {@code "Kigali, Rwanda"}. There is no geo-IP lookup in
 * this service and never has been. So the dashboard told every customer "last sign-in from
 * Kigali, Rwanda" whoever they were and wherever they had been, and the profile page said
 * the same under a heading reading "Where".
 *
 * <p>THAT IS NOT A COSMETIC FAULT. The sign-in history exists for one purpose: so a
 * customer notices access that was not theirs. A constant defeats it in exactly the case
 * that matters — somebody signing in from another country with a stolen password produced a
 * row identical to the customer's own — which makes it a false negative in the only control
 * the customer operates themselves.
 *
 * <p>SO THE COLUMN IS GONE AND THIS IS WHAT STANDS IN ITS PLACE. The method is something
 * the service genuinely knows, at the moment it writes the row, without asking anybody: it
 * is the branch it just took. And it is more use to the customer than a city would be. "A
 * remembered browser signed in without a code" is a recognisable event; "Kigali" is not.
 *
 * <p>Geo-location is NOT reimplemented here. Doing it honestly needs a geo-IP provider the
 * bank has not chosen and a decision about retaining IP addresses the bank has not made —
 * and an IP stored against every sign-in is a movement history of the customer, which is
 * the reason V2's own comment gave for keeping the location coarse. It stays in
 * docs/OPEN-ITEMS.md as waiting on the bank.
 */
public enum SignInMethod {

    /** The password, then a code emailed to the customer. The full path. */
    PASSWORD_AND_CODE("Password and emailed code"),

    /**
     * The password alone, on a browser that had completed the code step before.
     *
     * <p>THE ONE A CUSTOMER SHOULD LOOK AT HARDEST, which is why it is its own value
     * rather than being folded into {@link #PASSWORD_ONLY}. It means a browser somewhere
     * is still trusted, and if the customer does not recognise the sign-in then that
     * browser is the thing to revoke.
     */
    TRUSTED_BROWSER("Password only, from a remembered browser"),

    /**
     * The password alone, because the emailed code is switched off service-wide.
     *
     * <p>Distinct from {@link #TRUSTED_BROWSER}: this one says nothing about the browser,
     * and a customer who revoked every device would still see it. See SignInProperties for
     * what turning the code off gives up.
     */
    PASSWORD_ONLY("Password only, the emailed code is switched off"),

    /**
     * The temporary password a member of staff handed over.
     *
     * <p>Worth its own value for the reason the recording of it is commented in
     * {@code CustomerAuthService}: it is the credential most likely to have been used by
     * somebody else first, because it travelled on paper or across a counter.
     */
    TEMPORARY_PASSWORD("The temporary password"),

    /**
     * Recorded before the method was tracked.
     *
     * <p>NOT A PLACEHOLDER FOR LAZINESS. V19 drops the fabricated location and adds this
     * column; rows written before it ran recorded no method, and the honest value for them
     * is that nobody knows. The alternative was backfilling them with a guess, which is
     * the same mistake as the one being fixed.
     */
    UNKNOWN("Recorded before we kept this detail");

    private final String label;

    SignInMethod(String label) {
        this.label = label;
    }

    /** What the customer reads on their own security page. */
    public String label() {
        return label;
    }
}
