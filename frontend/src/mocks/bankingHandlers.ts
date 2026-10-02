import { http, HttpResponse } from 'msw';
import type { ApiErrorEnvelope, MoneyDto, Permission } from '@/types/api';
import type { Account, ApprovalItem, Beneficiary } from '@/types/banking';
import type { CashReceipt } from '@/types/movement';
import { mockApiError } from './handlers';
import { accountsFor } from './data/accounts';
import {
  approvalsFor,
  batchesFor,
  BENEFICIARIES,
  beneficiariesFor,
  publicBeneficiary,
  type MockBeneficiary,
  CARDS,
  cardsFor,
  loansFor,
  mockRequestReference,
  NOTIFICATIONS,
  notificationsFor,
  publicNotification,
  publicServiceRequest,
  type MockServiceRequest,
  SERVICE_REQUESTS,
  serviceRequestsFor,
} from './data/banking';
import { activeCustomer } from './data/activeSession';
import { customerById, sessionUserFor } from './data/onboarding';
import { corporateById, membershipsFor } from './data/personas';
import { customerSessionId, getActiveCorporateId, setActiveCorporate } from './data/sessionStore';
import { recentTransactions, transactionsFor } from './data/transactions';
import { PROVISIONED_ACCOUNTS, saveAccounts } from './data/onboarding';
import {
  addMoney,
  compareMoney,
  isZeroMoney,
  moneyFromDto,
  moneyToDto,
  parseMoney,
  subtractMoney,
  type Money,
} from '@/utils/money';
import { newCorrelationId } from '@/services/correlation';

/**
 * Mock banking endpoints, scoped to the active seeded persona.
 *
 * Every handler filters by the current user or the selected company — the same scoping
 * the real API must apply. Returning everything and filtering in the UI would hide
 * exactly the bug that matters: one company seeing another's data.
 */

const API = '*/api/v1';

/** 401 for an unauthenticated caller — what drives the route guard in the app. */
function unauthenticated(): HttpResponse<ApiErrorEnvelope> {
  return mockApiError(
    401,
    'UNAUTHENTICATED',
    'You have been signed out. Please sign in again — your money is unaffected.',
  );
}

function visibleAccounts(): readonly Account[] {
  const persona = activeCustomer();
  if (persona === null) return [];
  return accountsFor(persona.user.id, getActiveCorporateId());
}

/**
 * Strips the amount from a salary item for a caller without SALARY_VIEW_DETAILS.
 *
 * The rule lives on the server side of the mock deliberately. Hiding the figure in the
 * component would mean the browser had been sent it, which is the leak itself.
 */
function withheldIfSalary(persona: { user: { permissions: readonly Permission[] } }) {
  const allowed = persona.user.permissions.includes('SALARY_VIEW_DETAILS');

  return (item: ApprovalItem): ApprovalItem => {
    if (item.itemType !== 'SALARY' || allowed) return item;
    const { amount: _withheld, ...rest } = item;
    return { ...rest, amountWithheld: true };
  };
}

/** Refuses any account the active persona is not entitled to see. */
function entitledAccount(accountId: string): Account | undefined {
  return visibleAccounts().find((account) => account.id === accountId);
}

/**
 * The mock's ledger, for idempotency only.
 *
 * In memory rather than persisted: an idempotency key that outlives the page is a key
 * that can resurrect a transaction somebody believes they abandoned.
 */
const CASH_LEDGER: { key: string; receipt: CashReceipt }[] = [];

/** Writes the new balance onto the stored account, so the next read agrees with it. */
function applyBalance(accountId: string, balance: MoneyDto, at: string): void {
  const index = PROVISIONED_ACCOUNTS.findIndex((entry) => entry.id === accountId);
  if (index < 0) return;

  const existing = PROVISIONED_ACCOUNTS[index];
  if (existing === undefined) return;

  PROVISIONED_ACCOUNTS[index] = {
    ...existing,
    availableBalance: balance,
    currentBalance: balance,
    balanceAsOf: at,
  };
  saveAccounts();
}

function paginate<T>(items: readonly T[], url: URL) {
  const page = Number(url.searchParams.get('page') ?? '0');
  const size = Number(url.searchParams.get('size') ?? '20');
  const safePage = Number.isInteger(page) && page >= 0 ? page : 0;
  const safeSize = Number.isInteger(size) && size > 0 && size <= 200 ? size : 20;
  const start = safePage * safeSize;

  return {
    content: items.slice(start, start + safeSize),
    page: safePage,
    size: safeSize,
    totalElements: items.length,
    totalPages: Math.max(1, Math.ceil(items.length / safeSize)),
  };
}

