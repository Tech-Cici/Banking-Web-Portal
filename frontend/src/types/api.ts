/**
 * Shared API types.
 *
 * PROVISIONAL. The blueprint (sections 25 and 29) states that the route list is *proposed*
 * and that the real OpenAPI document is still owed by the backend team. These types mirror
 * what this repo's Spring Boot service returns today. When the OpenAPI spec lands, generate
 * from it and delete the hand-written duplicates here rather than maintaining both.
 */

/** Mirrors `rw.bank.ibanking.common.api.ApiErrorCode` on the server. */
export type ApiErrorCode =
  | 'VALIDATION_FAILED'
  | 'BAD_REQUEST'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'BUSINESS_RULE_VIOLATION'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR'
  | 'UPSTREAM_UNAVAILABLE';

/** One field-level validation failure, as returned by the server. */
export interface ApiFieldViolation {
  readonly field: string;
  readonly code: string;
  readonly message: string;
}

/** The single error envelope every non-2xx server response uses. */
export interface ApiErrorEnvelope {
  readonly timestamp: string;
  readonly status: number;
  readonly code: ApiErrorCode;
  readonly message: string;
  readonly path?: string;
  readonly correlationId?: string;
  readonly fieldErrors?: readonly ApiFieldViolation[];
}

/** Response of `GET /api/v1/health`. */
export interface HealthResponse {
  readonly status: 'UP';
  readonly service: string;
  readonly time: string;
}

/**
 * Terminal and non-terminal states of a money-moving operation.
 *
 * PENDING_CONFIRMATION is the state the blueprint insists on (sections 1 and 19): a request
 * whose outcome is unknown, typically after a timeout. It is emphatically NOT a failure,
 * and the UI must not invite an immediate retry.
 */
export type TransactionStatus =
  'COMPLETED' | 'PROCESSING' | 'PENDING_CONFIRMATION' | 'PENDING_APPROVAL' | 'FAILED' | 'REJECTED';

/** States of a request the bank processes rather than posts immediately. */
/**
 * REMOVED, and the removal is the point.
 *
 * This union listed DRAFT, SUBMITTED, UNDER_REVIEW, ADDITIONAL_INFO_REQUIRED, APPROVED,
 * REJECTED, PROCESSING and COMPLETED — eight states written speculatively before anything
 * implemented a service request, and nothing ever produced any of them. A type that
 * describes states the system cannot reach is worse than no type: every screen reading it
 * has to handle cases that will never arrive, and a reviewer cannot tell which are real.
 *
 * The real states live in `ServiceRequestState` in banking.ts, and there are four of
 * them: SUBMITTED, READY, COLLECTED, DECLINED. They match the server's enum and V18's
 * CHECK constraint.
 */

/**
 * A monetary value as it crosses the wire.
 *
 * The amount is a decimal STRING, never a number: `0.1 + 0.2 !== 0.3` in IEEE-754, and a
 * balance is not something to be approximately right about. See `utils/money.ts`.
 */
export interface MoneyDto {
  readonly amount: string;
  readonly currency: string;
}

/**
 * Permission identifiers, supplied by the backend per session.
 *
 * ARCHITECTURE ONLY. The blueprint (section 29) lists the permission matrix as still owed
 * by business/security. These are the illustrative names from the brief; the front end
 * consumes whatever the backend sends and never hard-codes entitlement decisions.
 */
export type Permission =
  | 'RETAIL_ACCOUNT_VIEW'
  | 'TRANSFER_CREATE'
  | 'PAYMENT_CREATE'
  | 'BENEFICIARY_MANAGE'
  | 'LOAN_VIEW'
  | 'CARD_MANAGE'
  | 'CORPORATE_VIEW'
  | 'CORPORATE_TRANSFER_CREATE'
  | 'CORPORATE_PAYMENT_CREATE'
  | 'APPROVAL_VIEW'
  | 'APPROVAL_APPROVE'
  | 'APPROVAL_REJECT'
  | 'BULK_CREATE'
  | 'BULK_VIEW'
  | 'BULK_APPROVE'
  | 'SALARY_VIEW_DETAILS';

/** Cursor/page envelope for list endpoints. Provisional until the OpenAPI spec lands. */
export interface Page<T> {
  readonly content: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}
