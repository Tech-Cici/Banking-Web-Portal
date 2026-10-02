package rw.bank.ibanking.onboarding.domain;

/**
 * How loudly the portal says a notification.
 *
 * <p>NOT PERSISTED, and that is deliberate: it is derived from {@link NotificationKind}, so
 * storing it would be a second copy that could disagree with the first — and a row whose
 * stored severity contradicted its kind would be unresolvable after the fact. The API sends
 * it because the client renders on it; the database holds only the kind.
 *
 * <p>THREE LEVELS, not five. The panel's whole job is to put the one thing that matters at
 * the top, and a scale with more rungs than that is a scale nobody calibrates: everything
 * drifts to the middle and the ordering stops meaning anything.
 */
public enum NotificationSeverity {

    /** Something happened that the customer asked for. */
    INFO,

    /**
     * Something did not happen, and the customer is usually the one who can fix it.
     *
     * <p>A refused payee, a declined card, a rejected transfer. Rendered in the same grey as
     * INFO, each of these waits for a telephone call instead of a correction.
     */
    WARNING,

    /**
     * Something that may mean somebody else has their access.
     *
     * <p>PINNED ABOVE EVERYTHING by the panel's sort. A security message fourth in a list,
     * below a cheque book, is a security message nobody acts on in time.
     */
    SECURITY
}
