package rw.bank.ibanking.collateral;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks identifiers and details out of the text a document reader produced.
 *
 * <p>WHAT THIS IS FOR. It fills a form in, so a customer does not retype a seventeen-character
 * chassis number off a photograph, and it gives a member of staff something to compare the
 * typed answers against. Everything it finds is shown to the customer, editable, before
 * anything is submitted, and nothing it fails to find stops an application. It is a typing aid
 * with the standing of a guess.
 *
 * <p>IT CORRECTS NOTHING. The temptation is to map an O to a 0 in a field that should be
 * numeric and hand back something that looks right. That is not reading a document, it is
 * writing one: the customer would be shown a parcel number the page does not carry, and the
 * better it looks the less likely they are to check it.
 *
 * <p>EVERY RULE BELOW WAS LEARNED FROM A DOCUMENT, NOT REASONED OUT. They came from putting
 * real and specimen certificates through the sandbox in tools/ocr-lab, and each one is
 * recorded where it is enforced so the next reader of this file knows which page taught it.
 */
public final class FieldReader {

    private FieldReader() {}

    /** Everything this kind of document is expected to carry, as far as it could be found. */
    public static Map<CollateralFieldKey, String> read(CollateralKind kind, String text) {
        return read(kind.fields(), text);
    }

    /** The same, for any set of keys -- used for the extra detail staff are shown. */
    public static Map<CollateralFieldKey, String> read(
            List<CollateralFieldKey> keys, String text) {

        Map<CollateralFieldKey, String> found = new EnumMap<>(CollateralFieldKey.class);
        if (text == null || text.isBlank()) return found;

        String[] lines = text.split("\\R");

        for (CollateralFieldKey key : keys) {
            String value = valueOf(lines, text, key);

            /*
             * ONE VALUE CANNOT BE TWO FIELDS.
             *
             * A certificate laid out in two columns is read by some page modes as every label,
             * then every value -- so "Owner" is followed on the page by the FIRST value in the
             * other column, which on a vehicle registration is the plate. Without this, a real
             * reading offered RAD 123A as both the plate number and the owner's name.
             */
            if (value != null && !value.isBlank() && !claimedAlready(found, value)) {
                found.put(key, value);
            }
        }
        return found;
    }

    /**
     * The document's own title, lifted off the top of the page.
     *
     * <p>THESE PAGES ANNOUNCE THEMSELVES. A Rwandan land lease is headed AMASEZERANO Y'UBUKODE
     * BURAMBYE across the top, and that line says more about what the document is than any
     * amount of inference from the fields below it. It is taken, not composed: if it comes out
     * as nonsense, that is the reader making nonsense of the page, which is itself worth
     * showing a member of staff.
     *
     * <p>LENGTH, SHOUTING, AND BEING FIRST, in that balance. Certificates announce themselves
     * in long capitals; the bonus for the very first line is what stops a section heading
     * further down beating the title. Anything over sixty characters is a sentence rather than
     * a heading and is ignored.
     */
    public static String heading(String text) {
        if (text == null || text.isBlank()) return null;

        String best = null;
        double bestScore = 0;
        int seen = 0;

        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (++seen > 12) break;
            if (line.length() > 60) continue;

            long letters = line.chars().filter(Character::isLetter).count();
            if (letters < 8) continue;

            long capitals = line.chars().filter(Character::isUpperCase).count();
            double score = letters * (0.5 + (double) capitals / letters) * (seen == 1 ? 1.5 : 1.0);

            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return best;
    }

    /* ------------------------------------------------------------------------ one field */

    private static String valueOf(String[] lines, String text, CollateralFieldKey key) {
        if (key.pattern() == null) return valueNear(lines, key.keywords());

        /* A labelled match beats a loose one: the page said which number it is. */
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (key.keywords().stream().noneMatch(word -> onItsOwn(lower, word) >= 0)) continue;

            Matcher here = key.pattern().matcher(line);
            if (here.find()) return closeGaps(tidy(here.group()));
        }

