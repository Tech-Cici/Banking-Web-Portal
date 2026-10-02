import { http, HttpResponse } from 'msw';
import type { MoneyDto } from '@/types/api';
import type {
  FxQuote,
  MovementQuote,
  MovementReceipt,
  StandingOrder,
  Statement,
} from '@/types/movement';
import { accountsFor, accountById } from './data/accounts';
import { beneficiariesFor } from './data/banking';
import {
  BILLERS,
  billerById,
  devicesFor,
  DEVICES,
  quotedAmountFor,
  signInsFor,
  publicDevice,
  publicSignIn,
  STANDING_ORDERS,
  standingOrdersFor,
  STATEMENTS,
  statementsForAccounts,
} from './data/movement';
import { activeCustomer } from './data/activeSession';
import { getActiveCorporateId } from './data/sessionStore';
import { mockApiError } from './handlers';
import { newCorrelationId } from '@/services/correlation';

/**
 * Mock endpoints for moving money and managing an account.
 *
 * Three things here are not padding, because they are the behaviours the UI has to be
 * built against rather than retrofitted to:
 *
 *  1. QUOTE BEFORE SEND. Fees and totals are computed here, on the "server", and the
 *     review screen shows exactly what comes back. A client that adds up its own total
 *     will disagree with the bank the first time a fee rule changes.
 *  2. IDEMPOTENCY IS ENFORCED, not just accepted. A repeated key returns the ORIGINAL
 *     receipt instead of moving money twice. A mock that ignores the header lets a
 *     double-submit bug reach production undetected.
 *  3. A CORPORATE SUBMISSION DOES NOT POST. It goes to PENDING_APPROVAL, because a maker
 *     cannot release their own payment.
 */

const API = '*/api/v1';

/** Replays of a completed submission, keyed by idempotency key. */
const receipts = new Map<string, MovementReceipt>();

/** Live quotes, so a submission cannot invent its own fee. */
const quotes = new Map<string, MovementQuote>();
const fxQuotes = new Map<string, FxQuote>();

/** The signed-in customer, or null. A staff session is not a customer. */
function persona() {
  return activeCustomer();
}

function unauthenticated() {
  return mockApiError(
    401,
    'UNAUTHENTICATED',
    'You have been signed out. Please sign in again — your money is unaffected.',
  );
}

function visibleAccountIds(userId: string): readonly string[] {
  return accountsFor(userId, getActiveCorporateId()).map((account) => account.id);
}

/* ------------------------------------------------------------------ money helpers */

/**
 * Minor-unit arithmetic with BigInt.
 *
 * The mock is standing in for a server, and a server that adds fees in floating point
 * is a server that eventually debits 0.30000000000000004.
 */
function minorUnits(value: string, scale: number): bigint {
  const [whole = '0', fraction = ''] = value.split('.');
  return BigInt(`${whole}${fraction.padEnd(scale, '0').slice(0, scale)}`);
}

function toDecimal(units: bigint, scale: number): string {
  if (scale === 0) return units.toString();
  const padded = units.toString().padStart(scale + 1, '0');
  return `${padded.slice(0, -scale)}.${padded.slice(-scale)}`;
}

const SCALES: Readonly<Record<string, number>> = { RWF: 0, USD: 2, EUR: 2, GBP: 2 };

function scaleOf(currency: string): number {
  return SCALES[currency] ?? 2;
}

function addMoney(a: MoneyDto, b: MoneyDto): MoneyDto {
  const scale = scaleOf(a.currency);
  return {
    amount: toDecimal(minorUnits(a.amount, scale) + minorUnits(b.amount, scale), scale),
    currency: a.currency,
  };
}

function isPositive(amount: MoneyDto): boolean {
  return minorUnits(amount.amount, scaleOf(amount.currency)) > 0n;
}

/**
 * The bank's fee schedule, such as it is.
 *
 * Invented, and deliberately different per rail so the review screen has something real
 * to show. THE CLIENT MUST NEVER REPRODUCE THIS — that is the whole point of quoting.
 */
function feeFor(kind: string, amount: MoneyDto): MoneyDto {
  const scale = scaleOf(amount.currency);
  const units = minorUnits(amount.amount, scale);

  const flat: Readonly<Record<string, bigint>> = {
    OWN: 0n,
    INTERNAL: 0n,
    EXTERNAL: 1000n,
    INTERNATIONAL: 15n,
    PAYMENT: 500n,
  };

  const base = flat[kind] ?? 0n;

  // International adds 0.5% on top of the flat charge.
  const variable = kind === 'INTERNATIONAL' ? units / 200n : 0n;

  return { amount: toDecimal(base + variable, scale), currency: amount.currency };
}

