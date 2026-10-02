package rw.bank.ibanking.onboarding.service;

import java.util.List;
import rw.bank.ibanking.onboarding.domain.CorporateRole;

/**
 * WHAT A SIGNED-IN CUSTOMER MAY DO. The one place a role becomes permissions.
 *
 * <p>The role on a membership is a label. It grants nothing on its own — the server checks
 * permissions, and this is where those permissions come from. Both {@code CorporateRole}
 * and V11's comment promise that the derivation happens in one place; this is that place,
 * and a second {@code switch} on the role anywhere else breaks the promise.
 *
 * <p>WHY A ROLE AT ALL, rather than permissions stored per membership. Because the roles
 * are the bank's four, and a stored permission list would drift: a permission added to the
 * product would have to be backfilled onto every existing membership, and the ones it was
 * missed on would silently be less able than the same role elsewhere. The role is the
 * decision; this table turns it into the current meaning of that decision.
 *
 * <p>SOME OF THESE NAME THINGS THIS SERVICE HAS NOT BUILT — transfers, payments, approvals,
 * bulk files, salary. That is deliberate and matches what the retail list already did: the
 * permission describes the product, and the screens for the parts that are not live are
 * still served by the frontend's own mocks. It is recorded in docs/OPEN-ITEMS.md. What a
 * permission must never do is imply that a control exists where none does, so nothing here
 * is checked in place of a server-side check — every endpoint that moves money authorises
 * the caller itself.
 */
final class SessionPermissions {

    /**
     * A personal customer's permissions.
     *
     * <p>Fixed, because a personal customer's authority does not vary: they hold their own
     * accounts and may do everything the retail product offers with them.
     */
    static final List<String> RETAIL =
            List.of(
                    "RETAIL_ACCOUNT_VIEW",
                    "TRANSFER_CREATE",
                    "PAYMENT_CREATE",
                    "BENEFICIARY_MANAGE",
                    "LOAN_VIEW",
                    "CARD_MANAGE");

    /** Seeing the company's accounts and history. Every role has this. */
    private static final List<String> CORPORATE_READ = List.of("CORPORATE_VIEW", "BULK_VIEW");

    /** Preparing money movements for somebody else to approve. */
    private static final List<String> CORPORATE_PREPARE =
            List.of("CORPORATE_TRANSFER_CREATE", "CORPORATE_PAYMENT_CREATE", "BULK_CREATE");

    /** Acting on what a maker prepared. */
    private static final List<String> CORPORATE_APPROVE =
            List.of("APPROVAL_VIEW", "APPROVAL_APPROVE", "APPROVAL_REJECT");

    private SessionPermissions() {}

    /**
     * What this role may do, for the company the session is currently acting for.
     *
     * <p>SCOPED TO ONE COMPANY, NOT UNIONED ACROSS ALL OF THEM. An accountant who is an
     * administrator for one client and a viewer for another must not carry approval
     * rights into the second, and a union would give them exactly that. The caller passes
     * the role of the ACTIVE membership.
     */
    static List<String> forCorporateRole(CorporateRole role) {
        return switch (role) {
            /*
             * The contact on the approved application. Prepares and approves, because
             * they are the only member the bank has admitted — a company of one cannot
             * have somebody else approve its payments, and refusing to let them approve
             * their own would mean they could move no money at all.
             *
             * MAKER/CHECKER SEPARATION IS NOT EXPRESSED HERE and is not something a
             * permission list can express: "not the same person for one payment" is a
             * rule about a payment, and belongs in whatever approves them. Recorded in
             * docs/OPEN-ITEMS.md as owed rather than pretended at.
             */
            case ADMIN ->
                    concat(
                            CORPORATE_READ,
                            CORPORATE_PREPARE,
                            CORPORATE_APPROVE,
                            List.of("SALARY_VIEW_DETAILS"));

            case MAKER -> concat(CORPORATE_READ, CORPORATE_PREPARE);

            case APPROVER -> concat(CORPORATE_READ, CORPORATE_APPROVE);

            /*
             * Reads, moves nothing. No SALARY_VIEW_DETAILS either: a viewer can see that
             * a salary batch exists without seeing what each person in the company is
             * paid, which is the one piece of a batch that is nobody's business by
             * default.
             */
            case VIEWER -> CORPORATE_READ;
        };
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        return java.util.Arrays.stream(parts).flatMap(List::stream).toList();
    }
}
