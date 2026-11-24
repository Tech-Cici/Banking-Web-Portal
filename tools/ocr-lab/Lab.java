import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * A scratchpad for working out how to read Rwandan collateral documents.
 *
 * IT IS NOT THE BANK'S READER. The portal has its own, in backend/.../collateral, and this
 * file deliberately holds a SECOND COPY of the patterns. That is the one thing a sandbox may
 * do that production may not, and it is only worth it for what it buys: this can read the
 * same page eight ways and show which wins, where the real reader must pick one and live
 * with it.
 *
 * SO FINDINGS HERE ARE NOT CHANGES THERE. Anything learned has to be carried across by hand
 * into FieldReader and TesseractReader, and until it is, the two disagree.
 *
 * WHAT IS NOT A SECOND COPY: how a PDF becomes pixels. That is PDFBox at 300 DPI in grey,
 * borrowed from the backend's own dependencies, because how a page is rasterised decides
 * most of what any reader can see -- and tuning a conversion that never runs in production
 * is the exact failure a sandbox is supposed to prevent.
 */
public final class Lab {

    /** What the portal renders PDF pages at. Matching it is the point. */
    static final float PDF_DPI = 300f;

    /** Preprocessing attempts for the sweep, in the order they are tried. */
    enum Prep {
        AS_IS("as rendered", 1, false),
        GREY("grayscale", 1, false),
        GREY_2X("grayscale, doubled", 2, false),
        GREY_2X_SHARP("grayscale, doubled, bicubic", 2, true),
        GREY_3X("grayscale, tripled", 3, false);

        final String label;
        final int scale;
        final boolean smooth;

        Prep(String label, int scale, boolean smooth) {
            this.label = label;
            this.scale = scale;
            this.smooth = smooth;
        }
    }

    /** 6 treats the page as one block; 3 detects the layout. The portal runs both. */
    static final String[] MODES = {"6", "3"};

    /* ------------------------------------------------------- what we know how to read */

    /**
     * One thing worth lifting off a page.
     *
     * @param label what to call it on screen
     * @param keywords the words that introduce it, Kinyarwanda first because the documents are
     * @param pattern its shape, when it has one. Null means the value is simply whatever
     *     follows the label, which is how names and places are found.
     */
    record Field(String label, List<String> keywords, Pattern pattern) {
        static Field of(String label, String... keywords) {
            return new Field(label, List.of(keywords), null);
        }

        static Field shaped(String label, Pattern pattern, String... keywords) {
            return new Field(label, List.of(keywords), pattern);
        }
    }

    /**
     * A kind of document, the words that give it away, and the questions worth asking of it.
     *
     * <p>THE QUESTIONS BELONG TO THE DOCUMENT. A land title has a parcel number and no plate;
     * a payslip has a net figure and no chassis. Asking every page every question produces a
     * column of "not found" that tells you nothing, and now and then a confident wrong answer
     * -- a national ID number matched as a company code, which is what this replaced.
     *
     * <p>MARKERS ARE NOT FIELDS. They answer only "what is this", so they are cheap words that
     * survive a bad photograph. The fields are the careful part.
     */
    record DocType(String code, String label, List<String> markers, List<Field> fields) {}

