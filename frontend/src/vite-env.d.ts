/// <reference types="vite/client" />

/**
 * Typed view of the environment variables this app reads.
 *
 * Declaring them here means a typo in `import.meta.env.VITE_...` is a compile error rather
 * than a runtime `undefined`. Values are still validated at startup in `src/config/env.ts`,
 * because types say nothing about what the build actually injected.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_API_TIMEOUT_MS: string;
  readonly VITE_ENABLE_MOCK_API: string;
  /**
   * Comma-separated API path prefixes to send to the real backend, e.g.
   * `registration,auth,session`. Optional: absent means everything is mocked.
   */
  readonly VITE_LIVE_API?: string;
  /**
   * Superseded by VITE_LIVE_API, still read so an existing .env.local keeps working.
   * Equivalent to `VITE_LIVE_API=registration`.
   */
  readonly VITE_LIVE_REGISTRATION?: string;
  readonly VITE_APP_VERSION: string;
  /**
   * `true` to include the staff Developer tools page (message log and the reset) in this
   * build. Absent or anything else means the page, its route and the reset client are all
   * removed by the bundler.
   *
   * A DEMONSTRATION DEPLOYMENT WANTS THIS ON. It is a production build, but with email
   * switched off the message log is the only way to read a verification code — so keying
   * the page on the build mode would produce a demo nobody could sign into.
   */
  readonly VITE_ENABLE_DEV_TOOLS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
