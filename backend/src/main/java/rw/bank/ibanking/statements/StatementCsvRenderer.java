package rw.bank.ibanking.statements;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * The same statement as rows a spreadsheet can open.
 *
 * <p>ONE FLAT TABLE WITH AN ACCOUNT COLUMN, rather than the PDF's sections. A spreadsheet
 * sorts and filters for itself, so repeating the account on every row is more useful than
 * grouping -- and a CSV with headings and blank lines between blocks is a CSV that no
 * spreadsheet imports cleanly.
 *
 * <p>ISO DATES, not the PDF's readable ones. A spreadsheet reading "01 Sept 2026" may or may
 * not recognise it as a date depending on its locale; "2026-09-01" is unambiguous everywhere
 * and sorts correctly even when it is read as text.
 *
 * <p>THE FIGURES ARRIVE UNGROUPED and this class does not touch them. The service builds the
 * document with a plain formatter when the customer asked for CSV and a grouped one when they
 * asked for PDF, so "3,056,912" never reaches here to be stripped back. Stripping it would
 * mean this class knowing how the service formats money, which is the second opinion about
 * money that {@link StatementDocument} exists to prevent. It matters: thousands separators
 * are the most reliable way to turn a number into text a spreadsheet will not add up, and the
 * grouping character is itself the decimal separator in half the world's locales.
 */
public class StatementCsvRenderer {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String HEADER =
            "Account,Account number,Currency,Date,Description,Other party,Money out,Money in,Balance";

    /** The whole statement, as the bytes of a CSV file. */
    public byte[] render(StatementDocument statement) {
        StringBuilder out = new StringBuilder();

        /*
         * A BYTE ORDER MARK, deliberately. Excel on Windows reads a BOM-less UTF-8 CSV as the
         * system code page, which turns every accented name in a Rwandan customer's statement
         * into mojibake. Everything else ignores it.
         */
        out.append('﻿').append(HEADER).append("\r\n");

        for (StatementDocument.Section section : statement.sections()) {
            for (StatementDocument.Line line : section.lines()) {
                out.append(quote(section.accountName())).append(',')
                        .append(quote(section.maskedNumber())).append(',')
                        .append(quote(section.currency())).append(',')
                        .append(ISO.format(line.date())).append(',')
                        .append(quote(line.description())).append(',')
                        .append(quote(line.counterparty())).append(',')
                        .append(line.moneyOut()).append(',')
                        .append(line.moneyIn()).append(',')
                        .append(line.balanceAfter())
                        .append("\r\n");
            }
        }

        /* CRLF line endings, which is what RFC 4180 specifies and what Excel expects. */
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * RFC 4180 quoting.
     *
     * <p>EVERY TEXT FIELD IS QUOTED, not only the ones that need it. A customer's reference is
     * free text and may contain a comma, a quote or a newline; deciding case by case means one
     * of those cases is eventually wrong, and a wrong one shifts every column after it.
     */
    private static String quote(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return '"' + safe + '"';
    }
}
