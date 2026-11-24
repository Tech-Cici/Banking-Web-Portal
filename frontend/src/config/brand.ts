/**
 * The product's name, in one place.
 *
 * It appeared in nine files before this existed, which is how a portal ends up half
 * rebranded. Everything that displays the name reads it from here.
 *
 * `SHORT` is the wordmark in the header, where space is tight. `SERVICE` is what the
 * product is, used where the name alone would not say that this is the banking portal.
 *
 * THE DEMONSTRATION NAME IS THE DEFAULT, AND A BUILD CANNOT OVERRIDE IT. Google Safe
 * Browsing flagged the deployment as phishing, and Brevo suspended the mail account,
 * both because this presented itself as Zigama CSS. A public page carrying a real
 * bank's name above a live login box is phishing to every classifier that sees it,
 * whatever it was meant to be.
 *
 * So the override below is read ONLY in a dev build. `.env.development` sets the bank's
 * name for local work, where nothing is published and the supervisor is looking at the
 * real thing. `vite build` replaces `import.meta.env.DEV` with `false`, which makes the
 * override dead code the bundler deletes -- so putting the bank's name back on a
 * deployment takes a deliberate change here AND a domain the bank controls, never a
 * forgotten environment variable on Vercel.
 */
const DEMO_SHORT = "Ciara's demo";
const DEMO_FULL = "Ciara's demo — Internet Banking";

function devOverride(value: string | undefined, fallback: string): string {
  if (!import.meta.env.DEV) return fallback;
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? fallback : trimmed;
}

export const BRAND = {
  SHORT: devOverride(import.meta.env.VITE_BRAND_SHORT, DEMO_SHORT),
  SERVICE: 'Internet Banking',
  /** Header wordmark and page titles, e.g. "Ciara's demo — Internet Banking". */
  FULL: devOverride(import.meta.env.VITE_BRAND_FULL, DEMO_FULL),
} as const;
