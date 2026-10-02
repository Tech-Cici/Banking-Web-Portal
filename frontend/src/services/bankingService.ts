import type { MoneyDto, Page } from '@/types/api';
import type {
  Account,
  ApprovalItem,
  Beneficiary,
  BulkBatch,
  Card,
  Loan,
  Notification,
  ServiceRequest,
  Transaction,
} from '@/types/banking';
import { apiClient, type QueryValue } from './apiClient';

/**
 * Read services for the banking data the dashboard and list screens need.
 *
 * Split by resource, matching the service layout in the brief (section 39). Each is a
 * thin typed wrapper over the API client: no caching, no UI concerns, and no swallowing
 * of errors — an ApiError propagates to the caller, which decides what to show.
 */

export interface BalanceResponse {
  readonly availableBalance: Account['availableBalance'];
  readonly currentBalance: Account['currentBalance'];
  readonly asOf: string;
}

export interface TransactionQuery {
  readonly from?: string;
  readonly to?: string;
  readonly search?: string;
  readonly direction?: 'DEBIT' | 'CREDIT';
  readonly page?: number;
  readonly size?: number;
}

function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

export const accountService = {
  list: (signal?: AbortSignal): Promise<readonly Account[]> =>
    apiClient.get<readonly Account[]>('/accounts', withSignal(signal)),

  byId: (accountId: string, signal?: AbortSignal): Promise<Account> =>
    apiClient.get<Account>(`/accounts/${encodeURIComponent(accountId)}`, withSignal(signal)),

  /**
   * The customer's OWN full account number, for giving to somebody paying them.
   *
   * <p>ITS OWN REQUEST, deliberately. The number is absent from `list` and from `byId`,
   * so it only ever reaches the client when the customer asks to see it — it appears in
   * exactly one payload, on purpose, rather than sitting in every account response.
   *
   * <p>The server checks entitlement with the same rule that decides whether money may
   * move, so this can only return a number for an account the caller could already empty.
   */
  fullNumber: (accountId: string, signal?: AbortSignal): Promise<{ accountNumber: string }> =>
    apiClient.get<{ accountNumber: string }>(
      `/accounts/${encodeURIComponent(accountId)}/number`,
      withSignal(signal),
    ),

  balance: (accountId: string, signal?: AbortSignal): Promise<BalanceResponse> =>
    apiClient.get<BalanceResponse>(
      `/accounts/${encodeURIComponent(accountId)}/balance`,
      withSignal(signal),
    ),

  transactions: (
    accountId: string,
    query: TransactionQuery = {},
    signal?: AbortSignal,
  ): Promise<Page<Transaction>> =>
    apiClient.get<Page<Transaction>>(`/accounts/${encodeURIComponent(accountId)}/transactions`, {
      query: query as Readonly<Record<string, QueryValue>>,
      ...withSignal(signal),
    }),

  /** Newest activity across every account the user can see. */
  recentTransactions: (limit = 8, signal?: AbortSignal): Promise<readonly Transaction[]> =>
    apiClient.get<readonly Transaction[]>('/transactions/recent', {
      query: { limit },
      ...withSignal(signal),
    }),
} as const;

export interface NewBeneficiary {
  readonly name: string;
  readonly beneficiaryType: Beneficiary['beneficiaryType'];
  readonly provider: string;
  /** The full number the customer typed. Sent once; the API returns it masked. */
  readonly destination: string;
  readonly currency: string;
}

export const beneficiaryService = {
  list: (signal?: AbortSignal): Promise<readonly Beneficiary[]> =>
    apiClient.get<readonly Beneficiary[]>('/beneficiaries', withSignal(signal)),

  create: (payload: NewBeneficiary, signal?: AbortSignal): Promise<Beneficiary> =>
    apiClient.post<Beneficiary>('/beneficiaries', { body: payload, ...withSignal(signal) }),

  remove: async (id: string, signal?: AbortSignal): Promise<void> => {
    await apiClient.delete<undefined>(
      `/beneficiaries/${encodeURIComponent(id)}`,
      withSignal(signal),
    );
  },
} as const;

export const cardService = {
  list: (signal?: AbortSignal): Promise<readonly Card[]> =>
    apiClient.get<readonly Card[]>('/cards', withSignal(signal)),

  /**
   * Blocks a card.
   *
   * Carries an idempotency key even though blocking twice is harmless, because the
   * endpoint is a state change on a card and the server is entitled to treat a repeat
   * as a repeat rather than guessing.
   */
  block: (id: string, idempotencyKey: string, signal?: AbortSignal): Promise<Card> =>
    apiClient.post<Card>(`/cards/${encodeURIComponent(id)}/block`, {
      idempotencyKey,
      ...withSignal(signal),
    }),

  /*
   * `requestNew` HAS GONE from this service, and the removal is deliberate rather than a
   * move. It posted to `/cards/requests` — an endpoint only the browser's own mock ever
   * answered — and carried a `branch` the customer had chosen from six names the front end
   * had invented. Asking for a card and asking for a cheque book are one process at the
   * bank, so both now go through `serviceRequestService.raise` below and the screens call
   * that directly. One layer fewer, and no forward reference between two consts in this
   * file.
   */
} as const;