export const bankingHandlers = [
  /* ------------------------------------------------ session */

  /**
   * The current session.
   *
   * Deliberately does NOT go through `activeCustomer`, which refuses a session still on
   * its temporary password. This endpoint has to answer for exactly that customer — it
   * is what tells the app to send them to the change-password screen. Every endpoint
   * that returns money or account data does go through it.
   */
  http.get(`${API}/session`, () => {
    const id = customerSessionId();
    const record = id === null ? undefined : customerById(id);

    if (record?.status !== 'ACTIVE') return unauthenticated();

    const company =
      record.corporateId === undefined ? undefined : corporateById(record.corporateId);

    return HttpResponse.json({
      user: sessionUserFor(
        record,
        membershipsFor(record.corporateId, company === undefined ? 'VIEWER' : 'ADMIN'),
      ),
      activeCorporateId: getActiveCorporateId() ?? null,
      mustChangePassword: record.mustChangePassword,
    });
  }),

  http.post(`${API}/session/corporate`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    const corporateId = typeof body['corporateId'] === 'string' ? body['corporateId'] : '';

    const current = activeCustomer();
    if (current === null) return unauthenticated();

    const permitted = current.user.corporates.some((membership) => membership.id === corporateId);
    if (!setActiveCorporate(corporateId, permitted)) {
      /*
       * The same answer whether the company does not exist or simply is not theirs.
       *
       * This deliberately returned 403 for "exists but you are not a member" and 404 for
       * "no such company", with a comment explaining the distinction as if it were a
       * feature. It let any signed-in customer probe company ids and map the bank's
       * corporate client list by the difference in status code — and company ids appear
       * in URLs, so they are not hard to come by.
       *
       * A member switching companies picks from their own list and never sees this. The
       * only person who reaches it is someone asking about a company that is not theirs,
       * and the honest answer to them is that there is nothing here for them.
       */
      return mockApiError(404, 'NOT_FOUND', 'We could not find that company in your list.');
    }

    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    return HttpResponse.json({
      user: persona.user,
      activeCorporateId: getActiveCorporateId() ?? null,
      mustChangePassword: persona.record.mustChangePassword,
    });
  }),

  /*
   * The development persona switch is GONE.
   *
   * It existed to jump between three seeded users. There are no seeded users any more —
   * every customer has to be registered, created and approved — so there is nothing to
   * switch to, and an endpoint that could adopt another customer's session has no place
   * in a banking app even in development.
   */

  /* ------------------------------------------------ accounts */

  http.get(`${API}/accounts`, () => HttpResponse.json(visibleAccounts())),

  http.get(`${API}/accounts/:accountId`, ({ params }) => {
    const account = entitledAccount(String(params['accountId']));
    if (account === undefined) {
      // Same response whether it does not exist or is not visible, so the endpoint
      // cannot be used to discover which accounts exist.
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that account. It may have been closed, or it may not be one of yours.',
      );
    }
    return HttpResponse.json(account);
  }),

  http.get(`${API}/accounts/:accountId/balance`, ({ params }) => {
    const account = entitledAccount(String(params['accountId']));
    if (account === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that account. It may have been closed, or it may not be one of yours.',
      );

    return HttpResponse.json({
      availableBalance: account.availableBalance,
      currentBalance: account.currentBalance,
      asOf: account.balanceAsOf,
    });
  }),

  http.get(`${API}/accounts/:accountId/transactions`, ({ params, request }) => {
    const account = entitledAccount(String(params['accountId']));
    if (account === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that account. It may have been closed, or it may not be one of yours.',
      );

    const url = new URL(request.url);
    let rows = transactionsFor(account.id);

    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');
    const search = url.searchParams.get('search');
    const direction = url.searchParams.get('direction');

    if (from !== null) rows = rows.filter((r) => Date.parse(r.bookedAt) >= Date.parse(from));
    if (to !== null) rows = rows.filter((r) => Date.parse(r.bookedAt) <= Date.parse(to));
    if (direction === 'DEBIT' || direction === 'CREDIT') {
      rows = rows.filter((r) => r.direction === direction);
    }
    if (search !== null && search.trim() !== '') {
      const needle = search.trim().toLowerCase();
      rows = rows.filter(
        (r) =>
          r.description.toLowerCase().includes(needle) ||
          r.reference.toLowerCase().includes(needle),
      );
    }

    return HttpResponse.json(paginate(rows, url));
  }),

  /* ------------------------------------------------ cash in and out */

  /*
   * Deposit and withdrawal, mirroring the server's rules rather than a friendlier
   * version of them.
   *
   * A mock that is kinder than the thing it stands in for teaches a false model of the
   * product, and this repository has already been bitten by exactly that: the admin
   * create-account mock once showed an account appearing while the real endpoint
   * discarded the body, so nobody asked why it never worked against the backend.
   *
   * So all four rules are here: the idempotency key is REQUIRED, a replay returns the
   * original entry, a withdrawal may not take the balance below zero, and the amount's
   * precision must fit the currency — RWF has no minor unit, so it takes no decimals.
   */

  ...(['deposit', 'withdraw'] as const).map((direction) =>
    http.post(`${API}/accounts/:accountId/${direction}`, async ({ params, request }) => {
      const account = entitledAccount(String(params['accountId']));
      if (account === undefined) {
        return mockApiError(
          404,
          'NOT_FOUND',
          'We could not find that account. It may have been closed, or it may not be one of yours.',
        );
      }

      const key = request.headers.get('Idempotency-Key');
      if (key === null || key.trim() === '') {
        /*
         * Refused rather than given a generated fallback. A fallback defeats the whole
         * mechanism: the retry after a lost response arrives with a fresh key and posts
         * the money a second time.
         */
        return mockApiError(400, 'BAD_REQUEST', 'A required request header is missing.');
      }

      const replay = CASH_LEDGER.find((entry) => entry.key === key);
      if (replay !== undefined) {
        if (replay.receipt.accountId !== account.id) {
          return mockApiError(
            422,
            'BUSINESS_RULE_VIOLATION',
            'That request has already been used for a different account.',
          );
        }
        return HttpResponse.json(replay.receipt);
      }

      const payload = (await request.json()) as Record<string, unknown>;
      const raw = typeof payload['amount'] === 'string' ? payload['amount'].trim() : '';

      if (!/^\d{1,15}(\.\d{1,2})?$/.test(raw)) {
        return mockApiError(400, 'VALIDATION_FAILED', 'Enter an amount such as 5000 or 5000.75.');
      }

      /*
       * The CURRENCY decides the scale, not a constant. RWF has no minor unit, so
       * "500.50" is refused here exactly as the server refuses it — assuming two decimal
       * places would make every franc amount a hundred times out.
       */
      let amount: Money;
      let balance: Money;
      try {
        amount = parseMoney(raw, account.currency);
        balance = moneyFromDto(
          account.currentBalance ?? { amount: '0', currency: account.currency },
        );
      } catch {
        return mockApiError(
          422,
          'BUSINESS_RULE_VIOLATION',
          `${account.currency} amounts do not have that many decimal places.`,
        );
      }

      if (isZeroMoney(amount)) {
        return mockApiError(422, 'BUSINESS_RULE_VIOLATION', 'Enter an amount greater than zero.');
      }

      if (direction === 'withdraw' && compareMoney(amount, balance) > 0) {
        // No overdraft product: an account that could go negative is lending money
        // nobody approved.
        return mockApiError(
          422,
          'BUSINESS_RULE_VIOLATION',
          'There is not enough in that account to cover this withdrawal.',
        );
      }

      const after =
        direction === 'deposit' ? addMoney(balance, amount) : subtractMoney(balance, amount);
      const afterDto = moneyToDto(after);
      const bookedAt = new Date().toISOString();
      const note =
        typeof payload['description'] === 'string' && payload['description'].trim() !== ''
          ? payload['description'].trim()
          : direction === 'deposit'
            ? 'Deposit'
            : 'Withdrawal';

      applyBalance(account.id, afterDto, bookedAt);

      const receipt = {
        id: crypto.randomUUID(),
        accountId: account.id,
        direction: direction === 'deposit' ? ('CREDIT' as const) : ('DEBIT' as const),
        amount: moneyToDto(amount),
        balanceAfter: afterDto,
        description: note,
        bookedAt,
        status: 'COMPLETED' as const,
      };

      CASH_LEDGER.push({ key, receipt });
      return HttpResponse.json(receipt);
    }),
  ),

  /** Newest activity across every visible account — the dashboard's recent list. */
  http.get(`${API}/transactions/recent`, ({ request }) => {
    const url = new URL(request.url);
    const limit = Number(url.searchParams.get('limit') ?? '8');
    const safeLimit = Number.isInteger(limit) && limit > 0 && limit <= 50 ? limit : 8;
    const ids = visibleAccounts().map((account) => account.id);
    return HttpResponse.json(recentTransactions(ids, safeLimit));
  }),

  /* ------------------------------------------------ other reads */

  http.get(`${API}/beneficiaries`, () => {
    const persona = activeCustomer();
    /* Mapped through publicBeneficiary: the full number is mock-side only. */
    return persona === null
      ? unauthenticated()
      : HttpResponse.json(beneficiariesFor(persona.user.id).map(publicBeneficiary));
  }),

  /**
   * Adds a beneficiary.
   *
   * Created as PENDING_VERIFICATION, never ACTIVE, and there is no second factory that
   * makes an ACTIVE one. A new payee is held at every bank that has thought about fraud:
   * the commonest social-engineering script is "add this account and send the money now",
   * and the hold is what breaks it. A mock that returned ACTIVE would let the UI be built
   * as though the hold did not exist.
   *
   * WHAT RELEASES IT IS A MEMBER OF STAFF, through `/admin/beneficiaries/:id/approve` —
   * not a timer. For a long time nothing released it at all, in the mock or anywhere
   * else, and the screens told the customer about a "cooling-off period" that did not
   * exist.
   */
  http.post(`${API}/beneficiaries`, async ({ request }) => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    const payload = (await request.json()) as Record<string, unknown>;
    const read = (field: string): string =>
      typeof payload[field] === 'string' ? payload[field] : '';

    const name = read('name').trim();
    const destination = read('destination').replace(/\s+/g, '');

    if (name === '' || destination.length < 4) {
      return HttpResponse.json(
        {
          timestamp: new Date().toISOString(),
          status: 422,
          code: 'VALIDATION_FAILED',
          message: 'Check the payee details below.',
          correlationId: newCorrelationId(),
          fieldErrors: [
            ...(name === ''
              ? [{ field: 'name', code: 'REQUIRED', message: 'Enter the payee name.' }]
              : []),
            ...(destination.length < 4
              ? [
                  {
                    field: 'destination',
                    code: 'INVALID',
                    message: 'Enter a full account number or phone number.',
                  },
                ]
              : []),
          ],
        },
        { status: 422 },
      );
    }

    const created: MockBeneficiary = {
      id: crypto.randomUUID(),
      name,
      beneficiaryType: (read('beneficiaryType') || 'INTERNAL') as Beneficiary['beneficiaryType'],
      provider: read('provider') || 'Same bank',
      maskedDestination: `**** ${destination.slice(-4)}`,
      currency: read('currency') || 'RWF',
      status: 'PENDING_VERIFICATION',
      ownerId: persona.user.id,
      addedAt: new Date().toISOString(),
      /*
       * KEPT HERE AND STRIPPED FROM EVERY RESPONSE. The staff review queue compares the
       * name the customer typed with the name the bank holds for this ACCOUNT, and a mask
       * cannot be looked up — masks are not unique. `publicBeneficiary` is what the
       * handlers return.
       */
      accountNumber: destination,
    };

    BENEFICIARIES.push(created);
    return HttpResponse.json(publicBeneficiary(created), { status: 201 });
  }),

  http.delete(`${API}/beneficiaries/:id`, ({ params }) => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    const found = BENEFICIARIES.find(
      (item) => item.id === String(params['id']) && item.ownerId === persona.user.id,
    );
    if (found === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that saved payee. It may have been removed.',
      );

    BENEFICIARIES.splice(BENEFICIARIES.indexOf(found), 1);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/cards`, () => {
    const persona = activeCustomer();
    return persona === null ? unauthenticated() : HttpResponse.json(cardsFor(persona.user.id));
  }),

  /**
   * Blocks or unblocks a card.
   *
   * Blocking is immediate and needs no second factor: a customer who has just lost their
   * wallet should not be made to find a one-time code first. UNBLOCKING is the dangerous
   * direction, so that is where the friction belongs — refused here, and routed to the
   * branch or the contact centre.
   */
  http.post(`${API}/cards/:id/block`, ({ params }) => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    const index = CARDS.findIndex(
      (card) => card.id === String(params['id']) && card.ownerId === persona.user.id,
    );
    if (index < 0)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that card. It may have been closed or replaced.',
      );

    const card = CARDS[index];
    if (card === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that card. It may have been closed or replaced.',
      );

    if (card.status === 'BLOCKED') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'This card is already blocked. To unblock it, call the bank or visit a branch.',
      );
    }

    const updated = { ...card, status: 'BLOCKED' as const };
    CARDS[index] = updated;
    return HttpResponse.json(updated);
  }),

  http.get(`${API}/loans`, () => {
    const persona = activeCustomer();
    return persona === null ? unauthenticated() : HttpResponse.json(loansFor(persona.user.id));
  }),

  http.get(`${API}/notifications`, () => {
    const persona = activeCustomer();
    return persona === null
      ? unauthenticated()
      : HttpResponse.json(notificationsFor(persona.user.id).map(publicNotification));
  }),

  http.post(`${API}/notifications/:id/read`, ({ params }) => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    const index = NOTIFICATIONS.findIndex(
      (item) => item.id === String(params['id']) && item.ownerId === persona.user.id,
    );
    if (index < 0)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that message. It may already have been deleted.',
      );

    const current = NOTIFICATIONS[index];
    if (current === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that message. It may already have been deleted.',
      );

    const updated = { ...current, read: true };
    NOTIFICATIONS[index] = updated;
    return HttpResponse.json(publicNotification(updated));
  }),

  http.post(`${API}/notifications/read-all`, () => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    NOTIFICATIONS.forEach((item, index) => {
      if (item.ownerId === persona.user.id) NOTIFICATIONS[index] = { ...item, read: true };
    });

    return HttpResponse.json(notificationsFor(persona.user.id).map(publicNotification));
  }),

  /* -------------------------------- cards and cheque books the customer asked for */

  /**
   * THE REAL SERVICE IS NOW THE AUTHORITY HERE (migration V18), and these two handlers are
   * the development stand-in for it. They were rewritten rather than left alone because the
   * old pair lied in two ways that mattered.
   *
   * <p>`POST /cards/requests` minted a reference from `Date.now()` and pushed a row onto an
   * in-memory array that starts empty on every page load, so the reference the customer was
   * shown stopped existing when they refreshed. And it accepted a `branch` the customer had
   * chosen from six names the front end had invented, which the confirmation screen then
   * told them to carry their ID to.
   *
   * <p>So: one endpoint for both kinds, no branch accepted, and NO COLLECTION POINT until a
   * member of staff types one. A mock that let the customer pick a branch would let the UI
   * be built as though the portal knew the bank's branches.
   */
  http.get(`${API}/service-requests`, () => {
    const persona = activeCustomer();
    return persona === null
      ? unauthenticated()
      : HttpResponse.json(serviceRequestsFor(persona.user.id).map(publicServiceRequest));
  }),

  http.post(`${API}/service-requests`, async ({ request }) => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    const payload = (await request.json()) as Record<string, unknown>;
    const read = (field: string): string =>
      typeof payload[field] === 'string' ? payload[field] : '';

    const kind = read('requestType').toUpperCase();
    if (kind !== 'CARD' && kind !== 'CHEQUE_BOOK') {
      return mockApiError(422, 'BUSINESS_RULE_VIOLATION', 'Choose a card or a cheque book.');
    }

    const account = PROVISIONED_ACCOUNTS.find((row) => row.id === read('accountId'));
    if (account === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that account on your profile.');
    }

    /* Current accounts only, matching the server rather than only the dropdown. */
    if (kind === 'CHEQUE_BOOK' && account.accountType !== 'CURRENT') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Cheque books are only issued on a current account. Choose a current account, or' +
          ' speak to the bank about opening one.',
      );
    }

    const leaves = typeof payload['leaves'] === 'number' ? payload['leaves'] : 0;
    if (kind === 'CHEQUE_BOOK' && ![25, 50, 100].includes(leaves)) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Cheque books come with 25, 50 or 100 leaves.',
      );
    }

    /* One open request per kind per account, and the refusal names the existing one. */
    const open = SERVICE_REQUESTS.find(
      (row) =>
        row.ownerId === persona.user.id &&
        row.requestType === kind &&
        row.accountId === account.id &&
        (row.status === 'SUBMITTED' || row.status === 'READY'),
    );
    if (open !== undefined) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        `You have already asked for a ${kind === 'CARD' ? 'card' : 'cheque book'} on that` +
          ` account. The reference is ${open.reference} and it is still with us.`,
      );
    }

    const cardType = read('cardType').toUpperCase() === 'PREPAID' ? 'Prepaid' : 'Debit';
    const created: MockServiceRequest = {
      id: crypto.randomUUID(),
      reference: mockRequestReference(kind),
      requestType: kind,
      details:
        kind === 'CARD'
          ? `${cardType} card for ${account.maskedNumber}`
          : `Cheque book, ${String(leaves)} leaves, for ${account.maskedNumber}`,
      status: 'SUBMITTED',
      submittedAt: new Date().toISOString(),
      ownerId: persona.user.id,
      accountId: account.id,
    };

    SERVICE_REQUESTS.unshift(created);
    return HttpResponse.json(publicServiceRequest(created), { status: 201 });
  }),

  /* ------------------------------------------------ corporate */

  http.get(`${API}/approvals`, () => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();
    if (!persona.user.permissions.includes('APPROVAL_VIEW')) {
      return mockApiError(403, 'FORBIDDEN', 'Your access does not include the approvals queue.');
    }

    // Same salary rule as /bulk below: the amount is withheld, not zeroed.
    return HttpResponse.json(approvalsFor(getActiveCorporateId()).map(withheldIfSalary(persona)));
  }),

  http.post(`${API}/approvals/:id/approve`, ({ params }) => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();

    if (!persona.user.permissions.includes('APPROVAL_APPROVE')) {
      return mockApiError(
        403,
        'FORBIDDEN',
        'Your access does not include approving payments. Someone with approver access needs to do this.',
      );
    }

    const item = approvalsFor(getActiveCorporateId()).find((a) => a.id === String(params['id']));
    if (item === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that item. Someone else may have approved or rejected it already.',
      );

    // The rule that matters: nobody approves their own submission.
    if (item.makerId === persona.user.id) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'You cannot approve a transaction you submitted yourself.',
      );
    }

    return HttpResponse.json({
      ...item,
      approvalsCollected: item.approvalsCollected + 1,
      status:
        item.approvalsCollected + 1 >= item.approvalsRequired ? 'APPROVED' : 'PENDING_APPROVAL',
    });
  }),

  http.post(`${API}/approvals/:id/reject`, ({ params }) => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();
    if (!persona.user.permissions.includes('APPROVAL_REJECT')) {
      return mockApiError(
        403,
        'FORBIDDEN',
        'Your access does not include rejecting payments. Someone with approver access needs to do this.',
      );
    }

    const item = approvalsFor(getActiveCorporateId()).find((a) => a.id === String(params['id']));
    if (item === undefined)
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that item. Someone else may have approved or rejected it already.',
      );

    return HttpResponse.json({ ...item, status: 'REJECTED' });
  }),

  http.get(`${API}/bulk`, () => {
    const persona = activeCustomer();
    if (persona === null) return unauthenticated();
    if (!persona.user.permissions.includes('BULK_VIEW')) {
      return mockApiError(403, 'FORBIDDEN', 'Your access does not include bulk payments.');
    }

    const canSeeSalaryDetail = persona.user.permissions.includes('SALARY_VIEW_DETAILS');

    /*
     * Salary privacy, enforced at the API rather than hidden in the UI.
     *
     * Without SALARY_VIEW_DETAILS the counts and status still come through — they are
     * needed to do the job — but the amount is OMITTED, because a payroll total plus a
     * headcount is close enough to individual pay.
     *
     * Omitted rather than zeroed. Sending `0` would put a false figure in front of
     * someone about to release a payroll, and no client-side flag reliably undoes that.
     */
    const batches = batchesFor(getActiveCorporateId()).map((batch) => {
      if (!batch.containsSalaryDetail || canSeeSalaryDetail) return batch;
      const { totalAmount: _withheld, ...rest } = batch;
      return { ...rest, amountWithheld: true };
    });

    return HttpResponse.json(batches);
  }),
];
