package rw.bank.ibanking.collateral;

/**
 * How the attempt to read a document ended.
 *
 * <p>THREE OUTCOMES, NOT TWO. "There was nothing legible on the page" and "the reader fell
 * over" look the same in an empty text column and are completely different things to tell a
 * customer: the first asks them to take a better photograph, the second is the bank's problem
 * and nothing they do will help. Collapsing them produces the worst sentence in banking
 * software -- the one that blames the customer for a fault on the server.
 */
public enum OcrStatus {

    /** Text came back. It may still be wrong; it is a reading, not a fact. */
    READ,

    /** The reader ran and found nothing it could make words out of. */
    UNREADABLE,

    /** The reader did not run, or did not finish. Never the customer's fault. */
    FAILED
}
