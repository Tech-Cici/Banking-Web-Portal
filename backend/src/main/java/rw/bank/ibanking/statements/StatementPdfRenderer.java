package rw.bank.ibanking.statements;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Draws a {@link StatementDocument} onto A4 pages.
 *
 * <p>IT DOES NO ARITHMETIC. Every figure arrives already formatted; this class decides where
 * text goes and nothing else. See {@link StatementDocument} for why that line is drawn here.
 *
 * <p>STANDARD-14 FONTS, SO NOTHING IS EMBEDDED. Helvetica is one of the fourteen fonts every
 * PDF reader must provide, which keeps the file small and removes a licence question about
 * shipping a typeface inside a document the customer will keep. The cost is the character set:
 * these fonts encode Latin-1, so {@link #safe} maps the typography that reaches a statement
 * description onto characters that exist and drops anything beyond them. Without that, one
 * unusual character in a reference throws IllegalArgumentException deep inside the content
 * stream and the customer gets a 500 instead of a statement.
 *
 * <p>PAGE NUMBERS ARE STAMPED AT THE END, in a second pass, because "page 2 of 7" cannot be
 * written while it is still possible that a page 8 will be needed.
 *
 * <p>NOT A SPRING BEAN. It holds a time zone and nothing else -- no repository, no session,
 * no configuration that can change under it -- so the service constructs one rather than
 * having it injected. A pure function object in the container is a thing to mock in a test
 * that would be better off just calling it.
 */
public class StatementPdfRenderer {

    /* A4 portrait, in points. */
    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 40f;

    private static final float TITLE_SIZE = 16f;
    private static final float HEADING_SIZE = 10.5f;
    private static final float BODY_SIZE = 8.5f;
    private static final float ROW_HEIGHT = 14f;

    /*
     * COLUMN WIDTHS, SUMMING TO THE USABLE WIDTH. A4 is 595.28pt wide and two 40pt margins
     * leave 515.28. These add to 515: date, description, the other party, out, in, balance.
     * Description takes the slack because it is the column that actually varies.
     */
    private static final float W_DATE = 58f;
    private static final float W_DESC = 175f;
    private static final float W_PARTY = 100f;
    private static final float W_OUT = 60f;
    private static final float W_IN = 60f;
    private static final float W_BALANCE = 62f;

    private static final PDType1Font REGULAR =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd MMM yyyy 'at' HH:mm", Locale.UK);

    private final ZoneId zone;

    public StatementPdfRenderer(ZoneId zone) {
        this.zone = zone;
    }

    /** The whole statement, as the bytes of a PDF file. */
    public byte[] render(StatementDocument statement) {
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Cursor cursor = new Cursor(pdf);
            cursor.newPage();
            drawHeader(cursor, statement);

            for (StatementDocument.Section section : statement.sections()) {
                drawSection(cursor, section);
            }

            if (statement.sections().isEmpty()) {
                cursor.ensure(ROW_HEIGHT * 2);
                cursor.text(
                        REGULAR,
                        BODY_SIZE,
                        MARGIN,
                        "There are no accounts to report on for this period.");
                cursor.down(ROW_HEIGHT);
            }

            cursor.close();
            stampPageNumbers(pdf, statement);

            pdf.save(out);
            return out.toByteArray();
        } catch (IOException failure) {
            /*
             * Nothing here touches a disk or a socket -- the document is assembled in memory
             * -- so an IOException is a bug in the drawing rather than an environment that
             * can be retried. Unchecked, so it surfaces as a 500 with a correlation id rather
             * than being caught and turned into an empty file the customer downloads.
             */
            throw new UncheckedIOException("The statement PDF could not be assembled.", failure);
        }
    }

    /* ----------------------------------------------------------------- the page furniture */

    private void drawHeader(Cursor cursor, StatementDocument statement) {
        cursor.text(BOLD, TITLE_SIZE, MARGIN, safe(statement.brandName()));
        cursor.down(TITLE_SIZE + 4f);

        cursor.text(BOLD, HEADING_SIZE, MARGIN, "Account statement");
        cursor.down(HEADING_SIZE + 6f);

        cursor.text(REGULAR, BODY_SIZE, MARGIN, safe(statement.customerName()));
        cursor.down(ROW_HEIGHT);

        cursor.text(
                REGULAR,
                BODY_SIZE,
                MARGIN,
                DAY.format(statement.from()) + " to " + DAY.format(statement.to()));
        cursor.down(ROW_HEIGHT);

        cursor.text(
                REGULAR,
                BODY_SIZE,
                MARGIN,
                "Generated " + STAMP.format(statement.generatedAt().atZone(zone)));
        cursor.down(ROW_HEIGHT + 6f);
    }

    private void drawSection(Cursor cursor, StatementDocument.Section section) {
        /*
         * A SECTION HEADING AND ITS TABLE HEADER ARE NOT SPLIT ACROSS A PAGE. Four rows' worth
         * of room is demanded before the heading is drawn, so a section never begins at the
         * foot of a page with its first movement stranded overleaf.
         */
        cursor.ensure(ROW_HEIGHT * 4);

        cursor.text(
                BOLD,
                HEADING_SIZE,
                MARGIN,
                safe(section.accountName() + "  " + section.maskedNumber() + "  (" + section.currency() + ")"));
        cursor.down(HEADING_SIZE + 4f);

        cursor.text(
                REGULAR,
                BODY_SIZE,
                MARGIN,
                "Opening balance " + section.openingBalance() + " " + section.currency());
        cursor.down(ROW_HEIGHT);

        drawTableHeader(cursor);

        if (section.lines().isEmpty()) {
            cursor.ensure(ROW_HEIGHT);
            cursor.text(REGULAR, BODY_SIZE, MARGIN, "No movements in this period.");
            cursor.down(ROW_HEIGHT);
        }

        for (StatementDocument.Line line : section.lines()) {
            if (cursor.wouldOverflow(ROW_HEIGHT)) {
                cursor.newPage();
                drawTableHeader(cursor);
            }
            drawLine(cursor, line);
        }

        cursor.down(4f);
        cursor.ensure(ROW_HEIGHT * 3);
        cursor.rule();
        cursor.down(ROW_HEIGHT);

        cursor.text(
                REGULAR,
                BODY_SIZE,
                MARGIN,
                "Paid in " + section.totalIn() + "    Paid out " + section.totalOut());
        cursor.down(ROW_HEIGHT);

        cursor.text(
                BOLD,
                BODY_SIZE,
                MARGIN,
                "Closing balance " + section.closingBalance() + " " + section.currency());
        cursor.down(ROW_HEIGHT * 2);
    }

    private void drawTableHeader(Cursor cursor) {
        float x = MARGIN;
        cursor.text(BOLD, BODY_SIZE, x, "Date");
        x += W_DATE;
        cursor.text(BOLD, BODY_SIZE, x, "Description");
        x += W_DESC;
        cursor.text(BOLD, BODY_SIZE, x, "Other party");
        x += W_PARTY;
        /* The same right edges the figures use below, or the headings sit off their column. */
        cursor.rightText(BOLD, BODY_SIZE, x + W_OUT - 6f, "Out");
        x += W_OUT;
        cursor.rightText(BOLD, BODY_SIZE, x + W_IN - 6f, "In");
        x += W_IN;
        cursor.rightText(BOLD, BODY_SIZE, x + W_BALANCE, "Balance");

        cursor.down(4f);
        cursor.rule();
        cursor.down(ROW_HEIGHT - 4f);
    }

    private void drawLine(Cursor cursor, StatementDocument.Line line) {
        float x = MARGIN;
        cursor.text(REGULAR, BODY_SIZE, x, DAY.format(line.date()));
        x += W_DATE;
        cursor.text(REGULAR, BODY_SIZE, x, fit(safe(line.description()), W_DESC - 6f));
        x += W_DESC;
        cursor.text(REGULAR, BODY_SIZE, x, fit(safe(line.counterparty()), W_PARTY - 6f));
        x += W_PARTY;
        cursor.rightText(REGULAR, BODY_SIZE, x + W_OUT - 6f, line.moneyOut());
        x += W_OUT;
        cursor.rightText(REGULAR, BODY_SIZE, x + W_IN - 6f, line.moneyIn());
        x += W_IN;
        cursor.rightText(REGULAR, BODY_SIZE, x + W_BALANCE, line.balanceAfter());
        cursor.down(ROW_HEIGHT);
    }

    /**
     * Writes "Page n of m" on every page, once the count is known.
     *
     * <p>ALSO THE LINE SAYING WHAT THIS DOCUMENT IS. A statement that can be downloaded and
     * forwarded should say on every page that it came from a demonstration portal, so that a
     * page separated from its context cannot be mistaken for a document a bank issued.
     */
    private void stampPageNumbers(PDDocument pdf, StatementDocument statement) throws IOException {
        int total = pdf.getNumberOfPages();
        for (int index = 0; index < total; index++) {
            PDPage page = pdf.getPage(index);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                String left = safe(statement.brandName()) + " - generated by the demonstration portal";
                String right = "Page " + (index + 1) + " of " + total;

                stream.beginText();
                stream.setFont(REGULAR, 7f);
                stream.newLineAtOffset(MARGIN, MARGIN - 14f);
                stream.showText(left);
                stream.endText();

                stream.beginText();
                stream.setFont(REGULAR, 7f);
                stream.newLineAtOffset(
                        PAGE.getWidth() - MARGIN - widthOf(REGULAR, 7f, right), MARGIN - 14f);
                stream.showText(right);
                stream.endText();
            }
        }
    }

    /* ------------------------------------------------------------------------- text helpers */

    private static float widthOf(PDType1Font font, float size, String text) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException failure) {
            throw new UncheckedIOException("A standard font could not measure its own text.", failure);
        }
    }

    /** Shortens with an ellipsis until it fits, so a long reference cannot run into a figure. */
    private static String fit(String text, float available) {
        if (text.isEmpty() || widthOf(REGULAR, BODY_SIZE, text) <= available) return text;

        String ellipsis = "...";
        StringBuilder shortened = new StringBuilder(text);
        while (shortened.length() > 1
                && widthOf(REGULAR, BODY_SIZE, shortened + ellipsis) > available) {
            shortened.setLength(shortened.length() - 1);
        }
        return shortened + ellipsis;
    }

    /**
     * Makes text the standard fonts can actually encode.
     *
     * <p>The typography that reaches a statement is mapped rather than stripped -- an em dash
     * becomes a hyphen, a curly quote becomes a straight one -- because those characters are
     * in descriptions this system writes itself ("Standing order - rent"). Latin-1 passes
     * through untouched, so accented names are unharmed. Anything beyond that becomes a
     * question mark, which is visibly wrong on the page rather than a failed download.
     */
    static String safe(String text) {
        if (text == null) return "";

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '—', '–', '−' -> out.append('-');
                case '‘', '’' -> out.append('\'');
                case '“', '”' -> out.append('"');
                case '…' -> out.append("...");
                case ' ' -> out.append(' ');
                case '\n', '\r', '\t' -> out.append(' ');
                default -> out.append(c <= 0xFF ? c : '?');
            }
        }
        return out.toString();
    }

    /* ------------------------------------------------------------------------------ cursor */

    /** Where the next line goes, and what to do when the page runs out. */
    private static final class Cursor {
        private final PDDocument pdf;
        private PDPageContentStream stream;
        private float y;

        Cursor(PDDocument pdf) {
            this.pdf = pdf;
        }

        void newPage() {
            close();
            PDPage page = new PDPage(PAGE);
            pdf.addPage(page);
            try {
                stream = new PDPageContentStream(pdf, page);
            } catch (IOException failure) {
                throw new UncheckedIOException("A statement page could not be started.", failure);
            }
            y = PAGE.getHeight() - MARGIN;
        }

        boolean wouldOverflow(float height) {
            return y - height < MARGIN;
        }

        /** Starts a new page if {@code height} would not fit on this one. */
        void ensure(float height) {
            if (wouldOverflow(height)) newPage();
        }

        void down(float amount) {
            y -= amount;
        }

        void text(PDType1Font font, float size, float x, String value) {
            if (value.isEmpty()) return;
            try {
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(x, y);
                stream.showText(value);
                stream.endText();
            } catch (IOException failure) {
                throw new UncheckedIOException("A statement line could not be written.", failure);
            }
        }

        /** Right-aligned at {@code rightEdge}, which is how money is read. */
        void rightText(PDType1Font font, float size, float rightEdge, String value) {
            if (value.isEmpty()) return;
            text(font, size, rightEdge - widthOf(font, size, value), value);
        }

        void rule() {
            try {
                stream.setLineWidth(0.4f);
                stream.moveTo(MARGIN, y);
                stream.lineTo(PAGE.getWidth() - MARGIN, y);
                stream.stroke();
            } catch (IOException failure) {
                throw new UncheckedIOException("A statement rule could not be drawn.", failure);
            }
        }

        void close() {
            if (stream == null) return;
            try {
                stream.close();
            } catch (IOException failure) {
                throw new UncheckedIOException("A statement page could not be finished.", failure);
            }
            stream = null;
        }
    }
}
