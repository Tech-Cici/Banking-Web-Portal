package rw.bank.ibanking.onboarding.domain;

/**
 * What somebody may do for a company.
 *
 * <p>A LABEL, NOT THE AUTHORITY. Permissions are derived from this in one place and the
 * server checks those on every request; a role stored on a membership grants nothing by
 * itself, exactly as a signatory listed on an application grants nothing.
 *
 * <p>Only ADMIN is issued today — the named contact on an approved company application
 * becomes the company's first administrator. The other three exist because the portal's
 * own CorporateMembership type carries them and a role column that could not express them
 * would need widening the day a second signatory is added; nothing creates them yet.
 */
public enum CorporateRole {
    /** Can act and can grant access to colleagues. The contact on the application. */
    ADMIN,
    /** Prepares payments for somebody else to approve. */
    MAKER,
    /** Approves what a maker prepared. Never both, for the same payment. */
    APPROVER,
    /** Reads, moves nothing. */
    VIEWER
}
