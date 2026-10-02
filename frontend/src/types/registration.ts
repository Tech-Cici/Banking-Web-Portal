/**
 * Registration DTOs.
 *
 * PROPOSED CONTRACT — NOT DEFINED IN THE BLUEPRINT. Section 25's route list has no
 * registration endpoints, and section 5.1 mentions registration only as
 * "Optional: Register/Activate Internet Banking if onboarding is enabled".
 *
 * These shapes are what the front end needs; the backend team should treat them as a
 * starting point, publish the real OpenAPI document, and this file should then be
 * generated from it rather than maintained by hand.
 *
 * Proposed endpoints:
 *
 *   POST /api/v1/registration/personal/start      -> OtpChallenge
 *   POST /api/v1/registration/personal/verify     -> VerifiedChallenge
 *   POST /api/v1/registration/personal/complete   -> RegistrationResult
 *   POST /api/v1/registration/business            -> RegistrationResult
 *   POST /api/v1/registration/join                -> RegistrationResult
 *   POST /api/v1/registration/otp/resend          -> OtpChallenge
 */

/** Returned when the server has issued a one-time code. */
export interface OtpChallenge {
  /** Opaque id that must accompany verification (blueprint 5.2). */
  readonly challengeId: string;
  /** How many digits the code has. Never assumed client-side. */
  readonly otpLength: number;
  /** Seconds before a resend is permitted. Enforced by the server. */
  readonly resendAfterSeconds: number;
  /** Partially masked destination, e.g. "***456". Never the full number. */
  readonly deliveryHint: string;
}

/** Short-lived token proving a challenge was completed. */
export interface VerifiedChallenge {
  readonly verificationToken: string;
  readonly expiresInSeconds: number;
}

/** Status of a submitted registration. */
export type RegistrationStatus =
  'COMPLETED' | 'SUBMITTED' | 'UNDER_REVIEW' | 'PENDING_APPROVAL' | 'REJECTED';

export interface RegistrationResult {
  /** Reference the applicant quotes when chasing progress. */
  readonly reference: string;
  readonly status: RegistrationStatus;
  /** Safe, displayable description of what happens next. */
  readonly message: string;
}

/* ------------------------------------------------ personal */

export interface PersonalRegistrationStart {
  /**
   * The applicant's name, as they say the bank holds it.
   *
   * A CLAIM, not a fact. This system has no core banking lookup — matching these details
   * against the bank's records happens separately, and a manager approves only once that
   * has been done. Nothing may present this as confirmed before then.
   */
  readonly fullName: string;
  readonly accountNumber: string;
  readonly nationalId: string;
  readonly dateOfBirth: string;
  readonly phone: string;
  readonly email: string;
}

/**
 * Finishes a personal registration.
 *
 * NO PASSWORD. The applicant used to choose one here; the bank issues a temporary
 * password when an administrator creates the account, and the customer replaces it on
 * first sign-in. Two passwords on one account is a state nobody can reason about.
 */
export interface PersonalRegistrationComplete {
  readonly verificationToken: string;
}

/* ------------------------------------------------ business */

export interface BusinessSignatory {
  readonly fullName: string;
  readonly nationalId: string;
  readonly role: string;
  readonly email: string;
  readonly phone: string;
}

export interface BusinessRegistrationRequest {
  readonly companyName: string;
  readonly registrationNumber: string;
  readonly tin: string;
  readonly businessType: string;
  readonly sector: string;
  readonly address: string;
  readonly companyEmail: string;
  readonly companyPhone: string;
  readonly existingAccountNumber: string;
  readonly contactFullName: string;
  readonly contactRole: string;
  readonly contactEmail: string;
  readonly contactPhone: string;
  readonly signatories: readonly BusinessSignatory[];
  /** Document metadata only; the files themselves upload separately as multipart. */
  readonly documentNames: readonly string[];
}

/* ------------------------------------------------ join a business */

export interface JoinBusinessRequest {
  /** Code issued by the company's own administrator. Never browsable or guessable. */
  readonly companyCode: string;
  readonly fullName: string;
  readonly nationalId: string;
  readonly staffNumber: string;
  readonly email: string;
  readonly phone: string;
  readonly requestedRole: string;
  readonly justification: string;
}
