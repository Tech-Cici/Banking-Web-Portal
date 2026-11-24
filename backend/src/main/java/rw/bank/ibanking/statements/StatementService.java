package rw.bank.ibanking.statements;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.common.time.BankZone;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.AccountTransactionEntity;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.TransactionDirection;
import rw.bank.ibanking.onboarding.repo.AccountTransactionRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.service.AccountAccess;
import rw.bank.ibanking.onboarding.service.AccountHolders;

/**
 * Produces a statement, and remembers that one was produced.
 *
 * <p>SYNCHRONOUS. The customer presses the button and the file comes back; there is no job,
 * no queue and no polling. At this bank's size a statement is a few hundred rows read from an
 * indexed table, and the machinery to do that in the background would cost more to maintain
 * than the wait it saves.
 *
 * <p>THE DOCUMENT IS NOT STORED, only the fact of it. Re-downloading renders again from the
 * ledger, which is append-only, so the same period produces the same document. V28 argues it
 * at length.
 *
 * <p>MONEY IS FORMATTED ONCE, HERE, and never again downstream. The renderers are handed
 * strings. Which strings depends on where they are going: grouped for a person reading a PDF,
 * plain for a spreadsheet that has to add them up.
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    /**
     * How many movements one statement may carry.
     *
     * <p>A BOUND ON MEMORY, not on how far back a customer may look. Everything is assembled
     * in memory -- the rows, the document, the rendered bytes -- so an unbounded period is an
     * unbounded allocation on a shared server. A row limit refuses the one request that would
     * hurt and leaves every reasonable one alone, where a date limit would refuse a perfectly
     * small ten-year statement from a quiet account.
     */
    private static final int MAX_LINES = 5_000;

    private final StatementRepository statements;
    private final AccountTransactionRepository transactions;
    private final CustomerAccountRepository accounts;
    private final AccountAccess access;
    private final AccountHolders holders;
    private final StatementProperties properties;

    private final StatementPdfRenderer pdf = new StatementPdfRenderer(BankZone.ID);
    private final StatementCsvRenderer csv = new StatementCsvRenderer();

    StatementService(
            StatementRepository statements,
            AccountTransactionRepository transactions,
            CustomerAccountRepository accounts,
            AccountAccess access,
            AccountHolders holders,
            StatementProperties properties) {
        this.statements = statements;
        this.transactions = transactions;
        this.accounts = accounts;
        this.access = access;
        this.holders = holders;
        this.properties = properties;
    }

    /** A finished document, and what to call it. */
    public record Rendered(byte[] content, String filename, StatementFormat format) {}

    /* ========================================================= what the customer asks for */

    @Transactional(readOnly = true)
    public List<StatementEntity> forCustomer(UUID customerId) {
        return statements.findByCustomerIdOrderByRequestedAtDesc(customerId);
    }

    /**
     * Produces a statement and records that it was produced.
     *
     * <p>THE ROW IS WRITTEN AFTER THE RENDER, never before. A row that exists because somebody
     * asked, rather than because a document came out, is a list entry that offers a download
     * which fails.
     */
    @Transactional
    public Rendered produce(
            UUID customerId,
            UUID accountId,
            LocalDate from,
            LocalDate to,
            StatementFormat format) {

        refuseAnImpossiblePeriod(from, to);

        List<CustomerAccountEntity> covered = coveredAccounts(customerId, accountId);
        StatementDocument document = build(customerId, covered, from, to, format);

        byte[] content = format == StatementFormat.PDF ? pdf.render(document) : csv.render(document);

        StatementEntity record =
                statements.save(
                        StatementEntity.produced(
                                customerId, accountId, from, to, format, content.length));

        log.info(
                "Statement {} produced for customer {}: {} to {}, {} account(s), {} bytes",
                record.id(),
                customerId,
                from,
                to,
                covered.size(),
                content.length);

        return new Rendered(content, filenameFor(record), format);
    }

    /**
     * The same statement again, rendered from its recorded terms.
     *
     * <p>NOT A STORED COPY. It re-reads the ledger, so a statement taken before a movement was
     * reversed and downloaded again afterwards shows the reversal. That is the honest answer:
     * the ledger is the record, and a frozen copy that disagrees with it would be the thing
     * worth worrying about.
     */
    @Transactional(readOnly = true)
    public Rendered reproduce(UUID customerId, UUID statementId) {
        StatementEntity record =
                statements
                        .findById(statementId)
                        .filter(row -> row.customerId().equals(customerId))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that statement."));

        List<CustomerAccountEntity> covered =
                coveredAccounts(customerId, record.accountId().orElse(null));

        StatementDocument document =
                build(customerId, covered, record.periodFrom(), record.periodTo(), record.format());

        byte[] content =
                record.format() == StatementFormat.PDF ? pdf.render(document) : csv.render(document);

        return new Rendered(content, filenameFor(record), record.format());
    }

    /* ============================================================== assembling the document */

    private StatementDocument build(
            UUID customerId,
            List<CustomerAccountEntity> covered,
            LocalDate from,
            LocalDate to,
            StatementFormat format) {

        /*
         * THE PERIOD IS INCLUSIVE AT BOTH ENDS, in the bank's own zone. "to" becomes the first
         * instant of the following day, so a movement at 23:30 on the last day is inside the
         * statement. Using the start of "to" instead would silently drop a whole final day,
         * and it would do it quietly enough that nobody would notice until a customer did.
         */
        Instant start = BankZone.startOfDay(from);
        Instant end = BankZone.startOfDay(to.plusDays(1));

        Function<Money, String> amount =
                format == StatementFormat.PDF ? StatementService::grouped : Money::toPlainString;

        /*
         * COUNTED BEFORE ANYTHING IS READ. This used to add up the rows as it loaded them and
         * throw when the total went over, which is a limit that does its allocating first and
         * complains afterwards -- precisely the memory it exists to protect. Counting is one
         * indexed scan per account and returns a number.
         */
        long total = 0;
        for (CustomerAccountEntity account : covered) {
            total += transactions.countByAccountIdAndCreatedAtBetween(account.id(), start, end);
        }

        if (total > MAX_LINES) {
            /*
             * THE REAL NUMBER IS IN THE MESSAGE. "More than 5000" leaves somebody unable to
             * tell whether halving the period will help or whether they are an order of
             * magnitude out, so the advice that follows it is a guess they have to test.
             */
            throw new BusinessRuleException(
                    "That period covers "
                            + total
                            + " movements across "
                            + (covered.size() == 1 ? "that account" : covered.size() + " accounts")
                            + ", and one statement can carry "
                            + MAX_LINES
                            + ". Choose a shorter period, or a single account.");
        }

        List<StatementDocument.Section> sections = new ArrayList<>();

        for (CustomerAccountEntity account : covered) {
            List<AccountTransactionEntity> rows =
                    transactions.findByAccountIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                            account.id(), start, end);

            sections.add(sectionFor(account, rows, start, amount));
        }

        return new StatementDocument(
                properties.brandName(),
                nameOf(customerId, covered),
                from,
                to,
                Instant.now(),
                List.copyOf(sections));
    }

    private StatementDocument.Section sectionFor(
            CustomerAccountEntity account,
            List<AccountTransactionEntity> rows,
            Instant start,
            Function<Money, String> amount) {

        /*
         * THE OPENING BALANCE IS A READ, NOT A SUM. It is the balance_after already recorded
         * on the last entry before the period. An account with no entry before it opens at
         * zero, which is the truth: the ledger carries the opening balance an administrator
         * entered as its own entry, so there is no money that predates the first row.
         */
        Money opening =
                transactions
                        .findFirstByAccountIdAndCreatedAtLessThanOrderByCreatedAtDesc(
                                account.id(), start)
                        .map(AccountTransactionEntity::balanceAfter)
                        .orElse(new Money(0L, account.currency()));

        Money totalIn = new Money(0L, account.currency());
        Money totalOut = new Money(0L, account.currency());

        List<StatementDocument.Line> lines = new ArrayList<>(rows.size());
        for (AccountTransactionEntity row : rows) {
            boolean credit = row.direction() == TransactionDirection.CREDIT;
            if (credit) {
                totalIn = totalIn.plus(row.amount());
            } else {
                totalOut = totalOut.plus(row.amount());
            }

            lines.add(
                    new StatementDocument.Line(
                            BankZone.dateOf(row.createdAt()),
                            row.description(),
                            row.hasCounterparty() ? row.counterpartyName() : "",
                            credit ? amount.apply(row.amount()) : "",
                            credit ? "" : amount.apply(row.amount()),
                            amount.apply(row.balanceAfter())));
        }

        /*
         * THE CLOSING BALANCE IS THE LAST ENTRY'S OWN balance_after, not opening plus the
         * totals. Both should agree; if they ever did not, the ledger is right and the
         * addition is wrong, so the statement quotes the ledger.
         */
        Money closing = rows.isEmpty() ? opening : rows.get(rows.size() - 1).balanceAfter();

        return new StatementDocument.Section(
                account.accountType().label(),
                account.maskedNumber(),
                account.currency(),
                amount.apply(opening),
                amount.apply(closing),
                amount.apply(totalIn),
                amount.apply(totalOut),
                List.copyOf(lines));
    }

    /* ============================================================================== plumbing */

    /**
     * The accounts a statement covers: one the customer holds, or all of them.
     *
     * <p>OWNERSHIP IS CHECKED HERE even for the single-account case, because an account id
     * arriving in a request is a number from a browser until something says otherwise.
     */
    private List<CustomerAccountEntity> coveredAccounts(UUID customerId, UUID accountId) {
        if (accountId == null) return holders.accountsOf(customerId);

        CustomerAccountEntity account =
                accounts.findById(accountId)
                        .filter(row -> access.mayAct(customerId, row))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that account on your profile."));

        return List.of(account);
    }

    private void refuseAnImpossiblePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessRuleException("Choose the dates this statement should cover.");
        }
        if (to.isBefore(from)) {
            throw new BusinessRuleException("The end of the period is before the start.");
        }
        /*
         * A STATEMENT OF THE FUTURE IS AN EMPTY DOCUMENT WITH A CONFIDENT HEADING. Refusing is
         * kinder than handing somebody a page that says nothing happened in a month that has
         * not happened.
         */
        if (from.isAfter(BankZone.today())) {
            throw new BusinessRuleException("That period has not started yet.");
        }
    }

    /** The holder's name, which is the same person for every account in the statement. */
    private String nameOf(UUID customerId, List<CustomerAccountEntity> covered) {
        return covered.stream()
                .findFirst()
                .map(holders::nameOf)
                .orElseGet(() -> holders.accountsOf(customerId).stream()
                        .findFirst()
                        .map(holders::nameOf)
                        .orElse("Customer"));
    }

    /**
     * A filename a customer can find again in a downloads folder.
     *
     * <p>NO ACCOUNT NUMBER IN IT, not even masked. A filename survives being forwarded, sits
     * in backup indexes and shows up in screen shares; the period and the format are enough to
     * tell two statements apart.
     */
    private static String filenameFor(StatementEntity record) {
        return "statement-"
                + record.periodFrom()
                + "-to-"
                + record.periodTo()
                + "."
                + record.format().extension();
    }

    /**
     * A figure with thousands separators, at the currency's own scale.
     *
     * <p>THE LOCALE IS NAMED. Left to the default, the grouping and decimal characters would
     * follow whatever locale the server happens to boot with, so the same statement would read
     * "3,056,912" on one host and "3.056.912" on another.
     *
     * <p>BIGDECIMAL ALL THE WAY THROUGH. DecimalFormat keeps a BigDecimal exact; handing it a
     * double would round somebody's balance on the way to the page.
     */
    private static String grouped(Money money) {
        int scale = Money.scaleOf(money.currency());

        DecimalFormat format = new DecimalFormat();
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.UK));
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(scale);
        format.setMaximumFractionDigits(scale);

        return format.format(new BigDecimal(money.toPlainString()));
    }
}
