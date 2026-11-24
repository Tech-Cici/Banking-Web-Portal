package rw.bank.ibanking.collateral;

/**
 * Whether what the customer typed matched what the reader found.
 *
 * <p>NOT A VERDICT ON THE CUSTOMER. DIFFERENT is the ordinary outcome of a reader mistaking a
 * 0 for an O and the customer correcting it, which is the system working rather than a warning
 * sign. It is recorded so a reviewer can see which fields were typed over, not so anything can
 * be refused automatically.
 */
public enum FieldAgreement {

    /** Same value, ignoring case, spacing and punctuation. */
    SAME,

    /** Both present, and not the same. Usually a correction; occasionally worth a question. */
    DIFFERENT,

    /** The reader found nothing for this field, so there is nothing to compare. */
    NOT_READ
}
