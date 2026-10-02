import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { server } from '@/mocks/server';
import { apiClient, onUnauthenticated } from './apiClient';
import { friendlyError } from './errorMessage';

/**
 * A 401 does not mean the same thing everywhere.
 *
 * Reported symptom: filling in the personal registration form and pressing the code
 * button produced "You have been signed out, either because you were inactive for a
 * while or because you signed in somewhere else." The visitor had never signed in.
 *
 * Two separate faults produced that one sentence, and each is pinned below.
 */

const API = '*/api/v1';

function respond401(path: string) {
  server.use(
    http.post(`${API}${path}`, () =>
      HttpResponse.json(
        { status: 401, code: 'UNAUTHENTICATED', message: 'Unauthorized' },
        { status: 401 },
      ),
    ),
  );
}

describe('a 401 from a public endpoint', () => {
  const unsubscribes: (() => void)[] = [];

  afterEach(() => {
    for (const unsubscribe of unsubscribes.splice(0)) unsubscribe();
  });

  function listen(): { calls: () => number } {
    const listener = vi.fn();
    unsubscribes.push(onUnauthenticated(listener));
    return { calls: () => listener.mock.calls.length };
  }

  it('does not end the session — registration', async () => {
    /*
     * The original guard excluded `/auth/` only. `/registration/` is just as public, so
     * an anonymous visitor's 401 was broadcast as "this session is over" to the whole
     * application.
     */
    const heard = listen();
    respond401('/registration/personal/start');

    await expect(apiClient.post('/registration/personal/start', { body: {} })).rejects.toThrow();
    expect(heard.calls()).toBe(0);
  });

  it('does not end the session — sign-in', async () => {
    const heard = listen();
    respond401('/auth/login');

    await expect(apiClient.post('/auth/login', { body: {} })).rejects.toThrow();
    expect(heard.calls()).toBe(0);
  });

  it('DOES end the session for an endpoint that needed one', async () => {
    // The other half: narrowing the guard must not stop a genuine expiry being noticed.
    const heard = listen();
    respond401('/transfers/own');

    await expect(apiClient.post('/transfers/own', { body: {} })).rejects.toThrow();
    expect(heard.calls()).toBe(1);
  });
});

describe('what a 401 says, depending on who is reading', () => {
  it('tells a signed-in customer their session ended', async () => {
    respond401('/transfers/own');

    const cause = await apiClient.post('/transfers/own', { body: {} }).catch((e: unknown) => e);
    const friendly = friendlyError(cause, undefined, { hadSession: true });

    expect(friendly.summary).toMatch(/signed out/i);
  });

  it('does not tell a visitor with no account that they were signed out', async () => {
    respond401('/registration/personal/start');

    const cause = await apiClient
      .post('/registration/personal/start', { body: {} })
      .catch((e: unknown) => e);
    const friendly = friendlyError(cause, undefined, { hadSession: false });

    expect(friendly.summary).not.toMatch(/signed out/i);
    expect(friendly.summary).not.toMatch(/inactive/i);
    expect(friendly.summary).not.toMatch(/signed in somewhere else/i);
    expect(friendly.nextStep).toBeDefined();
  });

  it('still gives them a reference to quote', async () => {
    respond401('/registration/personal/start');

    const cause = await apiClient
      .post('/registration/personal/start', { body: {} })
      .catch((e: unknown) => e);

    expect(friendlyError(cause, undefined, { hadSession: false }).reference).toBeDefined();
  });
});