/* ------------------------------------------------------------------ quoting */

function makeQuote(kind: string, amount: MoneyDto, destinationSummary: string): MovementQuote {
  const fee = feeFor(kind, amount);
  const warnings: string[] = [];

  if (kind === 'INTERNATIONAL') {
    warnings.push('International payments can take up to three working days to arrive.');
  }

  const quote: MovementQuote = {
    quoteId: crypto.randomUUID(),
    amount,
    fee,
    totalDebit: addMoney(amount, fee),
    destinationSummary,
    // Two minutes: long enough to read, short enough that a stale fee cannot be sent.
    expiresAt: new Date(Date.now() + 120_000).toISOString(),
    warnings,
  };

  quotes.set(quote.quoteId, quote);
  return quote;
}

/* ------------------------------------------------------------------ submission */

interface SubmitArgs {
  readonly request: Request;
  readonly kind: string;
  readonly sourceAccountId: string;
  readonly amount: MoneyDto;
  readonly destinationSummary: string;
}

function submitMovement({
  request,
  kind,
  sourceAccountId,
  amount,
  destinationSummary,
}: SubmitArgs): Response {
  const active = persona();
  if (active === null) return unauthenticated();

  const key = request.headers.get('Idempotency-Key');
  if (key === null || key === '') {
    return mockApiError(
      400,
      'BAD_REQUEST',
      'This request is missing its idempotency key and was not processed.',
    );
  }

  /*
   * The same key twice returns the FIRST receipt. This is the behaviour the header
   * exists for: a double-click, a retry after a dropped connection, or a refresh must
   * not move money a second time.
   */
  const replay = receipts.get(key);
  if (replay !== undefined) return HttpResponse.json(replay);

  const source = accountById(sourceAccountId);
  if (source === undefined || !visibleAccountIds(active.user.id).includes(sourceAccountId)) {
    return mockApiError(
      404,
      'NOT_FOUND',
      'We could not find that account. It may have been closed, or it may not be one of yours.',
    );
  }

  if (!source.debitAllowed) {
    return mockApiError(
      422,
      'BUSINESS_RULE_VIOLATION',
      `${source.nickname} cannot be debited while it is ${source.status.toLowerCase()}.`,
    );
  }

  if (!isPositive(amount)) {
    return HttpResponse.json(
      {
        timestamp: new Date().toISOString(),
        status: 422,
        code: 'VALIDATION_FAILED',
        message: 'Enter an amount greater than zero.',
        correlationId: newCorrelationId(),
        fieldErrors: [
          { field: 'amount', code: 'POSITIVE', message: 'Enter an amount greater than zero.' },
        ],
      },
      { status: 422 },
    );
  }

  const fee = feeFor(kind, amount);
  const total = addMoney(amount, fee);
  const scale = scaleOf(amount.currency);

  /*
   * An unknown balance REFUSES the payment. It does not wave it through.
   *
   * A balance is absent when the account is real but core banking is not connected, so
   * this service genuinely does not know what is in it. Treating unknown as "probably
   * enough" is how an overdraft happens on a system that never had the authority to
   * decide; the only safe reading of "I don't know" is no.
   */
  if (source.availableBalance === undefined) {
    return mockApiError(
      422,
      'BUSINESS_RULE_VIOLATION',
      'We cannot check the balance on that account at the moment, so this payment has not ' +
        'been made. Please try again shortly.',
    );
  }

  if (
    source.currency === amount.currency &&
    minorUnits(total.amount, scale) > minorUnits(source.availableBalance.amount, scale)
  ) {
    return mockApiError(
      422,
      'BUSINESS_RULE_VIOLATION',
      'There is not enough available in that account to cover this payment and its fee.',
    );
  }

  /*
   * A corporate payment is PREPARED, not posted. Maker–checker is a property of the
   * account, not of the screen, so it is applied here rather than left to the UI.
   */
  const corporate = getActiveCorporateId() !== undefined;

  const receipt: MovementReceipt = {
    id: crypto.randomUUID(),
    reference: `TXN-2026-${String(Math.abs(hash(key)) % 1000000).padStart(6, '0')}`,
    status: corporate ? 'PENDING_APPROVAL' : 'COMPLETED',
    amount,
    fee,
    totalDebit: total,
    sourceAccountMask: source.maskedNumber,
    destinationSummary,
    submittedAt: new Date().toISOString(),
    awaitingApproval: corporate,
  };

  receipts.set(key, receipt);
  return HttpResponse.json(receipt, { status: 201 });
}