/** Indicative figures for a loan the customer is considering. Computed by the bank. */
export interface LoanSimulation {
  readonly principal: MoneyDto;
  readonly months: number;
  readonly product: string;
  readonly interestRatePercent: string;
  readonly monthlyInstallment: MoneyDto;
  readonly totalInterest: MoneyDto;
  readonly totalRepayable: MoneyDto;
}

export interface LoanApplication {
  readonly amount: MoneyDto;
  readonly months: number;
  readonly product: string;
  readonly disburseToAccountId: string;
  readonly purpose: string;
}

export const loanService = {
  list: (signal?: AbortSignal): Promise<readonly Loan[]> =>
    apiClient.get<readonly Loan[]>('/loans', withSignal(signal)),

  /**
   * Asks the bank what a loan would cost.
   *
   * A server call rather than client arithmetic, deliberately — see the note on the
   * simulation screen. The client displays the answer and computes none of it.
   */
  simulate: (
    payload: { readonly amount: MoneyDto; readonly months: number; readonly product: string },
    signal?: AbortSignal,
  ): Promise<LoanSimulation> =>
    apiClient.post<LoanSimulation>('/loans/simulate', { body: payload, ...withSignal(signal) }),

  apply: (payload: LoanApplication, signal?: AbortSignal): Promise<ServiceRequest> =>
    apiClient.post<ServiceRequest>('/loans/applications', {
      body: payload,
      ...withSignal(signal),
    }),
} as const;

export const notificationService = {
  list: (signal?: AbortSignal): Promise<readonly Notification[]> =>
    apiClient.get<readonly Notification[]>('/notifications', withSignal(signal)),

  markRead: (id: string, signal?: AbortSignal): Promise<Notification> =>
    apiClient.post<Notification>(
      `/notifications/${encodeURIComponent(id)}/read`,
      withSignal(signal),
    ),

  markAllRead: (signal?: AbortSignal): Promise<readonly Notification[]> =>
    apiClient.post<readonly Notification[]>('/notifications/read-all', withSignal(signal)),
} as const;

/** What the customer is asking for. One shape, discriminated by `requestType`. */
export interface NewServiceRequest {
  readonly requestType: 'CARD' | 'CHEQUE_BOOK';
  readonly accountId: string;
  /** CARD only. */
  readonly cardType?: string;
  /** CHEQUE_BOOK only. 25, 50 or 100 — the server refuses anything else. */
  readonly leaves?: number;
}

export const serviceRequestService = {
  list: (signal?: AbortSignal): Promise<readonly ServiceRequest[]> =>
    apiClient.get<readonly ServiceRequest[]>('/service-requests', withSignal(signal)),

  /**
   * Asks the bank for a card or a cheque book.
   *
   * NO IDEMPOTENCY KEY, deliberately, and it is worth saying why given the house rule that
   * every financial submission carries one. This moves no money. The server refuses a
   * second open request for the same kind on the same account and names the reference the
   * customer already has, which is a better answer to a double-tap than a replayed
   * response: somebody pressing twice usually could not find the first one.
   */
  raise: (payload: NewServiceRequest, signal?: AbortSignal): Promise<ServiceRequest> =>
    apiClient.post<ServiceRequest>('/service-requests', { body: payload, ...withSignal(signal) }),
} as const;

export const approvalService = {
  list: (signal?: AbortSignal): Promise<readonly ApprovalItem[]> =>
    apiClient.get<readonly ApprovalItem[]>('/approvals', withSignal(signal)),

  /**
   * Approve an item.
   *
   * Carries an idempotency key: approving twice because a button was double-clicked
   * would push an item past its required approval count.
   */
  approve: (id: string, idempotencyKey: string, signal?: AbortSignal): Promise<ApprovalItem> =>
    apiClient.post<ApprovalItem>(`/approvals/${encodeURIComponent(id)}/approve`, {
      idempotencyKey,
      ...withSignal(signal),
    }),

  reject: (
    id: string,
    reason: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<ApprovalItem> =>
    apiClient.post<ApprovalItem>(`/approvals/${encodeURIComponent(id)}/reject`, {
      body: { reason },
      idempotencyKey,
      ...withSignal(signal),
    }),
} as const;

export const bulkService = {
  list: (signal?: AbortSignal): Promise<readonly BulkBatch[]> =>
    apiClient.get<readonly BulkBatch[]>('/bulk', withSignal(signal)),
} as const;
