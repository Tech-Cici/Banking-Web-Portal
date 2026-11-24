package rw.bank.ibanking.collateral;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the document reader is run.
 *
 * <p>THE BINARY IS CONFIGURATION, NOT A CONSTANT. Tesseract lands in a different place on a
 * developer's Mac (/opt/homebrew/bin) than in a Linux container (/usr/bin), and a hard-coded
 * path turns a working feature into a mystery on the other machine.
 *
 * <p>THE TIMEOUT EXISTS BECAUSE THE READER IS ANOTHER PROGRAM. It can hang on a malformed
 * image, and a web request that waits for it forever holds a thread and then a connection
 * pool. Thirty seconds is far longer than a page needs and short enough that a customer gets
 * an answer rather than a spinner.
 *
 * <p>THE PAGE CAP IS A DEFENCE, not a preference. Somebody will upload a forty-page scan, and
 * each page costs seconds of CPU; the first few pages of a certificate carry its identifiers.
 *
 * @param binary the tesseract executable
 * @param languages the -l argument; "eng" alone unless the training data for others is
 *     installed, since naming a language whose data is missing makes tesseract exit rather
 *     than fall back
 * @param timeout how long one page may take before the process is killed
 * @param maxPages how many pages of a PDF are rendered and read
 * @param maxBytes the largest upload accepted, matching V35's own CHECK
 */
@ConfigurationProperties(prefix = "ibanking.ocr")
public record OcrProperties(
        String binary, String languages, Duration timeout, int maxPages, long maxBytes) {

    public OcrProperties {
        if (binary == null || binary.isBlank()) binary = "tesseract";
        if (languages == null || languages.isBlank()) languages = "eng";
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(30);
        }
        if (maxPages <= 0) maxPages = 3;

        /*
         * THE SAME NUMBER AS THE CHECK IN V35. A configured value above it would let an upload
         * past this layer only to be refused by the database, which is a 500 where a sentence
         * was wanted.
         */
        if (maxBytes <= 0 || maxBytes > 8_388_608L) maxBytes = 8_388_608L;
    }
}
