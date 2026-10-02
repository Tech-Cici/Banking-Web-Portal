/**
 * "Remember my username" — the identifier only, never the password.
 *
 * Stored in a cookie rather than localStorage so it obeys the same rule as everything
 * else here: the lint config bans direct web storage, because that is where tokens and
 * sensitive drafts end up. A username is not secret, but the mechanism should not differ
 * per field, and a cookie expires on its own.
 */

const COOKIE = 'ib_remembered_identifier';
const ONE_YEAR_SECONDS = 60 * 60 * 24 * 365;

export function readRememberedIdentifier(): string | null {
  if (typeof document === 'undefined') return null;

  const raw = document.cookie
    .split('; ')
    .find((entry) => entry.startsWith(`${COOKIE}=`))
    ?.slice(COOKIE.length + 1);

  if (raw === undefined || raw === '') return null;
  return decodeURIComponent(raw);
}

/** Pass null to forget. */
export function rememberIdentifier(identifier: string | null): void {
  if (typeof document === 'undefined') return;

  if (identifier === null || identifier === '') {
    document.cookie = `${COOKIE}=; path=/; max-age=0; SameSite=Lax`;
    return;
  }

  document.cookie = `${COOKIE}=${encodeURIComponent(identifier)}; path=/; max-age=${String(
    ONE_YEAR_SECONDS,
  )}; SameSite=Lax`;
}
