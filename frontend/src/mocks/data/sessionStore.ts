/**
 * The mock server's session store.
 *
 * Kept in a cookie on the app's own origin, written by the mock layer — which runs
 * inside the page, so it can do this directly. That choice matters for two reasons:
 *
 *  1. It survives a reload, so working on a screen does not mean signing in after every
 *     hot reload.
 *  2. The application code never touches a token. This stands in for the HttpOnly
 *     session cookie a real backend would set, which is exactly what the app must NOT
 *     be able to read. Nothing here is web storage, so the lint rule banning that
 *     still holds.
 *
 * A session now records WHAT KIND of user it belongs to. Bank staff and customers are
 * different populations with different portals, and a single "logged in" flag is how a
 * support tool ends up reachable with a customer's session.
 *
 * DEVELOPMENT ONLY. Excluded from production builds along with the rest of the mocks.
 */

const COOKIE = 'ib_mock_session';

export type SessionKind = 'customer' | 'staff';

export interface MockSession {
  readonly kind: SessionKind;
  readonly userId: string;
  readonly corporateId: string | undefined;
}

function readCookie(): MockSession | null {
  if (typeof document === 'undefined') return null;

  const raw = document.cookie
    .split('; ')
    .find((entry) => entry.startsWith(`${COOKIE}=`))
    ?.slice(COOKIE.length + 1);

  if (raw === undefined || raw === '') return null;

  const [kind = '', userId = '', corporateId = ''] = decodeURIComponent(raw).split('|');
  if (userId === '') return null;
  if (kind !== 'customer' && kind !== 'staff') return null;

  return { kind, userId, corporateId: corporateId === '' ? undefined : corporateId };
}

function writeCookie(session: MockSession | null): void {
  if (typeof document === 'undefined') return;

  if (session === null) {
    document.cookie = `${COOKIE}=; path=/; max-age=0; SameSite=Lax`;
    return;
  }

  const value = encodeURIComponent(
    `${session.kind}|${session.userId}|${session.corporateId ?? ''}`,
  );
  // Session-length cookie: no max-age, so closing the browser ends it, which is the
  // right default for banking.
  document.cookie = `${COOKIE}=${value}; path=/; SameSite=Lax`;
}

export function getSession(): MockSession | null {
  return readCookie();
}

/** The signed-in customer's id, or null when the session is staff or absent. */
export function customerSessionId(): string | null {
  const session = readCookie();
  return session !== null && session.kind === 'customer' ? session.userId : null;
}

/** The signed-in staff member's id, or null when the session is a customer or absent. */
export function staffSessionId(): string | null {
  const session = readCookie();
  return session !== null && session.kind === 'staff' ? session.userId : null;
}

export function signInCustomer(userId: string, corporateId: string | undefined): void {
  writeCookie({ kind: 'customer', userId, corporateId });
}

export function signInStaff(userId: string): void {
  writeCookie({ kind: 'staff', userId, corporateId: undefined });
}

export function signOut(): void {
  writeCookie(null);
}

export function getActiveCorporateId(): string | undefined {
  const session = readCookie();
  return session?.kind === 'customer' ? session.corporateId : undefined;
}

/** Returns false when the session is not a customer's, or not a member of that company. */
export function setActiveCorporate(corporateId: string, permitted: boolean): boolean {
  const session = readCookie();
  if (session?.kind !== 'customer' || !permitted) return false;

  writeCookie({ kind: 'customer', userId: session.userId, corporateId });
  return true;
}
