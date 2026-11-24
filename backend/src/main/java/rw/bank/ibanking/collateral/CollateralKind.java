package rw.bank.ibanking.collateral;

import java.util.List;
import java.util.Locale;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * What a customer is pledging, and therefore which document the bank expects.
 *
 * <p>THE KIND DECIDES THE FIELDS. A plot is evidenced by a certificate carrying a parcel
 * number; a vehicle by a registration carrying a plate and a chassis number; a company by a
 * certificate carrying its registration code. Asking for all of them on every pledge would
 * leave most of the form blank and most of the reading empty.
 *
 * <p>HOUSE AND LAND ASK FOR THE SAME THINGS, deliberately, because in Rwanda a house stands on
 * a titled parcel and the document is the same certificate. They are kept apart anyway: what
 * is being pledged is a different fact from what document proves it, and a manager reading
 * "house" knows to expect a building on the parcel.
 *
 * <p>THE LIST IS A COPY OF THE ONE IN V35's CHECK CONSTRAINT. Same arrangement as every other
 * enum in this schema: a kind added here without the migration fails at insert with a message
 * about a constraint rather than about collateral.
 */
public enum CollateralKind {
    LAND(
            "Land",
            "Land registration certificate",
            List.of(CollateralFieldKey.UPI, CollateralFieldKey.OWNER_NAME),
            List.of(CollateralFieldKey.UPI),
            Markers.LAND),

    HOUSE(
            "House",
            "Land registration certificate for the plot it stands on",
            List.of(CollateralFieldKey.UPI, CollateralFieldKey.OWNER_NAME),
            List.of(CollateralFieldKey.UPI),
            Markers.LAND),

    VEHICLE(
            "Vehicle",
            "Vehicle registration certificate",
            List.of(
                    CollateralFieldKey.PLATE,
                    CollateralFieldKey.CHASSIS,
                    CollateralFieldKey.OWNER_NAME),
            List.of(CollateralFieldKey.PLATE, CollateralFieldKey.CHASSIS),
            Markers.VEHICLE),

    BUSINESS(
            "Business",
            "Certificate of registration",
            List.of(CollateralFieldKey.COMPANY_CODE, CollateralFieldKey.COMPANY_NAME),
            List.of(CollateralFieldKey.COMPANY_CODE),
            Markers.BUSINESS);

    /**
     * The marker words, held in a nested class so the constants above can read them.
     *
     * <p>A FORWARD REFERENCE TO A STATIC FIELD OF AN ENUM IS A COMPILE ERROR, and it is the
     * right error: enum constants are built before the enum's own static fields are assigned,
     * so a constant reading one would be handed null. A nested class has its own
     * initialisation, triggered on first use -- which happens while the constants are being
     * built, so the lists are there.
     */
    private static final class Markers {
        private Markers() {}

        /*
         * WHAT THESE WORDS ARE FOR. They answer "is this the right sort of document", which is a
         * different and much more answerable question than "what is its parcel number". A
         * photograph can be too poor to give up an identifier and still plainly be a land lease;
         * refusing it because a number could not be read would turn a reading aid into a barrier
         * for the people least able to produce a perfect scan.
         *
         * THE LAND LIST COMES FROM A REAL CERTIFICATE. The vehicle and business lists do not --
         * they are reasoned from what those documents are called, and should be corrected the
         * first time a real one is put through.
         */
        static final List<String> LAND =
                List.of(
                        "ubutaka",
                        "ubukode",
                        "amasezerano",
                        "ikibanza",
                        "upi",
                        "umubitsi",
                        "ubuso",
                        "land",
                        "parcel");

        static final List<String> VEHICLE =
                List.of(
                        "ikinyabiziga",
                        "ibinyabiziga",
                        "chassis",
                        "plate",
                        "immatricul",
                        "vehicle",
                        "registration",
                        "moteur",
                        "engine");

        /*
         * NO "REGISTRATION" AND NO "CERTIFICATE" HERE, though both are on the document.
         *
         * They are also on a vehicle registration certificate, and two shared generic words were
         * enough to make one pass as the other -- a customer could pledge their car's papers as
         * evidence of a company. Every word left is one that only a company's own certificate
         * has reason to carry.
         */
        static final List<String> BUSINESS =
                List.of(
                        "ubucuruzi",
                        "registrar",
                        "company",
                        "incorporat",
                        "domestic",
                        "shareholder",
                        "rdb");
    }

    private final String label;
    private final String document;
    private final List<CollateralFieldKey> fields;
    private final List<CollateralFieldKey> identifiers;
    private final List<String> markers;
    private final List<CollateralFieldKey> alsoRead;

    CollateralKind(
            String label,
            String document,
            List<CollateralFieldKey> fields,
            List<CollateralFieldKey> identifiers,
            List<String> markers) {
        this.label = label;
        this.document = document;
        this.fields = fields;
        this.identifiers = identifiers;
        this.markers = markers;
        this.alsoRead = Extra.forKind(name());
    }

