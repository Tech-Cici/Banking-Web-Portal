package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.service.RegistrationService;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.ChallengeResponse;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.CompleteBusiness;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.CompletePersonal;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.RegistrationResultResponse;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.ResendCode;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.StartBusiness;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.StartPersonal;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.VerifiedResponse;
import rw.bank.ibanking.onboarding.web.dto.RegistrationDtos.VerifyCode;

/**
 * Registration, for people who do not have a login yet.
 *
 * <p>These six paths are the only unauthenticated write endpoints in the service, which
 * is why the rate limit and the attempt counter in {@link RegistrationService} are not
 * optional extras: anyone on the internet can call them.
 *
 * <p>Thin on purpose. No rule lives here — the controller validates the shape and hands
 * over. Anything that decides whether a registration may proceed belongs in the service,
 * where it is covered by tests that do not need a web layer.
 */
@RestController
@RequestMapping("/api/v1/registration")
class RegistrationController {

    private final RegistrationService registrations;

    RegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    /*
     * PERSONAL AND BUSINESS ARE TWO PATHS, sharing verify and resend.
     *
     * Company registration used to be a single POST to /registration/business that only
     * the front end's mock ever answered — the applicant got a reference number and a
     * page saying the bank had received their application, and nothing had been stored
     * anywhere. Building it as a parallel two-step flow rather than a one-shot submit
     * means it inherits the rate limit and the attempt counter that guard this
     * unauthenticated surface, instead of needing its own copies of both.
     */

    /** Checks the details against the bank's records and emails a code. */
    @PostMapping("/personal/start")
    ChallengeResponse startPersonal(@Valid @RequestBody StartPersonal request) {
        var challenge =
                registrations.startPersonal(
                        new RegistrationService.PersonalDetails(
                                request.accountNumber(),
                                request.nationalId(),
                                request.dateOfBirth(),
                                request.phone(),
                                request.email(),
                                request.fullName()));

        return new ChallengeResponse(
                challenge.challengeId(),
                challenge.otpLength(),
                challenge.resendAfterSeconds(),
                challenge.deliveryHint());
    }

    /**
     * Emails a code to the company's named contact.
     *
     * <p>Creates nothing durable: the details are held against the verification until the
     * code is entered. An application whose contact address nobody has proven may be
     * uncontactable, and every step after this one runs on that address.
     */
    @PostMapping("/business/start")
    ChallengeResponse startBusiness(@Valid @RequestBody StartBusiness request) {
        var challenge =
                registrations.startBusiness(
                        new RegistrationService.BusinessDetails(
                                request.companyName(),
                                request.registrationNumber(),
                                request.tin(),
                                request.businessType(),
                                request.sector(),
                                request.address(),
                                request.companyEmail(),
                                request.companyPhone(),
                                request.existingAccountNumber(),
                                request.contactFullName(),
                                request.contactRole(),
                                request.contactEmail(),
                                request.contactPhone(),
                                request.signatories() == null
                                        ? List.of()
                                        : request.signatories().stream()
                                                .map(
                                                        person ->
                                                                new RegistrationService.Signatory(
                                                                        person.fullName(),
                                                                        person.role(),
                                                                        person.nationalId(),
                                                                        person.email(),
                                                                        person.phone()))
                                                .toList(),
                                request.documentNames() == null
                                        ? List.of()
                                        : request.documentNames()));

        return new ChallengeResponse(
                challenge.challengeId(),
                challenge.otpLength(),
                challenge.resendAfterSeconds(),
                challenge.deliveryHint());
    }

    @PostMapping("/personal/verify")
    VerifiedResponse verify(@Valid @RequestBody VerifyCode request) {
        var verified = registrations.verify(request.challengeId(), request.code());
        return new VerifiedResponse(verified.verificationToken(), verified.expiresInSeconds());
    }

    @PostMapping("/otp/resend")
    ChallengeResponse resend(@Valid @RequestBody ResendCode request) {
        var challenge = registrations.resend(request.challengeId());
        return new ChallengeResponse(
                challenge.challengeId(),
                challenge.otpLength(),
                challenge.resendAfterSeconds(),
                challenge.deliveryHint());
    }

    /**
     * Creates the application.
     *
     * <p>201, because this is the call that brings something into existence — an
     * application in the bank's queue. The three above create nothing durable a client
     * could address.
     */
    @PostMapping("/personal/complete")
    @ResponseStatus(HttpStatus.CREATED)
    RegistrationResultResponse complete(@Valid @RequestBody CompletePersonal request) {
        var submitted = registrations.complete(request.verificationToken());
        return new RegistrationResultResponse(
                submitted.reference(), "SUBMITTED", submitted.message());
    }

    /** Creates the business application, its signatories and its promised document names. */
    @PostMapping("/business/complete")
    @ResponseStatus(HttpStatus.CREATED)
    RegistrationResultResponse completeBusiness(@Valid @RequestBody CompleteBusiness request) {
        var submitted = registrations.completeBusiness(request.verificationToken());
        return new RegistrationResultResponse(
                submitted.reference(), "SUBMITTED", submitted.message());
    }
}
