package rw.bank.ibanking.onboarding.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire shapes for registration.
 *
 * <p>These match `frontend/src/types/registration.ts` field for field. That file is the
 * agreed contract and the front end is already written against it, so the names here are
 * not free to change.
 *
 * <p>Validation lives on the DTO rather than in the service: a malformed request should be
 * rejected at the edge with field errors the form can bind, before any business rule runs.
 */
public final class RegistrationDtos {

    private RegistrationDtos() {}

    /** POST /registration/personal/start */
    public record StartPersonal(
            /*
             * FORMAT ONLY. Whether this account exists, and whether it belongs to the
             * person registering, is checked against the bank's records by a separate
             * process — and enforced by the manager's approval, not here.
             *
             * 10 to 16 digits, matching what the registration form accepts. The two used
             * to disagree (6-20 here, 10-16 there), which meant the form refused numbers
             * the API would have taken: a client with a valid short account number would
             * have been turned away by the browser with no way to find out why.
             *
             * THIS RANGE IS A GUESS AND THE BANK MUST CONFIRM IT. Too narrow turns real
             * clients away at the door. See frontend/docs/OPEN-ITEMS.md.
             */
            @NotBlank(message = "Enter your account number.")
                    @Pattern(
                            regexp = "\\d{10,16}",
                            message = "An account number is between 10 and 16 digits.")
                    String accountNumber,
            @NotBlank(message = "Enter your national ID number.")
                    @Pattern(regexp = "\\d{16}", message = "A national ID number has 16 digits.")
                    String nationalId,
            @Past(message = "A date of birth must be in the past.") LocalDate dateOfBirth,
            @NotBlank(message = "Enter your mobile number.")
                    @Pattern(
                            regexp = "\\+?\\d{9,15}",
                            message = "Enter a mobile number, digits only.")
                    String phone,
            @NotBlank(message = "Enter your email address.")
                    @Email(message = "Enter a valid email address.")
                    @Size(max = 254)
                    String email,
            /*
             * Required now. It used to be optional because the service filled it in from
             * a placeholder "bank record"; with no lookup here, the applicant is the only
             * source — and a queue of applications identified only by email address is
             * not something staff can work from.
             *
             * It is a CLAIM. Staff verify it against the bank's records before approving.
             */
            @NotBlank(message = "Enter your full name, as the bank holds it.")
                    @Size(max = 160)
                    String fullName) {}

    /**
     * POST /registration/business/start
     *
     * <p>SHAPE ONLY, and less strict than it looks like it should be. This service has no
     * company register to check a registration number against and no tax authority to
     * confirm a TIN with, so a pattern here would be a guess about the format of real
     * Rwandan company identifiers — and a wrong guess turns a real company away at a
     * public form with no way to find out why. Length caps match the columns; the
     * judgement is a person's, against the bank's own records.
     */
    public record StartBusiness(
            @NotBlank(message = "Enter the company name.") @Size(max = 200) String companyName,
            @NotBlank(message = "Enter the company registration number.")
                    @Size(max = 60)
                    String registrationNumber,
            @NotBlank(message = "Enter the company TIN.") @Size(max = 40) String tin,
            @NotBlank(message = "Choose the type of business.") @Size(max = 80) String businessType,
            @NotBlank(message = "Choose the sector.") @Size(max = 80) String sector,
            @NotBlank(message = "Enter the registered address.")
                    @Size(max = 400)
                    String address,
            @NotBlank(message = "Enter the company email address.")
                    @Email(message = "Enter a valid email address.")
                    @Size(max = 254)
                    String companyEmail,
            @NotBlank(message = "Enter the company phone number.")
                    @Pattern(
                            regexp = "\\+?\\d{9,15}",
                            message = "Enter a phone number, digits only.")
                    String companyPhone,
            /* Optional: a company applying for internet banking may hold no account yet. */
            @Size(max = 34) String existingAccountNumber,
            @NotBlank(message = "Enter the name of the person we should contact.")
                    @Size(max = 160)
                    String contactFullName,
            @NotBlank(message = "Enter that person's role at the company.")
                    @Size(max = 120)
                    String contactRole,
            /*
             * THE ADDRESS THE CODE GOES TO, which is why it is validated as an email and
             * the company's own inbox is not the one used. A named person can be asked a
             * question; a shared company inbox proves very little about who read it.
             */
            @NotBlank(message = "Enter that person's email address.")
                    @Email(message = "Enter a valid email address.")
                    @Size(max = 254)
                    String contactEmail,
            @NotBlank(message = "Enter that person's phone number.")
                    @Pattern(
                            regexp = "\\+?\\d{9,15}",
                            message = "Enter a phone number, digits only.")
                    String contactPhone,
            /*
             * At least one, capped at twenty.
             *
             * A company mandate with nobody authorised to act on it is not something
             * staff can process, and twenty is well past any real signatory list — the
             * cap is there because this is a public endpoint and the list is stored one
             * row per entry.
             */
            @Valid
                    @NotEmpty(message = "List at least one authorised signatory.")
                    @Size(max = 20, message = "That is more signatories than we can accept here.")
                    List<SignatoryDto> signatories,
            /*
             * File NAMES. There is no upload endpoint behind the wizard's Documents step,
             * so these are recorded as promised and the files are not held. See
             * BusinessDocumentEntity and docs/OPEN-ITEMS.md.
             */
            @Size(max = 20, message = "That is more documents than we can list.")
                    List<@Size(max = 260) String> documentNames) {}

    /** One signatory on a business application. Unverified, and not authorised by being listed. */
    public record SignatoryDto(
            @NotBlank(message = "Enter the signatory's full name.")
                    @Size(max = 160)
                    String fullName,
            @NotBlank(message = "Enter the signatory's role.") @Size(max = 120) String role,
            /*
             * No 16-digit pattern, unlike the personal form. A signatory may be a foreign
             * director whose identity document is not a Rwandan national ID, and refusing
             * those here would turn away a real company. The check that matters is a
             * human comparing it against the document.
             */
            @NotBlank(message = "Enter the signatory's ID number.")
                    @Size(max = 40)
                    String nationalId,
            @Email(message = "Enter a valid email address.") @Size(max = 254) String email,
            @Pattern(
                            regexp = "|\\+?\\d{9,15}",
                            message = "Enter a phone number, digits only.")
                    String phone) {}

    /** POST /registration/business/complete */
    public record CompleteBusiness(@NotBlank String verificationToken) {}

    /** POST /registration/personal/verify */
    public record VerifyCode(
            @NotBlank String challengeId,
            @NotBlank(message = "Enter the code we sent you.")
                    @Pattern(regexp = "\\d{6}", message = "The code has 6 digits.")
                    String code) {}

    /** POST /registration/otp/resend */
    public record ResendCode(@NotBlank String challengeId) {}

    /** POST /registration/personal/complete */
    public record CompletePersonal(@NotBlank String verificationToken) {}

    /* ------------------------------------------------------------- responses */

    /** Matches OtpChallenge. */
    public record ChallengeResponse(
            String challengeId, int otpLength, long resendAfterSeconds, String deliveryHint) {}

    /** Matches VerifiedChallenge. */
    public record VerifiedResponse(String verificationToken, long expiresInSeconds) {}

    /** Matches RegistrationResult. */
    public record RegistrationResultResponse(String reference, String status, String message) {}
}
