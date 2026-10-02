/**
 * Validated application configuration.
 *
 * Read once, at module load, and fail fast. A banking client that boots with a missing API
 * base URL and only discovers it when a customer presses "Transfer" is worse than one that
 * refuses to start, so anything malformed throws here.
 *
 * Everything in this module is PUBLIC: Vite inlines `import.meta.env.VITE_*` into the
 * bundle. No secret may ever be read through it.
 */

/** Runtime mode, derived from Vite rather than an env var so it cannot disagree with it. */
export type AppMode = 'development' | 'production' | 'test';

export interface AppConfig {
  /** API base URL with any trailing slash removed, e.g. `http://localhost:8080/api/v1`. */
  readonly apiBaseUrl: string;
  /** Default request timeout in milliseconds. */
  readonly apiTimeoutMs: number;
  /** Whether the Mock Service Worker should intercept API calls. */
  readonly enableMockApi: boolean;
  /**
   * Whether the staff Developer tools page exists in this build.
   *
   * <p>OFF BY DEFAULT, AND READ AT BUILD TIME so the bundler can delete the page and the
   * reset client entirely. Vite inlines `import.meta.env.*`, so a `false` here makes the
   * route registration dead code and the chunks are never emitted.
   *
   * <p>WHY A FLAG RATHER THAN `!isProduction`. A demonstration deployment is a production
   * BUILD — it is `vite build` behind a CDN — and it still needs the message log, because
   * with email switched off that is the only way to read a verification code. Keying on the
   * build mode would mean a demo nobody could sign into. Keying on an explicit variable
   * means a real production build drops it and a demo opts in on purpose.
   */
  readonly enableDevTools: boolean;

  /**
   * API path prefixes that go to the REAL backend while everything else stays mocked.
   *
   * Exists because the two all-or-nothing options are both useless while the backend is
   * partly built. With mocks on, a request never leaves the browser, so no verification
   * email is ever sent and no session is ever really created. With mocks off, the
   * implemented flows are real but the dashboard, accounts, transfers and the staff
   * portal all return 401 — which reads as a broken app rather than a missing backend.
   *
   * Each prefix is relative to the API root and has no trailing slash, e.g.
   * `/registration`, `/auth`, `/session`. Empty means everything is mocked.
   */
  readonly liveApiPaths: readonly string[];
  /** Build identifier shown in the footer for support. */
  readonly appVersion: string;
  readonly mode: AppMode;
  readonly isProduction: boolean;
}

class ConfigurationError extends Error {
  override name = 'ConfigurationError';
}

function requireString(key: keyof ImportMetaEnv, value: string | undefined): string {
  if (value === undefined || value.trim() === '') {
    throw new ConfigurationError(
      `Missing required environment variable ${key}. Copy .env.example to .env.local and set it.`,
    );
  }
  return value.trim();
}

function parseHttpUrl(key: keyof ImportMetaEnv, raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new ConfigurationError(`${key} is not a valid absolute URL: ${raw}`);
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ConfigurationError(`${key} must use http or https, got ${url.protocol}`);
  }

  // A banking client must not talk to an API over plain http outside local development.
  if (import.meta.env.PROD && url.protocol !== 'https:') {
    throw new ConfigurationError(
      `${key} must use https in a production build (got ${url.protocol}).`,
    );
  }

  return raw.replace(/\/+$/, '');
}

function parsePositiveInt(key: keyof ImportMetaEnv, raw: string): number {
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new ConfigurationError(`${key} must be a positive integer, got ${raw}`);
  }
  return parsed;
}

function parseBoolean(key: keyof ImportMetaEnv, raw: string): boolean {
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  throw new ConfigurationError(`${key} must be exactly "true" or "false", got ${raw}`);
}

/**
 * Reads VITE_LIVE_API into normalised path prefixes.
 *
 * Accepts `registration, auth, /session` and returns `['/registration','/auth','/session']`
 * — leading slash added, trailing slashes removed, blanks dropped — so the value in a
 * `.env` file does not have to be punctuated exactly right to work. A prefix that matches
 * no mocked endpoint is rejected later, by `handlersExcept`, which is the only place that
 * knows what the endpoints are.
 */
