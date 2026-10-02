package rw.bank.ibanking.onboarding.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.onboarding.domain.CompanyEntity;
import rw.bank.ibanking.onboarding.domain.CorporateMembershipEntity;
import rw.bank.ibanking.onboarding.domain.CorporateRole;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.UserType;
import rw.bank.ibanking.onboarding.repo.CompanyRepository;
import rw.bank.ibanking.onboarding.repo.CorporateMembershipRepository;

/**
 * WHAT THIS CUSTOMER MAY DO, AND WHO THEY MAY DO IT FOR. What the session reports.
 *
 * <p>Built here rather than in the controller for one reason: until now the session
 * answered {@code "RETAIL"} and a fixed permission list to every signed-in person, because
 * that was true — a personal customer was the only kind. It is not true any more, and a
 * controller deriving entitlement inline is a controller that will disagree with the
 * service that enforces it.
 *
 * <p>THE PERMISSIONS ARE FOR ONE COMPANY, not for all of them at once. An accountant
 * acting for two clients holds a membership of each, possibly at different roles, and a
 * session that unioned them would carry an administrator's approval rights into a company
 * where they are only a viewer. So a corporate session has an ACTIVE company and the
 * permissions of that membership.
 *
 * <p>Nothing here authorises anything. This is what the portal reads to decide which
 * screens to offer; every endpoint that shows or moves money checks the caller itself —
 * {@link AccountAccess} for accounts, and the ledger before any balance moves. A
 * permission list the client could tamper with would otherwise be the whole control.
 */
@Service
public class CustomerEntitlements {

    private final CorporateMembershipRepository memberships;
    private final CompanyRepository companies;

    CustomerEntitlements(
            CorporateMembershipRepository memberships, CompanyRepository companies) {
        this.memberships = memberships;
        this.companies = companies;
    }

    /** One company this customer may act for, as the portal renders it. */
    public record Company(UUID id, String name, String code, CorporateRole role) {}

    /**
     * The whole answer.
     *
     * @param companies every company they may act for, so the portal can offer a switch.
     * @param activeCompanyId the one {@code permissions} belongs to, or null for a
     *     personal customer.
     */
    public record Entitlements(
            UserType userType,
            List<String> permissions,
            List<Company> companies,
            UUID activeCompanyId) {}

    @Transactional(readOnly = true)
    public Entitlements of(CustomerEntity customer) {
        /*
         * THE CUSTOMER'S TYPE DECIDES, not the presence of memberships. The two should
         * always agree, and the day they did not, deriving the type from the memberships
         * would let a stray membership row silently turn a personal customer into a
         * corporate one — with a company's accounts on their dashboard. The type is a
         * decision recorded when the login was created; a membership is what it grants.
         */
        if (customer.userType() != UserType.CORPORATE) {
            return new Entitlements(UserType.RETAIL, SessionPermissions.RETAIL, List.of(), null);
        }

        List<CorporateMembershipEntity> live =
                memberships.findByCustomerIdAndRevokedAtIsNullOrderByGrantedAtAsc(customer.id());

        /*
         * A corporate login whose every membership has been revoked. Not an error — it is
         * what revoking the last one is supposed to look like — and the honest session is
         * one that can do nothing rather than one that falls back to retail permissions
         * and shows them their own empty profile as if they were a personal customer.
         */
        if (live.isEmpty()) {
            return new Entitlements(UserType.CORPORATE, List.of(), List.of(), null);
        }

        Map<UUID, CompanyEntity> named =
                companies
                        .findByIdIn(live.stream().map(CorporateMembershipEntity::companyId).toList())
                        .stream()
                        .collect(Collectors.toMap(CompanyEntity::id, Function.identity()));

        /*
         * A membership of a company that is not there cannot happen — the foreign key
         * cascades — so this filter should never drop anything. It is here so that the
         * active company below is chosen from the same set the switcher is offered: a
         * session naming an active company the client was never given would leave the
         * portal acting for a company it cannot show.
         */
        List<CorporateMembershipEntity> resolvable =
                live.stream().filter(m -> named.containsKey(m.companyId())).toList();

        if (resolvable.isEmpty()) {
            return new Entitlements(UserType.CORPORATE, List.of(), List.of(), null);
        }

        List<Company> mine =
                resolvable.stream()
                        .map(
                                membership -> {
                                    CompanyEntity company = named.get(membership.companyId());
                                    return new Company(
                                            company.id(),
                                            company.name(),
                                            company.code(),
                                            membership.role());
                                })
                        .sorted(Comparator.comparing(Company::name))
                        .toList();

        /*
         * THE OLDEST GRANT IS THE ACTIVE COMPANY.
         *
         * Which is the whole of the choice available today: this service creates exactly
         * one membership per company, for the contact on the approved application, so
         * every corporate customer has one and it is unambiguous. A customer with two
         * needs to be able to CHOOSE, and choosing means an endpoint that records the
         * choice on the session — which does not exist yet and is recorded in
         * docs/OPEN-ITEMS.md. Until it does, picking the oldest grant is at least
         * deterministic: the same company on every request, rather than whichever row
         * came back first.
         *
         * Note this is the oldest grant, not the first of the NAME-sorted list above:
         * that list is sorted for the switcher to read, and letting a display order
         * decide which company somebody is acting for would mean renaming a company
         * changed whose money they were looking at.
         */
        CorporateMembershipEntity active = resolvable.get(0);

        return new Entitlements(
                UserType.CORPORATE,
                SessionPermissions.forCorporateRole(active.role()),
                mine,
                active.companyId());
    }
}
