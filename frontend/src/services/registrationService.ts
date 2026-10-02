import type {
  BusinessRegistrationRequest,
  JoinBusinessRequest,
  OtpChallenge,
  PersonalRegistrationComplete,
  PersonalRegistrationStart,
  RegistrationResult,
  VerifiedChallenge,
} from '@/types/registration';
import { apiClient } from './apiClient';
import { newIdempotencyKey } from './idempotency';

/**
 * Registration service.
 *
 * Against the proposed contract in `types/registration.ts`. No backend implementation
 * exists yet, so in development these calls are served by the MSW handlers in
 * `src/mocks/handlers.ts`.
 *
 * Registration submissions carry an idempotency key even though they move no money: a
 * double-submitted company application creates two review cases, and a double-submitted
 * join request pesters an administrator twice. `financial` stays false — the
 * pending-confirmation semantics in the API client are for money movement only, and
 * applying them here would tell someone their registration "may have been processed"
 * when the honest answer is simply to try again.
 */
export const registrationService = {
  /** Identify the customer and trigger a one-time code. */
  startPersonal: (
    payload: PersonalRegistrationStart,
    signal?: AbortSignal,
  ): Promise<OtpChallenge> =>
    apiClient.post<OtpChallenge>('/registration/personal/start', {
      body: payload,
      ...(signal === undefined ? {} : { signal }),
    }),

  /** Verify the code against its challenge. */
  verifyOtp: (
    challengeId: string,
    code: string,
    signal?: AbortSignal,
  ): Promise<VerifiedChallenge> =>
    apiClient.post<VerifiedChallenge>('/registration/personal/verify', {
      body: { challengeId, code },
      ...(signal === undefined ? {} : { signal }),
    }),

  /** Request a fresh code. The server decides whether one is allowed yet. */
  resendOtp: (challengeId: string, signal?: AbortSignal): Promise<OtpChallenge> =>
    apiClient.post<OtpChallenge>('/registration/otp/resend', {
      body: { challengeId },
      ...(signal === undefined ? {} : { signal }),
    }),

  /** Set the password and finish. */
  completePersonal: (
    payload: PersonalRegistrationComplete,
    signal?: AbortSignal,
  ): Promise<RegistrationResult> =>
    apiClient.post<RegistrationResult>('/registration/personal/complete', {
      body: payload,
      idempotencyKey: newIdempotencyKey(),
      ...(signal === undefined ? {} : { signal }),
    }),

  /**
   * Step one of a corporate application: email a code to the named contact.
   *
   * <p>THIS USED TO BE A SINGLE POST to `/registration/business`, and nothing behind it
   * was real. The backend had no company registration at all, so the request was
   * answered by this app's own mock: the applicant was shown a reference number and
   * "your application has been received", and no application existed anywhere. Staff
   * opened Registrations and found nothing.
   *
   * <p>Now it mirrors personal registration — start, verify the code, complete — which
   * means it also inherits the rate limit and attempt counter guarding that
   * unauthenticated surface rather than needing its own.
   *
   * <p>NO IDEMPOTENCY KEY, deliberately. This creates nothing durable: it emails a code
   * and holds the details against it. The key belongs on `completeBusiness`, which is
   * the call that puts an application in the bank's queue.
   */
  startBusiness: (
    payload: BusinessRegistrationRequest,
    signal?: AbortSignal,
  ): Promise<OtpChallenge> =>
    apiClient.post<OtpChallenge>('/registration/business/start', {
      body: payload,
      ...(signal === undefined ? {} : { signal }),
    }),

  /** Step two: exchange the verified code for an application in the staff queue. */
  completeBusiness: (
    verificationToken: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RegistrationResult> =>
    apiClient.post<RegistrationResult>('/registration/business/complete', {
      body: { verificationToken },
      idempotencyKey,
      ...(signal === undefined ? {} : { signal }),
    }),

  /** Ask a company's administrator for access. */
  submitJoinRequest: (
    payload: JoinBusinessRequest,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RegistrationResult> =>
    apiClient.post<RegistrationResult>('/registration/join', {
      body: payload,
      idempotencyKey,
      ...(signal === undefined ? {} : { signal }),
    }),
} as const;
