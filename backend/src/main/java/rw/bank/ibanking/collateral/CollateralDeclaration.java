package rw.bank.ibanking.collateral;

import java.util.Map;
import java.util.UUID;

/**
 * What a customer says they are pledging, as it arrives with a loan application.
 *
 * <p>THE VALUES ARE THEIRS, NOT THE READER'S. The form offered whatever could be read off the
 * document; what comes back here is what the customer left in the boxes after looking at them.
 * That is the declaration, and it is what the bank holds them to. What the reader found is
 * recovered separately, from the stored text, and kept beside it.
 *
 * @param documentId a document this same customer has already uploaded
 * @param description their own words for the thing: "Plot at Kabeza", "Toyota Hiace"
 * @param values one entry per field the kind asks for
 */
public record CollateralDeclaration(
        UUID documentId, String description, Map<CollateralFieldKey, String> values) {}
