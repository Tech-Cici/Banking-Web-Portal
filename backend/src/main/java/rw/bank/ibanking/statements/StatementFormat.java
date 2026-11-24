package rw.bank.ibanking.statements;

/**
 * What a statement is produced as.
 *
 * <p>TWO, AND BOTH ARE REAL. PDF is what a customer prints or sends to a landlord; CSV is
 * what they open in a spreadsheet. Neither is a wrapper around the other -- the CSV carries
 * the same rows without the page furniture, which is what makes it useful.
 */
public enum StatementFormat {
    PDF("application/pdf", "pdf"),
    CSV("text/csv", "csv");

    private final String mediaType;
    private final String extension;

    StatementFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }
}
