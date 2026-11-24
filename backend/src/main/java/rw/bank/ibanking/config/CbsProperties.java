package rw.bank.ibanking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to reach the bank's core banking system, bound from {@code ibanking.cbs.*}.
 *
 * @param enabled whether to open a connection at all. FALSE BY DEFAULT, so that a
 *     developer with no SQL Server running still gets an application that starts. The
 *     lookup then reports that the CBS is unavailable, which is the truth, rather than the
 *     application failing to boot over a system it only reads from.
 * @param url the JDBC url, e.g. {@code jdbc:sqlserver://localhost:1433;databaseName=ZigamaCbs}
 * @param username the login this service uses
 * @param password its password. NEVER DEFAULTED, never logged, and read from the
 *     environment rather than written here.
 */
@ConfigurationProperties(prefix = "ibanking.cbs")
public record CbsProperties(boolean enabled, String url, String username, String password) {

    public CbsProperties {
        url = url == null ? "" : url.trim();
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;

        /*
         * Checked only when enabled, and checked at startup rather than at the first
         * lookup. A missing url discovered by a member of staff mid-application is a
         * support call; the same mistake discovered by the application refusing to start
         * is a deployment that never happened.
         */
        if (enabled) {
            if (url.isEmpty()) {
                throw new IllegalArgumentException(
                        "ibanking.cbs.url must be set when ibanking.cbs.enabled is true.");
            }
            if (username.isEmpty() || password.isEmpty()) {
                throw new IllegalArgumentException(
                        "ibanking.cbs.username and ibanking.cbs.password must be set when"
                                + " ibanking.cbs.enabled is true.");
            }
        }
    }
}
