package rw.bank.ibanking.collateral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One field on one pledge: what the customer declared, what the reader found, and whether they
 * agreed.
 *
 * <p>THE COMPARISON IS MADE ONCE AND KEPT. It is a statement about the document as it was read
 * on the day the application was made. Re-deriving it later, from a reading that may since
 * have been redone with a different engine, would quietly rewrite the history of a credit
 * decision -- a field that disagreed at the time would come to look as though it had always
 * matched.
 */
@Entity
@Table(name = "collateral_fields")
public class CollateralFieldEntity {

    @Id private UUID id;

    @Column(name = "collateral_id", nullable = false)
    private UUID collateralId;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_key", nullable = false, length = 40)
    private CollateralFieldKey fieldKey;

    @Column(name = "declared_value", nullable = false, length = 200)
    private String declaredValue;

    @Column(name = "read_value", length = 200)
    private String readValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FieldAgreement agreement;

    protected CollateralFieldEntity() {
        // JPA.
    }

    private CollateralFieldEntity(
            UUID collateralId, CollateralFieldKey fieldKey, String declaredValue, String readValue) {

        this.id = UUID.randomUUID();
        this.collateralId = collateralId;
        this.fieldKey = fieldKey;
        this.declaredValue = declaredValue;
        this.readValue = readValue;
        this.agreement = agreementOf(declaredValue, readValue);
    }

    public static CollateralFieldEntity of(
            UUID collateralId, CollateralFieldKey fieldKey, String declaredValue, String readValue) {

        return new CollateralFieldEntity(collateralId, fieldKey, declaredValue, readValue);
    }

    /**
     * Compares ignoring case, spacing and punctuation.
     *
     * <p>BECAUSE THOSE ARE THE READER'S, NOT THE DOCUMENT'S. A page printing 1/03/01/04/30 is
     * read as "1 / 03 / 01 / 04 / 30" and a plate reading RAD 123 A as "RAD123A"; marking
     * either as a disagreement would fill a reviewer's screen with differences that are not
     * differences, and a screen of false alarms is one nobody reads.
     */
    private static FieldAgreement agreementOf(String declared, String read) {
        if (read == null || read.isBlank()) return FieldAgreement.NOT_READ;

        return compactly(declared).equals(compactly(read))
                ? FieldAgreement.SAME
                : FieldAgreement.DIFFERENT;
    }

    private static String compactly(String value) {
        return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public UUID id() {
        return id;
    }

    public UUID collateralId() {
        return collateralId;
    }

    public CollateralFieldKey fieldKey() {
        return fieldKey;
    }

    public String declaredValue() {
        return declaredValue;
    }

    public String readValue() {
        return readValue;
    }

    public FieldAgreement agreement() {
        return agreement;
    }
}
