package rw.bank.ibanking.onboarding.domain;

/**
 * The kinds of account a branch can ask to have opened alongside a login.
 *
 * <p><b>THE FIRST FIVE ARE THE BANK'S OWN, AND THE REST ARE LEGACY.</b> This list used to
 * be a placeholder invented for a Rwandan savings bank, and it said so. The core banking
 * system has since answered the question: its balance columns are a current account,
 * compulsory savings, voluntary savings, a fixed deposit and education savings, each held
 * in RWF, USD, EUR or GBP. Those five are marked below and they are what a customer can
 * actually hold.
 *
 * <p>THE OTHERS ARE KEPT ONLY BECAUSE ROWS MAY ALREADY CARRY THEM. SALARY, JUNIOR_SAVINGS,
 * TARGET_SAVINGS and LOAN_SERVICING are not in the bank's catalogue and nothing should
 * create one. Deleting a value an existing row holds turns that row into a read that
 * throws, so they go when a migration has confirmed none are in use -- not before, and not
 * as a tidy-up. Nothing offers them: the type is now read from the core system rather than
 * chosen from a dropdown, so no member of staff can pick one by accident.
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

    /* ---------------------------------------- the bank's own five, from the CBS */

    /** Day-to-day transacting. CBS: BalanceCurrentAc and its currency variants. */
    CURRENT,

    /** Required contributions, the member's standing commitment. CBS: BalanceCompAc. */
    COMPULSORY_SAVINGS,

    /** Saved over and above the required amount. CBS: BalanceVolAc. */
    VOLUNTARY_SAVINGS,

    /** Locked for a fixed term at an agreed rate. CBS: BalanceFixedac. */
    FIXED_DEPOSIT,

    /** Put aside for school fees. CBS: BalanceEducationSavings, RWF only. */
    EDUCATION_SAVINGS,

    /* ------------------------------------- legacy, not offered, pending removal */

    /** Not in the bank's catalogue. Kept only until no row holds it. */
    SAVINGS,

    /** Not in the bank's catalogue. Kept only until no row holds it. */
    SALARY,

    /** Not in the bank's catalogue. Kept only until no row holds it. */
    JUNIOR_SAVINGS,

    /** Not in the bank's catalogue. Kept only until no row holds it. */
    TARGET_SAVINGS,

    /** Not in the bank's catalogue. Kept only until no row holds it. */
    LOAN_SERVICING;

    /**
     * The product's name as a customer reads it: "Compulsory savings", not
     * "COMPULSORY_SAVINGS".
     *
     * <p>HERE RATHER THAN IN A CONTROLLER, because more than one screen names an account and
     * two copies of this rule would eventually disagree. A customer whose dashboard says
     * "Compulsory savings" and whose standing order says "COMPULSORY SAVINGS" is reading two
     * systems, and will wonder which one moved their money.
     *
     * <p>DERIVED, NOT STORED, because there is nothing to store yet. When the bank gives
     * these products their marketing names -- or when a customer is allowed to nickname an
     * account -- this becomes a lookup and every screen follows at once.
     */
    public String label() {
        String words = name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
