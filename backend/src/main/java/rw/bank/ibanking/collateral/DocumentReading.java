package rw.bank.ibanking.collateral;

/**
 * What came back from reading one document.
 *
 * <p>NEVER AN EXCEPTION FOR AN UNREADABLE PAGE. A blurred photograph is an ordinary outcome of
 * asking people to photograph documents, and an exception would turn it into a failed upload
 * and lose the file the customer took the trouble to send. The file is kept either way; only
 * the reading is missing, and the form falls back to being typed.
 *
 * @param status how it ended
 * @param text what the page appeared to say, or null when nothing was read
 * @param engine which reader and which version, recorded because the same page read by a
 *     different version gives different text and a future reader needs to know which one
 *     produced the words on file
 * @param failure why it did not run, in words a customer can be shown, or null
 */
public record DocumentReading(OcrStatus status, String text, String engine, String failure) {

    public static DocumentReading read(String text, String engine) {
        return new DocumentReading(OcrStatus.READ, text, engine, null);
    }

    public static DocumentReading unreadable(String engine) {
        return new DocumentReading(OcrStatus.UNREADABLE, null, engine, null);
    }

    public static DocumentReading failed(String reason) {
        return new DocumentReading(OcrStatus.FAILED, null, null, reason);
    }

    public boolean hasText() {
        return status == OcrStatus.READ && text != null && !text.isBlank();
    }
}
