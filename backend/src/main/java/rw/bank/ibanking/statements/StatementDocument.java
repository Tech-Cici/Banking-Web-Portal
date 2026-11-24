package rw.bank.ibanking.statements;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything a statement says, with nothing left to work out.
 *
 * <p>EVERY FIGURE ARRIVES AS A STRING, already formatted. The renderers do not add, subtract,
 * round, or parse anything. That is the point of this type rather than an accident of it: a
 * renderer that could do arithmetic is a second place where a balance is decided, and the
 * second place is always the one that disagrees with the ledger. The service builds these
 * strings from {@code Money}, which knows each currency's scale; by the time a document gets
 * here the numbers are text and the only remaining question is where on the page they go.
 *
 * <p>NO ENTITIES. A renderer holding a JPA entity can lazy-load inside a loop, which turns a
 * statement with four hundred lines into four hundred queries, and it can only be tested with
 * a database behind it. This record is built once and read once.
 *
 * <p>ONE SECTION PER ACCOUNT, because a customer holding accounts in RWF, USD and GBP has no
 * single running balance. Adding a dollar to a franc to make one column would be inventing an
 * exchange rate, which this portal does not have and must not guess.
 */
public record StatementDocument(
        String brandName,
        String customerName,
        LocalDate from,
        LocalDate to,
        Instant generatedAt,
        List<Section> sections) {

    /**
     * One account's movements over the period.
     *
     * <p>THE OPENING BALANCE IS THE CLOSING BALANCE OF THE ENTRY BEFORE THE PERIOD, read from
     * that entry's own {@code balance_after} column rather than recomputed. An account with no
     * entry before the period opens at zero, which is the truth for an account whose first
     * movement is inside the range.
     */
    public record Section(
            String accountName,
            String maskedNumber,
            String currency,
            String openingBalance,
            String closingBalance,
            String totalIn,
            String totalOut,
            List<Line> lines) {}

    /**
     * One movement.
     *
     * <p>{@code moneyIn} AND {@code moneyOut} ARE EXCLUSIVE and the empty one is blank, not
     * "0.00". A zero in a column a customer reads as "money left this account" is a movement
     * that did not happen.
     */
    public record Line(
            LocalDate date,
            String description,
            String counterparty,
            String moneyIn,
            String moneyOut,
            String balanceAfter) {}
}
