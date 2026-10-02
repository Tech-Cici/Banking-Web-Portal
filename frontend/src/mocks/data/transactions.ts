import type { MoneyDto } from '@/types/api';
import type { Account, Transaction, TransactionDirection } from '@/types/banking';
import { ACCOUNTS } from './accounts';

/**
 * Seeded transaction history, generated deterministically.
 *
 * Deterministic, not random: the same account always produces the same history, so a
 * screenshot, a snapshot test or a bug report stays reproducible across reloads.
 * `Math.random` is also banned by lint — unseeded data in fixtures makes failures that
 * only reproduce sometimes.
 *
 * Running balances are computed BACKWARDS from each account's current balance, so the
 * newest row reconciles with the balance the dashboard shows. A history whose arithmetic
 * does not tie out makes every real balance bug look like a fixture bug.
 */

/** mulberry32 — small, fast, and stable for a given seed. */
function seededRandom(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Stable numeric seed from an account id. */
function seedFrom(text: string): number {
  let hash = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

interface Template {
  readonly description: string;
  readonly category: string;
  readonly direction: TransactionDirection;
  /** Amount range in MINOR units, so no float arithmetic is involved. */
  readonly min: number;
  readonly max: number;
}

const RETAIL_TEMPLATES: readonly Template[] = [
  {
    description: 'Salary — Kigali Tech Ltd',
    category: 'Income',
    direction: 'CREDIT',
    min: 850000,
    max: 850000,
  },
  {
    description: 'Airtime top-up — MTN',
    category: 'Airtime',
    direction: 'DEBIT',
    min: 1000,
    max: 10000,
  },
  {
    description: 'Electricity token — REG',
    category: 'Utilities',
    direction: 'DEBIT',
    min: 5000,
    max: 30000,
  },
  {
    description: 'Water bill — WASAC',
    category: 'Utilities',
    direction: 'DEBIT',
    min: 4000,
    max: 18000,
  },
  {
    description: 'Transfer to mobile wallet',
    category: 'Transfers',
    direction: 'DEBIT',
    min: 5000,
    max: 120000,
  },
  {
    description: 'Card purchase — Simba Supermarket',
    category: 'Shopping',
    direction: 'DEBIT',
    min: 8000,
    max: 95000,
  },
  {
    description: 'ATM withdrawal — KN 4 Ave',
    category: 'Cash',
    direction: 'DEBIT',
    min: 20000,
    max: 200000,
  },
  {
    description: 'Transfer from J. Nsengimana',
    category: 'Transfers',
    direction: 'CREDIT',
    min: 15000,
    max: 300000,
  },
  {
    description: 'TV subscription',
    category: 'Subscriptions',
    direction: 'DEBIT',
    min: 12000,
    max: 45000,
  },
  {
    description: 'Standing order — Rent',
    category: 'Housing',
    direction: 'DEBIT',
    min: 250000,
    max: 250000,
  },
];

const CORPORATE_TEMPLATES: readonly Template[] = [
  {
    description: 'Customer receipt — invoice settlement',
    category: 'Receipts',
    direction: 'CREDIT',
    min: 400000,
    max: 6500000,
  },
  {
    description: 'Supplier payment — bulk transfer',
    category: 'Payables',
    direction: 'DEBIT',
    min: 300000,
    max: 4200000,
  },
  {
    description: 'Salary run',
    category: 'Payroll',
    direction: 'DEBIT',
    min: 8500000,
    max: 8500000,
  },
  {
    description: 'Tax payment — RRA',
    category: 'Tax',
    direction: 'DEBIT',
    min: 250000,
    max: 2800000,
  },
  { description: 'Bank charges', category: 'Fees', direction: 'DEBIT', min: 5000, max: 45000 },
  {
    description: 'Fuel card settlement',
    category: 'Operations',
    direction: 'DEBIT',
    min: 80000,
    max: 900000,
  },
  {
    description: 'Interest credit',
    category: 'Income',
    direction: 'CREDIT',
    min: 12000,
    max: 180000,
  },
];

const USD_TEMPLATES: readonly Template[] = [
  {
    description: 'Inward remittance',
    category: 'Receipts',
    direction: 'CREDIT',
    min: 50000,
    max: 900000,
  },
  {
    description: 'International transfer — supplier',
    category: 'Payables',
    direction: 'DEBIT',
    min: 25000,
    max: 450000,
  },
  {
    description: 'FX conversion to RWF',
    category: 'Foreign exchange',
    direction: 'DEBIT',
    min: 10000,
    max: 200000,
  },
  {
    description: 'Correspondent bank charge',
    category: 'Fees',
    direction: 'DEBIT',
    min: 1500,
    max: 6000,
  },
];

function templatesFor(account: Account): readonly Template[] {
  if (account.currency === 'USD') return USD_TEMPLATES;
  return account.corporateId === undefined ? RETAIL_TEMPLATES : CORPORATE_TEMPLATES;
}

/** Minor-unit count for a currency. RWF has none; USD has two. */
function scaleFor(currency: string): number {
  return currency === 'RWF' ? 0 : 2;
}

function toMoney(minorUnits: bigint, currency: string): MoneyDto {
  const scale = scaleFor(currency);
  const negative = minorUnits < 0n;
  const digits = (negative ? -minorUnits : minorUnits).toString();

  if (scale === 0) return { amount: `${negative ? '-' : ''}${digits}`, currency };

  const padded = digits.padStart(scale + 1, '0');
  const whole = padded.slice(0, padded.length - scale);
  const fraction = padded.slice(padded.length - scale);
  return { amount: `${negative ? '-' : ''}${whole}.${fraction}`, currency };
}

function toMinorUnits(amount: string, currency: string): bigint {
  const scale = scaleFor(currency);
  const [whole = '0', fraction = ''] = amount.split('.');
  return BigInt(`${whole}${fraction.padEnd(scale, '0')}`);
}

const HISTORY_LENGTH = 40;
const DAY_MS = 24 * 60 * 60 * 1000;
/** Fixed "now" so generated dates never drift between runs. */
const NOW = Date.parse('2026-09-23T06:15:00.000Z');

function buildFor(account: Account): readonly Transaction[] {
  if (account.status === 'DORMANT' || account.status === 'CLOSED') return [];

  const random = seededRandom(seedFrom(account.id));
  const templates = templatesFor(account);
  const rows: Transaction[] = [];

  /*
   * Walk backwards from the current balance so the newest row reconciles with it.
   *
   * An account with no balance gets no invented history. Balances are optional now,
   * because a real account entered by bank staff has none until core banking is
   * connected — and generating a plausible statement against an unknown balance would be
   * fabricating transactions, which is worse than showing none.
   */
  if (account.currentBalance === undefined) return [];

  let balance = toMinorUnits(account.currentBalance.amount, account.currency);

  for (let index = 0; index < HISTORY_LENGTH; index += 1) {
    const template = templates[Math.floor(random() * templates.length)] ?? templates[0];
    if (template === undefined) break;

    const span = template.max - template.min;
    const minor = BigInt(template.min + Math.floor(random() * (span + 1)));

    const bookedAt = new Date(
      NOW - index * DAY_MS * (1 + Math.floor(random() * 2)) - Math.floor(random() * DAY_MS),
    ).toISOString();

    rows.push({
      id: `txn-${account.id}-${String(index).padStart(3, '0')}`,
      accountId: account.id,
      bookedAt,
      description: template.description,
      reference: `TXN${(seedFrom(account.id + String(index)) % 1_000_000_000).toString().padStart(9, '0')}`,
      direction: template.direction,
      amount: toMoney(minor, account.currency),
      runningBalance: toMoney(balance, account.currency),
      // The newest two entries are still settling, so the UI has non-final states to show.
      status: index === 0 ? 'PROCESSING' : index === 1 ? 'PENDING_CONFIRMATION' : 'COMPLETED',
      category: template.category,
    });

    // Undo this entry to get the balance as it stood before it.
    balance = template.direction === 'DEBIT' ? balance + minor : balance - minor;
  }

  return rows;
}

/**
 * History is built on demand, and a new account has none.
 *
 * The generator above stays because it is still the right way to produce a believable
 * statement for an account that HAS history — but nothing is pre-generated now. An
 * account opened this morning shows an empty transactions screen, which is the true
 * answer and the state most likely to be mishandled.
 */
const BY_ACCOUNT = new Map<string, readonly Transaction[]>();

export function transactionsFor(accountId: string): readonly Transaction[] {
  const cached = BY_ACCOUNT.get(accountId);
  if (cached !== undefined) return cached;

  const account = ACCOUNTS.find((item) => item.id === accountId);
  // Unknown or newly opened: no history, not an error.
  const rows = account === undefined ? [] : buildFor(account);
  BY_ACCOUNT.set(accountId, rows);
  return rows;
}

/** Newest first across several accounts — what a dashboard's "recent activity" needs. */
export function recentTransactions(
  accountIds: readonly string[],
  limit: number,
): readonly Transaction[] {
  return accountIds
    .flatMap((id) => transactionsFor(id))
    .sort((a, b) => Date.parse(b.bookedAt) - Date.parse(a.bookedAt))
    .slice(0, limit);
}
