import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { adminService } from './adminService';

/**
 * The create-account call sends the accounts, and the server receives them.
 *
 * THE DEFECT THIS PINS, because nothing in the suite would have caught it. The admin
 * screen collected an account type, a currency and an opening balance and posted them.
 * The endpoint was `createAccount(@PathVariable UUID id, Authentication caller)` — no
 * `@RequestBody` — so Spring bound the path variable and discarded the JSON. The
 * administrator chose "Current account", saw a success message, and nothing was stored.
 *
 * Every test passed the whole time, because they all asserted on the RESPONSE. A body
 * that is not read comes back 200 exactly like a body that is. So this test intercepts
 * the request and reads what actually went over the wire, which is the only place the
 * fault was visible from the client side.
 */

const API = '*/api/v1';

/** Captures the outgoing request body for one create-account call. */
function captureBody(): { read: () => unknown } {
  let captured: unknown = null;

  server.use(
    http.post(`${API}/admin/applications/:id/create-account`, async ({ request }) => {
      captured = await request.json();
      return HttpResponse.json(
        { customer: { fullName: 'Test' }, accounts: [] },
        { status: 201 },
      );
    }),
  );

  return { read: () => captured };
}

describe('adminService.createAccount', () => {
  it('sends the requested accounts in the body', async () => {
    const captured = captureBody();

    await adminService.createAccount(
      'app-1',
      {
        accounts: [
          { accountNumber: '4001111111111', accountType: 'CURRENT', currency: 'RWF', openingBalance: '50000' },
          { accountNumber: '4002222222222', accountType: 'SAVINGS', currency: 'USD', openingBalance: '0' },
        ],
      },
      'idem-1',
    );

    /*
     * If this ever comes back undefined or empty, the client has stopped sending what
     * the administrator chose — which is indistinguishable from success at every other
     * layer.
     */
    expect(captured.read()).toEqual({
      accounts: [
        { accountNumber: '4001111111111', accountType: 'CURRENT', currency: 'RWF', openingBalance: '50000' },
        { accountNumber: '4002222222222', accountType: 'SAVINGS', currency: 'USD', openingBalance: '0' },
      ],
    });
  });

  it('sends several accounts rather than one compound value', async () => {
    const captured = captureBody();

    await adminService.createAccount(
      'app-2',
      {
        accounts: [
          { accountNumber: '4001111111111', accountType: 'CURRENT', currency: 'RWF', openingBalance: '50000' },
          { accountNumber: '4003333333333', accountType: 'SAVINGS', currency: 'RWF', openingBalance: '0' },
        ],
      },
      'idem-2',
    );

    const body = captured.read() as { accounts: unknown[] };

    /*
     * "A current account and a savings account" must arrive as two accounts. Collapsing
     * it to something like CURRENT_AND_SAVINGS would produce a value the bank cannot
     * later close, count or report on one at a time.
     */
    expect(body.accounts).toHaveLength(2);
  });

  it('carries an idempotency key, so a double click cannot issue two logins', async () => {
    let header: string | null = null;

    server.use(
      http.post(`${API}/admin/applications/:id/create-account`, ({ request }) => {
        header = request.headers.get('Idempotency-Key');
        return HttpResponse.json({ customer: {}, accounts: [] }, { status: 201 });
      }),
    );

    await adminService.createAccount(
      'app-3',
      { accounts: [{ accountNumber: '4001111111111', accountType: 'CURRENT', currency: 'RWF', openingBalance: '50000' }] },
      'idem-3',
    );

    expect(header).toBe('idem-3');
  });
});
