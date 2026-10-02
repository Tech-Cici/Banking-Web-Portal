/**
 * CSRF token handling.
 *
 * The server issues a token in a cookie that JavaScript can read, and expects it echoed
 * in a header. That asymmetry is the whole defence: a cross-site page can cause the
 * browser to SEND the session cookie, but it cannot READ a cookie belonging to this
 * origin, so it cannot produce the header.
 *
 * This is the only place in the application that touches `document.cookie`, and only for
 * this one non-secret value. The session cookie is HttpOnly and deliberately invisible
 * here — nothing in the client should ever be able to read it.
 */

/** Header the server reads the token from. Must match Spring's default. */
export const CSRF_HEADER = 'X-XSRF-TOKEN';

/** Cookie the server writes the token to. Must match Spring's default. */
export const CSRF_COOKIE = 'XSRF-TOKEN';

/**
 * The current token, or `undefined` before the server has issued one.
 *
 * Read fresh on every call rather than cached. Spring rotates the token when the session
 * changes — sign-in replaces the session id by design — and a cached value would be sent
 * after it stopped being valid, producing a 403 on the first request after signing in.
 */
export function readCsrfToken(): string | undefined {
  // Guarded because this module is imported by code that also runs under Node in tests.
  if (typeof document === 'undefined') return undefined;

  const prefix = `${CSRF_COOKIE}=`;

  for (const entry of document.cookie.split(';')) {
    const candidate = entry.trim();
    if (!candidate.startsWith(prefix)) continue;

    /*
     * decodeURIComponent because the cookie value is percent-encoded in transit and the
     * server compares against the decoded form. Base64 tokens contain '+' and '=', which
     * is exactly where this goes wrong silently if skipped.
     */
    const raw = candidate.slice(prefix.length);
    try {
      const value = decodeURIComponent(raw);
      return value === '' ? undefined : value;
    } catch {
      // A malformed value is not a token. Better to send nothing and get a clear 403
      // than to send rubbish.
      return undefined;
    }
  }

  return undefined;
}
