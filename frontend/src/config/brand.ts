/**
 * The bank's name, in one place.
 *
 * It appeared in nine files before this existed, which is how a portal ends up half
 * rebranded. Everything that displays the bank's name reads it from here.
 *
 * `SHORT` is the wordmark in the header, where space is tight. `SERVICE` is what the
 * product is, used where the name alone would not say that this is the banking portal.
 *
 * THIS IS NOT DEFINED IN THE BLUEPRINT. The bank's registered legal name, its logo and
 * the exact wording it wants above a sign-in form are brand decisions, not engineering
 * ones — these are working values to be confirmed with Zigama CSS.
 */
export const BRAND = {
  SHORT: 'Zigama CSS',
  SERVICE: 'Internet Banking',
  /** Header wordmark and page titles: "Zigama CSS Internet Banking". */
  FULL: 'Zigama CSS Internet Banking',
} as const;