    private static final Pattern UPI_P =
            Pattern.compile("\\b\\d{1,2}(?:\\s?/\\s?\\d{1,5}){3,5}\\b");
    private static final Pattern PLATE_P = Pattern.compile("\\bR[A-Z]{2}\\s?\\d{3}\\s?[A-Z]\\b");
    private static final Pattern CHASSIS_P = Pattern.compile("\\b[A-Z0-9]{17}\\b");
    private static final Pattern ID16_P = Pattern.compile("\\b\\d{16}\\b|\\b\\d{4}(?:\\s\\d{4}){3}\\b");
    private static final Pattern TIN_P = Pattern.compile("\\b\\d{9}\\b");
    private static final Pattern MONEY_P = Pattern.compile(
            "\\b(?:RWF|FRW|USD|EUR)\\s?[\\d][\\d.,]{2,}|\\b[\\d][\\d.,]{3,}\\s?(?:RWF|FRW|USD|EUR)\\b");
    private static final Pattern DATE_P = Pattern.compile(
            "\\b\\d{1,2}[-/\\s](?:\\d{1,2}|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*"
                    + "[-/\\s]\\d{2,4}\\b");
    private static final Pattern EMAIL_P = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}");
    /*
     * A RWANDAN MOBILE IS TEN DIGITS: 07 and eight more. The first version of this asked for
     * seven more and so matched nothing at all -- 0788 456 123 has two digits after the 07
     * before the first space, not one.
     */
    private static final Pattern PHONE_P = Pattern.compile(
            "\\b(?:\\+?250[\\s-]?)?07\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{3}\\b");

    /**
     * Everything this reader knows how to recognise.
     *
     * <p>THE COUNT OF MARKERS DECIDES THE WINNER, not the order here. A land lease is also an
     * agreement; it wins because it carries seven land words against one contract word.
     */
    static final List<DocType> TYPES = List.of(
            new DocType("LAND", "Land title or lease",
                    List.of("ubutaka", "ubukode", "ikibanza", "upi", "umubitsi", "ubuso",
                            "parcel", "impapurompamo", "land lease"),
                    List.of(
                            Field.shaped("Parcel identifier (UPI)", UPI_P, "upi"),
                            Field.of("Parcel number", "ikibanza"),
                            Field.of("Area", "ubuso", "superficie"),
                            Field.of("Holder", "amazina", "ukodesha", "nyir", "owner", "titulaire"),
                            Field.shaped("Holder ID number", ID16_P, "indangamuntu"),
                            Field.of("Province", "intara", "province"),
                            Field.of("District", "akarere", "district"),
                            Field.of("Sector", "umurenge", "sector"),
                            Field.of("Cell", "akagari", "akagali", "cell"),
                            Field.of("Village", "umudugudu", "village"),
                            Field.of("Granted", "bwatangiriyeho", "issued"),
                            Field.of("Expires", "bwarangiriraho", "expiry"))),

            new DocType("VEHICLE", "Vehicle registration",
                    List.of("ikinyabiziga", "ibinyabiziga", "chassis", "plaque", "immatricul",
                            "moteur", "vehicle registration", "plate number"),
                    List.of(
                            Field.shaped("Plate number", PLATE_P, "plate", "plaque", "immatricul"),
                            Field.shaped("Chassis number", CHASSIS_P, "chassis", "vin"),
                            Field.of("Make and model", "marque", "make", "model"),
                            Field.of("Year", "year of manufacture", "year", "annee"),
                            Field.of("Engine", "moteur", "engine"),
                            Field.of("Owner", "nyir", "owner", "titulaire"),
                            Field.of("Registered on", "date of registration", "registered"))),

            new DocType("BUSINESS", "Company registration certificate",
                    List.of("ubucuruzi", "registrar general", "registrar", "incorporat",
                            "domestic company", "shareholder", "rdb"),
                    List.of(
                            Field.shaped("Company code", TIN_P, "company code",
                                    "registration number", "tin"),
                            Field.of("Company name", "company name", "izina ry", "denomination"),
                            Field.of("Legal form", "legal form", "company type"),
                            Field.of("Registered on", "date of registration", "registered on",
                                    "incorporated"),
                            Field.of("Address", "address", "aderesi"))),

            new DocType("ID", "National identity card",
                    List.of("indangamuntu", "national identity", "igitsina", "amavuko",
                            "aho yavukiye"),
                    List.of(
                            Field.shaped("ID number", ID16_P, "indangamuntu", "number"),
                            Field.of("Names", "amazina", "names", "izina"),
                            Field.of("Date of birth", "amavuko", "date of birth"),
                            Field.of("Sex", "igitsina", "sex"),
                            Field.of("Place of issue", "aho yatangiwe", "place of issue"))),

            new DocType("PAYSLIP", "Payslip",
                    List.of("payslip", "pay slip", "net pay", "gross pay", "umushahara",
                            "deduction", "rssb", "basic salary"),
                    List.of(
                            Field.of("Employee", "employee", "umukozi"),
                            Field.of("Employer", "employer", "institution"),
                            Field.of("Period", "period", "month", "ukwezi"),
                            Field.shaped("Gross pay", MONEY_P, "gross"),
                            Field.shaped("Net pay", MONEY_P, "net pay", "net salary", "take home"),
                            Field.shaped("Deductions", MONEY_P, "deduction"))),

            new DocType("STATEMENT", "Bank statement",
                    List.of("statement of account", "account statement", "opening balance",
                            "closing balance", "available balance", "brought forward"),
                    List.of(
                            Field.of("Account holder", "account name", "holder", "customer name"),
                            Field.of("Account number", "account number", "account no", "konti"),
                            Field.of("Period", "statement period", "period"),
                            Field.shaped("Opening balance", MONEY_P, "opening balance",
                                    "brought forward"),
                            Field.shaped("Closing balance", MONEY_P, "closing balance",
                                    "carried forward"))),

            new DocType("INVOICE", "Invoice or receipt",
                    List.of("invoice", "receipt", "facture", "total due", "amount due",
                            "tax invoice"),
                    List.of(
                            Field.of("Invoice number", "invoice no", "invoice number",
                                    "receipt no"),
                            Field.of("Issued to", "bill to", "customer", "client"),
                            Field.of("Issued by", "supplier", "vendor"),
                            Field.shaped("Total", MONEY_P, "total", "amount due"),
                            Field.shaped("VAT", MONEY_P, "vat"),
                            Field.shaped("TIN", TIN_P, "tin"))),

            new DocType("LETTER", "Letter",
                    List.of("dear ", "yours faithfully", "yours sincerely", "kind regards",
                            "i am writing", "to whom it may concern", "yours truly"),
                    List.of(
                            Field.of("Addressed to", "dear", "to whom", "attention"),
                            Field.of("Subject", "subject", "concerning", "re"),
                            /* A letter dates itself in the corner, with nothing labelling it. */
                            Field.shaped("Date", DATE_P),
                            Field.of("Signed", "yours faithfully", "yours sincerely",
                                    "kind regards", "yours truly"),
                            Field.of("Reference", "our ref", "your ref"))),

            new DocType("CV", "CV or resume",
                    List.of("curriculum vitae", "resume", "work experience", "employment history",
                            "professional experience", "career objective", "education",
                            "skills", "references available"),
                    List.of(
                            /*
                             * A CV LABELS ALMOST NOTHING. Its structure is headings and lists,
                             * not "label: value", so the fields that can be found are the ones
                             * with a shape of their own. The person's name is usually the
                             * largest line at the top, which is what the heading already shows.
                             */
                            Field.shaped("Email", EMAIL_P),
                            Field.shaped("Phone", PHONE_P),
                            Field.of("Date of birth", "date of birth", "born", "amavuko"),
                            Field.of("Nationality", "nationality", "ubwenegihugu"),
                            Field.of("Address", "address", "aderesi", "location"),
                            Field.of("Languages", "languages", "indimi"))),

            new DocType("ACADEMIC", "Academic certificate or transcript",
                    List.of("transcript", "diploma", "bachelor", "master of", "faculty",
                            "has been awarded", "conferred", "academic year", "grade point",
                            "university of"),
                    List.of(
                            Field.of("Awarded to", "awarded to", "this is to certify",
                                    "student name", "names"),
                            Field.of("Programme", "programme", "program", "degree", "course"),
                            Field.of("Institution", "university", "college", "school"),
                            Field.of("Registration number", "registration number", "student number",
                                    "reg no"),
                            Field.of("Grade", "grade point", "gpa", "class", "division"),
                            Field.shaped("Dated", DATE_P, "dated", "issued", "date"))),

            new DocType("CONTRACT", "Contract or agreement",
                    List.of("hereby agree", "the parties", "witnesseth", "terms and conditions",
                            "this agreement", "in witness whereof"),
                    List.of(
                            Field.of("Between", "between", "first party"),
                            Field.of("And", "second party"),
                            Field.shaped("Dated", DATE_P, "dated"),
                            Field.of("Subject", "subject", "purpose"))));

    /** Worth noticing on any page at all, whatever it turns out to be. */
    static final List<Field> ANYTHING = List.of(
            Field.shaped("Dates", DATE_P),
            Field.shaped("Amounts", MONEY_P),
            Field.shaped("Email", EMAIL_P),
            Field.shaped("Phone", PHONE_P));

    private Lab() {}

    /* ------------------------------------------------------------------ what comes back */

    /** One attempt at reading a page. */
    record Attempt(String prep, String mode, int characters, List<String> found) {}

    /** Everything learned about one document. */
    record Report(
            String filename,
            String kindOfFile,
            int pages,
            String text,
            String heading,
            String typeCode,
            String typeLabel,
            List<String> markersFound,
            Map<String, String> fields,
            Map<String, String> anything,
            List<Attempt> attempts,
            String note) {

        boolean identified() {
            return typeCode != null;
        }
    }

    /* ------------------------------------------------------------------ reading */

    /**
     * Reads a document the way the portal would, and reports what it found.
     *
     * <p>EVERY PAGE TWICE, under both page modes, joined -- which is exactly what
     * TesseractReader does, and for the reason recorded there: a boxed certificate loses its
     * labels under one mode and a two-column one is scrambled by the other.
     *
     * @param sweep also try the preprocessing variants, for tuning rather than prediction
     */
    static Report read(String binary, String filename, byte[] content, boolean sweep)
            throws Exception {

        List<BufferedImage> pages = new ArrayList<>();
        String kindOfFile;

        if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            kindOfFile = "PDF";
            try (PDDocument pdf = Loader.loadPDF(content)) {
                PDFRenderer renderer = new PDFRenderer(pdf);
                for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                    pages.add(renderer.renderImageWithDPI(i, PDF_DPI, ImageType.GRAY));
                }
            }
        } else {
            kindOfFile = "image";
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                return new Report(filename, "unreadable", 0, "", null, null, null, List.of(),
                        Map.of(), Map.of(), List.of(),
                        "Java cannot decode this file. HEIC? Export it as a PDF or JPEG.");
            }
            pages.add(image);
        }

        if (pages.isEmpty()) {
            return new Report(filename, kindOfFile, 0, "", null, null, null, List.of(),
                    Map.of(), Map.of(), List.of(), "No pages in that file.");
        }

        StringBuilder whole = new StringBuilder();
        List<Attempt> attempts = new ArrayList<>();

        for (BufferedImage page : pages) {
            /* The portal's own setting: the page as rendered, read under both modes. */
            for (String mode : MODES) {
                String text = tesseract(binary, write(page), mode);
                if (!text.isBlank()) {
                    if (!whole.isEmpty()) whole.append("\n\n");
                    whole.append(text.strip());
                }
            }

            if (sweep) {
                for (Prep prep : Prep.values()) {
                    Path prepared = write(prepare(page, prep));
                    try {
                        for (String mode : MODES) {
                            String text = tesseract(binary, prepared, mode);
                            attempts.add(new Attempt(prep.label, "psm " + mode,
                                    text.strip().length(), List.of()));
                        }
                    } finally {
                        Files.deleteIfExists(prepared);
                    }
                }
            }
        }

        String text = whole.toString();

        String[] lines = text.split("\\R");
        DocType type = classify(text);

        Map<String, String> fields = new LinkedHashMap<>();
        if (type != null) {
            for (Field field : type.fields()) {
                String value = valueOf(lines, text, field);

                /*
                 * THE SAME VALUE TWICE IS A MISREAD, NOT TWO FACTS. Labels overlap on a
                 * bilingual form, and showing one value under two names invites a reader to
                 * believe the page said it twice.
                 */
                if (value != null && !fields.containsValue(value)) fields.put(field.label(), value);
                else fields.putIfAbsent(field.label(), null);
            }
        }

        Map<String, String> anything = new LinkedHashMap<>();
        for (Field field : ANYTHING) {
            String value = everyMatch(text, field.pattern());
            if (value != null) anything.put(field.label(), value);
        }

        return new Report(filename, kindOfFile, pages.size(), text, heading(text),
                type == null ? null : type.code(),
                type == null ? null : type.label(),
                type == null ? markersOf(text, TYPES) : present(text, type.markers()),
                fields, anything, attempts, null);
    }

    /* ------------------------------------------------------------------ what is this? */

    /**
     * Which kind of document this is, by how many of each kind's words are on the page.
     *
     * <p>TWO IS THE BAR, as it is in the portal. One word is a coincidence -- "registration"
     * appears on a vehicle certificate and a company one alike -- and demanding three loses
     * documents that read badly.
     *
     * <p>A CLEAR WINNER IS REQUIRED. When two kinds tie, nothing is claimed: saying "this is
     * a payslip" about a page that is equally a bank statement is worse than saying neither,
     * because the fields that follow would be read with confidence and be wrong.
     */
    static DocType classify(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);

        DocType best = null;
        int bestScore = 0;
        boolean tied = false;

        for (DocType type : TYPES) {
            int score = (int) type.markers().stream().filter(lower::contains).count();
            if (score > bestScore) {
                bestScore = score;
                best = type;
                tied = false;
            } else if (score == bestScore && score > 0) {
                tied = true;
            }
        }
        return bestScore >= 2 && !tied ? best : null;
    }

    private static List<String> present(String text, List<String> words) {
        String lower = text.toLowerCase(Locale.ROOT);
        return words.stream().filter(lower::contains).toList();
    }

    /** For an unidentified page: whatever words were seen, so the reason is visible. */
    private static List<String> markersOf(String text, List<DocType> types) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> seen = new ArrayList<>();
        for (DocType type : types) {
            for (String word : type.markers()) {
                if (lower.contains(word) && !seen.contains(word)) seen.add(word);
            }
        }
        return seen;
    }

    /** One field: by its shape where it has one, by its label where it does not. */
    private static String valueOf(String[] lines, String text, Field field) {
        if (field.pattern() == null) return valueNear(lines, field.keywords());

        /* A labelled match is worth more than a loose one: the page said which number it is. */
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (field.keywords().stream().noneMatch(w -> onItsOwn(lower, w) >= 0)) continue;

            Matcher here = field.pattern().matcher(line);
            if (here.find()) return tidy(here.group());
        }

        if (field.keywords().isEmpty()) {
            Matcher anywhere = field.pattern().matcher(text);
            return anywhere.find() ? tidy(anywhere.group()) : null;
        }

        /*
         * NO UNLABELLED FALLBACK WHEN THE FIELD HAS KEYWORDS. Letting a nine-digit run count
         * as a company code anywhere on the page is how a national ID number became one.
         */
        return null;
    }

    /** Up to four distinct matches, joined -- for the things worth noticing on any page. */
    private static String everyMatch(String text, Pattern pattern) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find() && found.size() < 4) {
            String value = tidy(m.group());
            if (!value.isBlank() && !found.contains(value)) found.add(value);
        }
        return found.isEmpty() ? null : String.join("   ", found);
    }

    /* ------------------------------------------------------------------ reading the content */

    /**
     * The document's own title, taken from the page rather than guessed at.
     *
     * <p>THESE PAGES ANNOUNCE THEMSELVES. A Rwandan land lease is headed AMASEZERANO Y'UBUKODE
     * BURAMBYE in capitals across the top, and that line says more about what the document is
     * than any amount of inference from the fields below it. So the heading is lifted, not
     * composed: whatever appears here is the paper's own words, and if it is nonsense that is
     * because the reader made nonsense of it, which is itself worth seeing.
     */
    static String heading(String text) {
        String best = null;
        double bestScore = 0;

        int seen = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (++seen > 12) break;

            long letters = line.chars().filter(Character::isLetter).count();
            if (letters < 8) continue;

            /*
             * A HEADING IS SHORT. Without this, the longest line near the top of a CV is its
             * career objective -- a whole sentence, which scores well on length and is not a
             * heading at all. Sixty characters is longer than any title on these documents
             * and shorter than any sentence worth mistaking for one.
             */
            if (line.length() > 60) continue;

            long capitals = line.chars().filter(Character::isUpperCase).count();

            /*
             * LENGTH, SHOUTING, AND BEING FIRST.
             *
             * A certificate announces itself in long capitals, so those win -- but a CV puts
             * the person's name at the top in ordinary case, and the first version of this
             * preferred the words CAREER OBJECTIVE further down to the name itself. The bonus
             * for the very first line is what lets a name beat a section heading without
             * letting it beat a title that is genuinely the title.
             */
            double score = letters * (0.5 + (double) capitals / letters) * (seen == 1 ? 1.5 : 1.0);

            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return best;
    }

    /** What a labelling line introduced: after its colon, or on the next line that has text. */
    private static String valueNear(String[] lines, List<String> words) {
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String lower = line.toLowerCase(Locale.ROOT);

            int at = -1;
            String matched = null;
            for (String word : words) {
                int found = onItsOwn(lower, word);
                if (found >= 0) {
                    at = found;
                    matched = word;
                    break;
                }
            }
            if (at < 0) continue;

            /*
             * THE SEPARATOR IS WHATEVER FOLLOWS THE LABEL, and it is only a separator if it
             * follows it immediately.
             *
             * Two things learned from a real reading. A colon comes back as a semicolon often
             * enough that insisting on ":" loses the field -- "Intara; SOUTHERN" was read that
             * way. And searching the whole line for a colon reaches past a missing one into
             * the NEXT label's, so the province was reported as the cell's value from the
             * column beside it. Looking only at the few characters after the label fixes both.
             */
            int after = at + matched.length();

            /*
             * A KINYARWANDA LABEL IS OFTEN A COMPOUND, joined by an apostrophe: the owner of a
             * vehicle is the Nyir'ikinyabiziga, and the stem matched here is only its first
             * half. Stepping over the rest of the word is what lets the separator after it be
             * found -- without it the owner came back as "ikinyabiziga : UWIMANA Beatrice",
             * the label's own tail glued to the answer.
             */
            while (after < line.length()
                    && (line.charAt(after) == '\'' || line.charAt(after) == '\u2019')
                    && after + 1 < line.length()
                    && Character.isLetter(line.charAt(after + 1))) {
                after++;
                while (after < line.length() && Character.isLetter(line.charAt(after))) after++;
            }

            int sep = after;
            while (sep < line.length() && line.charAt(sep) == ' ') sep++;

            boolean separated = sep < line.length() && ":;.=-".indexOf(line.charAt(sep)) >= 0;

            /*
             * TIDIED LAST, ON PURPOSE. Tidying strips the leading punctuation, and the slash
             * it strips is exactly the mark that tells a bilingual label from a value -- so
             * doing it first left "Owner / Nyir'ikinyabiziga : UWIMANA" looking like an answer.
             */
            String candidate = line.substring(separated ? sep + 1 : after);

            candidate = tidy(beforeNextLabel(afterLabelTail(candidate)));

            /*
             * A LINE THAT NAMES SEVERAL FIELDS IS A HEADER, NOT A VALUE. The holders table is
             * headed "Amazina y'Ukodesha ... Nomero z'indangamuntu ... Ingabane", and taking
             * what follows the first of those as the holder's name yields the rest of the
             * header. When the text after a label mentions other labels, the value is below.
             */
            if (plausible(candidate) && !namesOtherFields(candidate)) return candidate;

            for (int below = index + 1; below < lines.length && below <= index + 2; below++) {
                String next = tidy(lines[below]);
                if (next.isBlank()) continue;
                return plausible(next) ? next : null;
            }
        }
        return null;
    }

    /**
     * Where {@code word} appears as a word, rather than inside a longer one.
     *
     * <p>BOTH EDGES, and the second one was learned the hard way. "ukodesha" is the stem of
     * the Kinyarwanda for the lessee, and it is also inside "gukodesha", to lease -- a word
     * that appears in the legal preamble of every lease. Matching it there made the holder's
     * name come out as "ubutaka", which is the next word in the sentence.
     */
    private static int onItsOwn(String haystack, String word) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(word, from);
            if (at < 0) return -1;

            boolean leftClear = at == 0 || !Character.isLetter(haystack.charAt(at - 1));
            int after = at + word.length();
            boolean rightClear = after >= haystack.length()
                    || !Character.isLetter(haystack.charAt(after));

            if (leftClear && rightClear) return at;
            from = at + 1;
        }
    }

    /**
     * Cuts a value where the next label begins.
     *
     * <p>THESE FORMS ARE LAID OUT IN TWO COLUMNS, and a reader walking left to right puts both
     * pairs on one line: "Intara: SOUTHERN Akagari: GATAGARA". Without this the province is
     * reported as "SOUTHERN Akagari: GATAGARA", which is two facts glued together and neither
     * of them readable.
     */
    private static String beforeNextLabel(String value) {
        Matcher next = Pattern.compile("\\s+[\\p{L}'\u2019]{3,}\\s*:").matcher(value);
        return next.find() ? value.substring(0, next.start()).strip() : value;
    }

    /**
     * Drops the rest of a label when it turned out to be longer than the word that matched.
     *
     * <p>THESE FORMS ARE BILINGUAL, and the two halves are joined by a slash: "Owner /
     * Nyir'ikinyabiziga : UWIMANA Beatrice". Matching the English half leaves the Kinyarwanda
     * half sitting in front of the answer -- and a reader that drops the apostrophe defeats
     * every attempt to step over it as a word. So: if what we extracted is still a run of
     * letters, slashes and spaces followed by a separator, that run was label too.
     *
     * <p>THE SLASH IS REQUIRED, and that is what keeps this from undoing the column fix above.
     * "SOUTHERN Akagari: GATAGARA" also looks like words-then-colon, but the words are the
     * province -- the answer -- and stripping them would report the cell instead. A slash is
     * what marks a label written twice; a value does not contain one.
     */
    private static String afterLabelTail(String candidate) {
        Matcher tail =
                Pattern.compile("^[\\p{L}'\u2019 .]{0,20}/[\\p{L}'\u2019 .]{0,30}[:;]\\s*(?=\\S)")
                        .matcher(candidate);
        return tail.find() ? candidate.substring(tail.end()) : candidate;
    }

    /** Whether this text mentions labels of its own -- the mark of a header row. */
    private static boolean namesOtherFields(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        long mentioned = TYPES.stream()
                .flatMap(type -> type.fields().stream())
                .filter(field -> field.keywords().stream().anyMatch(w -> onItsOwn(lower, w) >= 0))
                .count();
        return mentioned >= 2;
    }

    private static boolean plausible(String value) {
        if (value == null) return false;
        String v = value.strip();
        return v.length() >= 2 && v.length() <= 80
                && v.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static String tidy(String value) {
        return value.replaceAll("\\s+", " ")
                .replaceAll("^[^\\p{L}\\p{N}]+", "")
                .replaceAll("[^\\p{L}\\p{N}%)]+$", "")
                .strip();
    }

    /* ------------------------------------------------------------------ plumbing */

    static BufferedImage prepare(BufferedImage source, Prep prep) {
        if (prep == Prep.AS_IS) return source;

        BufferedImage out = new BufferedImage(
                source.getWidth() * prep.scale,
                source.getHeight() * prep.scale,
                BufferedImage.TYPE_BYTE_GRAY);

        Graphics2D canvas = out.createGraphics();
        try {
            if (prep.smooth) {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                canvas.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
            }
            canvas.drawImage(source, 0, 0, out.getWidth(), out.getHeight(), null);
        } finally {
            canvas.dispose();
        }
        return out;
    }

    static Path write(BufferedImage image) throws IOException {
        Path file = Files.createTempFile("lab-", ".png");
        ImageIO.write(image, "png", file.toFile());
        file.toFile().deleteOnExit();
        return file;
    }

    static String tesseract(String binary, Path image, String mode) throws Exception {
        Path errors = Files.createTempFile("lab-err-", ".txt");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    binary, image.toAbsolutePath().toString(), "stdout",
                    "-l", System.getenv().getOrDefault("OCR_LANGUAGES", "eng"),
                    "--psm", mode);
            builder.redirectError(errors.toFile());

            Process process = builder.start();
            byte[] out = process.getInputStream().readAllBytes();

            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : "";
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    static String binary() {
        return System.getenv().getOrDefault("OCR_BINARY", "tesseract");
    }

    static String version(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "--version").redirectErrorStream(true).start();
            String first = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .lines().findFirst().orElse("unknown");
            p.waitFor(10, TimeUnit.SECONDS);
            return first;
        } catch (Exception missing) {
            return binary + " — NOT FOUND. brew install tesseract, or set OCR_BINARY.";
        }
    }

    /* ------------------------------------------------------------------ the terminal */

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("""
                Usage:
                  ./lab.sh <file> [more files...]     read them and print what was found
                  ./lab.sh --sweep <file>             also compare preprocessing settings
                  ./lab.sh --serve [port]             open the drag-and-drop app instead
                """);
            return;
        }

        boolean sweep = false;
        List<String> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--sweep")) sweep = true;
            else files.add(arg);
        }

        String binary = binary();
        System.out.println("reader: " + version(binary));

        for (String name : files) {
            Path file = Path.of(name);
            if (!Files.isReadable(file)) {
                System.out.println("\n" + file.toAbsolutePath() + " — no such file. Skipped.");
                continue;
            }

            Report report = read(binary, file.getFileName().toString(), Files.readAllBytes(file), sweep);
            print(file, report);
        }
    }

    private static void print(Path file, Report report) {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("FILE  " + file.toAbsolutePath());
        System.out.printf("      %s, %d page%s%n",
                report.kindOfFile(), report.pages(), report.pages() == 1 ? "" : "s");

        if (report.note() != null) {
            System.out.println("      " + report.note());
            return;
        }

        if (report.heading() != null) {
            System.out.println();
            System.out.println("  \"" + report.heading() + "\"");
        }

        System.out.println();
        if (report.identified()) {
            System.out.println("  THIS IS    " + report.typeLabel());
            System.out.println("  because    " + String.join(", ", report.markersFound()));
        } else {
            System.out.println("  THIS IS    not recognised");
            System.out.println("  seen       "
                    + (report.markersFound().isEmpty()
                            ? "none of the words any known document carries"
                            : String.join(", ", report.markersFound()) + " (too few to be sure)"));
        }

        if (!report.fields().isEmpty()) {
            System.out.println();
            report.fields().forEach((key, value) ->
                    System.out.printf("  %-24s %s%n", key, value == null ? "(not read)" : value));
        }

        if (!report.anything().isEmpty()) {
            System.out.println();
            System.out.println("  also on the page");
            report.anything().forEach((key, value) ->
                    System.out.printf("  %-24s %s%n", key, value));
        }

        if (!report.attempts().isEmpty()) {
            System.out.println();
            System.out.printf("  %-30s %-7s %-7s%n", "preprocessing", "mode", "chars");
            System.out.println("  " + "-".repeat(50));
            for (Attempt a : report.attempts()) {
                System.out.printf("  %-30s %-7s %-7d%n", a.prep(), a.mode(), a.characters());
            }
        }

        System.out.println();
        System.out.println("---- what the reader saw ----------------------------------------");
        System.out.println(report.text().strip());
        System.out.println("-----------------------------------------------------------------");
    }
}
