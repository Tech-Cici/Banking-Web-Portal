import { http, HttpResponse, type RequestHandler } from 'msw';
import type { ApiErrorEnvelope, HealthResponse } from '@/types/api';
import { authHandlers } from './authHandlers';
import { adminHandlers } from './adminHandlers';
import { bankingHandlers } from './bankingHandlers';
import { movementHandlers } from './movementHandlers';
import { registrationHandlers } from './registrationHandlers';
import { newCorrelationId } from '@/services/correlation';

/**
 * Mock API handlers.
 *
 * PHASE 1 SCOPE: the health endpoint only, plus the error-shape helpers below. The blueprint
 * (section 40 of the brief) calls for realistic fixtures across accounts, transactions,
 * beneficiaries, loans, cards, approvals and batches — those arrive with the phases that
 * consume them, so the fixtures match the real payloads instead of being guessed now and
 * rewritten later.
 *
 * The rule for this directory: mocks live here, never inside page components. A page that
 * hard-codes its own data cannot be switched to a real API without being rewritten.
 */

const API = '*/api/v1';

/** Builds the server's error envelope so mocked failures match production exactly. */
export function mockApiError(
  status: number,
  code: ApiErrorEnvelope['code'],
  message: string,
  extras: Partial<ApiErrorEnvelope> = {},
): HttpResponse<ApiErrorEnvelope> {
  const body: ApiErrorEnvelope = {
    timestamp: new Date().toISOString(),
    status,
    code,
    message,
    correlationId: newCorrelationId(),
    ...extras,
  };

  return HttpResponse.json(body, { status });
}

/**
 * A path that exists ONLY in the mock, used to prove the worker is actually intercepting.
 *
 * <p>WHY THIS IS NEEDED. MSW works by service worker, and a service worker can be bypassed
 * while remaining registered: Chrome bypasses it for a hard reload (Cmd+Shift+R), and
 * DevTools has a "Bypass for network" checkbox that does the same until it is unticked. The
 * page is then still "controlled" — `navigator.serviceWorker.controller` is not null — so
 * there is nothing to check except whether a mocked request actually comes back mocked.
 *
 * <p>What it looked like without this: every still-mocked area answered 404 from the real
 * service, and the customer-facing panels said "We could not load loans. The requested
 * endpoint does not exist." Three bank-flavoured errors on a dashboard, for a browser
 * setting. It changed with whatever page was open, and a normal reload cleared it, which
 * made it look intermittent rather than configured.
 *
 * <p>The name begins with a double underscore so it cannot collide with a real area, and
 * it matches no sensible `VITE_LIVE_API` prefix, so `handlersExcept` can never strip it.
 */
export const MOCK_PROBE_PATH = '/__mock-intercepting';

export const handlers = [
  /*
   * Answered by the mock and by nothing else. The real service has no such path, so a 404
   * — or a network or CORS failure — is the answer that means "not intercepting".
   */
  http.get(`${API}${MOCK_PROBE_PATH}`, () => HttpResponse.json({ intercepting: true })),

  http.get(`${API}/health`, () => {
    const body: HealthResponse = {
      status: 'UP',
      service: 'ibanking-api (mock)',
      time: new Date().toISOString(),
    };
    return HttpResponse.json(body);
  }),

  // Registration has no backend implementation yet; these stand in for the proposed
  // contract in types/registration.ts.
  ...registrationHandlers,

  // Sign-in for the seeded users.
  ...authHandlers,

  // Seeded banking data for dashboard development, scoped to the active persona.
  ...bankingHandlers,

  // Moving money, plus statements, cheque books and security.
  ...movementHandlers,

  // The bank's own portal: onboarding applications, account creation, approval.
  ...adminHandlers,
];

/**
 * Per-scenario overrides, applied in a test with `server.use(...)`.
 *
 * Every data page must handle loading, empty, success and error, and every financial
 * operation must handle the full state set including PENDING CONFIRMATION (blueprint
 * section 47). These make those states reachable on demand rather than only by accident.
 */
export const scenarioHandlers = {
  /** 500 from the health endpoint. */
  healthServerError: http.get(`${API}/health`, () =>
    mockApiError(
      500,
      'INTERNAL_ERROR',
      'This part of the bank is temporarily unavailable. Please try again in a few minutes.',
    ),
  ),

  /** 401, to exercise session expiry. */
  healthUnauthenticated: http.get(`${API}/health`, () =>
    mockApiError(
      401,
      'UNAUTHENTICATED',
      'You have been signed out. Please sign in again — your money is unaffected.',
    ),
  ),

  /** 429 with retry guidance. */
  healthRateLimited: http.get(`${API}/health`, () =>
    HttpResponse.json(
      {
        timestamp: new Date().toISOString(),
        status: 429,
        code: 'RATE_LIMITED',
        message: 'You have tried this too many times in a row. Please wait a moment and try again.',
        correlationId: newCorrelationId(),
      } satisfies ApiErrorEnvelope,
      { status: 429, headers: { 'Retry-After': '30' } },
    ),
  ),

  /**
   * A request that never resolves, so the client's timeout path runs.
   *
   * On a financial request this is what must produce PENDING CONFIRMATION rather than a
   * failure — the single most important error case in the application.
   */
  healthNeverResponds: http.get(`${API}/health`, async () => {
    await new Promise(() => {
      /* never settles */
    });
    return HttpResponse.json({});
  }),
} as const;

