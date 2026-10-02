package rw.bank.ibanking.onboarding.domain;

/** Which way the money went. Amounts are stored positive; this says add or remove. */
public enum TransactionDirection {
    /** Money in. */
    CREDIT,
    /** Money out. */
    DEBIT
}
