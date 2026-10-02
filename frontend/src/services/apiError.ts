import type { ApiErrorCode, ApiErrorEnvelope, ApiFieldViolation } from '@/types/api';

/**
 * How the UI should react to a failure.
 *
 * This is the single vocabulary the whole app switches on, so a component never inspects a
 * raw status code. It maps the blueprint's standard error experience (section 26) onto
 * discrete, exhaustively-checkable cases.
 */
export type ApiErrorKind =
  /** 400/422 with field detail — show inline messages against the fields. */
  | 'validation'
  /** 401 — renew the session if possible, otherwise return to login. */
  | 'unauthenticated'
  /** 403 — access denied. */
  | 'forbidden'
  /** 404 — resource absent or not entitled. */
  | 'notFound'
  /** 409 — state conflict. Explain; do NOT blindly resubmit. */
  | 'conflict'
  /** 422 — a business rule refused the request. Show the server's safe message. */
  | 'businessRule'
  /** 429 — respect `retryAfterSeconds`. */
  | 'rateLimited'
  /** 5xx — preserve the draft, offer retry only when the operation is safe to repeat. */
  | 'server'
  /** Request never completed — offline, DNS, CORS, connection reset. */
  | 'network'
  /** Request exceeded the timeout on a NON-financial call. */
  | 'timeout'
  /**
   * The outcome is genuinely unknown: a financial submission timed out or the connection
   * dropped mid-flight. The money may or may not have moved.
   *
   * The blueprint is explicit (sections 1, 19, 26): never infer success from a timeout, and
   * never present this as a failure. Show PENDING CONFIRMATION and query the transaction
   * status with the same idempotency key.
   */
  | 'pendingConfirmation'
  /** Anything unrecognised. */
  | 'unknown';

interface ApiErrorInit {
  readonly kind: ApiErrorKind;
  readonly message: string;
  readonly status?: number;
  readonly code?: ApiErrorCode;
  readonly correlationId?: string;
  readonly fieldErrors?: readonly ApiFieldViolation[];
  readonly retryAfterSeconds?: number;
  readonly path?: string;
  readonly cause?: unknown;
}

/**
 * The only error type the service layer throws.
 *
 * Carries a safe, displayable `message` plus the `correlationId` so the UI can show the
 * customer a reference that support can trace. It deliberately exposes no stack, no
 * upstream payload and no request body.
 */
export class ApiError extends Error {
  override readonly name = 'ApiError';

  readonly kind: ApiErrorKind;
  readonly status: number | undefined;
  readonly code: ApiErrorCode | undefined;
  readonly correlationId: string | undefined;
  readonly fieldErrors: readonly ApiFieldViolation[];
  readonly retryAfterSeconds: number | undefined;
  readonly path: string | undefined;

  constructor(init: ApiErrorInit) {
    super(init.message, init.cause === undefined ? undefined : { cause: init.cause });
    this.kind = init.kind;
    this.status = init.status;
    this.code = init.code;
    this.correlationId = init.correlationId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.retryAfterSeconds = init.retryAfterSeconds;
    this.path = init.path;
  }

  /**
   * Whether repeating this exact request is safe.
   *
   * Conservative on purpose. A conflict or an unknown financial outcome is never safe to
   * repeat, because the first attempt may already have moved money.
   */
  get isSafeToRetry(): boolean {
    switch (this.kind) {
      case 'network':
      case 'timeout':
      case 'server':
      case 'rateLimited':
        return true;
      case 'pendingConfirmation':
      case 'conflict':
      case 'validation':
      case 'businessRule':
      case 'unauthenticated':
      case 'forbidden':
      case 'notFound':
      case 'unknown':
        return false;
    }
  }

  /** True when the transaction's fate is unknown and must be polled, not retried. */
  get requiresStatusCheck(): boolean {
    return this.kind === 'pendingConfirmation';
  }

  /** Field messages keyed by field name, for binding into a form. */
  get fieldErrorMap(): Readonly<Record<string, string>> {
    const map: Record<string, string> = {};
    for (const violation of this.fieldErrors) {
      map[violation.field] ??= violation.message;
    }
    return map;
  }
}

/** Maps a server error code to the UI's reaction vocabulary. */
function kindFromEnvelope(envelope: ApiErrorEnvelope): ApiErrorKind {
  switch (envelope.code) {
    case 'VALIDATION_FAILED':
      return 'validation';
    case 'BAD_REQUEST':
      return 'validation';
    case 'UNAUTHENTICATED':
      return 'unauthenticated';
    case 'FORBIDDEN':
      return 'forbidden';
    case 'NOT_FOUND':
      return 'notFound';
    case 'CONFLICT':
      return 'conflict';
    case 'BUSINESS_RULE_VIOLATION':
      return 'businessRule';
    case 'RATE_LIMITED':
      return 'rateLimited';
    case 'INTERNAL_ERROR':
    case 'UPSTREAM_UNAVAILABLE':
      return 'server';
    default:
      return kindFromStatus(envelope.status);
  }
}

/** Fallback when the body is not our envelope (a gateway page, say). */
export function kindFromStatus(status: number): ApiErrorKind {
  if (status === 401) return 'unauthenticated';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'notFound';
  if (status === 409) return 'conflict';
  if (status === 422) return 'businessRule';
  if (status === 429) return 'rateLimited';
  if (status === 400) return 'validation';
  if (status >= 500) return 'server';
  return 'unknown';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/** Narrows an unknown parsed body to our error envelope. */
export function isApiErrorEnvelope(value: unknown): value is ApiErrorEnvelope {
  return (
    isRecord(value) &&
    typeof value.status === 'number' &&
    typeof value.code === 'string' &&
    typeof value.message === 'string'
  );
}

const GENERIC_MESSAGE = 'We could not complete your request. Please try again in a moment.';

/**
 * Builds an {@link ApiError} from a server response.
 *
 * For 5xx the server's message is discarded in favour of a generic one: a proxy or
 * container error page can carry internal hostnames and stack traces, and none of that
 * belongs in front of a customer.
 */
export function apiErrorFromResponse(
  status: number,
  body: unknown,
  correlationId: string | undefined,
  retryAfterSeconds: number | undefined,
): ApiError {
  if (isApiErrorEnvelope(body)) {
    const kind = kindFromEnvelope(body);
    const resolvedCorrelationId = body.correlationId ?? correlationId;

    return new ApiError({
      kind,
      message: kind === 'server' ? GENERIC_MESSAGE : body.message,
      status: body.status,
      code: body.code,
      ...(resolvedCorrelationId === undefined ? {} : { correlationId: resolvedCorrelationId }),
      fieldErrors: body.fieldErrors ?? [],
      ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }),
      ...(body.path === undefined ? {} : { path: body.path }),
    });
  }

  const kind = kindFromStatus(status);
  return new ApiError({
    kind,
    message: kind === 'server' ? GENERIC_MESSAGE : GENERIC_MESSAGE,
    status,
    ...(correlationId === undefined ? {} : { correlationId }),
    ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }),
  });
}
