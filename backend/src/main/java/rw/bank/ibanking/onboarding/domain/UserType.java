package rw.bank.ibanking.onboarding.domain;

/**
 * What kind of login this is.
 *
 * <p>RETAIL holds accounts in a person's own name. CORPORATE acts for a company, and sees
 * the company's accounts through a {@link CorporateMembershipEntity} rather than by
 * holding them — a company's money is not the finance director's money.
 */
public enum UserType {
    RETAIL,
    CORPORATE
}
