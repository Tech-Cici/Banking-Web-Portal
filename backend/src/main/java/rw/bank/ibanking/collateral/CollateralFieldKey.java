package rw.bank.ibanking.collateral;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One thing that can be read off a collateral document and checked against what was typed.
 *
 * <p>EACH KEY CARRIES ITS OWN WORDS AND ITS OWN SHAPE. They used to live in parallel lists
 * inside the reader, which is the arrangement where a key gains a label here and keeps the
 * old keywords there. A field is one idea: what it is called, what introduces it on the page,
 * and what it looks like when it has a shape at all.
 *
 * <p>THE KEYWORDS ARE KINYARWANDA FIRST, because the documents are. A Rwandan land lease
 * labels its holder <i>amazina y'ukodesha</i>, its parcel <i>nomero y'ikibanza</i> and its size
 * <i>ubuso</i>, and carries no English except the province names. A list written in English
 * finds nothing on it.
 *
 * <p>A NULL PATTERN MEANS THE VALUE IS WHATEVER FOLLOWS THE LABEL, which is how names and
 * places are found. A pattern means the value has a recognisable form, and then the reader
 * prefers a match on a line that labels it over one found loose on the page.
 *
 * <p>EVERY KEY IS STORED AS ITS NAME, in {@code collateral_fields.field_key}, a VARCHAR
 * precisely so a key added here needs no migration. The cost is that a key removed here
 * leaves rows nothing maps, so keys are added and not deleted.
 */
public enum CollateralFieldKey {

    /* ----------------------------------------------------------------- land and houses */

    /** The Unique Parcel Identifier on a land registration certificate, e.g. 1/03/01/04/30. */
    UPI("Parcel number (UPI)", "As printed on the certificate, with the slashes.",
            Patterns.UPI, "upi", "parcel", "plot"),

    /** Whose name the thing is registered in. Compared to the applicant, advisory only. */
    OWNER_NAME("Registered owner", "Exactly as the document spells it.",
            null, "amazina", "ukodesha", "nyir", "owner", "proprietor", "titulaire"),

    PARCEL_NUMBER("Plot number", "The plot number, where the certificate gives one separately.",
            null, "ikibanza"),

    AREA("Size", "As printed — ares, hectares or square metres.", null, "ubuso", "superficie"),

    PROVINCE("Province", "", null, "intara", "province"),
    DISTRICT("District", "", null, "akarere", "district"),
    SECTOR("Sector", "", null, "umurenge", "sector"),
    CELL("Cell", "", null, "akagari", "akagali", "cell"),
    VILLAGE("Village", "", null, "umudugudu", "village"),

    GRANTED_ON("Granted", "When the lease began.", null, "bwatangiriyeho", "issued"),
    EXPIRES_ON("Expires", "When the lease ends.", null, "bwarangiriraho", "expiry"),

    /* --------------------------------------------------------------------- vehicles */

    /** A Rwandan civilian plate: three letters beginning with R, three digits, one letter. */
    PLATE("Plate number", "For example RAD 123 A.",
            Patterns.PLATE, "plate", "plaque", "immatricul"),

    /** The chassis or VIN, seventeen characters. */
    CHASSIS("Chassis number", "Seventeen characters, usually on the same line as the make.",
            Patterns.CHASSIS, "chassis", "vin"),

    MAKE_MODEL("Make and model", "", null, "marque", "make", "model"),
    YEAR("Year", "", null, "year of manufacture", "year", "annee"),
    ENGINE("Engine", "", null, "moteur", "engine"),
    REGISTERED_ON("Registered", "", null, "date of registration", "registered"),

    /* -------------------------------------------------------------------- businesses */

    /** The company's registration code on its certificate from the registrar. */
    COMPANY_CODE("Company code", "The registration number on the certificate.",
            Patterns.COMPANY_CODE, "company code", "registration number", "tin"),

    COMPANY_NAME("Company name", "The registered name, not the trading name.",
            null, "company name", "izina ry", "denomination"),

    REGISTERED_OFFICE("Registered office", "", null, "address", "aderesi");

    /**
     * Held in a nested class because an enum constant cannot read a static field of its own
     * enum -- the constants are built before those fields are assigned. Same arrangement as
     * {@link CollateralKind}'s marker lists, for the same compiler rule.
     */
    private static final class Patterns {
        private Patterns() {}

        static final Pattern UPI = Pattern.compile("\\b\\d{1,2}(?:\\s?/\\s?\\d{1,5}){3,5}\\b");

        static final Pattern PLATE = Pattern.compile("\\bR[A-Z]{2}\\s?\\d{3}\\s?[A-Z]\\b");

        /*
         * I, O AND Q ARE ALLOWED, though a real VIN excludes them. The standard excludes them
         * precisely because they look like 1 and 0 -- which is also exactly why a reader
         * produces them. A real chassis photographed and read came back as JTEBH9FJ9HKO21547,
         * with the letter O where the plate has a nought, and a pattern enforcing the standard
         * rejected the one string on the page that was the answer.
         */
        static final Pattern CHASSIS = Pattern.compile("\\b[A-Z0-9]{17}\\b");

        static final Pattern COMPANY_CODE = Pattern.compile("\\b\\d{6,12}\\b");
    }

    private final String label;
    private final String hint;
    private final Pattern pattern;
    private final List<String> keywords;

    CollateralFieldKey(String label, String hint, Pattern pattern, String... keywords) {
        this.label = label;
        this.hint = hint;
        this.pattern = pattern;
        this.keywords = List.of(keywords);
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    /** The value's shape, or null when it is simply whatever follows the label. */
    public Pattern pattern() {
        return pattern;
    }

    /** The words that introduce this field on a page. */
    public List<String> keywords() {
        return keywords;
    }
}
