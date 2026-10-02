import { appConfig } from '@/config/env';
import { apiErrorFromResponse, ApiError } from './apiError';
import {
  CORRELATION_HEADER,
  isDisplayableReference,
  newCorrelationId,
  readCorrelationId,
} from './correlation';
import { CSRF_HEADER, readCsrfToken } from './csrf';
import { IDEMPOTENCY_HEADER } from './idempotency';

/**
 * The single place this application performs HTTP.
 *
 * Nothing else in the app may call `fetch`. Centralising it is what makes correlation ids,
 * timeouts, idempotency, the error envelope and the pending-confirmation rule uniform
 * instead of per-component guesswork.
 *
 * Feature code does not use this module directly either — it calls a service
 * (`accountService`, `transferService`, ...) which wraps it. That keeps URLs and payload
 * shapes in one layer, so an OpenAPI change touches services only.
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export type QueryValue = string | number | boolean | undefined | null;

export interface ApiRequest {
  readonly method?: HttpMethod;
  /** Path relative to the API base URL, e.g. `/accounts/123`. Must begin with `/`. */
  readonly path: string;
  readonly query?: Readonly<Record<string, QueryValue>>;
  readonly body?: unknown;
  /** Caller-owned cancellation, combined with the internal timeout. */
  readonly signal?: AbortSignal;
  /** Overrides the configured default. */
  readonly timeoutMs?: number;
  /**
   * Idempotency key. REQUIRED for every money-moving submission, and the same key must be
   * reused when checking that submission's status.
   */
  readonly idempotencyKey?: string;
  /**
   * Marks this request as moving money.
   *
   * Changes failure semantics: a timeout or dropped connection becomes
   * `pendingConfirmation` rather than `timeout`/`network`, because the request may have
   * been executed server-side. Never set this on a read.
   */
  readonly financial?: boolean;
}

/** Notified whenever the API reports the session is no longer valid. */
type UnauthenticatedListener = (error: ApiError) => void;

const unauthenticatedListeners = new Set<UnauthenticatedListener>();

/**
 * Paths reachable without a session.
 *
 * A 401 from one of these means "these credentials are wrong" or "this endpoint is not
 * implemented" — never "your session ended", because there was none to end.
 */
/**
 * Tells the DEVELOPER that the request went to a backend that has no such endpoint.
 *
 * There was a version of this on the sign-in page only, rendered on screen. It was
 * removed for being a developer note on a customer surface — and the same day, a 401
 * on the REGISTRATION form sent someone hunting, because nothing said "you are pointed
 * at the real API and it does not implement this yet".
 *
 * So it lives here, where every endpoint passes through, and goes to the console, where
 * the only person who can act on it is looking. `import.meta.env.DEV` is a build-time
 * literal, so none of this reaches production.
 */
function warnIfBackendIsNotImplemented(path: string, error: ApiError): void {
  if (!import.meta.env.DEV) return;
  if (appConfig.enableMockApi) return;
  if (error.kind !== 'unauthenticated' && error.kind !== 'notFound') return;

  // eslint-disable-next-line no-console
  console.warn(
    `[api] ${path} returned ${String(error.status ?? '')} and mocks are OFF. The Spring Boot ` +
      'service implements /health and denies everything else by design, so this is most ' +
      'likely a missing endpoint rather than a fault in this screen. Run `npm run dev` ' +
      'instead of `npm run dev:live` to use the mock API.',
  );
}

const PUBLIC_PATH_PREFIXES = ['/auth/', '/registration/', '/health'] as const;

function isPublicPath(path: string): boolean {
  return PUBLIC_PATH_PREFIXES.some((prefix) => path.startsWith(prefix));
}

/**
 * Registers a 401 handler.
 *
 * Used by the session layer to redirect to login and clear sensitive drafts. Implemented as
 * a subscription so this module stays free of React and router imports — those would make
 * it unusable from tests and create an import cycle.
 */
export function onUnauthenticated(listener: UnauthenticatedListener): () => void {
  unauthenticatedListeners.add(listener);
  return () => unauthenticatedListeners.delete(listener);
}