        /*
         * THEN ANYWHERE ON THE PAGE. A certificate that prints its parcel number without a
         * label still carries one, and the shape is distinctive enough to trust. Loose matching
         * is safe here only because these patterns are narrow; the company code, which is a
         * bare run of digits, is the one that must never be taken this way -- and it is not,
         * because the keyword check below refuses it.
         */
        if (key == CollateralFieldKey.COMPANY_CODE) {
            /*
             * A NINE-DIGIT RUN IS A DATE, A PHONE NUMBER AND A NATIONAL ID AS OFTEN AS IT IS A
             * COMPANY CODE. On a land lease this matched the first nine digits of the holder's
             * identity number and reported it as a company code.
             */
            return null;
        }

        Matcher anywhere = key.pattern().matcher(text);
        if (anywhere.find()) return closeGaps(tidy(anywhere.group()));

        /*
         * LAST: WHATEVER THE LABEL INTRODUCED, even though it does not match the pattern. A
         * line reading "Plate number : RAD1I123A" is the page telling us where the answer is
         * and the reader telling us it could not quite make it out. Handing back the mangled
         * value is more use than nothing: the customer is looking at the field, the plate is
         * in their hand, and correcting two characters beats typing nine.
         */
        String labelled = valueNear(lines, key.keywords());
        return labelled != null && labelled.length() <= 40 ? labelled : null;
    }

    /**
     * What a labelling line introduced: after its separator, or on the next line with text.
     *
     * <p>BLANK LINES ARE SKIPPED, and that is not a detail. A reader working down a boxed
     * certificate emits the label, then the rule between the cells as an empty line, then the
     * value -- so a real land title reading "Registered owner" with the name two lines below
     * it found nothing while this looked only at the line immediately after.
     */
    private static String valueNear(String[] lines, List<String> words) {
        if (words.isEmpty()) return null;

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

            int after = at + matched.length();

            /*
             * A KINYARWANDA LABEL IS OFTEN A COMPOUND joined by an apostrophe: the owner of a
             * vehicle is the Nyir'ikinyabiziga, and the word matched is only its first half.
             * Stepping over the rest is what lets the separator after it be found -- without
             * it the owner came back as "ikinyabiziga : UWIMANA Beatrice".
             */
            while (after < line.length()
                    && (line.charAt(after) == '\'' || line.charAt(after) == '’')
                    && after + 1 < line.length()
                    && Character.isLetter(line.charAt(after + 1))) {
                after++;
                while (after < line.length() && Character.isLetter(line.charAt(after))) after++;
            }

            /*
             * THE SEPARATOR MUST FOLLOW THE LABEL IMMEDIATELY. Two things learned from a real
             * reading: a colon comes back as a semicolon often enough that insisting on ":"
             * loses the field, and searching the whole line for one reaches past a missing
             * colon into the NEXT label's -- which reported a province as the cell beside it.
             */
            int separator = after;
            while (separator < line.length() && line.charAt(separator) == ' ') separator++;

            boolean separated =
                    separator < line.length() && ":;.=-".indexOf(line.charAt(separator)) >= 0;

            /*
             * TIDIED LAST. Tidying strips leading punctuation, and the slash it strips is
             * exactly what tells a bilingual label from a value -- so doing it first left
             * "Owner / Nyir'ikinyabiziga : UWIMANA" looking like an answer.
             */
            String candidate = line.substring(separated ? separator + 1 : after);
            candidate = tidy(beforeNextLabel(afterLabelTail(candidate)));

            if (plausible(candidate)) return candidate;

            for (int below = index + 1; below < lines.length && below <= index + 3; below++) {
                String next = tidy(lines[below]);
                if (next.isBlank()) continue;
                return plausible(next) ? next : null;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------------- the rules */

    /**
     * Where {@code word} appears as a word rather than inside a longer one.
     *
     * <p>BOTH EDGES, and the second was learned the hard way. "ukodesha" is the stem of the
     * Kinyarwanda for the lessee, and it is also inside "gukodesha", to lease -- a word in the
     * legal preamble of every lease. Matching it there made the holder's name come out as
     * "ubutaka", the next word in the sentence.
     */
    private static int onItsOwn(String haystack, String word) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(word, from);
            if (at < 0) return -1;

            boolean leftClear = at == 0 || !Character.isLetter(haystack.charAt(at - 1));
            int after = at + word.length();
            boolean rightClear =
                    after >= haystack.length() || !Character.isLetter(haystack.charAt(after));

            if (leftClear && rightClear) return at;
            from = at + 1;
        }
    }

    /**
     * Drops the rest of a label when it turned out to be longer than the word that matched.
     *
     * <p>THESE FORMS ARE BILINGUAL, the halves joined by a slash: "Owner / Nyir'ikinyabiziga :
     * UWIMANA Beatrice". Matching the English half leaves the Kinyarwanda half in front of the
     * answer, and a reader that drops the apostrophe defeats stepping over it as a word.
     *
     * <p>THE SLASH IS REQUIRED, which is what keeps this from undoing the rule below.
     * "SOUTHERN Akagari: GATAGARA" also looks like words-then-colon, but those words are the
     * province -- the answer -- and stripping them would report the cell instead.
     */
    private static String afterLabelTail(String candidate) {
        Matcher tail =
                Pattern.compile("^[\\p{L}'’ .]{0,20}/[\\p{L}'’ .]{0,30}[:;]\\s*(?=\\S)")
                        .matcher(candidate);
        return tail.find() ? candidate.substring(tail.end()) : candidate;
    }

    /**
     * Cuts a value where the next label begins.
     *
     * <p>THESE FORMS ARE LAID OUT IN TWO COLUMNS, and a reader walking left to right puts both
     * pairs on one line: "Intara: SOUTHERN Akagari: GATAGARA". Without this the province is
     * reported as "SOUTHERN Akagari: GATAGARA", two facts glued together and neither readable.
     */
    private static String beforeNextLabel(String value) {
        Matcher next = Pattern.compile("\\s+[\\p{L}'’]{3,}\\s*:").matcher(value);
        return next.find() ? value.substring(0, next.start()).strip() : value;
    }

    /** Whether some other field on this document has already been given this exact value. */
    private static boolean claimedAlready(
            Map<CollateralFieldKey, String> found, String candidate) {

        for (String taken : found.values()) {
            if (taken.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    /**
     * Whether what was found is worth offering at all.
     *
     * <p>DELIBERATELY WEAK: the customer is about to read it. A stricter test would throw away
     * correctly-read values with an initial, a hyphen or a unit, and the cost of showing a
     * wrong one is a deletion.
     */
    private static boolean plausible(String value) {
        if (value == null) return false;

        String trimmed = value.strip();
        return trimmed.length() >= 2
                && trimmed.length() <= 160
                && trimmed.chars().anyMatch(Character::isLetterOrDigit);
    }

    /**
     * Removes the spaces a reader put either side of a separator.
     *
     * <p>A reader emits "2 / 05 / 11" for what the page prints as "2/05/11"; closing those gaps
     * changes no character, it undoes the reader's own spacing.
     */
    private static String closeGaps(String value) {
        return value == null ? null : value.replaceAll("\\s*/\\s*", "/");
    }

    /** Collapses the whitespace a reader sprinkles through a line, and trims the edges. */
    private static String tidy(String value) {
        String cleaned = value.replaceAll("\\s+", " ").strip();

        /* Leading and trailing punctuation is the box rule the reader mistook for a character. */
        cleaned = cleaned.replaceAll("^[^\\p{L}\\p{N}]+", "").replaceAll("[^\\p{L}\\p{N}%)]+$", "");

        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
