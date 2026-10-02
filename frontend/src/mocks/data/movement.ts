import type {
  Biller,
  SignIn,
  StandingOrder,
  Statement,
  TrustedDevice,
} from '@/types/movement';

/**
 * Fixtures for the money-movement and account-management screens.
 *
 * Invented throughout — no real biller account numbers, no real branch codes. The
 * biller list mirrors the categories in the brief (section 11) so each payment screen
 * has something real to render rather than a placeholder.
 */

/* ------------------------------------------------------------------ billers */

export const BILLERS: readonly Biller[] = [
  {
    id: 'biller-mtn',
    name: 'MTN Mobile Money',
    category: 'WALLET',
    accountLabel: 'Mobile money number',
    accountHint: 'The number that receives the money, e.g. 0781 000 111.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-airtel',
    name: 'Airtel Money',
    category: 'WALLET',
    accountLabel: 'Mobile money number',
    accountHint: 'The number that receives the money.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-mtn-airtime',
    name: 'MTN airtime',
    category: 'AIRTIME',
    accountLabel: 'Phone number',
    accountHint: 'The number to top up.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-airtel-airtime',
    name: 'Airtel airtime',
    category: 'AIRTIME',
    accountLabel: 'Phone number',
    accountHint: 'The number to top up.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-reg',
    name: 'REG — electricity (cash power)',
    category: 'ELECTRICITY',
    accountLabel: 'Meter number',
    accountHint: '11 digits, printed on the meter.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-wasac',
    name: 'WASAC — water',
    category: 'WATER',
    accountLabel: 'Customer number',
    accountHint: 'On your latest water bill.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-canal',
    name: 'Canal+ subscription',
    category: 'TV',
    accountLabel: 'Decoder number',
    accountHint: 'On the back of the decoder.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-startimes',
    name: 'StarTimes subscription',
    category: 'TV',
    accountLabel: 'Smartcard number',
    accountHint: 'On the front of the smartcard.',
    currency: 'RWF',
    amountFixed: false,
  },
  {
    id: 'biller-rra',
    name: 'RRA — tax declaration',
    category: 'TAX',
    accountLabel: 'Declaration reference',
    accountHint: 'The reference from your RRA declaration.',
    currency: 'RWF',
    /*
     * The amount comes from the declaration, not from the customer. A tax payment the
     * payer can round down is not a tax payment, so the field is locked and the figure
     * is quoted by the biller.
     */
    amountFixed: true,
  },
  {
    id: 'biller-afos',
    name: 'AFOS contribution',
    category: 'AFOS',
    accountLabel: 'Member number',
    accountHint: 'Your AFOS membership number.',
    currency: 'RWF',
    amountFixed: false,
  },
];

export function billersInCategory(category: string): readonly Biller[] {
  return BILLERS.filter((biller) => biller.category === category);
}

export function billerById(id: string): Biller | undefined {
  return BILLERS.find((biller) => biller.id === id);
}

/** What a fixed-amount biller quotes for a given reference. Deterministic, not random. */
export function quotedAmountFor(reference: string): string {
  let hash = 2166136261;
  for (let i = 0; i < reference.length; i += 1) {
    hash ^= reference.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  // 50,000 – 3,050,000 RWF, in whole thousands.
  return String(50000 + ((hash >>> 0) % 3000) * 1000);
}

/*
 * Everything below used to be fixtures for three seeded customers. They are gone, so
 * their standing orders, statements, cheque books, devices and security history are
 * gone with them. These arrays fill up by using the portal.
 *
 * The biller catalogue above is NOT customer data — it is the bank's own product list,
 * the same for everybody — so it stays. The branch list that used to sit below these was
 * not the bank's either; see the note where it was.
 */

/**
 * A trusted browser, plus the column the API never sends back.
 *
 * <p>The real response carries no owner — a customer reading their own list already knows
 * whose it is — but the mock needs one to scope the list and to refuse a revoke of
 * somebody else's row, which is the rule worth having a mock for. Same pattern as
 * `MockBeneficiary` in data/banking.ts.
 */
export interface MockTrustedDevice extends TrustedDevice {
  /** Mock-side only. Stripped by `publicDevice`. */
  readonly ownerId: string;
}

/** A sign-in, plus the owner the API never sends back. */
export interface MockSignIn extends SignIn {
  /** Mock-side only. Stripped by `publicSignIn`. */
  readonly ownerId: string;
}

export const STANDING_ORDERS: StandingOrder[] = [];
export const STATEMENTS: Statement[] = [];
export const DEVICES: MockTrustedDevice[] = [];
export const SIGN_INS: MockSignIn[] = [];

/* ------------------------------------------------------------------ scoping */

export function standingOrdersFor(userId: string): readonly StandingOrder[] {
  return STANDING_ORDERS.filter((item) => item.ownerId === userId);
}

export function statementsForAccounts(accountIds: readonly string[]): readonly Statement[] {
  return STATEMENTS.filter((item) => accountIds.includes(item.accountId));
}

/**
 * One customer's trusted browsers, WITH the mock-side owner still attached.
 *
 * <p>Stripped by `publicDevice` before anything is returned, for the reason recorded on
 * `MockTrustedDevice`.
 */
export function devicesFor(userId: string): readonly MockTrustedDevice[] {
  return DEVICES.filter((item) => item.ownerId === userId);
}

/** A trusted browser as the API returns it: no owner id. */
export function publicDevice(device: MockTrustedDevice): TrustedDevice {
  const { ownerId: _owner, ...rest } = device;
  return rest;
}

/** A sign-in as the API returns it: when, how, and on what. */
export function publicSignIn(signIn: MockSignIn): SignIn {
  const { ownerId: _owner, ...rest } = signIn;
  return rest;
}

export function signInsFor(userId: string): readonly MockSignIn[] {
  return SIGN_INS.filter((item) => item.ownerId === userId);
}

/*
 * THE BRANCH LIST IS GONE, and it was the third copy of the same six invented names —
 * CardsPage and ChequeBooksPage each held one too. docs/OPEN-ITEMS.md has recorded
 * throughout that the bank never supplied a branch list, so all three were the front end's
 * own invention, offered to customers as somewhere to go and collect a card.
 *
 * Nothing replaces it here. A member of staff types the collection point when the thing
 * exists; see ServiceRequestsPage.
 */


