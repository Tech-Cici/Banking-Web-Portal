import type { SessionUser } from '@/types/banking';
import { apiClient } from './apiClient';

/**
 * Session.
 *
 * DEVELOPMENT SHAPE. Phase 3 replaces the backing endpoints with the real OIDC session,
 * but the shape the app consumes — a user plus the company they are currently acting for
 * — should not need to change.
 */

export interface SessionResponse {
  readonly user: SessionUser;
  /**
   * The company the session is currently acting for, or null for a personal customer.
   *
   * NULL OR ABSENT, both meaning the same thing, and normalised to null before anything
   * reads it — see `normalise` below. The API omits null properties, so a personal
   * customer's session simply has no `activeCorporateId` at all, and this used to be
   * typed `string | null` as though it were always sent.
   *
   * That is the shape of a bug this codebase has already had: `debitAllowed` was missing
   * from every account response, arrived as `undefined`, was read as false, and silently
   * disabled every withdrawal. Here `undefined` happened to behave, because the one
   * place that reads it compares against null and then looks the id up in a list where
   * `undefined` matches nothing. Relying on that is relying on two mistakes cancelling.
   */
  readonly activeCorporateId: string | null;
  /**
   * True while the customer is still using the temporary password an admin issued.
   *
   * The whole portal is closed until it is replaced, so this has to travel with the
   * session rather than only being returned by sign-in — otherwise typing a URL walks
   * straight past the redirect, which is exactly what happened before this existed.
   */
  readonly mustChangePassword: boolean;
}

/**
 * Fills in what the API leaves out.
 *
 * One property, and it is here rather than at each reader so that "absent" cannot mean
 * something different in two places.
 *
 * `corporates` is deliberately NOT coerced. It looked like the same case and is not: the
 * API omits null properties, and that list is always sent — empty for a personal
 * customer, which is a value rather than an absence. A `?? []` on it would be a guard
 * against something that cannot happen, and the compiler says so.
 */
function normalise(response: SessionResponse): SessionResponse {
  return { ...response, activeCorporateId: response.activeCorporateId ?? null };
}

export const sessionService = {
  /** Current session. Throws an `unauthenticated` ApiError when there is none. */
  current: (signal?: AbortSignal): Promise<SessionResponse> =>
    apiClient
      .get<SessionResponse>('/session', signal === undefined ? {} : { signal })
      .then(normalise),

  /** Switch which company the user is acting for. */
  switchCorporate: (corporateId: string, signal?: AbortSignal): Promise<SessionResponse> =>
    apiClient
      .post<SessionResponse>('/session/corporate', {
        body: { corporateId },
        ...(signal === undefined ? {} : { signal }),
      })
      .then(normalise),

  /*
   * A development persona switch used to live here, then in its own module — as a
   * property of this object it survived tree-shaking and shipped the `/dev/persona`
   * path into the production bundle. Both are now gone along with the seeded personas
   * themselves; customers are created through the onboarding pipeline instead.
   */
} as const;
