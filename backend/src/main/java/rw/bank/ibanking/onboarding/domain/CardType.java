package rw.bank.ibanking.onboarding.domain;

/**
 * What kind of card it is.
 *
 * <p>TWO VALUES, MATCHING THE CHECK CONSTRAINT IN V23 AND THE TypeScript UNION. Credit is
 * deliberately absent: the bank has not described a credit card product to this project,
 * and an enum value nothing can produce is a promise on a screen.
 */
public enum CardType {
    DEBIT,
    PREPAID
}
