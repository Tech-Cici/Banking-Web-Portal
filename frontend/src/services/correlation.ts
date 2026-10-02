/**
 * Correlation ids — the reference on an error screen.
 *
 * Every request carries one. The server echoes it on the response and stamps it on its
 * log lines, so a customer quoting the reference gives support an exact pointer into the
 * logs. That is the whole job, and the format has to serve it.
 *
 * It used to be `crypto.randomUUID()`:
 *
 *   4b39518e-b8d2-4301-9f64-37a5a4a26638
 *
 * Thirty-six characters, and a customer is expected to read that down a phone line to
 * someone in a call centre. Nobody does. They say "it's a long code" and the reference
 * goes unused, which means the one mechanism for tracing a failure to its cause is dead
 * on arrival. A reference too long to say out loud is not a reference.
 *
 * So: nine characters in two groups.
 *
 *   K7M4-P29X
 *
 * Choices behind that, each for a reason:
 *
 *  - The alphabet excludes I, L, O, U and the digits 0 and 1. `0` versus `O` and `1`
 *    versus `I` or `l` are the two mistakes people actually make when copying a code,
 *    and U is dropped so the generator cannot spell anything unfortunate.
 *  - Uppercase, because it is read aloud and dictated back, and case is one more thing
 *    to get wrong.
 *  - Grouped with a hyphen, because four characters is about as much as anyone holds in
 *    their head at once.
 *  - The hyphen travels in the header too, so what the customer reads on screen is
 *    EXACTLY what appears in the log. A prettified display value that differs from the
 *    logged one is worse than a long ugly one: support searches for what they were told
 *    and find nothing.
 *
 * Collisions: 22 letters and 8 digits over 8 positions is 30^8, about 6.6 × 10^11
 * combinations. References are not identifiers — nothing is keyed on them, they are
 * search terms scoped to a moment in a log — so a repeat is a curiosity rather than a
 * fault, and the log line carries a timestamp regardless.
 *
 * The format still matches the server's allow-list (`[A-Za-z0-9_-]{8,64}`); the backend
 * discards anything else rather than reflecting it, so a mismatch here would silently
 * lose traceability.
 */

const CORRELATION_HEADER = 'X-Correlation-Id';

export { CORRELATION_HEADER };

/**
 * Unambiguous when read aloud, written down, or dictated back.
 *
 * No I, L, O or U; no 0 or 1. Thirty symbols left.
 */
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ';

/** Generates a fresh reference, e.g. `K7M4-P29X`. */
export function newCorrelationId(): string {
  /*
   * `crypto.getRandomValues`, not `Math.random`. A predictable reference would let
   * someone guess another customer's — and references end up in support tickets and
   * server logs, which is not somewhere to invite guessing.
   *
   * Rejection sampling rather than `% ALPHABET.length`: 256 does not divide evenly by
   * 30, so the modulo would quietly favour the first few letters of the alphabet.
   */
  const characters: string[] = [];
  const limit = 256 - (256 % ALPHABET.length);

  while (characters.length < 8) {
    const bytes = crypto.getRandomValues(new Uint8Array(8));
    for (const byte of bytes) {
      if (characters.length === 8) break;
      if (byte >= limit) continue; // Would bias the result. Draw again.
      characters.push(ALPHABET[byte % ALPHABET.length] ?? '');
    }
  }

  return `${characters.slice(0, 4).join('')}-${characters.slice(4).join('')}`;
}

/** Reads the correlation id the server reported, if it sent one. */
export function readCorrelationId(headers: Headers): string | undefined {
  return headers.get(CORRELATION_HEADER) ?? undefined;
}

/**
 * Whether a string looks like one of our references.
 *
 * Used to keep a value the server sent from reaching the screen if it is not in our
 * format — a raw UUID or an internal trace id would undo the whole point of the change,
 * and it is the kind of thing a backend quietly starts doing.
 */
export function isDisplayableReference(value: string): boolean {
  return /^[2-9A-HJ-NP-TV-Z]{4}-[2-9A-HJ-NP-TV-Z]{4}$/.test(value);
}