/**
 * Mocked endpoints the BACKEND DOES NOT IMPLEMENT.
 *
 * Sending one of these to the real service does not produce a 404. Spring Security
 * authorises before the dispatcher resolves a handler, so an unimplemented path falls to
 * the catch-all `hasRole("CUSTOMER")` and is DENIED — and with a staff session in the same
 * browser, the person sees "Your account does not have permission to do this". A
 * permission error, for a feature that does not exist, shown to someone whose permissions
 * were never the problem. That happened to company registration, and nothing caught it
 * because listing `registration` in VITE_LIVE_API is a perfectly ordinary thing to write.
 *
 * COMPANY REGISTRATION HAS LEFT THIS LIST, because the backend now implements it — as
 * `/registration/business/start` and `/registration/business/complete`, with no mock
 * behind either. Joining an existing company is still only here.
 *
 * So the impossible configuration is refused at start-up instead. A test cannot do this
 * job: the value usually comes from a developer's own .env.local, which no committed test
 * can see.
 *
 * DELETE A PATH FROM HERE WHEN THE BACKEND IMPLEMENTS IT, at the same time as its fixture
 * goes. An entry left behind would block a real feature from going live.
 */
const NOT_ON_THE_BACKEND: readonly string[] = ['/registration/join'];

/**
 * The handlers to install, with the live features left out.
 *
 * MSW passes a request it has no handler for through to the network, so omitting a
 * handler is how a feature goes live. Chosen over `worker.use(http.post(..., () =>
 * passthrough()))` because a handler that exists but declines is easy to misread as one
 * that answers; an absent handler is unambiguous.
 *
 * This replaced a single VITE_LIVE_REGISTRATION boolean. That flag had to be
 * all-registration-or-nothing, which stopped being enough the moment the backend also
 * implemented sign-in: testing the real password change meant either mocking it (so it
 * never happened) or turning mocks off entirely (so the dashboard, accounts and the staff
 * portal all 401, because the backend does not implement them yet). A list lets each area
 * cross over as it lands.
 *
 * Declared after `handlers` rather than before it, because a const cannot read a const
 * that has not been initialised yet.
 */
export function handlersExcept(livePrefixes: readonly string[]): readonly RequestHandler[] {
  if (livePrefixes.length === 0) return handlers;

  const kept = handlers.filter((handler) => !isLive(handler, livePrefixes));

  /*
   * A prefix that matches nothing is a configuration error, not a no-op.
   *
   * Without this, a typo — `sesssion`, or `/auth/` with a trailing slash — leaves the
   * feature mocked while the developer believes they are testing the real backend, and
   * everything appears to work. Failing at start-up is the only way that gets noticed.
   */
  const unmatched = livePrefixes.filter(
    (prefix) => !handlers.some((handler) => matches(handler, prefix)),
  );

  if (unmatched.length > 0) {
    /*
     * TWO CAUSES, AND THE MESSAGE HAS TO NAME BOTH.
     *
     * A typo is the common one, and the reason this check exists: `sesssion` leaves the
     * area mocked while the developer believes they are exercising the real backend.
     *
     * The other only appeared once company registration was implemented and its mock
     * deleted. There is no handler left for the prefix to remove, so listing it throws —
     * and "matches no mocked endpoint" reads as a spelling mistake when the truth is the
     * opposite: that area is already live, because MSW passes through whatever it has no
     * handler for. Somebody hitting that with only the first half of the message would
     * go looking for a typo that is not there.
     */
    throw new Error(
      `VITE_LIVE_API lists ${unmatched.join(', ')}, which match no mocked endpoint. ` +
        'Either the spelling does not match src/mocks/handlers.ts, or that area has no ' +
        'mock left at all — in which case it is already live and the entry can simply ' +
        'be removed.',
    );
  }

  /*
   * And the other way a prefix can be wrong: it matches a mocked endpoint perfectly well,
   * but the real service has nothing there. See NOT_ON_THE_BACKEND.
   */
  const missing = NOT_ON_THE_BACKEND.filter((path) =>
    livePrefixes.some((prefix) => path === prefix || path.startsWith(`${prefix}/`)),
  );

  if (missing.length > 0) {
    throw new Error(
      `VITE_LIVE_API would send ${missing.join(', ')} to the real API, which does not ` +
        'implement them — the request is denied as a permission error rather than a 404. ' +
        'Name the implemented paths instead, e.g. registration/personal,registration/otp.',
    );
  }

  return kept;
}

/** Whether this handler's path falls under any live prefix. */
function isLive(handler: RequestHandler, livePrefixes: readonly string[]): boolean {
  return livePrefixes.some((prefix) => matches(handler, prefix));
}

/**
 * Prefix match on the handler's registered path, relative to the API root.
 *
 * Path-based rather than grouping by the per-feature handler arrays, because the groups
 * do not line up with what the backend implements: `/session` lives in
 * `bankingHandlers` alongside twenty endpoints that are still fixtures, and it has to be
 * able to go live on its own.
 *
 * The segment check is what stops `/auth` from also matching a future `/authorisations`.
 */
function matches(handler: RequestHandler, prefix: string): boolean {
  const path = pathOf(handler);
  if (path === undefined) return false;
  if (!path.startsWith(prefix)) return false;
  return path.length === prefix.length || path.charAt(prefix.length) === '/';
}

/** The handler's path with the `*` /api/v1 root stripped, e.g. `/auth/login`. */
function pathOf(handler: RequestHandler): string | undefined {
  const raw: unknown = (handler as { info?: { path?: unknown } }).info?.path;
  if (typeof raw !== 'string') return undefined;
  return raw.startsWith(API) ? raw.slice(API.length) : undefined;
}