function buildUrl(path: string, query: ApiRequest['query']): string {
  if (!path.startsWith('/')) {
    throw new Error(`API path must start with "/", got: ${path}`);
  }

  const url = new URL(`${appConfig.apiBaseUrl}${path}`);

  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value));
      }
    }
  }

  return url.toString();
}

function parseRetryAfter(headers: Headers): number | undefined {
  const raw = headers.get('Retry-After');
  if (raw === null) return undefined;

  const seconds = Number(raw);
  if (Number.isFinite(seconds) && seconds >= 0) return seconds;

  // The header may also be an HTTP date.
  const asDate = Date.parse(raw);
  if (Number.isNaN(asDate)) return undefined;

  return Math.max(0, Math.round((asDate - Date.now()) / 1000));
}

/** Reads the body without letting a malformed payload mask the real status code. */
async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204 || response.status === 205) return undefined;

  const contentType = response.headers.get('Content-Type') ?? '';
  if (!contentType.includes('json')) {
    return undefined;
  }

  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

/**
 * Performs one API request.
 *
 * Returns the parsed body on 2xx and throws {@link ApiError} otherwise. There is no
 * automatic retry: whether repeating a request is safe depends on what it does, and only
 * the caller knows that.
 */
export async function apiRequest<TResponse>(request: ApiRequest): Promise<TResponse> {
  const {
    method = 'GET',
    path,
    query,
    body,
    signal,
    timeoutMs = appConfig.apiTimeoutMs,
    idempotencyKey,
    financial = false,
  } = request;

  if (financial && idempotencyKey === undefined) {
    // A programming error, caught loudly in development rather than producing duplicate
    // transactions in production.
    throw new Error(
      `Financial request to ${path} is missing an idempotency key. ` +
        'Every money-moving submission requires one (blueprint section 44).',
    );
  }

  const correlationId = newCorrelationId();

  const headers = new Headers({
    Accept: 'application/json',
    [CORRELATION_HEADER]: correlationId,
  });

  if (body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (idempotencyKey !== undefined) {
    headers.set(IDEMPOTENCY_HEADER, idempotencyKey);
  }

  /*
   * CSRF, for anything that changes state.
   *
   * Required because this client sends `credentials: 'include'` below. A cookie session
   * is attached by the browser to any request any page makes, so without a token that a
   * cross-site page cannot read, a link in an email could make a customer's browser issue
   * an authenticated transfer.
   *
   * The token is read from a cookie the server sets and echoed in a header. A GET is not
   * given one, both because it should not change anything and because the server does not
   * ask for one — sending it anyway would only widen what the header is used for. HEAD is
   * not checked because ApiRequest does not offer it; naming it here would look like a
   * guard while being dead code.
   */
  if (method !== 'GET') {
    const csrfToken = readCsrfToken();
    if (csrfToken !== undefined) {
      headers.set(CSRF_HEADER, csrfToken);
    }
    /*
     * No token is NOT treated as an error here. Sign-in and registration are exempt
     * server-side — there is no session to protect before one exists — so the first
     * requests the app ever makes legitimately have nothing to send. A request that did
     * need one fails with 403, which friendlyError already words for a customer.
     */
  }

  const timeoutSignal = AbortSignal.timeout(timeoutMs);
  const combinedSignal =
    signal === undefined ? timeoutSignal : AbortSignal.any([signal, timeoutSignal]);

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: combinedSignal,
      // Cookies are sent when the approved session architecture uses them; harmless for
      // bearer tokens. The server's CORS allow-list is what actually gates this.
      credentials: 'include',
      // Never let a balance or a transaction list come from the HTTP cache.
      cache: 'no-store',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
    });
  } catch (cause) {
    throw networkFailureToApiError(cause, {
      financial,
      correlationId,
      timedOut: timeoutSignal.aborted,
      cancelled: signal?.aborted ?? false,
    });
  }

  /*
   * The server's echoed reference is preferred, but only if it is still one of ours.
   *
   * A backend that substitutes its own trace id — a UUID, a span id, an internal ticket
   * number — would put a 36-character string back on the error screen and undo the whole
   * reason the short format exists. When that happens the request's own reference is used
   * instead: it went out in the header, so it is in the server's logs either way.
   */
  const echoed = readCorrelationId(response.headers);
  const serverCorrelationId =
    echoed !== undefined && isDisplayableReference(echoed) ? echoed : correlationId;

  if (!response.ok) {
    const errorBody = await readBody(response);
    const error = apiErrorFromResponse(
      response.status,
      errorBody,
      serverCorrelationId,
      parseRetryAfter(response.headers),
    );

    /*
     * Only endpoints that NEED a session may end one.
     *
     * This used to exclude `/auth/` alone, on the reasoning that a 401 there is a failed
     * sign-in rather than an expired session. True, and incomplete: `/registration/` is
     * equally public, so a 401 while someone filled in the registration form broadcast
     * "you have been signed out" to the whole app — at a visitor who had never signed in
     * and could not have been. An allow-list of one prefix was a guess that held until
     * the second public endpoint existed.
     *
     * Listing the public prefixes is the honest version. A path that requires a session
     * is the default, so a new protected endpoint needs no thought here; a new PUBLIC one
     * has to be added, and forgetting shows up immediately as a spurious sign-out.
     */
    if (error.kind === 'unauthenticated' && !isPublicPath(path)) {
      for (const listener of unauthenticatedListeners) listener(error);
    }

    warnIfBackendIsNotImplemented(path, error);

    throw error;
  }

  return (await readBody(response)) as TResponse;
}

