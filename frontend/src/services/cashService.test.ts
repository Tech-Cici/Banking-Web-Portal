import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { cashService } from './movementService';

/**
 * What a deposit and a withdrawal actually put on the wire.
 *
 * <p>ASSERTING ON THE RESPONSE WOULD PROVE NOTHING HERE. A server that ignored the
 * idempotency header, or a client that sent the amount as a JSON number, would return a
 * perfectly ordinary 200 — and the damage (a doubled deposit, a rounded balance) only
 * appears later, in somebody's account. So these read the outgoing request.
 */

const API = '*/api/v1';

interface Captured {
  readonly body: unknown;
  readonly key: string | null;
}

/** Captures one outgoing cash request. */
function capture(direction: 'deposit' | 'withdraw'): { read: () => Captured | null } {
  let captured: Captured | null = null;

  server.use(
    http.post(`${API}/accounts/:accountId/${direction}`, async ({ request }) => {
      captured = {
        body: await request.json(),
        key: request.headers.get('Idempotency-Key'),
      };
      return HttpResponse.json({
        id: 'entry-1',
        accountId: 'acc-1',
        direction: direction === 'deposit' ? 'CREDIT' : 'DEBIT',
        amount: { amount: '1500', currency: 'RWF' },
        balanceAfter: { amount: '51500', currency: 'RWF' },
        description: 'Deposit',
        bookedAt: '2026-09-28T09:00:00Z',
        status: 'COMPLETED',
      });
    }),
  );

  return { read: () => captured };
}

describe('cashService', () => {
  it('sends the amount as a decimal STRING, not a number', async () => {
    const captured = capture('deposit');

    await cashService.deposit('acc-1', { amount: '1234.30' }, 'key-1');

    const sent = captured.read();
    expect(sent).not.toBeNull();

    /*
     * THE POINT. A JSON number cannot hold 1234.30 — it becomes 1234.2999999999999, and
     * a bank that adds a hundred such amounts is wrong by an amount somebody eventually
     * notices. `typeof` is the assertion, not the value: 1234.3 would compare equal to
     * "1234.30" under a loose check and still be the bug.
     */
    const body = sent?.body as Record<string, unknown>;
    expect(typeof body['amount']).toBe('string');
    expect(body['amount']).toBe('1234.30');
  });

  it('sends the idempotency key it was given, unchanged', async () => {
    const captured = capture('deposit');

    await cashService.deposit('acc-1', { amount: '1000' }, 'the-only-key');

    /*
     * The caller owns the key. A service that minted its own — or quietly replaced this
     * one — would turn every retry into a second deposit, which is the exact failure the
     * key exists to prevent.
     */
    expect(captured.read()?.key).toBe('the-only-key');
  });

  it('omits the description rather than sending an empty one', async () => {
    const captured = capture('withdraw');

    await cashService.withdraw('acc-1', { amount: '500' }, 'key-2');

    const body = captured.read()?.body as Record<string, unknown>;
    expect('description' in body).toBe(false);
  });

  it('sends a description when there is one', async () => {
    const captured = capture('withdraw');

    await cashService.withdraw('acc-1', { amount: '500', description: 'Rent' }, 'key-3');

    const body = captured.read()?.body as Record<string, unknown>;
    expect(body['description']).toBe('Rent');
  });

  it('posts a withdrawal to the withdraw path, never the deposit one', async () => {
    /*
     * Not paranoia. The two calls differ by one word in a template string, and a
     * copy-paste that sent a withdrawal to /deposit would ADD the money instead of
     * removing it — and would look entirely successful doing so.
     */
    let hitDeposit = false;
    server.use(
      http.post(`${API}/accounts/:accountId/deposit`, () => {
        hitDeposit = true;
        return HttpResponse.json({});
      }),
    );
    const captured = capture('withdraw');

    await cashService.withdraw('acc-1', { amount: '500' }, 'key-4');

    expect(hitDeposit).toBe(false);
    expect(captured.read()).not.toBeNull();
  });
});
