package rw.bank.ibanking.collateral;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the text off a photographed or scanned document, by running Tesseract.
 *
 * <p>ANOTHER PROGRAM, NOT A LIBRARY. The alternative is a JNA binding that loads a native
 * library at class-load time and fails on the wrong architecture with a stack trace nobody can
 * act on. A child process costs a few milliseconds to start and either runs or does not, which
 * is a fault that reports itself.
 *
 * <p>NO SHELL, EVER. The command is built as a list of arguments and handed to the operating
 * system directly, so a file called {@code "; rm -rf ~"} is a file name and not a command. The
 * upload is written to a temporary file the bank names itself; the customer's own file name
 * never reaches this class.
 *
 * <p>IT NEVER THROWS FOR A BAD DOCUMENT. Everything that can go wrong with somebody's
 * photograph -- blurred, upside down, a PDF with no pages, a file that claims to be a JPEG and
 * is not -- comes back as a {@link DocumentReading} saying so. The upload still succeeds and
 * the form falls back to being typed. An exception here would throw away a file the customer
 * sent and tell them to try again, which is the one thing that does not help.
 *
 * <p>EVERY PAGE IS READ TWICE, under two different assumptions about the layout, and the
 * two readings are kept. This is not belt and braces, it is the result of measuring: a boxed
 * land certificate read as a single block loses its labels entirely -- the values come back
 * with nothing to say which is which -- while a vehicle registration read with automatic
 * layout detection is split into a column of labels followed by a column of values, so that
 * "Owner" is followed on the page by the plate number. Each mode reads one of those documents
 * well and the other badly. Reading both ways costs a second and removes the need to guess
 * which kind of document arrived.
 *
 * <p>WHAT IT DOES TO THE IMAGE FIRST, and why so little. Grayscale and, for a small image, a
 * clean doubling. Both are reversible-looking operations that do not invent detail. The
 * tempting next steps -- thresholding, deskewing, sharpening -- each improve some photographs
 * and destroy others, and a reader that silently mangles one customer's title in ten is worse
 * than one that reads a bit less.
 */
@Component
public class TesseractReader {

    private static final Logger log = LoggerFactory.getLogger(TesseractReader.class);

    /**
     * Marks where the second reading of the same page begins, in the stored text.
     *
     * <p>IT IS IN THE TEXT RATHER THAN IN A SECOND COLUMN so that what a member of staff reads
     * is exactly what the extractor read, duplication and all. A stored reading that quietly
     * omitted half of what the fields were taken from would be the wrong kind of tidy.
     */
    public static final String SECOND_READING =
            "--- the same page, read again with a different layout ---";

    /** Below this, on the shorter side, doubling the image measurably helps. */
    private static final int SMALL_IMAGE = 1000;

    /** PDF pages are rendered at this, which is what Tesseract's own documentation suggests. */
    private static final float PDF_DPI = 300f;

    private final OcrProperties properties;

    public TesseractReader(OcrProperties properties) {
        this.properties = properties;
    }

