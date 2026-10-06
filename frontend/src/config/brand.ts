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
 * ones.
 *
 * RENAMED. This is a student demonstration, not Zigama CSS's own portal, and it
 * must not present itself as one. Google Safe Browsing flagged the deployed site
 * as phishing while it carried the bank's name above a live login box.
 */
export const BRAND = {
  SHORT: "Ciara's demo",
  SERVICE: 'Internet Banking',
  /** Header wordmark and page titles: "Ciara's demo — Internet Banking". */
  FULL: "Ciara's demo — Internet Banking",
} as const;