interface FailureContext {
  readonly financial: boolean;
  readonly correlationId: string;
  readonly timedOut: boolean;
  readonly cancelled: boolean;
}

/**
 * Turns a thrown `fetch` failure into an {@link ApiError}.
 *
 * The important branch is the financial one. When a money-moving request does not return,
 * the client cannot know whether the server executed it, so the result is
 * `pendingConfirmation` and the UI must poll the transaction status with the same
 * idempotency key rather than resubmit.
 */
function networkFailureToApiError(cause: unknown, context: FailureContext): ApiError {
  const { financial, correlationId, timedOut, cancelled } = context;

  // A caller-initiated cancellation is not a failure to report.
  if (cancelled && !timedOut) {
    return new ApiError({
      kind: 'network',
      message: 'The request was cancelled.',
      correlationId,
      cause,
    });
  }

  if (financial) {
    return new ApiError({
      kind: 'pendingConfirmation',
      message:
        'We did not receive a confirmation for this transaction. It may still have been ' +
        'processed. Please check the transaction status before trying again.',
      correlationId,
      cause,
    });
  }

  if (timedOut) {
    return new ApiError({
      kind: 'timeout',
      message: 'The request took too long to complete. Please try again.',
      correlationId,
      cause,
    });
  }

  return new ApiError({
    kind: 'network',
    message: 'We could not reach the bank. Please check your connection and try again.',
    correlationId,
    cause,
  });
}

/** Convenience wrappers. Services should use these rather than `apiRequest` directly. */
export const apiClient = {
  get: <T>(path: string, options: Omit<ApiRequest, 'path' | 'method' | 'body'> = {}): Promise<T> =>
    apiRequest<T>({ ...options, path, method: 'GET' }),

  post: <T>(path: string, options: Omit<ApiRequest, 'path' | 'method'> = {}): Promise<T> =>
    apiRequest<T>({ ...options, path, method: 'POST' }),

  put: <T>(path: string, options: Omit<ApiRequest, 'path' | 'method'> = {}): Promise<T> =>
    apiRequest<T>({ ...options, path, method: 'PUT' }),

  patch: <T>(path: string, options: Omit<ApiRequest, 'path' | 'method'> = {}): Promise<T> =>
    apiRequest<T>({ ...options, path, method: 'PATCH' }),

  delete: <T>(
    path: string,
    options: Omit<ApiRequest, 'path' | 'method' | 'body'> = {},
  ): Promise<T> => apiRequest<T>({ ...options, path, method: 'DELETE' }),
} as const;
