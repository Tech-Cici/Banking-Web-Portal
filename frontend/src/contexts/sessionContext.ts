import { createContext } from 'react';
import type { Permission } from '@/types/api';
import type { CorporateMembership, SessionUser } from '@/types/banking';

/**
 * Session context value.
 *
 * In its own module (no components) so fast refresh stays clean and consumers can import
 * the hook without pulling the provider.
 */

export type SessionStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error';

export interface SessionState {
  readonly status: SessionStatus;
  readonly user: SessionUser | null;
  /** The company currently being acted for, if any. */
  readonly activeCorporate: CorporateMembership | null;
  /** Safe message when status is 'error'. */
  readonly errorMessage: string | undefined;

  /**
   * True while the customer is still on the temporary password an admin issued.
   *
   * The route guard sends them to the change-password screen and nowhere else. The
   * server refuses account data for such a session anyway; this is what stops the app
   * rendering an empty portal around the refusals.
   */
  readonly mustChangePassword: boolean;

  /**
   * Whether the backend granted this permission.
   *
   * A UX affordance only. Hiding a button is not authorisation — the server decides on
   * every request, every time (blueprint sections 3 and 24).
   */
  readonly can: (permission: Permission) => boolean;

  /** Switch company. Clears company-scoped state and refetches. */
  readonly switchCorporate: (corporateId: string) => Promise<void>;

  /*
   * There was a `switchPersona` here. It existed so a developer could become one of
   * three seeded customers in one click. Those personas are gone — every customer now
   * arrives through registration, admin creation and manager approval — so the switch
   * had nothing left to switch between and its endpoint no longer has a handler.
   *
   * Removed rather than left dangling. A context method that reads "become someone
   * else" is the kind of thing that gets re-pointed at something real later.
   */

  /**
   * Ends the session.
   *
   * Clears client state first and calls the server after, so a failed logout request
   * still leaves the browser signed out. The reverse order can strand a user in a
   * signed-in-looking app after they pressed sign out.
   */
  readonly signOut: () => Promise<void>;

  readonly refresh: () => Promise<void>;

  /**
   * Reloads the session and returns the user directly.
   *
   * Sign-in needs to know who just authenticated in order to choose a destination, and
   * reading `user` straight after `refresh()` would read the pre-update value.
   */
  readonly refreshAndGet: () => Promise<SessionUser | null>;
}

export const SessionContext = createContext<SessionState | null>(null);
