import type { MoneyDto } from '@/types/api';

/**
 * Decimal-safe money.
 *
 * The blueprint is unambiguous (sections 3 and 42): never use JavaScript floating point for
 * authoritative money, and never override a backend figure with a client calculation.
 * `0.1 + 0.2 === 0.30000000000000004`, and a `number` loses integer precision past 2^53 —
 * which RWF amounts reach sooner than most currencies.
 *
 * So an amount is held as a `bigint` count of minor units plus an explicit scale, parsed
 * from the decimal STRING the API sends. Arithmetic here is for presentation only:
 * subtotals in a review panel, a running total in a bulk file. Fees, limits, balances,
 * FX rates and loan figures come from the server, always.
 */

export interface Money {
  /** Amount in minor units. 1234 at scale 2 is 12.34. */
  readonly minorUnits: bigint;
  /** ISO 4217 alphabetic code, e.g. `RWF`, `USD`. */
  readonly currency: string;
  /** Number of decimal places this amount is expressed in. */
  readonly scale: number;
}

export class MoneyError extends Error {
  override readonly name = 'MoneyError';
}

/** Strict decimal string: optional sign, digits, optional fraction. No exponents. */
const DECIMAL_PATTERN = /^-?\d+(\.\d+)?$/;

const CURRENCY_PATTERN = /^[A-Z]{3}$/;

/**
 * Minor-unit digits for a currency.
 *
 * Resolved from `Intl.NumberFormat`, which follows ISO 4217 — so RWF resolves to 0 decimal
 * places, JPY to 0, USD to 2, and no table needs maintaining here.
 *
 * THIS IS NOT DEFINED IN THE BLUEPRINT. Section 9.1's example review panel shows
 * `RWF 100,000.00` with two decimals, while ISO 4217 assigns RWF zero. The bank's display
 * convention must be confirmed. Until then this follows ISO 4217, and `formatMoney` accepts
 * an explicit override so the decision can be applied in one place.
 */
const scaleCache = new Map<string, number>();

export function currencyScale(currency: string): number {
  assertCurrency(currency);

  const cached = scaleCache.get(currency);
  if (cached !== undefined) return cached;

  const resolved = new Intl.NumberFormat('en', {
    style: 'currency',
    currency,
  }).resolvedOptions();

  // The lib types mark these optional even though `style: 'currency'` always resolves
  // them. Fall back rather than assert; 2 is the commonest ISO 4217 scale.
  const scale = resolved.maximumFractionDigits ?? resolved.minimumFractionDigits ?? 2;
  scaleCache.set(currency, scale);
  return scale;
}

function assertCurrency(currency: string): void {
  if (!CURRENCY_PATTERN.test(currency)) {
    throw new MoneyError(`Invalid currency code: ${currency}. Expected three uppercase letters.`);
  }
}

/**
 * Parses a decimal string from the API into {@link Money}.
 *
 * Rejects anything that is not a plain decimal — including `NaN`, exponent notation and
 * already-rounded `number` input — rather than coercing it. Silent coercion is how a wrong
 * amount reaches a confirmation screen.
 *
 * A fraction longer than the currency's scale is an error, not something to round: throwing
 * away a customer's digits is never the right default.
 */
export function parseMoney(amount: string, currency: string): Money {
  assertCurrency(currency);

  const trimmed = amount.trim();
  if (!DECIMAL_PATTERN.test(trimmed)) {
    throw new MoneyError(`Invalid decimal amount: "${amount}".`);
  }

  const scale = currencyScale(currency);
  const negative = trimmed.startsWith('-');
  const unsigned = negative ? trimmed.slice(1) : trimmed;
  const [wholePart = '0', fractionPart = ''] = unsigned.split('.');

  if (fractionPart.length > scale) {
    throw new MoneyError(
      `Amount "${amount}" has ${fractionPart.length} decimal place(s) but ${currency} ` +
        `supports ${scale}.`,
    );
  }

  const paddedFraction = fractionPart.padEnd(scale, '0');
  const minorUnits = BigInt(`${wholePart}${paddedFraction}`);

  return { minorUnits: negative ? -minorUnits : minorUnits, currency, scale };
}

/** Builds {@link Money} straight from an API {@link MoneyDto}. */
export function moneyFromDto(dto: MoneyDto): Money {
  return parseMoney(dto.amount, dto.currency);
}

/** Converts back to the wire shape. */
export function moneyToDto(money: Money): MoneyDto {
  return { amount: toDecimalString(money), currency: money.currency };
}