function hash(text: string): number {
  let value = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    value ^= text.charCodeAt(i);
    value = Math.imul(value, 16777619);
  }
  return value | 0;
}

async function body(request: Request): Promise<Record<string, unknown>> {
  return (await request.json()) as Record<string, unknown>;
}

function str(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  return typeof value === 'string' ? value : '';
}

function money(source: Record<string, unknown>, field: string): MoneyDto {
  const value = source[field];
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    return { amount: str(record, 'amount'), currency: str(record, 'currency') };
  }
  return { amount: '0', currency: 'RWF' };
}

/* ------------------------------------------------------------------ handlers */

export const movementHandlers = [
  /* ---------------------------------------------- transfers */

  /*
   * THE TRANSFER HANDLERS ARE GONE, and their absence is what makes transfers live.
   *
   * MSW passes a request it has no handler for through to the network, so deleting these
   * is how `/transfers` reaches the Spring Boot service. `transfers` is listed in
   * VITE_LIVE_API as well, which is belt and braces — the list is what fails the build
   * loudly if anybody routes a path that has no controller behind it.
   *
   * WHAT WAS HERE, because it caused a real bug worth not repeating. `POST /transfers`
   * and `POST /transfers/quote` were answered from the mock's own fixture store. Once
   * accounts went live, the accounts a customer could see came from the database and the
   * mock knew nothing about them — so moving money from a current account to a savings
   * account of the same person returned "We could not find that account. It may have been
   * closed, or it may not be one of yours." The account was right there on the screen.
   *
   * The quote endpoint is not replaced. There is no fee inside Zigama and no quote
   * endpoint in the service; a client-side quote step would have been a fee schedule in
   * the browser, which is the thing the brief forbids outright.
   */

  /* ---------------------------------------------- payments */

  http.get(`${API}/billers`, ({ request }) => {
    const category = new URL(request.url).searchParams.get('category');
    return HttpResponse.json(
      category === null ? BILLERS : BILLERS.filter((biller) => biller.category === category),
    );
  }),

  http.post(`${API}/payments/quote`, async ({ request }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const payload = await body(request);
    const biller = billerById(str(payload, 'billerId'));
    if (biller === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We do not recognise that company. Choose one from the list.',
      );

    const reference = str(payload, 'customerReference');

    /*
     * For a fixed-amount biller the figure comes from the biller, not the request. A
     * tax payment whose amount the payer can choose is not a tax payment.
     */
    const amount = biller.amountFixed
      ? { amount: quotedAmountFor(reference), currency: biller.currency }
      : money(payload, 'amount');

    return HttpResponse.json(makeQuote('PAYMENT', amount, `${biller.name} — ${reference}`));
  }),

  http.post(`${API}/payments`, async ({ request }) => {
    const payload = await body(request);
    const quote = quotes.get(str(payload, 'quoteId'));

    if (quote === undefined) {
      return mockApiError(
        409,
        'CONFLICT',
        'That quote has expired. Please review the details and the fee again.',
      );
    }

    return submitMovement({
      request,
      kind: 'PAYMENT',
      sourceAccountId: str(payload, 'sourceAccountId'),
      amount: quote.amount,
      destinationSummary: quote.destinationSummary,
    });
  }),

  /* ---------------------------------------------- foreign exchange */

  http.post(`${API}/fx/quote`, async ({ request }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const payload = await body(request);
    const sell = money(payload, 'sellAmount');
    const buyCurrency = str(payload, 'buyCurrency');

    /*
     * An invented but fixed rate table. The RATE IS THE BANK'S — the client must never
     * work out the other side of the conversion itself, which is why both sides come
     * back in the quote.
     */
    const rates: Readonly<Record<string, number>> = {
      'RWF>USD': 1 / 1285.4,
      'USD>RWF': 1285.4,
      'RWF>EUR': 1 / 1402.1,
      'EUR>RWF': 1402.1,
    };

    const pair = `${sell.currency}>${buyCurrency}`;
    const rate = rates[pair];

    if (rate === undefined) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        `We do not exchange ${sell.currency} for ${buyCurrency}. Choose a different pair of currencies.`,
      );
    }

    const sellScale = scaleOf(sell.currency);
    const buyScale = scaleOf(buyCurrency);
    const sellUnits = Number(minorUnits(sell.amount, sellScale));
    const buyUnits = BigInt(Math.round((sellUnits / 10 ** sellScale) * rate * 10 ** buyScale));

    const quote: FxQuote = {
      quoteId: crypto.randomUUID(),
      sellAmount: sell,
      buyAmount: { amount: toDecimal(buyUnits, buyScale), currency: buyCurrency },
      rate: pair.startsWith('RWF') ? (1 / rate).toFixed(2) : rate.toFixed(2),
      rateExpiresAt: new Date(Date.now() + 90_000).toISOString(),
      fee: { amount: toDecimal(0n, sellScale), currency: sell.currency },
    };

    fxQuotes.set(quote.quoteId, quote);
    return HttpResponse.json(quote);
  }),

  http.get(`${API}/fx/quote/:quoteId`, ({ params }) => {
    const quote = fxQuotes.get(String(params['quoteId']));
    return quote === undefined
      ? mockApiError(
          404,
          'NOT_FOUND',
          'That exchange rate has expired — rates only hold for a short time. Please get a new one.',
        )
      : HttpResponse.json(quote);
  }),

  http.post(`${API}/fx`, async ({ request }) => {
    const payload = await body(request);
    const quote = fxQuotes.get(str(payload, 'quoteId'));

    if (quote === undefined) {
      return mockApiError(
        409,
        'CONFLICT',
        'That rate has expired — rates only hold for a short time. Please get a new one.',
      );
    }

    return submitMovement({
      request,
      kind: 'OWN',
      sourceAccountId: str(payload, 'sourceAccountId'),
      amount: quote.sellAmount,
      destinationSummary: `Buy ${quote.buyAmount.currency} ${quote.buyAmount.amount} at ${quote.rate}`,
    });
  }),

  /* ---------------------------------------------- standing orders */

  http.get(`${API}/standing-orders`, () => {
    const active = persona();
    return active === null
      ? unauthenticated()
      : HttpResponse.json(standingOrdersFor(active.user.id));
  }),

  http.get(`${API}/standing-orders/:id`, ({ params }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const order = standingOrdersFor(active.user.id).find((o) => o.id === String(params['id']));
    return order === undefined
      ? mockApiError(
          404,
          'NOT_FOUND',
          'We could not find that standing order. It may already have been cancelled.',
        )
      : HttpResponse.json(order);
  }),

  http.post(`${API}/standing-orders`, async ({ request }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const payload = await body(request);
    const source = accountById(str(payload, 'sourceAccountId'));
    const beneficiary = beneficiariesFor(active.user.id).find(
      (b) => b.id === str(payload, 'beneficiaryId'),
    );

    if (source === undefined || beneficiary === undefined) {
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that account or that saved payee. Check your list and try again.',
      );
    }

    /*
     * AN UNAPPROVED PAYEE IS REFUSED HERE, not only hidden from the picker.
     *
     * The screen already filters its list to ACTIVE payees, and that filter is a
     * convenience rather than a control: a request built by anything other than that
     * screen bypassed it completely. This is the same check the real service applies to a
     * transfer, and it is here so the mock cannot be the reason a missing server-side
     * check goes unnoticed in development.
     */
    if (beneficiary.status !== 'ACTIVE') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        beneficiary.status === 'REFUSED'
          ? 'We were not able to approve that payee, so it cannot be paid. There is a reason on your payee list.'
          : 'That payee is still being checked by the bank, so it cannot be paid yet. You will be emailed when it is done.',
      );
    }

    const created: StandingOrder = {
      id: crypto.randomUUID(),
      reference: `SO-2026-${String(Math.abs(hash(beneficiary.id + Date.now().toString())) % 100000).padStart(5, '0')}`,
      sourceAccountMask: source.maskedNumber,
      destinationSummary: `${beneficiary.name} — ${beneficiary.maskedDestination}`,
      amount: money(payload, 'amount'),
      frequency: (str(payload, 'frequency') || 'MONTHLY') as StandingOrder['frequency'],
      nextRunOn: str(payload, 'startOn'),
      ...(str(payload, 'endsOn') === '' ? {} : { endsOn: str(payload, 'endsOn') }),
      status: 'ACTIVE',
      ownerId: active.user.id,
    };

    STANDING_ORDERS.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),

  http.post(`${API}/standing-orders/:id/pause`, ({ params }) => {
    const order = STANDING_ORDERS.find((o) => o.id === String(params['id']));
    if (order === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that standing order. It may already have been cancelled.',
      );

    const index = STANDING_ORDERS.indexOf(order);
    const next: StandingOrder = {
      ...order,
      status: order.status === 'PAUSED' ? 'ACTIVE' : 'PAUSED',
    };
    STANDING_ORDERS[index] = next;
    return HttpResponse.json(next);
  }),

  http.delete(`${API}/standing-orders/:id`, ({ params }) => {
    const order = STANDING_ORDERS.find((o) => o.id === String(params['id']));
    if (order === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that standing order. It may already have been cancelled.',
      );

    STANDING_ORDERS[STANDING_ORDERS.indexOf(order)] = { ...order, status: 'ENDED' };
    return new HttpResponse(null, { status: 204 });
  }),

  /* ---------------------------------------------- statements */

  http.get(`${API}/statements`, () => {
    const active = persona();
    return active === null
      ? unauthenticated()
      : HttpResponse.json(statementsForAccounts(visibleAccountIds(active.user.id)));
  }),

  http.post(`${API}/statements`, async ({ request }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const payload = await body(request);
    const account = accountById(str(payload, 'accountId'));

    if (account === undefined || !visibleAccountIds(active.user.id).includes(account.id)) {
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that account. It may have been closed, or it may not be one of yours.',
      );
    }

    const from = str(payload, 'from');
    const to = str(payload, 'to');

    if (from === '' || to === '' || Date.parse(from) > Date.parse(to)) {
      return HttpResponse.json(
        {
          timestamp: new Date().toISOString(),
          status: 422,
          code: 'VALIDATION_FAILED',
          message: 'Check the dates you chose.',
          correlationId: newCorrelationId(),
          fieldErrors: [
            {
              field: 'to',
              code: 'RANGE',
              message:
                'The end date is before the start date. Choose an end date that comes after it.',
            },
          ],
        },
        { status: 422 },
      );
    }

    const created: Statement = {
      id: crypto.randomUUID(),
      accountId: account.id,
      accountMask: account.maskedNumber,
      periodFrom: from,
      periodTo: to,
      format: str(payload, 'format') === 'CSV' ? 'CSV' : 'PDF',
      requestedAt: new Date().toISOString(),
      // Not READY: a statement is produced by a batch, and pretending otherwise would
      // hide the waiting state the real screen has to handle.
      status: 'GENERATING',
    };

    STATEMENTS.unshift(created);
    return HttpResponse.json(created, { status: 201 });
  }),

  /* ---------------------------------------------- loans */

  /**
   * Indicative repayment figures.
   *
   * The amortisation lives here, on the server side of the boundary, for the reason
   * stated on the simulation screen: the bank's own rounding and day-count conventions
   * decide the instalment, and a browser that reproduces them will drift out of step
   * the first time either changes.
   */
  http.post(`${API}/loans/simulate`, async ({ request }) => {
    const payload = await body(request);
    const principal = money(payload, 'amount');
    const months = Number(str(payload, 'months')) || Number(payload['months']) || 12;
    const product = str(payload, 'product') || 'PERSONAL';

    const rates: Readonly<Record<string, number>> = {
      PERSONAL: 16.5,
      SALARY_ADVANCE: 12.0,
      BUSINESS: 18.25,
    };
    const annual = rates[product] ?? 16.5;

    const scale = scaleOf(principal.currency);
    const units = Number(minorUnits(principal.amount, scale));
    const monthly = annual / 100 / 12;

    // Standard annuity, rounded to whole minor units the way the core system would.
    const factor = Math.pow(1 + monthly, months);
    const installment = Math.round((units * monthly * factor) / (factor - 1));
    const totalRepayable = installment * months;

    return HttpResponse.json({
      principal,
      months,
      product,
      interestRatePercent: annual.toFixed(2),
      monthlyInstallment: {
        amount: toDecimal(BigInt(installment), scale),
        currency: principal.currency,
      },
      totalInterest: {
        amount: toDecimal(BigInt(totalRepayable - units), scale),
        currency: principal.currency,
      },
      totalRepayable: {
        amount: toDecimal(BigInt(totalRepayable), scale),
        currency: principal.currency,
      },
    });
  }),

  http.post(`${API}/loans/applications`, async ({ request }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const payload = await body(request);
    const amount = money(payload, 'amount');

    if (!isPositive(amount)) {
      return mockApiError(422, 'BUSINESS_RULE_VIOLATION', 'Enter an amount greater than zero.');
    }

    return HttpResponse.json(
      {
        id: crypto.randomUUID(),
        reference: `LNA-2026-${String(Date.now() % 100000).padStart(5, '0')}`,
        requestType: 'LOAN',
        submittedAt: new Date().toISOString(),
        status: 'SUBMITTED',
        summary: `${str(payload, 'product')} — ${amount.currency} ${amount.amount}`,
        ownerId: active.user.id,
      },
      { status: 201 },
    );
  }),

  /* ---------------------------------------------- security */

  /**
   * Browsers that may skip the emailed code.
   *
   * <p>NOT "DEVICES SIGNED IN", which is what the screen used to call these and what the
   * old 422 below said. A trusted browser is permission to skip the code; it is not a
   * session and removing it signs nobody out of anything.
   */
  http.get(`${API}/security/devices`, () => {
    const active = persona();
    return active === null
      ? unauthenticated()
      : HttpResponse.json(devicesFor(active.user.id).map(publicDevice));
  }),

  http.delete(`${API}/security/devices/:id`, ({ params }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const device = DEVICES.find((row) => row.id === String(params['id']));
    /*
     * 404 FOR BOTH "no such row" AND "not yours", and the same sentence for each —
     * matching the server, and for the reason the server gives: "that row exists but
     * belongs to somebody else" is more than the caller should learn.
     */
    if (device?.ownerId !== active.user.id) {
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that browser on your account. It may already have been removed.',
      );
    }

    if (device.current) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'This is the browser you are using now, so it cannot be removed from here. Sign out' +
          ' to end this session, or remove your other browsers.',
      );
    }

    /*
     * REVOKED IN PLACE, NOT SPLICED OUT. The old mock deleted the row, which made the
     * screen behave quite differently from production: V14 keeps revoked devices because
     * "this browser was trusted and then it was not" is the history somebody investigating
     * an unauthorised sign-in needs, and the screen shows them labelled.
     */
    DEVICES[DEVICES.indexOf(device)] = {
      ...device,
      revoked: true,
      revokedReason: 'Removed by the customer',
    };
    return new HttpResponse(null, { status: 204 });
  }),

  /**
   * The customer's own sign-ins, newest first.
   *
   * <p>NO LOCATION AND NO OUTCOME. The old mock returned both: a `location` the tables have
   * never held — see V19 on the literal "Kigali, Rwanda" the real service recorded at four
   * call sites — and an `outcome` that was always SUCCESS because nothing records a failed
   * attempt. A mock that invents a field the server cannot send is a mock that lets a
   * screen be built around it.
   */
  http.get(`${API}/security/events`, () => {
    const active = persona();
    return active === null
      ? unauthenticated()
      : HttpResponse.json(
          [...signInsFor(active.user.id)]
            .sort((a, b) => b.at.localeCompare(a.at))
            .slice(0, 20)
            .map(publicSignIn),
        );
  }),

  http.post(`${API}/security/password`, async ({ request }) => {
    const active = persona();
    if (active === null) return unauthenticated();

    const payload = await body(request);
    const current = str(payload, 'currentPassword');
    const next = str(payload, 'newPassword');

    if (current !== 'Seeded1Password') {
      return HttpResponse.json(
        {
          timestamp: new Date().toISOString(),
          status: 422,
          code: 'VALIDATION_FAILED',
          message: 'That is not your current password. Check it and type it again.',
          correlationId: newCorrelationId(),
          fieldErrors: [
            {
              field: 'currentPassword',
              code: 'INCORRECT',
              message: 'That is not your current password. Check it and type it again.',
            },
          ],
        },
        { status: 422 },
      );
    }

    if (next === current) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Choose a password that is different from your current one.',
      );
    }

    /*
     * EVERY TRUSTED BROWSER IS REVOKED, which the old mock did not do — it answered 204
     * and left the device list untouched, so the screen could be written as though a
     * password change had no other effect. It has: a customer changing their password
     * because they fear somebody has it has not finished the job while every browser that
     * person trusted still signs in without a code.
     */
    let signedOut = 0;
    for (const device of [...DEVICES]) {
      if (device.ownerId !== active.user.id || device.revoked) continue;
      DEVICES[DEVICES.indexOf(device)] = {
        ...device,
        revoked: true,
        revokedReason: 'The customer changed their password',
      };
      signedOut++;
    }

    /*
     * The password itself is NOT changed. The seeded one stays what it was on purpose — a
     * mock that really changed it would lock the developer out of their own fixtures on
     * reload. The count is real, because the screen branches on it.
     */
    return HttpResponse.json({ browsersSignedOut: signedOut });
  }),
];
