package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.AccountTransactionEntity;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.MovementKind;
import rw.bank.ibanking.onboarding.service.AccountLedgerService;
import rw.bank.ibanking.onboarding.service.OnboardingService;

/**
 * The signed-in customer's own accounts, their balances, and the money moving through them.
 *
 * <p>SCOPED TO THE CALLER, always. The customer id comes from the authenticated principal
 * and never from the request. An endpoint that accepted a customer id would let anyone
 * signed in read — or drain — anybody's account by changing a number, which is the most
 * ordinary and most serious mistake an API like this can make.
 *
 * <p>THE BALANCES ARE REAL NOW, and that is a change worth stating. This service used to
 * hold no money and said "Not available" where a balance would go, because the ledger was
 * core banking's. The bank has decided otherwise: an administrator enters an opening
 * balance when creating the login, and the customer deposits and withdraws here. Every one
 * of those movements is a row in account_transactions, and the balance is the sum of those
 * rows — see {@link AccountLedgerService}, which is the only code that moves one.
 *
 * <p>NOTHING HERE COMPUTES A BALANCE. The figures in every response below are read from
 * the account row after the service has posted the entry. The portal renders what it is
 * sent and does not add up transactions to check — a client-side total that disagrees with
 * the server is a second opinion about someone's money, and there is no good way for a
 * customer to tell which of the two is right.
 *
 * <p>MASKED NUMBERS ONLY. {@code CustomerAccountEntity} keeps the full number
 * package-private so that no response assembled here can carry it.
 */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountsController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AccountsController.class);

    /** A statement page nobody asked to be enormous. */
    private static final int MAX_PAGE_SIZE = 100;

    private final OnboardingService onboarding;
    private final AccountLedgerService ledger;

    AccountsController(OnboardingService onboarding, AccountLedgerService ledger) {
        this.onboarding = onboarding;
        this.ledger = ledger;
    }

    /**
     * Everything this customer can see.
     *
     * <p>{@code hasRole("CUSTOMER")} rather than {@code authenticated()}: a session holding
     * MUST_CHANGE_PASSWORD is authenticated, and it must not reach a customer's accounts
     * before the password bank staff issued has been replaced.
     */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<AccountView> myAccounts(Authentication caller) {
        UUID customerId = UUID.fromString(caller.getName());
        return onboarding.accountsFor(customerId).stream().map(AccountView::of).toList();
    }

    /**
     * One of the customer's accounts.
     *
     * <p>SCOPED THE SAME WAY, and the 404 is deliberate. Asking for an account that
     * belongs to somebody else must be indistinguishable from asking for one that does not
     * exist — "you may not see that" tells a stranger their guess was right.
     */
    @GetMapping("/{accountId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    AccountView oneAccount(@PathVariable UUID accountId, Authentication caller) {
        return AccountView.of(mine(accountId, caller));
    }

    /**
     * The customer's OWN full account number, so they can give it to somebody paying them.
     *
     * <p>ITS OWN ENDPOINT, and that is the point. The number is deliberately absent from
     * {@code GET /accounts} and from {@code GET /accounts/{id}}: a list response carrying
     * every number is one leak away from being every number, and a detail response
     * carrying it means the client holds it whether or not anybody asked. This is fetched
     * only when the customer asks to see it, so it appears in exactly one payload,
     * requested on purpose.
     *
     * <p>{@code mine(...)} is the control: it goes through {@code AccountAccess.mayAct},
     * the same check that decides whether money may move. A customer can read the number
     * of an account they could already empty.
     *
     * <p>WHY THIS EXISTS AT ALL. The portal masked the number everywhere, including from
     * its owner, while the transfer form told senders to type "the full number, not the
     * masked one". A customer could therefore not be paid: to receive money they had to
     * read out a number their own bank refused to show them. The mask protects logs,
     * lists and other people's screens; it was never meant to hide a passbook number
     * from the person whose passbook it is.
     */
    record AccountNumberView(String accountNumber) {}

    @GetMapping("/{accountId}/number")
    @PreAuthorize("hasRole('CUSTOMER')")
    AccountNumberView fullNumber(@PathVariable UUID accountId, Authentication caller) {
        CustomerAccountEntity account = mine(accountId, caller);

        /*
         * Logged as a MASK, and only that it happened. A log line carrying the full
         * number would recreate, in the one place this endpoint exists to avoid, exactly
         * what the mask was built to keep out of logs.
         */
        log.info("Customer revealed their own account number {}", account.maskedNumber());

        return new AccountNumberView(account.fullNumberForHolder());
    }

    /**
     * Just the balance, for a screen that polls it.
     *
     * <p>The portal's {@code accountService.balance} has always called this path; until
     * now there was no controller behind it, so a signed-in customer got the catch-all's
     * 404 and the panel rendered "we could not find that account" beside an account that
     * was plainly there.
     */
    @GetMapping("/{accountId}/balance")
    @PreAuthorize("hasRole('CUSTOMER')")
    BalanceView balance(@PathVariable UUID accountId, Authentication caller) {
        CustomerAccountEntity account = mine(accountId, caller);
        MoneyView balance = MoneyView.of(account);

        /*
         * AVAILABLE AND CURRENT ARE THE SAME FIGURE HERE, and they are sent as two fields
         * anyway because they are genuinely two things: current is what the account holds,
         * available is what the customer may spend. They differ the moment there are
         * pending authorisations, uncleared deposits or a hold — none of which this service
         * has. Collapsing them into one field now would mean changing the portal's contract
         * the day the bank introduces any of them.
         */
        return new BalanceView(balance, balance, account.balanceAsOf().toString());
    }

    /**
     * The account's statement, newest first.
     *
     * <p>Real entries, from the ledger this service now keeps. Page and size are honoured
     * loosely: the ledger is small per account, and a customer paging through their own
     * history is not a case worth an index-scan optimisation yet.
     */
    @GetMapping("/{accountId}/transactions")
    @PreAuthorize("hasRole('CUSTOMER')")
    TransactionPage transactions(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication caller) {

        // Confirms the account is this customer's, and 404s identically if it is not.
        mine(accountId, caller);

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);

        List<AccountTransactionEntity> all = ledger.statement(accountId, MAX_PAGE_SIZE);

        int from = Math.min(pageNumber * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());

        return new TransactionPage(
                all.subList(from, to).stream().map(TransactionView::of).toList(),
                pageNumber,
                pageSize,
                all.size(),
                (all.size() + pageSize - 1) / pageSize);
    }

    /* --------------------------------------------------------- moving money */

    /**
     * Money in.
     *
     * <p>THE IDEMPOTENCY KEY IS REQUIRED, not optional with a generated fallback. A
     * fallback would defeat the whole mechanism: the retry after a lost response would
     * arrive with a fresh key and post the money a second time, which is precisely the
     * failure the key exists to prevent. A client that does not send one is a client with
     * that bug, and it is better to refuse it here than to double a customer's deposit.
     */
    @PostMapping("/{accountId}/deposit")
    @PreAuthorize("hasRole('CUSTOMER')")
    MovementReceipt deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody MovementRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String idempotencyKey,
            Authentication caller) {

        UUID customerId = UUID.fromString(caller.getName());

        AccountTransactionEntity entry =
                ledger.deposit(
                        customerId,
                        accountId,
                        request.amount(),
                        request.descriptionOrDefault("Deposit"),
                        customerId.toString(),
                        idempotencyKey);

        return MovementReceipt.of(entry);
    }

    /**
     * Money out.
     *
     * <p>Refused if it would take the account below zero — there is no overdraft product,
     * so an account that could go negative would be lending money nobody approved. The
     * check is in the entity and again as a CHECK constraint on the column; neither is in
     * the browser, where it would be a suggestion rather than a rule.
     */
    @PostMapping("/{accountId}/withdraw")
    @PreAuthorize("hasRole('CUSTOMER')")
    MovementReceipt withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody MovementRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String idempotencyKey,
            Authentication caller) {

        UUID customerId = UUID.fromString(caller.getName());

        AccountTransactionEntity entry =
                ledger.withdraw(
                        customerId,
                        accountId,
                        request.amount(),
                        request.descriptionOrDefault("Withdrawal"),
                        customerId.toString(),
                        idempotencyKey);

        return MovementReceipt.of(entry);
    }

    /* ------------------------------------------------------------- internals */

    /**
     * The account, if it is this caller's.
     *
     * <p>Reached through {@code accountsFor(customerId)} rather than by loading the account
     * and comparing — the scoping is then a property of the query rather than of a check
     * somebody could omit in a later method.
     */
    private CustomerAccountEntity mine(UUID accountId, Authentication caller) {
        UUID customerId = UUID.fromString(caller.getName());

        return onboarding.accountsFor(customerId).stream()
                .filter(account -> account.id().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("We could not find that account."));
    }

    /* ---------------------------------------------------------------- shapes */

    /**
     * A deposit or a withdrawal, as the customer submitted it.
     *
     * <p>THE AMOUNT IS A STRING and the currency is not in it. JSON numbers go through a
     * binary floating-point type in most clients, so "1234.30" can arrive as
     * 1234.2999999999999 — a rounding nobody chose. And the currency is the ACCOUNT's:
     * accepting one from the client would let a request in USD move money in an RWF
     * account at an exchange rate nobody applied.
     */
    record MovementRequest(
            @NotBlank(message = "Enter an amount.")
                    @Pattern(
                            regexp = "\\d{1,15}(\\.\\d{1,2})?",
                            message = "Enter an amount such as 5000 or 5000.75.")
                    String amount,
            @Size(max = 140, message = "That note is too long.") String description) {

        /*
         * The shape check above admits two decimals so an obvious typo gets a friendly
         * message. Whether THIS currency allows decimals at all is decided by Money.parse
         * against the account's own currency — RWF has no minor unit, so "500.50" is
         * refused there even though it passes here. One authority for scale: the currency.
         */

        String descriptionOrDefault(String fallback) {
            return description == null || description.isBlank() ? fallback : description.trim();
        }
    }

    /** What happened, and what the balance is now. */
    record MovementReceipt(
            String id,
            String accountId,
            String direction,
            MoneyView amount,
            MoneyView balanceAfter,
            String description,
            String bookedAt,
            /*
             * COMPLETED, and it is true rather than convenient. The entry and the balance
             * committed in one database transaction before this response was assembled; if
             * that had failed the customer would have an error, not this. A status is only
             * worth sending when it can also say PENDING, and here it genuinely cannot.
             */
            String status) {

        static MovementReceipt of(AccountTransactionEntity e) {
            return new MovementReceipt(
                    e.id().toString(),
                    e.accountId().toString(),
                    e.direction().name(),
                    new MoneyView(e.amount().toPlainString(), e.currency()),
                    new MoneyView(e.balanceAfter().toPlainString(), e.currency()),
                    e.description(),
                    e.createdAt().toString(),
                    "COMPLETED");
        }
    }

    /** The portal's {@code MoneyDto}: a decimal STRING and a currency, never a number. */
    record MoneyView(String amount, String currency) {

        static MoneyView of(CustomerAccountEntity account) {
            return new MoneyView(
                    account.balance().toPlainString(), account.balance().currency());
        }
    }

    /** The portal's balance envelope. */
    record BalanceView(MoneyView availableBalance, MoneyView currentBalance, String asOf) {}

    /** The portal's page envelope. */
    record TransactionPage(
            List<TransactionView> content,
            int page,
            int size,
            int totalElements,
            int totalPages) {}

    /** One statement line. */
    record TransactionView(
            String id,
            String accountId,
            String bookedAt,
            String description,
            String reference,
            String direction,
            MoneyView amount,
            MoneyView runningBalance,
            String status,
            String category,
            /**
             * Who the money came from or went to, or null when there was nobody.
             *
             * <p>Null for a deposit, a withdrawal or an opening balance — those are the
             * customer and the bank. The client must therefore treat it as absent rather
             * than as an empty string, which Jackson's non_null inclusion makes
             * unavoidable anyway: the field is simply missing from those rows.
             */
            String counterpartyName,
            String counterpartyMask,
            /**
             * What the movement was: CASH, TRANSFER_OUT, TRANSFER_IN or
             * TRANSFER_RETURNED.
             *
             * <p>THE CLIENT WORDS THE LINE FROM THIS, not from the direction. A transfer
             * arriving and a transfer being refused are both a credit with somebody
             * else's name on them, so a screen wording it from the direction alone tells
             * a customer whose payment just failed that they received money.
             */
            String movementKind) {

        static TransactionView of(AccountTransactionEntity e) {
            return new TransactionView(
                    e.id().toString(),
                    e.accountId().toString(),
                    e.createdAt().toString(),
                    e.description(),
                    /*
                     * The reference a customer would quote. The entry's own id, shortened —
                     * NOT the idempotency key, which is the client's and would let anyone
                     * who saw a receipt replay that submission.
                     */
                    e.id().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                    e.direction().name(),
                    new MoneyView(e.amount().toPlainString(), e.currency()),
                    new MoneyView(e.balanceAfter().toPlainString(), e.currency()),
                    "COMPLETED",
                    /*
                     * CASH OR TRANSFER, decided by whether there was another party.
                     *
                     * This used to be the constant "CASH", with a comment explaining
                     * that transfers were a movement the service could not make. It can
                     * now: a transfer posts a debit on one account and a credit on
                     * another, and calling those cash was the comment going stale rather
                     * than the rule changing. The remaining categories the portal knows
                     * about — bill, card — stay unused, because those movements still do
                     * not exist and labelling an entry with one would be a guess printed
                     * on a statement.
                     */
                    e.movementKind() == MovementKind.CASH ? "CASH" : "TRANSFER",
                    e.counterpartyName(),
                    e.counterpartyMask(),
                    e.movementKind().name());
        }
    }

    /**
     * One account, as the customer sees it.
     */
    record AccountView(
            String id,
            String nickname,
            String accountType,
            String maskedNumber,
            String currency,
            String status,
            MoneyView availableBalance,
            MoneyView currentBalance,
            String balanceAsOf,
            boolean debitAllowed) {

        static AccountView of(CustomerAccountEntity account) {
            MoneyView balance = MoneyView.of(account);

            return new AccountView(
                    account.id().toString(),
                    nicknameFor(account),
                    account.accountType().name(),
                    account.maskedNumber(),
                    account.currency(),
                    /*
                     * ACTIVE for every account here. The bank's own account statuses —
                     * dormant, blocked, closed — live in core banking, and guessing at one
                     * would tell a customer something about their money that nothing here
                     * knows. A frozen LOGIN is a separate thing and is handled by the
                     * customer's status, not this field.
                     */
                    "ACTIVE",
                    balance,
                    balance,
                    account.balanceAsOf().toString(),
                    /*
                     * WAS MISSING ENTIRELY, and silently. The portal's Account type
                     * requires this field; the response did not carry it, so it arrived
                     * undefined and every screen that guards a debit on it read false. The
                     * customer saw accounts they could not pay from and no reason given.
                     */
                    account.isActive());
        }

        /** A readable name, derived rather than stored — there is nothing to store yet. */
        private static String nicknameFor(CustomerAccountEntity account) {
            String words =
                    account.accountType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            return Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }
    }
}