/** Exact decimal string for this amount. Never lossy. */
export function toDecimalString(money: Money): string {
  const negative = money.minorUnits < 0n;
  const digits = (negative ? -money.minorUnits : money.minorUnits).toString();

  if (money.scale === 0) {
    return `${negative ? '-' : ''}${digits}`;
  }

  const padded = digits.padStart(money.scale + 1, '0');
  const whole = padded.slice(0, padded.length - money.scale);
  const fraction = padded.slice(padded.length - money.scale);

  return `${negative ? '-' : ''}${whole}.${fraction}`;
}

/** Zero in the given currency. */
export function zeroMoney(currency: string): Money {
  return { minorUnits: 0n, currency, scale: currencyScale(currency) };
}

function assertSameCurrency(a: Money, b: Money): void {
  if (a.currency !== b.currency) {
    throw new MoneyError(
      `Cannot combine ${a.currency} with ${b.currency}. ` +
        'Cross-currency totals require a server-supplied conversion (blueprint section 12).',
    );
  }
  if (a.scale !== b.scale) {
    throw new MoneyError(`Scale mismatch for ${a.currency}: ${a.scale} vs ${b.scale}.`);
  }
}

export function addMoney(a: Money, b: Money): Money {
  assertSameCurrency(a, b);
  return { ...a, minorUnits: a.minorUnits + b.minorUnits };
}

export function subtractMoney(a: Money, b: Money): Money {
  assertSameCurrency(a, b);
  return { ...a, minorUnits: a.minorUnits - b.minorUnits };
}

/** Sums amounts. Throws on a mixed-currency list rather than producing a meaningless total. */
export function sumMoney(amounts: readonly Money[], currency: string): Money {
  return amounts.reduce<Money>((total, next) => addMoney(total, next), zeroMoney(currency));
}

/** Negative when `a < b`, zero when equal, positive when `a > b`. */
export function compareMoney(a: Money, b: Money): number {
  assertSameCurrency(a, b);
  if (a.minorUnits < b.minorUnits) return -1;
  if (a.minorUnits > b.minorUnits) return 1;
  return 0;
}

export function isZeroMoney(money: Money): boolean {
  return money.minorUnits === 0n;
}

export function isPositiveMoney(money: Money): boolean {
  return money.minorUnits > 0n;
}

export function isNegativeMoney(money: Money): boolean {
  return money.minorUnits < 0n;
}

export interface FormatMoneyOptions {
  /** BCP 47 locale. Defaults to the bank's display locale. */
  readonly locale?: string;
  /** Force a specific number of decimals, overriding the currency's ISO scale. */
  readonly fractionDigits?: number;
  /** Render the code (`RWF 1,000`) rather than a symbol. Default true: unambiguous. */
  readonly useCode?: boolean;
}

/**
 * Formats an amount for display.
 *
 * Presentation only. The formatted string is never parsed back or submitted — grouping
 * separators and symbols are locale-dependent, and a round trip through them loses
 * information.
 */
export function formatMoney(money: Money, options: FormatMoneyOptions = {}): string {
  const { locale = 'en-RW', fractionDigits = money.scale, useCode = true } = options;

  const formatter = new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: money.currency,
    currencyDisplay: useCode ? 'code' : 'symbol',
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });

  // Intl takes a number. Safe here because this value is only ever displayed, and the
  // exact value remains in minorUnits; toDecimalString is what any further logic uses.
  return formatter.format(Number(toDecimalString(money)));
}

/** Formats an API {@link MoneyDto} directly. */
export function formatMoneyDto(dto: MoneyDto, options: FormatMoneyOptions = {}): string {
  return formatMoney(moneyFromDto(dto), options);
}

/**
 * A balance that may not be known, rendered without pretending.
 *
 * WHY A BALANCE CAN BE ABSENT. This service issues logins and records which accounts a
 * customer holds; the money is in core banking, which is not connected. So the accounts
 * are real and their balances are simply not available here.
 *
 * NEVER SUBSTITUTE ZERO. "RWF 0.00" is a statement about somebody's money and is
 * indistinguishable from a real balance until they act on it — someone would reasonably
 * conclude their account had been emptied. "Not available" is a statement about this
 * system, which is the true one.
 */
export function formatBalanceDto(
  dto: MoneyDto | undefined,
  options: FormatMoneyOptions = {},
): string {
  return dto === undefined ? 'Not available' : formatMoneyDto(dto, options);
}