    /**
     * The extra fields per kind, in a nested class for the compiler rule the markers hit: an
     * enum constant cannot read a static field of its own enum, because the constants are
     * built first.
     */
    private static final class Extra {
        private Extra() {}

        static final List<CollateralFieldKey> LAND = List.of(
                CollateralFieldKey.PARCEL_NUMBER,
                CollateralFieldKey.AREA,
                CollateralFieldKey.PROVINCE,
                CollateralFieldKey.DISTRICT,
                CollateralFieldKey.SECTOR,
                CollateralFieldKey.CELL,
                CollateralFieldKey.VILLAGE,
                CollateralFieldKey.GRANTED_ON,
                CollateralFieldKey.EXPIRES_ON);

        static final List<CollateralFieldKey> VEHICLE = List.of(
                CollateralFieldKey.MAKE_MODEL,
                CollateralFieldKey.YEAR,
                CollateralFieldKey.ENGINE,
                CollateralFieldKey.REGISTERED_ON);

        static final List<CollateralFieldKey> BUSINESS =
                List.of(CollateralFieldKey.REGISTERED_OFFICE);

        static List<CollateralFieldKey> forKind(String code) {
            return switch (code) {
                case "LAND", "HOUSE" -> LAND;
                case "VEHICLE" -> VEHICLE;
                case "BUSINESS" -> BUSINESS;
                default -> List.of();
            };
        }
    }

    /**
     * Words that appear on this class of document, whatever the reader made of the rest.
     *
     * <p>THESE ARE IN KINYARWANDA FIRST, because the documents are. A Rwandan land title is
     * headed AMASEZERANO Y'UBUKODE BURAMBYE and labels its fields amazina y'ukodesha, nomero
     * y'ikibanza, ubuso -- there is no English on the page except the province names. A
     * marker list written in English would recognise none of them.
     *
     * <p>SPELT AS A READER MIGHT MANGLE THEM, which is why they are short roots rather than
     * whole phrases: "ubukode" survives a photograph that turns "BURAMBYE" into something
     * else. Matching is case-insensitive and anywhere on the page.
     */
    public List<String> markers() {
        return markers;
    }

    /**
     * Whether the text looks like this class of document at all.
     *
     * <p>TWO MARKERS, NOT ONE. A single common word -- "rwanda" appears on every official
     * document printed in the country -- would let anything through. Two independent ones
     * from a short list is a reasonable bar for "this is the right sort of page", and it is
     * all this check claims: it says nothing about whether the document is genuine, current,
     * or the customer's.
     */
    public boolean looksLikeThisDocument(String text) {
        if (text == null || text.isBlank()) return false;

        String lower = text.toLowerCase(Locale.ROOT);
        return markers.stream().filter(lower::contains).limit(2).count() >= 2;
    }

    public String label() {
        return label;
    }

    /** What the customer is asked to photograph. */
    public String document() {
        return document;
    }

    /** What the customer is asked to declare. Kept short: every one of these is required. */
    public List<CollateralFieldKey> fields() {
        return fields;
    }

    /**
     * Extra detail read off the page and shown to staff, which the customer never types.
     *
     * <p>SEPARATE FROM {@link #fields()} BECAUSE OF WHAT REQUIRING SOMETHING COSTS. V35 makes
     * a declared value mandatory for every field of the kind, so adding the district and the
     * cell and the dates to that list would be adding eight more boxes a customer must fill in
     * by hand whenever the reader missed them -- to collect facts the bank can simply read.
     *
     * <p>SO THESE ARE NEVER DECLARED AND NEVER COMPARED. They are a reading, recovered from the
     * stored text whenever a screen wants them, and they carry exactly the authority of a
     * reading: useful to a manager looking at a plot in Nyanza, and evidence of nothing.
     */
    public List<CollateralFieldKey> alsoRead() {
        return alsoRead;
    }

    /**
     * The fields that identify the document as this kind, rather than merely appearing on it.
     *
     * <p>A NAME IS NOT ONE OF THEM, deliberately. Every document in the world carries a name
     * somewhere; "Owner: X" on a page proves nothing about which page it is. A parcel number,
     * a plate, a chassis number or a company registration code belong to one class of
     * document and do not turn up on a letter, so finding one is evidence that the right
     * thing was attached.
     *
     * <p>ANY ONE OF THEM IS ENOUGH. A vehicle registration photographed at an angle may give
     * up its plate and not its chassis number; demanding both would reject a genuine document
     * because of the light.
     */
    public List<CollateralFieldKey> identifiers() {
        return identifiers;
    }

    /** Parses what arrived over the wire, refusing anything this bank does not take. */
    public static CollateralKind parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException("Say what you are pledging.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessRuleException("The bank does not take that as collateral.");
        }
    }
}
