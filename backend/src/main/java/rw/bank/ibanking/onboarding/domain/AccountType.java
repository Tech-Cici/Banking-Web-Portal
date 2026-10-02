package rw.bank.ibanking.onboarding.domain;

/**
 * The kinds of account a branch can ask to have opened alongside a login.
 *
 * <p><b>THIS LIST IS A PLACEHOLDER AND THE BANK HAS NOT CONFIRMED IT.</b> It is a plausible
 * set for a Rwandan savings bank, which is exactly what makes it dangerous: it will look
 * authoritative to anyone who reads the dropdown. Zigama's real product catalogue — what
 * each account is called to a customer, who is eligible, which currencies each supports —
 * has to replace this before anyone outside the team uses the screen. Tracked in
 * frontend/docs/OPEN-ITEMS.md.
 *
 * <p>CURRENCY IS NOT A TYPE, and that is the one structural decision worth defending. A
 * foreign-currency account is not a different kind of account; it is an account whose
 * currency is not RWF. Encoding it as {@code FCY_SAVINGS} would mean the same product
 * exists twice under different names, "savings in USD and savings in EUR" would need a
 * third value, and a report counting savings accounts would have to know every spelling.
 * So the type says what the account IS and the currency says what it HOLDS.
 *
 * <p>The same reasoning rules out combined values like {@code CURRENT_AND_SAVINGS}. A
 * customer holding both holds TWO accounts, each openable, closeable and reportable on its
 * own. One request may therefore name several accounts; see AccountOpeningRequestEntity.
 */
public enum AccountType {

    /** Day-to-day transacting. */
    CURRENT,

    /** Ordinary interest-bearing savings. */
    SAVINGS,

    /** Locked for a fixed term at an agreed rate. */
    FIXED_DEPOSIT,

    /** Where an employer pays a salary, often with different fees. */
    SALARY,

    /** Held for a minor, operated by a guardian. */
    JUNIOR_SAVINGS,

    /** Contributions toward a specific goal, usually with restricted withdrawals. */
    TARGET_SAVINGS,

    /** Receives loan disbursements and takes repayments. */
    LOAN_SERVICING
}
