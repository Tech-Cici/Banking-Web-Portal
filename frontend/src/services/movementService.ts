import type {
  Biller,
  CashReceipt,
  CashRequest,
  FxQuote,
  InternalTransfer,
  InternalTransferRequest,
  MovementQuote,
  MovementReceipt,
  PaymentRequest,
  ResolvedBeneficiary,
  SignIn,
  StandingOrder,
  StandingOrderRequest,
  Statement,
  StatementRequest,
  TransferRequest,
  TrustedDevice,
} from '@/types/movement';
import type { MoneyDto } from '@/types/api';
import { apiClient } from './apiClient';

/**
 * Services for moving money and managing an account.
 *
 * Every money-moving call takes an idempotency key as an explicit argument rather than
 * minting one inside. The caller owns the key because the caller knows whether this is a
 * retry of the same transaction or a genuinely new one — a service that generates its
 * own key turns every retry into a second payment.
 *
 * Quote and submit are separate calls on purpose. The quote is the bank's figure for the
 * fee and the total; the submit references it. Nothing here computes an amount.
 */

function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

export const transferService = {
  quote: (payload: TransferRequest, signal?: AbortSignal): Promise<MovementQuote> =>
    apiClient.post<MovementQuote>('/transfers/quote', { body: payload, ...withSignal(signal) }),

  submit: (
    payload: TransferRequest & { readonly quoteId: string },
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<MovementReceipt> =>
    apiClient.post<MovementReceipt>('/transfers', {
      body: payload,
      idempotencyKey,
      ...withSignal(signal),
    }),
} as const;

/**
 * Cash in and out of an account the customer holds.
 *
 * <p>NO QUOTE STEP, unlike a transfer: there is no fee, no rate and no destination, so
 * there is nothing for the bank to quote. The amount the customer types is the amount
 * that moves.
 *
 * <p>THE IDEMPOTENCY KEY IS A REQUIRED ARGUMENT, not an optional one and not generated
 * here. The caller owns it because only the caller knows whether this is a retry of the
 * same deposit or a genuinely new one — a service that mints its own key turns every
 * retry into a second transaction, which is the exact failure the key exists to prevent.
 * The server refuses a request without one rather than inventing a fallback.
 */
export const cashService = {
  deposit: (
    accountId: string,
    payload: CashRequest,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<CashReceipt> =>
    apiClient.post<CashReceipt>(`/accounts/${encodeURIComponent(accountId)}/deposit`, {
      body: payload,
      idempotencyKey,
      ...withSignal(signal),
    }),

  withdraw: (
    accountId: string,
    payload: CashRequest,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<CashReceipt> =>
    apiClient.post<CashReceipt>(`/accounts/${encodeURIComponent(accountId)}/withdraw`, {
      body: payload,
      idempotencyKey,
      ...withSignal(signal),
    }),
} as const;

/**
 * Moving money inside Zigama, against the real service.
 *
 * <p>SEPARATE FROM {@link transferService}, which still speaks the four-rail mocked shape
 * (quote, fee, saved payee). This one is the API that exists: submit, watch, and check who
 * an account number belongs to.
 *
 * <p>The idempotency key is a required argument for the same reason cash movements take
 * one — only the caller knows whether this is a retry of the same instruction or a new
 * one, and a service minting its own key turns every retry into a second transfer sitting
 * in a manager's queue.
 */
export const internalTransferService = {
  /**
   * Who holds this account number.
   *
   * <p>Rate limited server-side to twenty an hour per customer. Over that it answers 429
   * with a Retry-After, which the client surfaces as the server worded it rather than as
   * a generic failure.
   */
  resolve: (accountNumber: string, signal?: AbortSignal): Promise<ResolvedBeneficiary> =>
    apiClient.post<ResolvedBeneficiary>('/transfers/resolve', {
      body: { accountNumber },
      ...withSignal(signal),
    }),

  submit: (
    payload: InternalTransferRequest,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<InternalTransfer> =>
    apiClient.post<InternalTransfer>('/transfers', {
      body: payload,
      idempotencyKey,
      ...withSignal(signal),
    }),

  /** Everything this customer's accounts have sent or received. */
  list: (signal?: AbortSignal): Promise<readonly InternalTransfer[]> =>
    apiClient.get<readonly InternalTransfer[]>('/transfers', withSignal(signal)),

  /**
   * What is currently held out of this customer's accounts.
   *
   * <p>The dashboard needs it to explain a balance that has already dropped: submitting
   * debits the sender straight away, so without this the money is simply gone from the
   * figure with nothing saying where.
   */
  pending: (signal?: AbortSignal): Promise<readonly InternalTransfer[]> =>
    apiClient.get<readonly InternalTransfer[]>('/transfers/pending', withSignal(signal)),
} as const;

export const paymentService = {
  billers: (category?: string, signal?: AbortSignal): Promise<readonly Biller[]> =>
    apiClient.get<readonly Biller[]>('/billers', {
      ...(category === undefined ? {} : { query: { category } }),
      ...withSignal(signal),
    }),

  quote: (payload: PaymentRequest, signal?: AbortSignal): Promise<MovementQuote> =>
    apiClient.post<MovementQuote>('/payments/quote', { body: payload, ...withSignal(signal) }),

  submit: (
    payload: PaymentRequest & { readonly quoteId: string },
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<MovementReceipt> =>
    apiClient.post<MovementReceipt>('/payments', {
      body: payload,
      idempotencyKey,
      ...withSignal(signal),
    }),
} as const;

export const fxService = {
  quote: (
    sellAmount: MoneyDto,
    buyCurrency: string,
    signal?: AbortSignal,
  ): Promise<FxQuote> =>
    apiClient.post<FxQuote>('/fx/quote', {
      body: { sellAmount, buyCurrency },
      ...withSignal(signal),
    }),

  byId: (quoteId: string, signal?: AbortSignal): Promise<FxQuote> =>
    apiClient.get<FxQuote>(`/fx/quote/${encodeURIComponent(quoteId)}`, withSignal(signal)),

  submit: (
    payload: { readonly quoteId: string; readonly sourceAccountId: string },
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<MovementReceipt> =>
    apiClient.post<MovementReceipt>('/fx', {
      body: payload,
      idempotencyKey,
      ...withSignal(signal),
    }),
} as const;

export const standingOrderService = {
  list: (signal?: AbortSignal): Promise<readonly StandingOrder[]> =>
    apiClient.get<readonly StandingOrder[]>('/standing-orders', withSignal(signal)),

  byId: (id: string, signal?: AbortSignal): Promise<StandingOrder> =>
    apiClient.get<StandingOrder>(`/standing-orders/${encodeURIComponent(id)}`, withSignal(signal)),

  create: (
    payload: StandingOrderRequest,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<StandingOrder> =>
    apiClient.post<StandingOrder>('/standing-orders', {
      body: payload,
      idempotencyKey,
      ...withSignal(signal),
    }),

  /** Pauses an active order, or resumes a paused one. */
  togglePause: (id: string, signal?: AbortSignal): Promise<StandingOrder> =>
    apiClient.post<StandingOrder>(
      `/standing-orders/${encodeURIComponent(id)}/pause`,
      withSignal(signal),
    ),

  cancel: async (id: string, signal?: AbortSignal): Promise<void> => {
    await apiClient.delete<undefined>(
      `/standing-orders/${encodeURIComponent(id)}`,
      withSignal(signal),
    );
  },
} as const;

export const statementService = {
  list: (signal?: AbortSignal): Promise<readonly Statement[]> =>
    apiClient.get<readonly Statement[]>('/statements', withSignal(signal)),

  request: (payload: StatementRequest, signal?: AbortSignal): Promise<Statement> =>
    apiClient.post<Statement>('/statements', { body: payload, ...withSignal(signal) }),
} as const;

/*
 * THERE IS NO `chequeBookService` ANY MORE. It had a `list` reading a `/cheque-books`
 * inventory and a `request` that took a `branch` the customer had chosen from a list the
 * front end invented. Both are gone: a cheque book a customer has asked for is a row in
 * `service_requests`, so `serviceRequestService` in bankingService.ts is the one client
 * for it, and the collection point is typed by whoever produces the book.
 *
 * Deleted rather than left unreferenced. A second, parallel, fictional cheque-book API
 * sitting in the services barrel is how the next screen gets wired back to the fiction.
 */

/**
 * What comes back from a password change.
 *
 * <p>IT CARRIES A COUNT BECAUSE SOMETHING ELSE JUST HAPPENED TO THE CUSTOMER. Changing a
 * password revokes every browser that could sign in without an emailed code — a customer
 * changing it because they fear somebody has it has not finished the job otherwise. They
 * did not ask for that, so the screen has to be able to say it; without this they meet an
 * unexplained code request on their next sign-in from a machine that had not needed one
 * for a month.
 */
export interface PasswordChanged {
  readonly browsersSignedOut: number;
}

export const securityService = {
  devices: (signal?: AbortSignal): Promise<readonly TrustedDevice[]> =>
    apiClient.get<readonly TrustedDevice[]>('/security/devices', withSignal(signal)),

  revokeDevice: async (id: string, signal?: AbortSignal): Promise<void> => {
    await apiClient.delete<undefined>(
      `/security/devices/${encodeURIComponent(id)}`,
      withSignal(signal),
    );
  },

  signIns: (signal?: AbortSignal): Promise<readonly SignIn[]> =>
    apiClient.get<readonly SignIn[]>('/security/events', withSignal(signal)),

  /**
   * Changes the password.
   *
   * Both values go in the body and neither is kept anywhere after the call. There is no
   * retry helper and no stored draft: a password sitting in component state after a
   * failure is a password waiting to be read out of a heap snapshot.
   */
  changePassword: (
    currentPassword: string,
    newPassword: string,
    signal?: AbortSignal,
  ): Promise<PasswordChanged> =>
    apiClient.post<PasswordChanged>('/security/password', {
      body: { currentPassword, newPassword },
      ...withSignal(signal),
    }),
} as const;
