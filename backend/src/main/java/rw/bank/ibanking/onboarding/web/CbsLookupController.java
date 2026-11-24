package rw.bank.ibanking.onboarding.web;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.cbs.CbsCustomer;
import rw.bank.ibanking.cbs.CbsDirectory;
import rw.bank.ibanking.exception.ResourceNotFoundException;

/**
 * Looks an account number up in the bank's core banking system, for staff.
 *
 * <p>A SEPARATE CONTROLLER rather than another method on {@link AdminController}, because
 * this reads a different database for a different purpose and shares none of that class's
 * dependencies.
 *
 * <p>STAFF ONLY, and a customer can never reach it. What it returns -- somebody's name,
 * their status and what they hold -- is exactly what a stranger would want in order to
 * impersonate them, which is why account numbers are never checked on the public
 * registration path.
 */
@RestController
@RequestMapping("/api/v1/admin/cbs")
class CbsLookupController {

    private final CbsDirectory cbs;

    CbsLookupController(CbsDirectory cbs) {
        this.cbs = cbs;
    }

    record HoldingView(String product, String currency, String balance) {}

    record CbsCustomerView(
            String accountNumber,
            String name,
            String customerType,
            String status,
            boolean dormant,
            String openedOn,
            String telephoneMask,
            List<HoldingView> holdings) {}

    /** Whether lookups can be answered, so a screen can say so before anyone types. */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    StatusView status() {
        return new StatusView(cbs.available());
    }

    record StatusView(boolean available) {}

    /**
     * One customer, by the account number on their application.
     *
     * <p>404 when the CBS has no such account; 503 when the CBS could not be asked. Those
     * are different answers and the screen must treat them differently -- one is grounds
     * to refuse the application, the other is grounds to wait.
     */
    @GetMapping("/accounts/{accountNumber}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    CbsCustomerView byAccountNumber(@PathVariable String accountNumber) {
        CbsCustomer found =
                cbs.findByAccountNumber(accountNumber)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "The bank's records hold no account with that"
                                                        + " number."));

        return new CbsCustomerView(
                found.accountNumber(),
                found.name(),
                found.company() ? "COMPANY" : "INDIVIDUAL",
                found.status(),
                found.dormant(),
                found.openedOn() == null ? null : found.openedOn().toString(),
                mask(found.telephone()),
                found.holdings().stream()
                        .map(
                                holding ->
                                        new HoldingView(
                                                holding.product(),
                                                holding.currency(),
                                                /*
                                                 * A decimal STRING, never a number. The
                                                 * same rule the rest of this API follows:
                                                 * a balance that goes through JavaScript's
                                                 * floating point is a balance that can
                                                 * come back wrong.
                                                 */
                                                holding.balance().toPlainString()))
                        .toList());
    }

    /**
     * The last three digits and nothing else.
     *
     * <p>Enough for a member of staff to compare against the number on an application,
     * and not enough to be worth harvesting. The full number stays in the CBS.
     */
    private static String mask(String telephone) {
        String digits = telephone == null ? "" : telephone.trim();
        if (digits.length() <= 3) {
            return "•••";
        }
        return "•••••••" + digits.substring(digits.length() - 3);
    }
}