function parseLiveApiPaths(env: ImportMetaEnv): readonly string[] {
  const raw = env.VITE_LIVE_API?.trim() ?? '';

  /*
   * The old boolean, still honoured.
   *
   * A developer's .env.local may well still say VITE_LIVE_REGISTRATION=true, and
   * silently ignoring it would put them back on mocks while they believe they are
   * testing the real registration email — the exact confusion the flag was added to end.
   */
  const legacy =
    env.VITE_LIVE_REGISTRATION !== undefined &&
    env.VITE_LIVE_REGISTRATION.trim() !== '' &&
    parseBoolean('VITE_LIVE_REGISTRATION', env.VITE_LIVE_REGISTRATION.trim())
      ? ['/registration']
      : [];

  const listed = raw
    .split(',')
    .map((entry) => entry.trim().replace(/\/+$/, ''))
    .filter((entry) => entry !== '')
    .map((entry) => (entry.startsWith('/') ? entry : `/${entry}`));

  for (const prefix of listed) {
    if (!/^\/[a-z0-9\-/]+$/.test(prefix)) {
      throw new ConfigurationError(
        `VITE_LIVE_API entry "${prefix}" is not a plain API path, e.g. "auth" or "/registration".`,
      );
    }
  }

  return [...new Set([...listed, ...legacy])];
}

function resolveMode(): AppMode {
  if (import.meta.env.MODE === 'test') return 'test';
  return import.meta.env.PROD ? 'production' : 'development';
}

function build(): AppConfig {
  const env = import.meta.env;

  const apiBaseUrl = parseHttpUrl(
    'VITE_API_BASE_URL',
    requireString('VITE_API_BASE_URL', env.VITE_API_BASE_URL),
  );
  const apiTimeoutMs = parsePositiveInt(
    'VITE_API_TIMEOUT_MS',
    requireString('VITE_API_TIMEOUT_MS', env.VITE_API_TIMEOUT_MS),
  );
  const enableMockApi = parseBoolean(
    'VITE_ENABLE_MOCK_API',
    requireString('VITE_ENABLE_MOCK_API', env.VITE_ENABLE_MOCK_API),
  );
  const appVersion = requireString('VITE_APP_VERSION', env.VITE_APP_VERSION);

  /*
   * ABSENT MEANS OFF. Unlike the variables above this one is optional, because every
   * existing .env predates it and a build should not start failing for not mentioning a
   * developer tool. Anything other than exactly "true" is off.
   */
  const enableDevTools = env.VITE_ENABLE_DEV_TOOLS === 'true';

  const liveApiPaths = parseLiveApiPaths(env);

  const isProduction = import.meta.env.PROD;

  // Mocks in a production bundle would mean customers seeing fabricated balances.
  if (isProduction && enableMockApi) {
    throw new ConfigurationError(
      'VITE_ENABLE_MOCK_API must be false in a production build. Refusing to serve mock banking data.',
    );
  }

  /*
   * A production bundle must not carry a passthrough list. Mocks are already refused
   * above, so the list would do nothing — but a leftover VITE_LIVE_API in a production
   * .env is a sign the build was configured from a developer's file, which is worth
   * stopping over rather than shipping.
   *
   * Note WHEN this fires: at load, in the browser, not during `vite build`. Everything in
   * this module runs on the inlined values when the bundle starts, so `vite build`
   * succeeds and the app then refuses to boot. Verified by serving such a build and
   * reading the page error, because the build exiting 0 makes it easy to assume the guard
   * is not there.
   */
  if (isProduction && liveApiPaths.length > 0) {
    throw new ConfigurationError(
      'VITE_LIVE_API and VITE_LIVE_REGISTRATION must be empty in a production build.',
    );
  }

  return {
    apiBaseUrl,
    apiTimeoutMs,
    enableMockApi,
    enableDevTools,
    liveApiPaths,
    appVersion,
    mode: resolveMode(),
    isProduction,
  };
}

export const appConfig: AppConfig = build();
