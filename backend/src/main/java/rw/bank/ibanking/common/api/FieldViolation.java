package rw.bank.ibanking.common.api;

/**
 * One field-level validation failure.
 *
 * @param field   dot-path of the offending field, e.g. {@code amount} or {@code beneficiary.iban}
 * @param code    constraint identifier, e.g. {@code NotBlank}
 * @param message safe, human-readable message
 */
public record FieldViolation(String field, String code, String message) {}
