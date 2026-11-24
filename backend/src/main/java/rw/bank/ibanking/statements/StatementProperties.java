package rw.bank.ibanking.statements;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What a statement calls the institution that issued it.
 *
 * <p>THE DEMONSTRATION NAME IS THE DEFAULT, for the same reason the frontend's brand.ts
 * defaults to it. A statement is a document that leaves the portal: it is downloaded,
 * forwarded, printed and attached to things. A PDF carrying a real bank's name, laid out like
 * a bank's statement and listing plausible transactions, is a fabricated financial record the
 * moment it leaves this machine -- regardless of what it was built for. The demonstration name
 * keeps it obviously what it is, and the footer on every page says so again.
 *
 * <p>IT IS CONFIGURATION RATHER THAN A CONSTANT so a real deployment, by a bank that actually
 * issues these, can set its own name deliberately. That is a decision somebody makes once, in
 * an environment they control, not something a build inherits by forgetting.
 */
@ConfigurationProperties(prefix = "ibanking.statements")
public record StatementProperties(String brandName) {

    public StatementProperties {
        if (brandName == null || brandName.isBlank()) {
            brandName = "Ciara's demo - Internet Banking";
        }
    }
}
