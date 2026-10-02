package rw.bank.ibanking.onboarding.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.config.OutboundMailProperties;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.RateLimitedException;
import rw.bank.ibanking.onboarding.domain.ApplicationEntity;
import rw.bank.ibanking.onboarding.domain.ApplicationKind;
import rw.bank.ibanking.onboarding.domain.BusinessApplicationEntity;
import rw.bank.ibanking.onboarding.domain.BusinessDocumentEntity;
import rw.bank.ibanking.onboarding.domain.BusinessSignatoryEntity;
import rw.bank.ibanking.onboarding.domain.EmailVerificationEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.repo.ApplicationRepository;
import rw.bank.ibanking.onboarding.repo.BusinessApplicationRepository;
import rw.bank.ibanking.onboarding.repo.BusinessDocumentRepository;
import rw.bank.ibanking.onboarding.repo.BusinessSignatoryRepository;
import rw.bank.ibanking.onboarding.repo.EmailVerificationRepository;

/**
 * Registration: prove the email address, then create the application.
 *
 * <p>The order is the point. Nothing reaches an administrator's queue until somebody has
 * shown they can read the address they gave, because everything after this runs on that
 * address — the approval notice, and the conversation in which a temporary password is
 * arranged. An application with an unverified address may be uncontactable, or may belong
 * to somebody else entirely.
 *
 * <p>IDENTITY IS NOT CHECKED HERE, and that is the design rather than a gap. Whether the
 * account number, national id and date of birth belong to an existing client is answered
 * against the bank's own records by a separate process, and the manager's approval is
 * where that answer is enforced. This service validates the SHAPE of what was submitted
 * and nothing more.
 *
 * <p>The consequence runs through everything downstream: an application holds claims, not
 * facts. The applicant's own name is among them. No screen may present any of it as
 * confirmed before a manager has approved, and the approval is the point at which a human
 * takes responsibility for having checked.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /**
     * How many codes one address may be sent in the window below.
     *
     * <p>Low on purpose. This endpoint needs no session and sends mail to whatever address
     * it is given, so without a limit it is a way to use the bank to post mail to a
     * stranger — from the bank's own address, which is worse than spam.
     */
    private static final int MAX_CODES_PER_WINDOW = 3;

    private static final Duration RATE_WINDOW = Duration.ofMinutes(15);

    private final EmailVerificationRepository verifications;
    private final VerificationAttempts attempts;
    private final ApplicationRepository applications;
    private final BusinessApplicationRepository businesses;
    private final BusinessSignatoryRepository signatories;
    private final BusinessDocumentRepository documents;
    private final Mailer mailer;
    private final OutboundMailProperties mailSettings;
    private final ObjectMapper json;

    RegistrationService(
            EmailVerificationRepository verifications,
            VerificationAttempts attempts,
            ApplicationRepository applications,
            BusinessApplicationRepository businesses,
            BusinessSignatoryRepository signatories,
            BusinessDocumentRepository documents,
            Mailer mailer,
            OutboundMailProperties mailSettings,
            ObjectMapper json) {
        this.verifications = verifications;
        this.attempts = attempts;
        this.applications = applications;
        this.businesses = businesses;
        this.signatories = signatories;
        this.documents = documents;
        this.mailer = mailer;
        this.mailSettings = mailSettings;
        this.json = json;
    }

    /** What the caller needs to enter a code. */
    public record Challenge(String challengeId, int otpLength, long resendAfterSeconds, String deliveryHint) {}

    /** Proof that a code was entered, exchanged at the final step. */
    public record Verified(String verificationToken, long expiresInSeconds) {}

    /** The outcome of a completed registration. */
    public record Submitted(String reference, String message) {}

    /** The details captured at the first step, held server-side until the last. */
    public record PersonalDetails(
            String accountNumber,
            String nationalId,
            LocalDate dateOfBirth,
            String phone,
            String email,
            /**
             * The name the APPLICANT gave. A claim, not a fact.
             *
             * <p>This service cannot check it. There is no core banking lookup here by
             * design — matching the account number, national id and date of birth against
             * the bank's records happens outside this system, and a manager approves only
             * once that has been done. So everything the applicant submits is held and
             * shown as submitted, and nothing in the portal may present it as verified
             * before that approval.
             */
            String fullName) {}

    /**
     * What a company submitted, held server-side between {@code start} and
     * {@code complete}.
     *
     * <p>ALL CLAIMS, like the personal equivalent. This service has no company register
     * to check a registration number against and no way to know whether the person
     * filling the form may act for the company. An administrator reads these against the
     * bank's own records; the approving manager is where a human takes responsibility.
     *
     * <p>THE CODE GOES TO {@link #contactEmail}, not {@link #companyEmail}. The contact
     * is a named person who can be asked a question; a company inbox is frequently shared
     * or forwarded to several people, and proving that somebody can read it proves very
     * little about who they are. Everything afterwards — the approval, the conversation
     * in which credentials are arranged — runs on the address that was proven.
     */
    public record BusinessDetails(
            String companyName,
            String registrationNumber,
            String tin,
            String businessType,
            String sector,
            String address,
            String companyEmail,
            String companyPhone,
            String existingAccountNumber,
            String contactFullName,
            String contactRole,
            String contactEmail,
            String contactPhone,
            List<Signatory> signatories,
            /**
             * File NAMES the applicant listed. The files themselves never arrive — there
             * is no upload endpoint behind the wizard's Documents step. Kept so staff can
             * see what was promised and ask for it; see BusinessDocumentEntity.
             */
            List<String> documentNames) {}

    /** One person the company says may act on its account. Unverified, and not authorised. */
    public record Signatory(
            String fullName, String role, String nationalId, String email, String phone) {}

    /* ------------------------------------------------------------------ start */

    @Transactional
    public Challenge startPersonal(PersonalDetails submitted) {
        String email = normalise(submitted.email());

        /*
         * No identity check here, and that is deliberate rather than unfinished.
         *
         * Whether these details belong to an existing client is answered against the
         * bank's own records, by a separate process, and a manager approves the account
         * only once that answer is yes. This service's job is to capture the claim, prove
         * the applicant can read the email address they gave, and put it in front of
         * staff. The shape of the account number is checked by the request DTO; its
         * existence is not this system's question.
         *
         * What this means for everything downstream: the name, account number, national
         * id and date of birth are UNVERIFIED until a manager approves. The screens say
         * so, and they must keep saying so.
         */
        PersonalDetails details = submitted;

        enforceRateLimit(email);

        String code = VerificationCodes.generate();
        EmailVerificationEntity verification =
                EmailVerificationEntity.issue(
                        email,
                        VerificationCodes.hash(code),
                        ApplicationKind.PERSONAL,
                        serialise(details),
                        mailSettings.codeValidity());
        verifications.save(verification);

        /*
         * The code is passed straight to the mailer and never logged, never returned and
         * never held beyond this method. The only copies are the hash in the database and
         * the message in the customer's inbox.
         */
        mailer.send(
                OutboxKind.EMAIL_VERIFICATION,
                email,
                EmailTemplates.VERIFICATION_SUBJECT,
                EmailTemplates.verification(code, mailSettings.codeValidity().toMinutes()));

        log.info("Issued a verification code for a registration (id={})", verification.id());

        return new Challenge(verification.id().toString(), 6, 30, maskEmail(email));
    }

    /* --------------------------------------------------------- start business */

    /**
     * Emails a code to the company's named contact and holds the application until it is
     * entered.
     *
     * <p>DELIBERATELY THE SAME SHAPE AS {@link #startPersonal}, sharing the same rate
     * limit, the same verification table and the same {@link #verify} endpoint. Company
     * registration used to be a single unauthenticated POST that the front end's mock
     * answered; making it a second, parallel path rather than a special case means the
     * limit that stops this service being used to post mail to strangers applies here
     * too, without anyone having to remember to add it.
     *
     * <p>NOTHING REACHES THE STAFF QUEUE YET. That is the whole reason the flow has two
     * steps: an application whose contact address nobody has proven may be uncontactable,
     * or may belong to somebody else entirely, and every later step — the approval, the
     * conversation in which credentials are arranged — runs on that address.
     */
    @Transactional
    public Challenge startBusiness(BusinessDetails submitted) {
        String email = normalise(submitted.contactEmail());

        enforceRateLimit(email);

        String code = VerificationCodes.generate();
        EmailVerificationEntity verification =
                EmailVerificationEntity.issue(
                        email,
                        VerificationCodes.hash(code),
                        ApplicationKind.BUSINESS,
                        serialiseBusiness(submitted),
                        mailSettings.codeValidity());
        verifications.save(verification);

        mailer.send(
                OutboxKind.EMAIL_VERIFICATION,
                email,
                EmailTemplates.VERIFICATION_SUBJECT,
                EmailTemplates.verification(code, mailSettings.codeValidity().toMinutes()));

        /*
         * The company name is not logged, and neither is the address.
         *
         * Which companies are applying to bank at Zigama is commercially sensitive, and a
         * log aggregator is read by more people than the staff queue is. The verification
         * id is enough to follow one registration through the logs.
         */
        log.info("Issued a verification code for a business registration (id={})", verification.id());

        return new Challenge(verification.id().toString(), 6, 30, maskEmail(email));
    }

    /* ----------------------------------------------------------------- verify */

    @Transactional
    public Verified verify(String challengeId, String code) {
        EmailVerificationEntity verification =
                verifications
                        .findById(parseId(challengeId))
                        .orElseThrow(
                                () ->
                                        new BusinessRuleException(
                                                "This registration has expired. Please start"
                                                        + " again."));

        Instant now = Instant.now();

        if (verification.consumed()) {
            throw new BusinessRuleException(
                    "That code has already been used. Please start again.");
        }
        if (verification.exhausted()) {
            throw new BusinessRuleException(
                    "Too many incorrect codes were entered, so this code has been cancelled."
                            + " Please start again.");
        }
        if (verification.expired(now)) {
            throw new BusinessRuleException(
                    "That code has expired — they only last a few minutes. Please start again to"
                            + " get a new one.");
        }

        if (!VerificationCodes.matches(code, verification.codeHash())) {
            /*
             * Counted in a SEPARATE transaction, because this one is about to roll back.
             *
             * Incrementing here and saving would be undone by the throw below, which is
             * exactly the bug that made the attempt limit decorative — see
             * VerificationAttempts for the full story. The counter has to commit even
             * though the request fails.
             */
            int left = attempts.recordFailure(verification.id());

            throw new BusinessRuleException(
                    left <= 0
                            ? "That code is not correct, and there are no attempts left. Please"
                                    + " start again."
                            : "That code is not correct. Check the code we sent you and type it"
                                    + " again. "
                                    + left
                                    + (left == 1 ? " attempt left." : " attempts left."));
        }

        String token = VerificationCodes.newToken();
        verification.consume(token);
        verifications.save(verification);

        return new Verified(token, mailSettings.codeValidity().toSeconds());
    }

    /* ----------------------------------------------------------------- resend */

    @Transactional
    public Challenge resend(String challengeId) {
        EmailVerificationEntity previous =
                verifications
                        .findById(parseId(challengeId))
                        .orElseThrow(
                                () ->
                                        new BusinessRuleException(
                                                "This registration has expired. Please start"
                                                        + " again."));

        /*
         * DISPATCHED ON THE STORED KIND, not assumed.
         *
         * This used to read the payload back as personal details unconditionally, which
         * was correct while personal was the only flow and would now deserialise a
         * company into a person — a Jackson error surfacing as "could not read stored
         * registration details", which reads as a corrupt database rather than as the
         * missing discriminator it is.
         *
         * Re-running start rather than re-sending the same code also re-runs the rate
         * limit, because resend is the obvious way around a limit that only guards start.
         */
        return switch (previous.kind()) {
            case PERSONAL -> startPersonal(deserialise(previous.payload()));
            case BUSINESS -> startBusiness(deserialiseBusiness(previous.payload()));
            /*
             * Joining an existing company proves nothing by email: it asks that company's
             * own administrator for access. No code is ever issued for it, so a row
             * claiming to be one is a bug rather than something to handle gracefully.
             */
            case JOIN_BUSINESS ->
                    throw new IllegalStateException(
                            "A verification code exists for a flow that does not use one.");
        };
    }

    /* --------------------------------------------------------------- complete */

    @Transactional
    public Submitted complete(String verificationToken) {
        EmailVerificationEntity verification =
                verifications
                        .findByToken(verificationToken)
                        .orElseThrow(
                                () ->
                                        new BusinessRuleException(
                                                "This registration has expired. Please start"
                                                        + " again."));

        PersonalDetails details = deserialise(verification.payload());

        ApplicationEntity application =
                ApplicationEntity.submitted(
                        reference("REG"),
                        ApplicationKind.PERSONAL,
                        /*
                         * The bank's name, put here by startPersonal. It used to fall back
                         * to the email address when absent, which is how a customer could
                         * end up with an email address as their recorded name — the
                         * registration form never collects one. Blank now means the
                         * lookup was bypassed, which is a fault rather than a default.
                         */
                        /*
                         * The name as the APPLICANT gave it. @NotBlank on the request
                         * means it cannot be empty by this point; if it somehow is, that
                         * is a fault in this service rather than something to paper over
                         * with a default — an application in a bank's queue with no name
                         * on it is not a thing staff can act on.
                         */
                        blankToNull(details.fullName())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "A verified registration reached"
                                                            + " submission with no applicant"
                                                            + " name.")),
                        details.email(),
                        details.phone(),
                        // Reaching this point required entering a code sent to that address.
                        true);
        application.personalDetails(
                details.accountNumber(), details.nationalId(), details.dateOfBirth());
        applications.save(application);

        // The token is single-use: one verification must not be able to create two
        // applications.
        verification.burnToken();
        verifications.save(verification);

        log.info("Registration {} submitted", application.reference());

        return new Submitted(
                application.reference(),
                "Your registration has been received. The bank will check your details and"
                        + " email you when your account is ready.");
    }

    /* ------------------------------------------------------ complete business */

    /**
     * Turns a verified business registration into an application in the staff queue.
     *
     * <p>ONE TRANSACTION for the application, the company details, the signatories and
     * the promised document names. A company application half-written is worse than none:
     * staff would see a queue entry with no signatories and no way to know whether the
     * applicant listed none or the write failed halfway.
     */
    @Transactional
    public Submitted completeBusiness(String verificationToken) {
        EmailVerificationEntity verification =
                verifications
                        .findByToken(verificationToken)
                        .orElseThrow(
                                () ->
                                        new BusinessRuleException(
                                                "This registration has expired. Please start"
                                                        + " again."));

        /*
         * The token proves an address was verified; it does not say which flow. A token
         * from a personal registration must not be spendable here — it would read the
         * personal payload as a company and fail confusingly, and more importantly it
         * would let one verification create an application of a kind the applicant never
         * filled in.
         */
        if (verification.kind() != ApplicationKind.BUSINESS) {
            throw new BusinessRuleException(
                    "This registration has expired. Please start again.");
        }

        BusinessDetails details = deserialiseBusiness(verification.payload());

        ApplicationEntity application =
                ApplicationEntity.submitted(
                        reference("BRA"),
                        ApplicationKind.BUSINESS,
                        /*
                         * The COMPANY name is the display name, because that is what
                         * staff scan a queue of applications by — not the contact's name,
                         * which is one field further in and identifies a person rather
                         * than the applicant.
                         */
                        details.companyName(),
                        /*
                         * The CONTACT's address and phone, not the company switchboard.
                         * This is the address the code was sent to and the one the
                         * approval will go to.
                         */
                        normalise(details.contactEmail()),
                        details.contactPhone(),
                        // Reaching this point required entering a code sent to that address.
                        true);
        applications.save(application);

        businesses.save(
                BusinessApplicationEntity.of(
                        application.id(),
                        new BusinessApplicationEntity.Details(
                                details.companyName(),
                                details.registrationNumber(),
                                details.tin(),
                                details.businessType(),
                                details.sector(),
                                details.address(),
                                details.companyEmail(),
                                details.companyPhone(),
                                details.existingAccountNumber(),
                                details.contactFullName(),
                                details.contactRole())));

        List<Signatory> listed =
                details.signatories() == null ? List.of() : details.signatories();
        List<BusinessSignatoryEntity> rows = new ArrayList<>();
        for (int i = 0; i < listed.size(); i++) {
            Signatory person = listed.get(i);
            rows.add(
                    BusinessSignatoryEntity.listed(
                            application.id(),
                            // 1-based: "Signatory 1" is what the form and the screen call it.
                            i + 1,
                            person.fullName(),
                            person.role(),
                            person.nationalId(),
                            person.email(),
                            person.phone()));
        }
        signatories.saveAll(rows);

        /*
         * Names only. `promised` is the only factory, and it cannot set `received` true —
         * see BusinessDocumentEntity. Recorded rather than dropped so an administrator
         * knows what paperwork to ask for.
         */
        List<String> names = details.documentNames() == null ? List.of() : details.documentNames();
        documents.saveAll(
                names.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> BusinessDocumentEntity.promised(application.id(), name.trim()))
                        .toList());

        verification.burnToken();
        verifications.save(verification);

        log.info(
                "Business registration {} submitted with {} signatories and {} promised documents",
                application.reference(),
                rows.size(),
                names.size());

        return new Submitted(
                application.reference(),
                "Your application has been received. The bank will review it and contact your"
                        + " named representative.");
    }

    /* ------------------------------------------------------------------ rules */

    /*
     * There is deliberately no detailsMatchBankRecords / lookUpBankRecord method here any
     * more.
     *
     * It used to accept one hardcoded account number and refuse everything else, which
     * made it impossible to register any real client and was only ever a stand-in. The
     * decision it stood in for — is this an existing client, and are these their details?
     * — is made outside this system against the bank's records, and enforced by the
     * manager's approval. Reintroducing a lookup here would mean this service answering a
     * question it has no source of truth for.
     */

    private void enforceRateLimit(String email) {
        long recent =
                verifications.countByEmailIgnoreCaseAndCreatedAtAfter(
                        email, Instant.now().minus(RATE_WINDOW));

        if (recent >= MAX_CODES_PER_WINDOW) {
            throw new RateLimitedException(
                    "You have asked for a code too many times. Please wait a few minutes and try"
                            + " again.",
                    RATE_WINDOW);
        }
    }

    /* ------------------------------------------------------------------ plumbing */

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Masks the address for display back to the applicant.
     *
     * <p>Enough to recognise what they typed, not enough to be worth reading off somebody
     * else's screen: {@code nicaise@kwikkoders.com} becomes {@code n******@kwikkoders.com}.
     */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at < 1) return "***";
        String name = email.substring(0, at);
        return name.charAt(0) + "*".repeat(Math.max(1, name.length() - 1)) + email.substring(at);
    }

    private static Optional<String> blankToNull(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static String reference(String prefix) {
        return prefix + "-" + VerificationCodes.newToken().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static java.util.UUID parseId(String value) {
        try {
            return java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // A malformed id is indistinguishable from an expired one, on purpose: the
            // client learns nothing about which ids exist.
            throw new BusinessRuleException("This registration has expired. Please start again.");
        }
    }

    private String serialise(PersonalDetails details) {
        try {
            return json.writeValueAsString(details);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not store registration details", e);
        }
    }

    private PersonalDetails deserialise(String payload) {
        try {
            return json.readValue(payload, PersonalDetails.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not read stored registration details", e);
        }
    }

    private String serialiseBusiness(BusinessDetails details) {
        try {
            return json.writeValueAsString(details);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not store registration details", e);
        }
    }

    private BusinessDetails deserialiseBusiness(String payload) {
        try {
            return json.readValue(payload, BusinessDetails.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not read stored registration details", e);
        }
    }
}