    /**
     * Reads one uploaded document.
     *
     * @param content the bytes as uploaded
     * @param contentType what the browser said it was, used only to choose between rendering a
     *     PDF and decoding an image -- never trusted further, since the decode either works or
     *     fails on its own
     */
    public DocumentReading read(byte[] content, String contentType) {
        List<Path> pages = new ArrayList<>();
        try {
            pages = toPageImages(content, contentType);

            if (pages.isEmpty()) {
                return DocumentReading.unreadable(engineName());
            }

            StringBuilder text = new StringBuilder();
            for (Path page : pages) {
                /*
                 * SINGLE BLOCK FIRST, because when it works it keeps a label and its value on
                 * one line, which is unambiguous. The automatic reading goes second as the
                 * fallback for layouts the block mode flattens, and whatever it finds is only
                 * consulted where the first reading came up empty.
                 */
                String block = runTesseract(page, "6");
                String automatic = runTesseract(page, "3");

                if (block == null || automatic == null) {
                    return DocumentReading.failed(
                            "The document reader did not finish. The file is saved; please type"
                                    + " the details instead.");
                }

                append(text, block);
                if (!automatic.isBlank()) {
                    if (!text.isEmpty()) text.append("\n\n").append(SECOND_READING).append('\n');
                    text.append(automatic.strip());
                }
            }

            return text.isEmpty()
                    ? DocumentReading.unreadable(engineName())
                    : DocumentReading.read(text.toString(), engineName());

        } catch (UnsupportedDocument unsupported) {
            return DocumentReading.unreadable(engineName());
        } catch (IOException | RuntimeException failure) {
            log.warn("Reading an uploaded document failed", failure);
            return DocumentReading.failed(
                    "The document reader is not available. The file is saved; please type the"
                            + " details instead.");
        } finally {
            for (Path page : pages) {
                try {
                    Files.deleteIfExists(page);
                } catch (IOException ignored) {
                    /*
                     * A temporary file that will not delete is worth a line in the log and
                     * nothing more: the reading already succeeded and failing the request now
                     * would throw away a good result over housekeeping.
                     */
                    log.warn("A temporary page image could not be deleted: {}", page);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ the child process */

    private static void append(StringBuilder text, String reading) {
        if (reading.isBlank()) return;
        if (!text.isEmpty()) text.append("\n\n");
        text.append(reading.strip());
    }

    /** Returns the page's text, or null when the process failed or was killed. */
    private String runTesseract(Path image, String pageSegmentation) throws IOException {
        Path errors = Files.createTempFile("ocr-err-", ".txt");
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(
                            properties.binary(),
                            image.toAbsolutePath().toString(),
                            /* Write the text to standard output rather than to a file. */
                            "stdout",
                            "-l",
                            properties.languages(),
                            /*
                             * 6 is "a single uniform block of text"; 3 is the default, which
                             * detects columns and reading order. Which one is right depends on
                             * the document, so the caller runs both. See the class note.
                             */
                            "--psm",
                            pageSegmentation);

            /*
             * STDERR TO A FILE, NOT MERGED. Tesseract narrates to stderr ("Estimating
             * resolution as 384"), and merging it would put that sentence into the text the
             * customer is shown as what their title deed says.
             */
            builder.redirectError(errors.toFile());

            Process process = builder.start();

            /*
             * READ THE OUTPUT WHILE IT RUNS. A pipe has a fixed buffer: waiting for the process
             * to exit before reading deadlocks on any page long enough to fill it.
             */
            byte[] out = process.getInputStream().readAllBytes();

            if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("Tesseract exceeded {} on one page", properties.timeout());
                return null;
            }

            if (process.exitValue() != 0) {
                log.warn(
                        "Tesseract exited {} — {}",
                        process.exitValue(),
                        Files.readString(errors).strip());
                return null;
            }

            return new String(out, StandardCharsets.UTF_8);

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    private String engineName() {
        return "tesseract (" + properties.binary() + ")";
    }

    /* --------------------------------------------------------------------- the page images */

    /** Marks a file this reader cannot turn into pages. Caught above and reported, not thrown. */
    private static final class UnsupportedDocument extends RuntimeException {
        UnsupportedDocument() {
            super(null, null, false, false);
        }
    }

    private List<Path> toPageImages(byte[] content, String contentType) throws IOException {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf")) {
            return renderPdf(content);
        }
        return List.of(writeImage(decodeImage(content)));
    }

    /**
     * Turns a PDF into page images.
     *
     * <p>ALWAYS RENDERED, NEVER READ AS TEXT, even though many PDFs carry a text layer and
     * reading it would be instant and perfect. A customer uploading a PDF of a title is
     * usually uploading a scan with no text layer at all, and a reader that sometimes extracts
     * and sometimes recognises produces two different qualities of answer under one word. That
     * is a refinement to make deliberately, with the two paths labelled, rather than a branch
     * taken quietly here.
     */
    private List<Path> renderPdf(byte[] content) throws IOException {
        PDDocument pdf;

        /*
         * ONLY THE PARSE IS TREATED AS THE CUSTOMER'S FAULT. Wrapping the whole method would
         * mean a full disk while writing page two came back as "we could not read your
         * document", which is the server blaming the customer for its own fault -- the exact
         * confusion OcrStatus exists to prevent.
         */
        try {
            pdf = Loader.loadPDF(content);
        } catch (IOException notReallyAPdf) {
            throw new UnsupportedDocument();
        }

        try (pdf) {
            int pages = Math.min(pdf.getNumberOfPages(), properties.maxPages());
            if (pages == 0) throw new UnsupportedDocument();

            PDFRenderer renderer = new PDFRenderer(pdf);
            List<Path> images = new ArrayList<>(pages);
            for (int index = 0; index < pages; index++) {
                images.add(writeImage(renderer.renderImageWithDPI(index, PDF_DPI, ImageType.GRAY)));
            }
            return images;
        }
    }

    private static BufferedImage decodeImage(byte[] content) throws IOException {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException corrupt) {
            /* A truncated or malformed image is a file the customer sent, not a server fault. */
            throw new UnsupportedDocument();
        }

        /* ImageIO returns null rather than throwing when no decoder claims the bytes. */
        if (source == null) throw new UnsupportedDocument();

        int scale = Math.min(source.getWidth(), source.getHeight()) < SMALL_IMAGE ? 2 : 1;

        BufferedImage grey =
                new BufferedImage(
                        source.getWidth() * scale,
                        source.getHeight() * scale,
                        BufferedImage.TYPE_BYTE_GRAY);

        Graphics2D canvas = grey.createGraphics();
        try {
            canvas.drawImage(source, 0, 0, grey.getWidth(), grey.getHeight(), null);
        } finally {
            canvas.dispose();
        }
        return grey;
    }

    /**
     * Writes a page to a temporary PNG for Tesseract to open.
     *
     * <p>PNG RATHER THAN JPEG, because JPEG's compression invents smudges around high-contrast
     * edges, and on a page of text every edge is high-contrast. Re-encoding a photograph as
     * JPEG to hand it to a reader is paying for a smaller file with the thing being read.
     */
    private static Path writeImage(BufferedImage image) throws IOException {
        Path file = Files.createTempFile("ocr-page-", ".png");
        try {
            ImageIO.write(image, "png", file.toFile());
            return file;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(file);
            throw failure;
        }
    }
}
