import {
  normaliseApplication,
  normaliseCreatedCustomer,
  normaliseCustomer,
  type Application,
  type BeneficiaryReview,
  type CreateCustomerRequest,
  type CreatedCustomer,
  type Customer,
  type OutboxMessage,
  type PasswordRequest,
  type PendingTransfer,
  type ServiceRequestQueueItem,
  type StaffUser,
} from '@/types/admin';
import type { ChangeTemporaryPasswordRequest, StaffLoginResult } from '@/types/auth';
import { apiClient } from './apiClient';

/**
 * The bank's own portal.
 *
 * Separate service, separate endpoints, separate session. Staff are not customers, and
 * one shared client for both is how a support screen ends up callable with a customer's
 * session.
 *
 * NOTHING HERE EVER RETURNS A PASSWORD. `createAccount` used to, back when an
 * administrator read it out in person; the bank chose to email it on approval instead,
 * so the temporary password now exists only in that message and no endpoint hands one
 * back.
 */

function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

/**
 * WHAT IS WAITING FOR SOMEBODY AT THE BANK, across every queue.
 *
 * RENAMED FROM `StaffSummary`, which had stopped being true. It carried five
 * onboarding figures while the service had grown six queues, so the staff overview answered
 * "who is waiting for an account?" and said nothing about money waiting to be released,
 * passwords to re-issue, payees to check or cards to hand over. A manager signing in saw
 * almost entirely an administrator's work.
 *
 * Every figure is a database count, never the length of a page the queue screen fetched —
 * see `OnboardingService.StaffSummary`.
 */
export interface StaffSummary {
  readonly submitted: number;
  readonly awaitingApproval: number;
  readonly active: number;
  readonly rejected: number;
  /** Approved but the temporary password has not been replaced yet. */
  readonly awaitingFirstSignIn: number;

  /**
   * Money that has left an account and is waiting for a manager.
   *
   * THE ONE QUEUE WHERE A SLOW DAY COSTS THE CUSTOMER rather than inconveniencing them:
   * the money is debited and not yet delivered. The overview never sorts it below anything.
   */
  readonly transfersToRelease: number;
  readonly passwordRequests: number;
  readonly payeesToCheck: number;
  /** Submitted and ready-to-collect together — both are still somebody's job. */
  readonly cardsAndChequeBooks: number;
}

export const staffAuthService = {
  signIn: (identifier: string, password: string, signal?: AbortSignal): Promise<StaffLoginResult> =>
    apiClient.post<StaffLoginResult>('/auth/staff/login', {
      body: { identifier, password },
      ...withSignal(signal),
    }),

  /** Current staff session. Throws an `unauthenticated` ApiError when there is none. */
  current: (signal?: AbortSignal): Promise<StaffUser> =>
    apiClient.get<StaffUser>('/auth/staff/session', withSignal(signal)),

  signOut: async (signal?: AbortSignal): Promise<void> => {
    await apiClient.post<undefined>('/auth/logout', withSignal(signal));
  },
} as const;

