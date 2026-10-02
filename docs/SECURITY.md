# Security rules for this codebase

Derived from blueprint section 24 (front-end security checklist) and section 3 (global
interaction rules). Every item below is either enforced in code today or is a standing rule
for the phases still to come.

## Never, in any phase

- **No secrets in the front end.** Everything in a `VITE_*` variable is inlined into the
  bundle and readable by anyone. No API keys, client secrets, provider credentials or
  private keys — not in `.env`, not in a constant, not in a comment.
- **Never log** passwords, OTPs, PINs, CVVs, tokens, full PANs or full account numbers.
  `no-console: error` is on for the whole front end for this reason, and `ErrorBoundary`
  intentionally does *not* `console.error` — React error payloads contain component props,
  which on these screens means balances and account numbers.
- **Never display or store** a CVV or a PIN. The blueprint forbids it outright (15.1).
- **No sensitive data in URLs.** No account numbers, amounts, OTPs or tokens in a query
  string or path — URLs reach browser history, server access logs, referrer headers and
  analytics.
- **No `dangerouslySetInnerHTML`.** Banned by an ESLint `no-restricted-syntax` rule.
- **Never manufacture a `COMPLETED` state.** Transaction state comes from the server. A
  timeout means *unknown*, and unknown is `PENDING_CONFIRMATION`.
- **Hidden UI is not authorisation.** Hiding a button is a courtesy to the user. The
  backend decides entitlement on every request, every time.

## Enforced in code now

| Rule | Where |
| --- | --- |
| Deny-by-default API; only `/health` is public | `SecurityConfig` |
| 401/403 return the JSON envelope, no `WWW-Authenticate` challenge | `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` |
| CORS is an exact-origin allow-list; wildcards rejected at startup | `CorsProperties` |
| Unknown paths return 401 to an unauthenticated caller, so endpoints cannot be enumerated | `SecurityConfig` |
| No stack traces, messages or binding errors in error responses | `application.yml` → `server.error.*` |
| 5xx messages replaced with a generic one on both sides | `GlobalExceptionHandler`, `apiError.ts` |
| Client-supplied correlation ids validated against `[A-Za-z0-9_-]{8,64}` before reaching the log | `CorrelationIdFilter` |
| `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Cache-Control: no-store` | `SecurityConfig` (verified against a running server) |
| Actuator exposes only `health`, with no component detail | `application.yml` |
| Web storage banned by lint; no long-lived tokens persisted | `eslint.config.js` |
| `Math.random` banned for identifiers; `crypto.randomUUID` used for idempotency keys | `eslint.config.js`, `idempotency.ts` |
| Idempotency keys held in memory only, never persisted | `idempotency.ts` |
| Financial requests without an idempotency key throw before leaving the client | `apiClient.ts` |
| No sourcemaps in production assets | `vite.config.ts` |
| MSW excluded from production bundles; service worker deleted from `dist` | `main.tsx`, `vite.config.ts` |
| Production build refuses `VITE_ENABLE_MOCK_API=true` and non-HTTPS API URLs | `config/env.ts` |
| `noindex, nofollow`, `referrer: no-referrer` | `index.html` |
| Exact dependency pins, lockfiles committed | `package.json`, `pom.xml` |

## Two open decisions

**CSRF.** Currently disabled, because the planned session architecture is bearer-token,
where the browser does not attach the credential automatically and CSRF does not apply.
**If the bank mandates cookie-based sessions, CSRF protection must be re-enabled** in
`SecurityConfig` with `CookieCsrfTokenRepository`. This is flagged in the class comment as
the single most important note in that file.

**Content-Security-Policy.** Delivered as a response header by the hosting gateway, not from
a `<meta>` tag: a meta CSP cannot express `frame-ancestors` or report-only mode, and a
header cannot be stripped by injected markup. A starting policy for the gateway:

```
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
img-src 'self' data:;
connect-src 'self' https://<api-host>;
frame-ancestors 'none';
base-uri 'self';
form-action 'self';
object-src 'none';
upgrade-insecure-requests
```

`style-src 'unsafe-inline'` is required only because Phase 1 uses inline `style` attributes
on placeholder pages. Phase 2 moves all styling into stylesheets and that allowance should
be dropped then.

HSTS is likewise emitted by the gateway, the only hop that terminates TLS; it is explicitly
disabled in `SecurityConfig` so the two cannot disagree.

## Still to do

- Dependency and vulnerability scanning in CI (blueprint section 24 requires it).
- Session expiry warning and sensitive-draft clearing on logout and on corporate context
  switch (Phase 3, Phase 6).
- Rate-limit handling with `Retry-After` in the UI — parsed by `apiClient` already,
  surfaced in Phase 2.
- Secure statement/document download that never exposes a permanent public URL
  (blueprint 7.5, Phase 8).
- Automated accessibility checks in CI (Phase 26).
