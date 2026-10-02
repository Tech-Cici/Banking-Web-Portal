# Internet Banking Web Portal

Retail and corporate internet banking, built from the *Internet Banking Front-End Screen,
Form & UX Blueprint* (v1.0). The blueprint is the source of truth for screens, fields,
flows and states; this repository does not invent banking behaviour.

**Status: Phase 1 complete — project foundation.** No banking screen is implemented yet.
Every route in the map renders a placeholder that names the phase that will build it.

---

## Contents

- [Architecture](#architecture)
- [Repository layout](#repository-layout)
- [How the front end and back end communicate](#how-the-front-end-and-back-end-communicate)
- [Prerequisites](#prerequisites)
- [Running it](#running-it)
- [Verifying it](#verifying-it)
- [Dependency choices, and the two constraints behind them](#dependency-choices-and-the-two-constraints-behind-them)
- [What Phase 1 deliberately does not include](#what-phase-1-deliberately-does-not-include)
- [Open questions for the bank](#open-questions-for-the-bank)
- [Phase plan](#phase-plan)

---

## Architecture

Two deployable units, no shared code, talking over a versioned JSON API.

```
┌───────────────────────────┐         ┌──────────────────────────────┐
│  Browser (SPA)            │         │  Spring Boot API             │
│                           │         │                              │
│  React 19 + TypeScript    │  HTTPS  │  Controllers (DTOs only)     │
│  Vite 8                   │ ──────► │       │                      │
│  React Router 7           │  JSON   │  Services (business rules,   │
│                           │ ◄────── │       │  authoritative)      │
│  ── services/ ──────────  │         │  Repositories (JPA)          │
│  the ONLY place that      │         │       │                      │
│  performs HTTP            │         └───────┼──────────────────────┘
└───────────────────────────┘                 │
                                              ▼
                                   PostgreSQL  +  Core Banking / providers
```

The division of responsibility is the important part, and it is not negotiable:

| Decision | Owner |
| --- | --- |
| Balances, fees, limits, FX rates, loan figures | **Backend.** The client displays what it is given. |
| Account and beneficiary entitlement | **Backend.** A route parameter is not authorisation. |
| Transaction state (`COMPLETED`, `PENDING_CONFIRMATION`, …) | **Backend.** The client never manufactures a state. |
| Required/format validation, obvious currency mismatches | Both. The client's copy is a courtesy, never a substitute. |
| Which actions are visible | Client, from backend-supplied permissions. Enforcement stays server-side. |

Blueprint sections 21 and 24 are the long form of that table.

### Frontend layering

```
pages/        route targets; compose features, own no business logic
  └── features/     one directory per capability, self-contained
        └── components/   shared design-system components (Phase 2)
              └── services/    the only code that calls fetch
                    └── utils/, types/    pure helpers and shared types
```

Imports point one direction only, and no feature imports another feature's internals. If
two features need the same thing, it moves down a layer.

### Backend layering

```
web/          controllers + DTOs. Entities never cross this boundary.
  └── service/     business rules, transaction boundaries
        └── repository/   Spring Data JPA
config/       security, CORS, configuration properties
common/       error envelope, correlation id
exception/    typed exceptions + the single @RestControllerAdvice
```

---

## Repository layout

```
.
├── docker-compose.yml          # local PostgreSQL
├── docs/
│   ├── ARCHITECTURE.md         # decisions and rationale
│   └── SECURITY.md             # the security rules this code follows
├── backend/
│   ├── pom.xml
│   ├── mvnw, mvnw.cmd          # Maven wrapper — no local Maven needed
│   └── src/
│       ├── main/java/rw/bank/ibanking/
│       │   ├── IbankingApplication.java
│       │   ├── common/api/         # ApiErrorResponse, ApiErrorCode, FieldViolation
│       │   ├── common/correlation/ # CorrelationIdFilter
│       │   ├── config/             # SecurityConfig, CorsProperties, 401/403 handlers
│       │   ├── exception/          # typed exceptions + GlobalExceptionHandler
│       │   └── web/                # HealthController + dto/
│       ├── main/resources/
│       │   ├── application.yml              # shared, no secrets
│       │   ├── application-dev.yml          # H2, CORS for the Vite dev server
│       │   ├── application-postgres.yml     # PostgreSQL, env-supplied credentials
│       │   └── db/migration/README.md       # migration policy
│       └── test/java/...                    # 7 tests
└── frontend/
    ├── eslint.config.js, .prettierrc.json
    ├── tsconfig.json + .app.json + .node.json
    ├── vite.config.ts
    ├── public/favicon.svg, public/mockServiceWorker.js
    └── src/
        ├── main.tsx                # entry point
        ├── app/App.tsx             # root; cross-cutting providers go here
        ├── config/env.ts           # validated environment, fails fast
        ├── services/               # apiClient, apiError, correlation, idempotency
        ├── routes/                 # paths.ts, router.tsx, ProtectedRoute.tsx
        ├── layouts/                # PublicLayout, AppLayout
        ├── pages/                  # placeholders + NotFound + SystemStatus
        ├── components/feedback/    # ErrorBoundary, LoadingState
        ├── utils/                  # money.ts, mask.ts (+ 35 tests)
        ├── styles/                 # tokens.css, reset.css, global.css
        ├── mocks/                  # MSW handlers
        ├── features/               # empty until Phase 5 (see its README)
        ├── contexts/, hooks/, assets/   # empty, reserved
        └── types/api.ts
```

Directories that are empty are empty on purpose. They mark where code goes so that the
first person to need one does not invent a different location.

---

## How the front end and back end communicate

Everything goes through `frontend/src/services/apiClient.ts`. Nothing else in the
application calls `fetch` — an ESLint rule and code review keep it that way, because that
single choke point is what makes the following uniform rather than per-component
guesswork.

**Request.** Base URL from validated config; `X-Correlation-Id` on every request;
`Idempotency-Key` on every money-moving submission; `AbortSignal.timeout` combined with any
caller signal; `cache: 'no-store'`; `redirect: 'error'`.

**Response.** Non-2xx becomes an `ApiError` with a `kind` the UI switches on — `validation`,
`unauthenticated`, `forbidden`, `notFound`, `conflict`, `businessRule`, `rateLimited`,
`server`, `network`, `timeout`, `pendingConfirmation`, `unknown`. Components never inspect a
status code.

**The error envelope** is identical for every failure:

```json
{
  "timestamp": "2026-09-22T13:31:23.786Z",
  "status": 401,
  "code": "UNAUTHENTICATED",
  "message": "Your session is not valid. Please sign in again.",
  "path": "/api/v1/accounts",
  "correlationId": "1620eae8-1117-4a94-866c-9724738792d2",
  "fieldErrors": []
}
```

`correlationId` is echoed in the `X-Correlation-Id` response header and stamped on the
server's log lines, so a reference shown to a customer leads straight to the log entry.
For 5xx the server's own message is replaced with a generic one on both sides — container
and proxy error pages leak hostnames and stack traces.

**The rule that matters most.** When a money-moving request does not come back — timeout,
dropped connection — the client cannot know whether the server executed it. That is
`pendingConfirmation`, not a failure. The UI shows *pending confirmation*, does not invite a
retry, and polls the transaction status using the **same** idempotency key. Blueprint
sections 1, 19 and 26; brief sections 19, 41 and 44.

---

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| Node.js | ≥ 22.12 | Vite 8 requires it. `.nvmrc` pins 22. |
| JDK | 21 | Spring Boot 4 needs ≥ 17; the build targets 21. |
| Maven | not required | Use the bundled `./mvnw`. |
| Docker | recommended | Runs PostgreSQL, which the default `dev` profile expects. The `h2` profile starts without it, but its database is in memory and is wiped on every restart. |

---

## Running it

Two terminals.

**Backend** — starts on `http://localhost:8080`:

```bash
docker compose up -d db          # PostgreSQL; the dev profile expects it
cd backend
./mvnw spring-boot:run
```

No profile is passed because `spring.profiles.default` is `dev`, which points at that
database. Without it the API refuses to start — Flyway cannot open a connection, so it
fails rather than coming up with no schema.

No Docker? `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2` needs nothing at all,
but its database is **in memory**: every customer you register disappears when the API
stops. See [backend/docs/LOCAL-DATABASE.md](backend/docs/LOCAL-DATABASE.md) for the
options, including running PostgreSQL without Docker.

**Frontend** — starts on `http://localhost:5173`:

```bash
cd frontend
npm install
npm run dev
```

Then open <http://localhost:5173>. It redirects to `/dashboard`.

### With the production-shaped profile

`dev` carries the local container's credentials as defaults. The `postgres` profile takes
everything from the environment and **refuses to start without `DB_PASSWORD`**, which is
the point — a deployed environment must never fall back to a password committed to a
repository.

```bash
docker compose up -d db
cd backend
DB_PASSWORD=ibanking_local_only ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

### With the mock API instead of the real backend

```bash
cd frontend
cp .env.example .env.local     # then set VITE_ENABLE_MOCK_API=true
npm run dev
```

A production build refuses to start with mocks enabled: serving fabricated balances to a
customer is worse than serving nothing.

---

## Verifying it

**Automated.** Both suites pass from a clean checkout.

```bash
cd backend  && ./mvnw test        # 7 tests
cd frontend && npm run verify     # format + lint + strict typecheck + 35 tests
cd frontend && npm run build      # production build
```

**By hand.** With both servers running:

| # | Check | Expected |
| --- | --- | --- |
| 1 | Open <http://localhost:5173> | Redirects to `/dashboard`; shell with grouped sidebar |
| 2 | Click **System status** in the sidebar | **✓ Connected**, service `ibanking-api`, server time |
| 3 | Stop the backend, reload that page | **⚠ Not connected**, a reference id, a **Try again** button |
| 4 | Restart the backend, press **Try again** | Returns to **✓ Connected** |
| 5 | Press <kbd>Tab</kbd> on any page | First stop is a visible **Skip to main content** link |
| 6 | Click through every sidebar item | Each shows its title, its phase and its blueprint section |
| 7 | Narrow the window below 1024px | Sidebar collapses behind a **Menu** button |
| 8 | Narrow to 390px | Single column, no horizontal scrolling |
| 9 | Visit `/definitely-not-a-route` | "Page not found", not a blank screen |
| 10 | `curl -i localhost:8080/api/v1/accounts` | 401 with the error envelope, no `WWW-Authenticate` |
| 11 | `curl -i -H 'Origin: https://evil.example' -X OPTIONS localhost:8080/api/v1/health` | 403, no `Access-Control-Allow-Origin` |

Checks 1–9 are also automated as a Playwright smoke test that passed 14/14 during
development; wiring it in as a committed E2E suite is Phase 28 work.

---

## Dependency choices, and the two constraints behind them

Versions are pinned exactly — no `^` ranges. A banking client should not change its
compiler or its router because someone ran `npm install` on a Tuesday. Two pins are not
simply "latest", and both are deliberate:

**TypeScript 6.0.3, not 7.0.2.** `typescript-eslint@8.70.1` declares
`typescript >=4.8.4 <6.1.0`. TypeScript 7 is outside that range, so type-aware linting —
which is what catches unhandled promises and unsafe `any` flow in transaction code — would
have to be given up to adopt it. Revisit when `typescript-eslint` supports TS 7.

**ESLint 9.39.5, not 10.11.0.** `eslint-plugin-jsx-a11y@6.10.2` peers on
`^3 || … || ^9`; ESLint 10 is an unresolvable conflict, verified by attempting the install.
WCAG 2.2 AA is a blueprint requirement (section 23), so the accessibility plugin wins.
npm reports ESLint 9.39.5 as an unsupported release — a real cost, tracked, and resolvable
the moment jsx-a11y ships ESLint 10 support.

No UI component framework is installed, per the brief. The design system is built from
scratch in Phase 2 on the tokens in `src/styles/tokens.css`.

Money has no dependency either. `src/utils/money.ts` holds amounts as `bigint` minor units
parsed from the decimal strings the API sends — `0.1 + 0.2 !== 0.3`, and a `number` loses
integer precision past 2^53, which RWF amounts reach sooner than most currencies.

---

## What Phase 1 deliberately does not include

Not "forgotten" — scheduled.

- **No design system.** `tokens.css` exists; buttons, inputs, tables, steppers, modals and
  skeletons are Phase 2.
- **No authentication.** `/login`, `/auth/verify`, `/forgot-password`, `/reset-password` are
  placeholders. `ProtectedRoute` bypasses its check in development builds only — tied to the
  build mode, so a production bundle cannot ship with the bypass active.
- **No session management, permissions or corporate context.** Phases 3 and 6.
- **No banking screens.** Every route is a placeholder.
- **No entities, no migrations.** JPA and Flyway are wired and `flyway.enabled=false`;
  the schema starts with the first real entity. Writing tables before knowing the payloads
  would mean guessing.
- **No mock fixtures beyond health.** Fixtures arrive with the phase that consumes them, so
  they match the real payloads instead of being guessed now and rewritten later.
- **No E2E suite in the repository.** Phase 28.
- **No CI pipeline.** `npm run verify` and `./mvnw test` are the commands a pipeline will
  run; the workflow file itself is not written.

---

## Open questions for the bank

Blueprint section 29 lists the artifacts still owed. These block specific decisions:

| # | Question | Blocks | Current placeholder |
| --- | --- | --- | --- |
| 1 | Brand tokens — colours, logo, typography | Phase 2 | Neutral accessible palette in `tokens.css`; placeholder `favicon.svg` |
| 2 | **RWF decimal display.** Blueprint 9.1 shows `RWF 100,000.00`; ISO 4217 gives RWF **zero** decimals | Every amount on every screen | Parsing follows ISO 4217; `formatMoney` takes a `fractionDigits` override so the answer applies in one place |
| 3 | OpenAPI specification | All service code | Hand-written types in `types/api.ts`, to be generated and deleted |
| 4 | Permission matrix | Phase 6 | Illustrative names in `types/api.ts`; nothing enforced client-side |
| 5 | Session architecture — bearer token or cookie | Phase 3 | CSRF disabled for bearer tokens. **If cookies are chosen, CSRF must be re-enabled** — see the comment in `SecurityConfig` |
| 6 | Transaction limits and approval rules | Phases 10+ | None assumed |
| 7 | Provider field contracts — RRA, AFOS, utilities, TV, wallet | Phases 15–16 | Blueprint field names only, marked provisional |
| 8 | Identifier for login — username, email or customer ID | Phase 3 | Undecided; the login placeholder names all three |

Question 2 is the one worth settling first: it affects every screen, and changing it late
means touching every amount in the application.

---

## Phase plan

Phase 1 ✅ · 2 design system · 3 authentication · 4 app shell · 5 retail dashboard ·
6 corporate dashboard · 7 accounts · 8 history + statements · 9 **transaction engine** ·
10 own transfer · 11 internal · 12 external · 13 international · 14 payment framework ·
15 wallet + airtime · 16 utilities + tax + AFOS · 17 standing orders · 18 FX · 19 loans ·
20 cards · 21 cheque books · 22 approvals · 23 bulk · 24 salary · 25 profile/security ·
26 accessibility · 27 security hardening · 28 testing · 29 performance · 30 production
readiness.

Phases 9–10 are the pivot. The reusable engine — entry → server validation → review →
verification → processing → result — plus own-account transfer as its reference
implementation is what every other money-moving flow then reuses. Building transfers and
payments as independent flows is the failure the blueprint warns about twice (sections 9
and 18.7).

## Further reading

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — decisions and their rationale
- [`docs/SECURITY.md`](docs/SECURITY.md) — the security rules this code follows
- [`frontend/src/features/README.md`](frontend/src/features/README.md) — feature boundaries
- [`frontend/src/mocks/README.md`](frontend/src/mocks/README.md) — mock API rules
- [`backend/src/main/resources/db/migration/README.md`](backend/src/main/resources/db/migration/README.md) — migration policy
