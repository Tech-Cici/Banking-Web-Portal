package rw.bank.ibanking.collateral;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puts a document through the bank's reader from the command line, and says what the portal
 * would do with it.
 *
 * <p>IT RUNS THE REAL CLASSES. {@link TesseractReader}, {@link FieldReader}, the marker test on
 * {@link CollateralKind} and the refusal in {@link CollateralService} are the ones the upload
 * endpoint uses; nothing here reimplements them. A probe with its own copy of the patterns
 * agrees with itself and tells you nothing about the thing you are shipping.
 *
 * <p>IT LIVES OUTSIDE THE BACKEND'S SOURCE TREE, in tools/, and is compiled on demand against
 * the backend's own classes. So it ships with nothing, runs without a server, a database or a
 * login, and cannot drift from production except by production moving under it -- which shows
 * up immediately as a compile error here.
 *
 * <p>IT IS IN THE SAME PACKAGE as what it tests, which is how it can call the package-private
 * refusal rather than a second opinion about it.
 */
public final class OcrProbe {

    private OcrProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println(
                    """
                    Usage:
                      probe.sh <file> [more files...]          test the file against every kind
                      probe.sh LAND <file> [more files...]     test it as one kind only

                    Kinds: LAND, HOUSE, VEHICLE, BUSINESS
                    """);
            return;
        }

        List<CollateralKind> kinds = new ArrayList<>();
        List<String> files = new ArrayList<>();

        for (String arg : args) {
            CollateralKind named = kindNamed(arg);
            if (named != null && files.isEmpty()) {
                kinds.add(named);
            } else {
                files.add(arg);
            }
        }
        if (kinds.isEmpty()) kinds = List.of(CollateralKind.values());

        /*
         * THE SAME DEFAULTS THE SERVICE RUNS WITH. OCR_BINARY is honoured because that is the
         * one setting that differs between a Mac and a container, and getting it wrong here
         * would look like a reading failure rather than a missing program.
         */
        OcrProperties properties =
                new OcrProperties(System.getenv("OCR_BINARY"), System.getenv("OCR_LANGUAGES"), null, 0, 0);
        TesseractReader reader = new TesseractReader(properties);

        if (files.isEmpty()) {
            System.out.println("No file given. Pass one, or drag it into the terminal window.");
            return;
        }

        for (String file : files) {
            Path path = Path.of(file);

            /*
             * A MISSING FILE IS A TYPO, NOT A CRASH. This tool is run repeatedly with several
             * paths at once; one wrong name should cost that one file, not the whole run and
             * a stack trace that buries the results of the others.
             */
            if (!Files.isReadable(path)) {
                System.out.println();
                System.out.printf("FILE      %s%n", path.toAbsolutePath());
                System.out.println("          no such file, or it cannot be read. Skipped.");
                continue;
            }

            probe(reader, path, kinds);
        }
    }

    private static void probe(TesseractReader reader, Path file, List<CollateralKind> kinds)
            throws Exception {

        byte[] content = Files.readAllBytes(file);
        String contentType = file.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? "application/pdf"
                : "image/jpeg";

        System.out.println();
        System.out.println("================================================================");
        System.out.printf("FILE      %s%n", file.toAbsolutePath());
        System.out.printf("SIZE      %,d bytes   treated as %s%n", content.length, contentType);

        long began = System.currentTimeMillis();
        DocumentReading reading = reader.read(content, contentType);
        long took = System.currentTimeMillis() - began;

        System.out.printf("READER    %s in %,d ms%n", reading.status(), took);
        if (reading.engine() != null) System.out.printf("ENGINE    %s%n", reading.engine());
        if (reading.failure() != null) System.out.printf("FAILURE   %s%n", reading.failure());

        for (CollateralKind kind : kinds) {
            System.out.println();
            System.out.printf("---- as %s (%s)%n", kind, kind.document());

            Map<CollateralFieldKey, String> found =
                    reading.hasText() ? FieldReader.read(kind, reading.text()) : Map.of();

            /* The portal's own decision, called rather than imitated. */
            String verdict;
            try {
                CollateralService.refuseIfNotTheRightDocument(kind, reading, found);
                verdict = "ACCEPTED";
            } catch (RuntimeException refused) {
                verdict = "REFUSED — " + refused.getMessage();
            }
            System.out.printf("     verdict   %s%n", verdict);

            List<String> markersHit =
                    reading.hasText()
                            ? kind.markers().stream()
                                    .filter(m -> reading.text().toLowerCase(Locale.ROOT).contains(m))
                                    .toList()
                            : List.of();
            System.out.printf(
                    "     markers   %s%n",
                    markersHit.isEmpty() ? "(none)" : String.join(", ", markersHit));

            for (CollateralFieldKey key : kind.fields()) {
                String value = found.get(key);
                boolean identifying = kind.identifiers().contains(key);
                System.out.printf(
                        "     %-12s %s%s%n",
                        key,
                        value == null ? "(not read)" : "<" + value + ">",
                        identifying ? "   [identifier]" : "");
            }
        }

        if (reading.hasText()) {
            System.out.println();
            System.out.println("---- what the reader saw ----------------------------------------");
            System.out.println(reading.text());
            System.out.println("-----------------------------------------------------------------");
        }
    }

    private static CollateralKind kindNamed(String value) {
        for (CollateralKind kind : CollateralKind.values()) {
            if (kind.name().equalsIgnoreCase(value)) return kind;
        }
        return null;
    }
}
