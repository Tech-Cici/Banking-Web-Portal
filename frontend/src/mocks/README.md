# Mock API

Fixtures and request handlers, served by [MSW](https://mswjs.io/). Enable with
`VITE_ENABLE_MOCK_API=true` in `.env.local`.

MSW intercepts at the network layer, so the application runs its real API client, real
error normalisation and real loading states against mocks. Nothing is stubbed at the
module level, which means a page proven against mocks needs no changes to work against the
live backend.

## Rules

- Handlers live here, never inside components or pages. A page with its own hard-coded data
  cannot be pointed at a real API without a rewrite.
- Mocked errors use `mockApiError`, so a mocked failure has the exact envelope the Spring
  Boot service returns. A mock that returns a different error shape tests nothing.
- Fixtures arrive with the phase that consumes them, not in advance. Guessed payloads
  written before the OpenAPI spec exists get rewritten anyway, and in the meantime they
  look authoritative.
- Never put real customer data, real account numbers or real phone numbers in a fixture,
  even one obtained from a test environment.
- `VITE_ENABLE_MOCK_API=true` is refused by a production build (see `src/config/env.ts`).
  Serving fabricated balances to a customer is a far worse failure than a blank screen.

## Files

| File | Purpose |
| --- | --- |
| `handlers.ts` | Default handlers plus `scenarioHandlers` for error/timeout states |
| `browser.ts` | Worker used by the dev server |
| `server.ts` | Server used by Vitest (wired in when the first request-level test lands) |
