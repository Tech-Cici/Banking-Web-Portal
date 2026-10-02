package rw.bank.ibanking.onboarding.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.onboarding.domain.CompanyEntity;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.repo.CompanyRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;

/**
 * WHO HOLDS AN ACCOUNT AT THIS BANK.
 *
 * <p>WHY IT IS ITS OWN CLASS. Turning an account into the name of the person or company
 * behind it was a private helper inside {@code TransferService}, written for one purpose —
 * labelling the two sides of a movement on a statement. The payee review queue needs the
 * same answer for a different purpose, and the alternative to extracting it was a second
 * copy that would drift: the first time a company account's naming rule changed, one
 * caller would follow it and the other would not, and the disagreement would show up as
 * two screens naming the same account differently.
 *
 * <p>IT IS NOT RATE LIMITED, AND THAT IS DELIBERATE RATHER THAN FORGOTTEN. {@code
 * TransferService.resolve} answers "who holds this number" for a SIGNED-IN CUSTOMER who
 * typed it, and is capped by {@link BeneficiaryLookups} at twenty an hour, because the same
 * answer asked at scale turns a list of account numbers into a list of the people behind
 * them. Nothing here is driven by an untrusted caller choosing a number:
 *
 * <ul>
 *   <li>the statement labelling asks about accounts already party to a movement this bank
 *       recorded;
 *   <li>the payee queue asks about numbers a customer ALREADY saved and a member of bank
 *       staff is now reviewing — the staff member picks nothing, the queue hands them the
 *       rows, and the customer's own saving of that payee was already counted.
 * </ul>
 *
 * <p>So a limit here would restrict the bank's own staff from reviewing their own queue
 * while stopping no harvest. Any FUTURE caller that lets an untrusted party choose the
 * number must go through {@code TransferService.resolve} instead — that is the whole
 * reason this class does not expose a method taking a number from a request.
 */
@Service
public class AccountHolders {

    private final CustomerAccountRepository accounts;
    private final CustomerRepository customers;
    private final CompanyRepository companies;

    AccountHolders(
            CustomerAccountRepository accounts,
            CustomerRepository customers,
            CompanyRepository companies) {
        this.accounts = accounts;
        this.customers = customers;
        this.companies = companies;
    }

    /**
     * The name on an account, personal or company.
     *
     * <p>Falls back to a generic phrase rather than throwing: a movement whose counterparty
     * row has gone still has to render on a statement, and "An account at Zigama CSS" is a
     * better answer there than an error page. Callers that need to know whether a name was
     * really found use {@link #nameOfAccountNumber}, which returns an empty optional.
     */
    @Transactional(readOnly = true)
    public String nameOf(CustomerAccountEntity account) {
        if (account.isCompanyAccount()) {
            return companies
                    .findById(account.companyId())
                    .map(CompanyEntity::name)
                    .orElse("A company account");
        }
        return customers
                .findById(account.customerId())
                .map(CustomerEntity::fullName)
                .orElse("An account at Zigama CSS");
    }

    /** The name on an account id, for labelling a movement whose counterparty may be gone. */
    @Transactional(readOnly = true)
    public String nameOfAccountId(UUID accountId) {
        return accounts
                .findById(accountId)
                .map(this::nameOf)
                .orElse("An account at this bank");
    }

    /**
     * One customer's own live accounts.
     *
     * <p>Here rather than in the payee service because it is the same question this class
     * already answers from the other direction, and because the payee service has no other
     * reason to hold an account repository. Its one caller needs it to refuse a customer
     * saving their OWN account as a payee — which would put the customer's own account into
     * a staff queue to be approved, for a transfer that has its own screen and needs no
     * payee at all.
     */
    @Transactional(readOnly = true)
    public List<CustomerAccountEntity> accountsOf(UUID customerId) {
        return accounts.findByCustomerIdAndRemovedAtIsNullOrderByAssignedAtAsc(customerId);
    }

    /**
     * The name held for a full account number, or empty if this bank holds no such account.
     *
     * <p>EMPTY IS A DIFFERENT ANSWER FROM A NAME, and the payee queue shows the difference.
     * A reviewer told "no match" has learned something; a reviewer shown a blank where the
     * comparison should be has learned nothing and will read it as a pass. So this returns
     * an optional and the caller is forced to say which it got.
     *
     * <p>Masks are not accepted and could not be: they are not unique, so a mask names
     * several accounts and this would be choosing which holder to report.
     */
    @Transactional(readOnly = true)
    public Optional<String> nameOfAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) return Optional.empty();

        return accounts.findByAccountNumberAndRemovedAtIsNull(accountNumber.trim()).stream()
                .findFirst()
                .map(this::nameOf);
    }
}
