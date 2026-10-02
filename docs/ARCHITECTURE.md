# Architecture decisions

Why things are the way they are. Each entry records the decision, the reasoning, and what
would change it.

## 1. Monorepo, two deployables

`frontend/` and `backend/` in one repository, deployed separately. The API contract changes
in lockstep with its consumer, and one pull request can carry both sides of a change. No
code is shared between them — the types in `frontend/src/types/api.ts` are a hand-written
mirror today and will be generated from the OpenAPI document once it exists.

## 2. One HTTP choke point

`frontend/src/services/apiClient.ts` is the only code that calls `fetch`. Correlation ids,
timeouts, idempotency, error normalisation and the pending-confirmation rule are properties
of *every* request, and the only way to guarantee that is to have one place where a request
is made. Feature code calls a service; a service calls the client.

**Changes if:** a caching/query library (TanStack Query) is adopted. It would wrap this
client rather than replace it.

## 3. Errors are normalised into a `kind`, not a status code

A component switching on `err.status === 422` couples the UI to HTTP. Instead every failure
becomes an `ApiError` with a `kind` from a closed set, so a `switch` over it is
exhaustively checked by the compiler and a new failure mode cannot be silently unhandled.

The `pendingConfirmation` kind is the reason this exists at all. A timed-out transfer is
neither success nor failure, and a client that models only those two will eventually tell a
customer their money did not move when it did.

## 4. Money is `bigint` minor units, parsed from strings

`0.1 + 0.2 !== 0.3` in IEEE-754, and `Number` loses integer precision above 2^53. RWF, with
no minor unit, reaches that magnitude at ~9 quadrillion — far off for one transfer, but not
for a salary batch total. Amounts arrive as decimal strings and are parsed into
`{ minorUnits: bigint, currency, scale }`.

Arithmetic in `money.ts` is for **presentation only** — a review-panel subtotal, a bulk-file
total. Fees, limits, balances, FX rates and loan figures are always the server's.

Parsing rejects rather than rounds: an amount with more decimals than the currency allows
is an error. Silently discarding a customer's digits is never the right default.

**Changes if:** a need arises for multiplication or division (interest, pro-rata splits).
Those need a rounding policy, and a rounding policy is a business decision, not a utility
function.

## 5. No UI component framework

Per the brief. A bank's design language is specific, and the blueprint specifies the
components precisely enough (section 2.2) that adapting a framework's opinions would cost
more than building them. Phase 2 builds them on the tokens in `src/styles/tokens.css`.

**Changes if:** the timeline compresses enough that a headless library (React Aria,
Radix) becomes worth it for accessibility primitives alone. That is a real option and the
token layer keeps it open.

## 6. Every route declared in Phase 1

All 50-odd routes exist now and render placeholders naming their phase and blueprint
section. The application's shape is verifiable immediately, navigation is real, and each
phase fills a slot rather than inventing a URL. Placeholders fetch nothing and render no
fixtures — a placeholder showing invented balances is worse than an empty one.

Code splitting is *not* configured yet; placeholders are trivial. From Phase 5 each feature
route should use React Router's `lazy` so a retail customer never downloads the
bulk-payments code.

## 7. Backend denies by default

Only `/api/v1/health` and the actuator health endpoint are public; everything else returns
401 because no authentication exists yet. An open API is a worse starting point than a
closed one, and endpoints open as their phase lands.

A consequence worth knowing: an unknown path also returns 401 rather than 404 for an
unauthenticated caller, so the API cannot be used to enumerate what exists.

## 8. JPA and Flyway wired, schema empty

The dependencies and configuration are in place; `flyway.enabled` is `false` and
`ddl-auto` is `none` in every profile including tests. There are no entities because there
are no confirmed payloads — writing tables now would mean guessing, and a guessed migration
is worse than none since Flyway migrations are immutable once merged.

`dev` uses in-memory H2 so the API starts with no dependencies. That is a Phase 1
convenience with a real risk: H2 and PostgreSQL disagree on enough SQL that a migration can
pass against H2 and fail in production. From the first migration onward, develop against
the `postgres` profile.

## 9. Correlation ids over request tracing

Every request carries one, the server echoes it and stamps it on its logs, and the UI shows
it on error screens. A customer reading a reference to a support agent is the cheapest
observability in a banking system.

Client-supplied ids are validated against `[A-Za-z0-9_-]{8,64}` before touching the MDC. An
unvalidated header reaching a log file lets a caller inject newlines and forge log lines.

**Changes if:** distributed tracing (OpenTelemetry) is adopted. The correlation id should
then become the trace id rather than a parallel identifier.

## 10. Strict TypeScript, including the awkward flags

`exactOptionalPropertyTypes` and `noUncheckedIndexedAccess` are on. They are the two flags
most often turned off for being inconvenient, and they are the two that catch the most real
defects in form and API code — both found genuine bugs in this Phase 1 code. For software
that moves money, requiring explicit handling of absent values is the correct trade.

## 11. Idempotency keys are in memory only

One key per financial submission, reused for every status check of that submission
(blueprint sections 3 and 44). Never persisted: a key that outlives the page is a key that
can resurrect a transaction the customer believes they abandoned.

`IdempotencyScope.reset()` exists for starting a genuinely new operation — "Repeat
transfer" must create a *new* transaction, never replay the old one (blueprint 10.5). It
must never be called to retry a submission whose outcome is unknown.

## 12. Mocks intercept the network, not modules

MSW runs the application's real API client, real error normalisation and real loading
states against mocked responses. A page proven against mocks needs no change to work
against the live backend, which is not true of module-level stubbing.

Mocked errors are built with `mockApiError` so they carry the exact envelope the Spring Boot
service returns. A mock with a different error shape tests nothing.
