import type { AuthChallenge, LoginRequest, LoginResult, VerifyRequest } from '@/types/auth';
import { apiClient } from './apiClient';

/**
 * Authentication.
 *
 * The password is passed straight through to the request and never stored, logged, or
 * held anywhere after the call — the only reference is the argument, which goes out of
 * scope when this returns.
 */
export const authService = {
  login: (payload: LoginRequest, signal?: AbortSignal): Promise<LoginResult> =>
    apiClient.post<LoginResult>('/auth/login', {
      body: payload,
      ...(signal === undefined ? {} : { signal }),
    }),

  verify: (payload: VerifyRequest, signal?: AbortSignal): Promise<LoginResult> =>
    apiClient.post<LoginResult>('/auth/verify', {
      body: payload,
      ...(signal === undefined ? {} : { signal }),
    }),

  resend: (challengeId: string, signal?: AbortSignal): Promise<AuthChallenge> =>
    apiClient.post<AuthChallenge>('/auth/resend', {
      body: { challengeId },
      ...(signal === undefined ? {} : { signal }),
    }),

  /**
   * "I have forgotten my password."
   *
   * RESOLVES THE SAME WAY WHATEVER HAPPENS, and that is the point rather than an
   * accident of the API. The server answers 202 with an empty body for an address that
   * banks here and one that does not, because anything else turns this into a way to
   * find out who banks here — so there is no result to inspect and nothing a screen
   * could accidentally branch on.
   *
   * It resets nothing by itself. A manager has to issue the new password, and the
   * customer's existing one keeps working until they do.
   */
  requestPasswordReset: async (identifier: string, signal?: AbortSignal): Promise<void> => {
    await apiClient.post<undefined>('/auth/password/forgot', {
      body: { identifier },
      ...(signal === undefined ? {} : { signal }),
    });
  },

  // Returns 204, so the parsed body is undefined. `post<void>` is rejected by
  // no-invalid-void-type; undefined is what actually comes back.
  logout: async (signal?: AbortSignal): Promise<void> => {
    await apiClient.post<undefined>('/auth/logout', signal === undefined ? {} : { signal });
  },
} as const;
