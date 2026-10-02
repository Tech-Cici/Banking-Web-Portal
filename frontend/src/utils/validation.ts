/**
 * Client-side validation rules.
 *
 * These exist to catch obvious mistakes before a round trip and to put the message next to
 * the field. They are NOT the authority on anything. The blueprint's validation table
 * (section 21) is explicit: format and required checks are done on both sides, and
 * everything else — entitlement, funds, limits, whether an account or company actually
 * exists — is decided by the server alone.
 *
 * So: never block a submission on a rule the server has not also confirmed, and never
 * treat passing these rules as evidence that anything is valid.
 */

export type FieldErrors<TForm> = Partial<Record<keyof TForm, string>>;

/** True when the form has no field errors. */
export function isValid<TForm>(errors: FieldErrors<TForm>): boolean {
  return Object.keys(errors).length === 0;
}

/* ---------------------------------------------------------------- generic rules */

export function required(value: string, fieldLabel: string): string | undefined {
  return value.trim() === '' ? `${fieldLabel} is required.` : undefined;
}

export function minLength(value: string, min: number, fieldLabel: string): string | undefined {
  return value.trim().length < min
    ? `${fieldLabel} must be at least ${min} characters.`
    : undefined;
}

export function maxLength(value: string, max: number, fieldLabel: string): string | undefined {
  return value.trim().length > max
    ? `${fieldLabel} must be ${max} characters or fewer.`
    : undefined;
}

export function matches(a: string, b: string, message: string): string | undefined {
  return a === b ? undefined : message;
}

/* ---------------------------------------------------------------- formats */

/**
 * Email.
 *
 * Deliberately permissive. The only real proof an address works is sending to it, and an
 * over-strict regex rejects valid addresses — which on a registration form means turning
 * away a genuine customer.
 */
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function email(value: string, fieldLabel = 'Email address'): string | undefined {
  if (value.trim() === '') return `${fieldLabel} is required.`;
  return EMAIL.test(value.trim()) ? undefined : `Enter a valid ${fieldLabel.toLowerCase()}.`;
}

/**
 * Rwandan mobile number.
 *
 * Accepts 07XXXXXXXX, 2507XXXXXXXX and +2507XXXXXXXX. Mobile numbers here are ten digits
 * beginning 07; the operator prefixes are checked by the provider, not here, because they
 * change and a stale list would reject a real number.
 */
export function rwandanPhone(value: string, fieldLabel = 'Phone number'): string | undefined {
  const digits = value.replace(/[\s()+-]/g, '');
  if (digits === '') return `${fieldLabel} is required.`;

  const national = digits.startsWith('250') ? digits.slice(3) : digits;
  if (!/^0?7\d{8}$/.test(national)) {
    return `Enter a valid Rwandan mobile number, for example 078 123 4567.`;
  }
  return undefined;
}

/** Normalises any accepted spelling to +2507XXXXXXXX for submission. */
export function normalisePhone(value: string): string {
  const digits = value.replace(/[\s()+-]/g, '');
  const national = digits.startsWith('250') ? digits.slice(3) : digits;
  const withoutLeadingZero = national.startsWith('0') ? national.slice(1) : national;
  return `+250${withoutLeadingZero}`;
}

/**
 * Digits-only field of an exact length.
 *
 * Used for account numbers and tax identifiers, whose real formats are set by the bank and
 * the RRA. Those exact rules are still owed (blueprint section 29), so the lengths passed
 * in by callers are marked as provisional at each call site.
 */
export function digits(value: string, length: number, fieldLabel: string): string | undefined {
  const cleaned = value.replace(/\s/g, '');
  if (cleaned === '') return `${fieldLabel} is required.`;
  if (!/^\d+$/.test(cleaned)) return `${fieldLabel} must contain digits only.`;
  if (cleaned.length !== length) return `${fieldLabel} must be ${length} digits.`;
  return undefined;
}

/** Digits-only field within a length range. */
export function digitsBetween(
  value: string,
  min: number,
  max: number,
  fieldLabel: string,
): string | undefined {
  const cleaned = value.replace(/\s/g, '');
  if (cleaned === '') return `${fieldLabel} is required.`;
  if (!/^\d+$/.test(cleaned)) return `${fieldLabel} must contain digits only.`;
  if (cleaned.length < min || cleaned.length > max) {
    return `${fieldLabel} must be between ${min} and ${max} digits.`;
  }
  return undefined;
}

/**
 * A date of birth that belongs to an adult.
 *
 * The bank's actual minimum age and any youth-account rules are its own; 18 is the common
 * default and is flagged at the call site as provisional.
 */
export function adultDateOfBirth(value: string, minimumAge = 18): string | undefined {
  if (value.trim() === '') return 'Date of birth is required.';

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Enter a valid date.';

  const now = new Date();
  if (date > now) return 'Date of birth cannot be in the future.';

  const cutoff = new Date(now.getFullYear() - minimumAge, now.getMonth(), now.getDate());
  if (date > cutoff) return `You must be at least ${minimumAge} to register.`;

  return undefined;
}

/* ---------------------------------------------------------------- password policy */

/**
 * Password rules shown beside the field.
 *
 * PLACEHOLDER POLICY. The bank's real policy is owed (blueprint section 29) and must be
 * enforced server-side regardless of what is shown here. These are conservative defaults
 * so the field is usable in the meantime.
 *
 * There is deliberately no "must contain a special character" rule: it measurably pushes
 * people towards predictable substitutions without adding much strength, and length is
 * what actually helps.
 */
export const PASSWORD_RULES = [
  {
    id: 'length',
    label: 'At least 12 characters',
    test: (value: string): boolean => value.length >= 12,
  },
  {
    id: 'lower',
    label: 'A lowercase letter',
    test: (value: string): boolean => /[a-z]/.test(value),
  },
  {
    id: 'upper',
    label: 'An uppercase letter',
    test: (value: string): boolean => /[A-Z]/.test(value),
  },
  {
    id: 'number',
    label: 'A number',
    test: (value: string): boolean => /\d/.test(value),
  },
] as const;

export function password(value: string): string | undefined {
  if (value === '') return 'Password is required.';
  const unmet = PASSWORD_RULES.filter((rule) => !rule.test(value));
  return unmet.length === 0 ? undefined : 'Password does not meet all the requirements below.';
}

/** Collects only the rules that actually failed, discarding undefined. */
export function collect<TForm>(
  entries: readonly (readonly [keyof TForm, string | undefined])[],
): FieldErrors<TForm> {
  const errors: FieldErrors<TForm> = {};
  for (const [field, message] of entries) {
    if (message !== undefined) {
      errors[field] = message;
    }
  }
  return errors;
}
