import type { StaffUser } from './admin';

/**
 * Authentication DTOs.
 *
 * DEVELOPMENT SHAPE. Phase 3 replaces the endpoints behind these with the bank's approved
 * OIDC flow; the shape the UI consumes should survive that, because it models the steps
 * (identify → verify → session) rather than the mechanism.
 *
 * Proposed endpoints:
 *
 *   POST /api/v1/auth/login    -> LoginResult
 *   POST /api/v1/auth/verify   -> LoginResult (COMPLETE)
 *   POST /api/v1/auth/resend   -> AuthChallenge
 *   POST /api/v1/auth/logout   -> 204
 */

export interface LoginRequest {
  /**
   * The customer's EMAIL ADDRESS. Not a username and not a customer number.
   *
   * Kept as `identifier` rather than renamed to `email`, because the field is the
   * server's contract and the server may one day accept more than one kind. What it
   * accepts today is an email address: CustomerAuthService looks a customer up with
   * findByEmailIgnoreCase and has no other lookup.
   */
  readonly identifier: string;
  readonly password: string;
}

/** Issued when credentials are accepted and a second factor is required. */
export interface AuthChallenge {
  readonly challengeId: string;
  readonly otpLength: number;
  readonly resendAfterSeconds: number;
  /** Masked destination, e.g. `***111`. Never the full number. */
  readonly deliveryHint: string;
}

/**
 * Outcome of a login step.
 *
 * `CHALLENGE_REQUIRED` means credentials were right but the session does not exist yet.
 * The UI must not treat it as signed in.
 */
export type LoginOutcome =
  /** Credentials accepted; a one-time code is still needed. No session yet. */
  | 'CHALLENGE_REQUIRED'
  /** Signed in. */
  | 'COMPLETE'
  /**
   * Signed in with the temporary password an admin issued, and allowed to do exactly
   * one thing: replace it. A session exists, but the portal must not open until it has
   * been replaced — the password is known to bank staff until then.
   */
  | 'PASSWORD_CHANGE_REQUIRED';

export interface LoginResult {
  readonly outcome: LoginOutcome;
  readonly challenge?: AuthChallenge;
}

/* ------------------------------------------------ staff */

/**
 * Bank staff sign in at their own endpoint and get their own result shape.
 *
 * Deliberately not reusing `LoginResult`. Staff and customers are different
 * populations, and one shared "logged in" response is how a support tool ends up
 * reachable with a customer's session.
 *
 * Proposed endpoints:
 *
 *   POST /api/v1/auth/staff/login   -> StaffLoginResult
 *   GET  /api/v1/auth/staff/session -> StaffUser
 *   POST /api/v1/auth/password/change-temporary -> 204
 */
export interface StaffLoginResult {
  readonly outcome: 'COMPLETE';
  readonly staff: StaffUser;
}

export interface ChangeTemporaryPasswordRequest {
  /** The temporary password the admin issued. Asked for again, on purpose. */
  readonly currentPassword: string;
  readonly newPassword: string;
}

export interface VerifyRequest {
  readonly challengeId: string;
  readonly code: string;
}