export const adminService = {
  applications: (status?: string, signal?: AbortSignal): Promise<readonly Application[]> =>
    apiClient
      .get<readonly Application[]>('/admin/applications', {
        ...(status === undefined ? {} : { query: { status } }),
        ...withSignal(signal),
      })
      .then((list) => list.map(normaliseApplication)),

  /*
   * Every response carrying an array is normalised here rather than in the screens.
   *
   * `default-property-inclusion: non_null` omits null fields, and an older server omits
   * fields it has never heard of, so a type saying `readonly T[]` can arrive undefined.
   * Three separate white screens came from that — see `asArray` in types/admin.ts — and
   * each was patched at the crash site, which is precisely why there was a next one.
   */
  application: (id: string, signal?: AbortSignal): Promise<Application> =>
    apiClient
      .get<Application>(`/admin/applications/${encodeURIComponent(id)}`, withSignal(signal))
      .then(normaliseApplication),

  /**
   * Creates the customer's LOGIN, and records which accounts the branch wants opened.
   *
   * It still does not OPEN an account — nothing in this service can, because the real
   * accounts live in core banking. What it now does is keep the request: one row per
   * account, marked `AWAITING_CORE_BANKING`.
   *
   * That is the fix for a quiet defect. The endpoint used to be
   * `createAccount(@PathVariable UUID id, Authentication caller)` with no `@RequestBody`,
   * so the account type, currency and opening balance this screen had been posting were
   * bound by nothing and discarded while the administrator was shown a success message.
   *
   * Carries an idempotency key. Creating the same login twice would issue two temporary
   * passwords for one person, and the second would be the one the customer never
   * received.
   */
  createAccount: (
    applicationId: string,
    payload: CreateCustomerRequest,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<CreatedCustomer> =>
    apiClient
      .post<CreatedCustomer>(
        `/admin/applications/${encodeURIComponent(applicationId)}/create-account`,
        { body: payload, idempotencyKey, ...withSignal(signal) },
      )
      .then(normaliseCreatedCustomer),

  customers: (status?: string, signal?: AbortSignal): Promise<readonly Customer[]> =>
    apiClient
      .get<readonly Customer[]>('/admin/customers', {
        ...(status === undefined ? {} : { query: { status } }),
        ...withSignal(signal),
      })
      .then((list) => list.map(normaliseCustomer)),

  approve: (id: string, idempotencyKey: string, signal?: AbortSignal): Promise<Customer> =>
    apiClient
      .post<Customer>(`/admin/customers/${encodeURIComponent(id)}/approve`, {
        idempotencyKey,
        ...withSignal(signal),
      })
      .then(normaliseCustomer),

  reject: (
    id: string,
    reason: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<Customer> =>
    apiClient
      .post<Customer>(`/admin/customers/${encodeURIComponent(id)}/reject`, {
        body: { reason },
        idempotencyKey,
        ...withSignal(signal),
      })
      .then(normaliseCustomer),

  /**
   * Freeze an account. Manager only — the backend enforces that; this is not a matter of
   * which buttons a screen renders.
   *
   * The reason is required by the server and is kept whatever happens next, so it is
   * worth writing as if a colleague will read it in six months. It is never shown to the
   * customer.
   */
  freeze: (
    id: string,
    reason: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<Customer> =>
    apiClient
      .post<Customer>(`/admin/customers/${encodeURIComponent(id)}/freeze`, {
        body: { reason },
        idempotencyKey,
        ...withSignal(signal),
      })
      .then(normaliseCustomer),

  /**
   * Restore access. Also requires a reason — restoring access to an account somebody
   * judged compromised is exactly as accountable an act as removing it.
   */
  unfreeze: (
    id: string,
    reason: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<Customer> =>
    apiClient
      .post<Customer>(`/admin/customers/${encodeURIComponent(id)}/unfreeze`, {
        body: { reason },
        idempotencyKey,
        ...withSignal(signal),
      })
      .then(normaliseCustomer),

  /**
   * Takes an account off a customer's profile.
   *
   * The accounts are entered by the administrator when the login is created; this is the
   * correction path for the case that matters — a wrong number was typed and somebody is
   * seeing an account that is not theirs.
   *
   * Returns the whole customer, so the caller replaces the row rather than merging a
   * fragment into it — which is how a list goes stale on screen while being right on the
   * server.
   */
  removeAccount: (
    id: string,
    accountId: string,
    reason: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<Customer> =>
    apiClient
      .post<Customer>(
        `/admin/customers/${encodeURIComponent(id)}/accounts/${encodeURIComponent(accountId)}/remove`,
        { body: { reason }, idempotencyKey, ...withSignal(signal) },
      )
      .then(normaliseCustomer),

  /* ---------------------------------------------- money awaiting a manager */

  /**
   * Transfers customers have submitted and nobody has decided, oldest first.
   *
   * The money in these has already left the sender's balance and is sitting on hold, so
   * this queue is not a list of requests — it is a list of people waiting.
   */
  transferQueue: (signal?: AbortSignal): Promise<readonly PendingTransfer[]> =>
    apiClient.get<readonly PendingTransfer[]>('/admin/transfers', withSignal(signal)),

  /**
   * Releases a transfer. The beneficiary is credited before this resolves.
   *
   * NO IDEMPOTENCY KEY, and that is deliberate rather than an omission. The credit is
   * posted under a key the server derives from the transfer's own id, and a transfer
   * that has already been decided refuses a second decision outright — so the
   * protection against a double-tap crediting twice does not depend on this client
   * generating anything, or on it being the same client that tapped the first time.
   */
  approveTransfer: (id: string, signal?: AbortSignal): Promise<PendingTransfer> =>
    apiClient.post<PendingTransfer>(
      `/admin/transfers/${encodeURIComponent(id)}/approve`,
      withSignal(signal),
    ),

  /**
   * Refuses a transfer and returns the money to the sender.
   *
   * The reason is required by the server and is the only thing the customer is told,
   * which is why the screen asks for it in words written to the customer.
   */
  rejectTransfer: (id: string, reason: string, signal?: AbortSignal): Promise<PendingTransfer> =>
    apiClient.post<PendingTransfer>(`/admin/transfers/${encodeURIComponent(id)}/reject`, {
      body: { reason },
      ...withSignal(signal),
    }),

  /* ------------------------------------------- forgotten passwords */

  /** Customers waiting for a new password, oldest first. Manager only. */
  passwordRequests: (signal?: AbortSignal): Promise<readonly PasswordRequest[]> =>
    apiClient.get<readonly PasswordRequest[]>('/admin/password-requests', withSignal(signal)),

  /**
   * Issues a new temporary password and emails it.
   *
   * NOTHING COMES BACK BUT THE REQUEST. The credential goes to the customer's inbox and
   * nowhere else — there is deliberately no response field a staff screen could display,
   * because a password on a screen is a password in a screenshot.
   */
  issuePassword: (id: string, signal?: AbortSignal): Promise<PasswordRequest> =>
    apiClient.post<PasswordRequest>(
      `/admin/password-requests/${encodeURIComponent(id)}/issue`,
      withSignal(signal),
    ),

  /** Refuses the request. The reason is emailed to the customer, so it is required. */
  refusePassword: (id: string, reason: string, signal?: AbortSignal): Promise<PasswordRequest> =>
    apiClient.post<PasswordRequest>(`/admin/password-requests/${encodeURIComponent(id)}/refuse`, {
      body: { reason },
      ...withSignal(signal),
    }),

  /**
   * Payees waiting for a member of staff, oldest first.
   *
   * EITHER STAFF ROLE may call this and the two below, unlike the password queue. The
   * four-eyes rule that keeps ADMIN and MANAGER apart is about creating an account and
   * releasing it; here the maker is the CUSTOMER and staff are the checker, so the
   * separation holds whoever clears it.
   */
  beneficiaryReviews: (signal?: AbortSignal): Promise<readonly BeneficiaryReview[]> =>
    apiClient.get<readonly BeneficiaryReview[]>('/admin/beneficiaries', withSignal(signal)),

  /** Clears a payee for payment. This is the call that emails the customer. */
  approveBeneficiary: (id: string, signal?: AbortSignal): Promise<BeneficiaryReview> =>
    apiClient.post<BeneficiaryReview>(
      `/admin/beneficiaries/${encodeURIComponent(id)}/approve`,
      withSignal(signal),
    ),

  /** Declines a payee. The reason is shown and emailed to the customer, so it is required. */
  refuseBeneficiary: (
    id: string,
    reason: string,
    signal?: AbortSignal,
  ): Promise<BeneficiaryReview> =>
    apiClient.post<BeneficiaryReview>(`/admin/beneficiaries/${encodeURIComponent(id)}/refuse`, {
      body: { reason },
      ...withSignal(signal),
    }),

  /**
   * Cards and cheque books still waiting for somebody at the bank, oldest first.
   *
   * EITHER STAFF ROLE, like the payee queue above and for the same reason — plus one of
   * its own: printing a card is volume work and should not queue behind the single role
   * that releases money.
   */
  serviceRequests: (signal?: AbortSignal): Promise<readonly ServiceRequestQueueItem[]> =>
    apiClient.get<readonly ServiceRequestQueueItem[]>(
      '/admin/service-requests',
      withSignal(signal),
    ),

  /**
   * Marks a request produced and emails the customer where to collect it.
   *
   * THE COLLECTION POINT IS REQUIRED and is free text, not a choice from a list. The
   * portal has no branch list — the six names the request form used to offer were invented
   * by the front end — so the one party who knows where the thing physically is types it.
   */
  markServiceRequestReady: (
    id: string,
    collectionPoint: string,
    signal?: AbortSignal,
  ): Promise<ServiceRequestQueueItem> =>
    apiClient.post<ServiceRequestQueueItem>(
      `/admin/service-requests/${encodeURIComponent(id)}/ready`,
      { body: { collectionPoint }, ...withSignal(signal) },
    ),

  /** Records that it was handed over. No email: the customer is at the counter. */
  markServiceRequestCollected: (
    id: string,
    signal?: AbortSignal,
  ): Promise<ServiceRequestQueueItem> =>
    apiClient.post<ServiceRequestQueueItem>(
      `/admin/service-requests/${encodeURIComponent(id)}/collected`,
      withSignal(signal),
    ),

  /** Declines it. The reason is shown and emailed to the customer, so it is required. */
  declineServiceRequest: (
    id: string,
    reason: string,
    signal?: AbortSignal,
  ): Promise<ServiceRequestQueueItem> =>
    apiClient.post<ServiceRequestQueueItem>(
      `/admin/service-requests/${encodeURIComponent(id)}/decline`,
      { body: { reason }, ...withSignal(signal) },
    ),

  summary: (signal?: AbortSignal): Promise<StaffSummary> =>
    apiClient.get<StaffSummary>('/admin/summary', withSignal(signal)),

  /** Development only: what the bank would have emailed. */
  outbox: (signal?: AbortSignal): Promise<readonly OutboxMessage[]> =>
    apiClient.get<readonly OutboxMessage[]>('/admin/outbox', withSignal(signal)),

  /*
   * THERE IS NO RESET CALL IN THIS CLIENT, AND THERE SHOULD NOT BE ONE.
   *
   * It lived here once as an ordinary method, and as a property of this object it survived
   * tree-shaking and shipped the `/admin/dev/reset` path string into the production bundle.
   * It was then moved to its own module behind a dynamic import so the bundler could drop
   * it, which fixed the leak and left a delete-every-customer button inside the bank's own
   * portal.
   *
   * It is now `npm run reset-bank` — scripts/reset-bank.mjs — and no browser code can reach
   * that endpoint at all. The server still refuses it outside the dev, h2 and test
   * profiles; that is the control. This is about there being nothing to click.
   */
} as const;

/**
 * Replaces the temporary password an admin issued.
 *
 * Lives here rather than in `authService` because it is the last step of the onboarding
 * pipeline, not part of ordinary sign-in. Neither value is kept after the call.
 */
export const temporaryPasswordService = {
  change: async (payload: ChangeTemporaryPasswordRequest, signal?: AbortSignal): Promise<void> => {
    await apiClient.post<undefined>('/auth/password/change-temporary', {
      body: payload,
      ...withSignal(signal),
    });
  },
} as const;
