import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { server } from '@/mocks/server';
import { SecurityPage } from './SecurityPage';

/**
 * THE SCREEN A CUSTOMER OPENS WHEN THEY THINK SOMEBODY HAS THEIR PASSWORD.
 *
 * THE HISTORY, which is why the tests are the ones they are. This page called
 * `/security/devices`, `/security/events` and `/security/password`; none of the three
 * existed on the server, and MSW answered all of them from arrays that start empty on
 * every page load. So the sign-in history showed nothing whatever had happened to the
 * account, and "Remove" withdrew trust from a browser that had never been trusted.
 *
 * It also made four claims the server never supported: a per-row location, "failed
 * attempts" in the history, an ok/failed badge nothing could set to failed, and a promise
 * that other devices stayed signed in through a password change. Each has a test below,
 * because each of them read as a working feature.
 */

const API = '*/api/v1';

const DEVICE_ID = '9f1c2b3a-4d5e-4f60-8a71-b2c3d4e5f607';
const OTHER_ID = '1a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9';

/**
 * The sign-in history as the real service sends it.
 *
 * NOTE WHAT IS ABSENT: no `location`, no `outcome`, no `description`, and no `device` on
 * the second row. The service omits null fields, so a browser that sent no User-Agent
 * produces a row with no such key — a fixture that sent `device: null` would be testing a
 * response the server cannot emit.
 */
function historyIs(...rows: readonly Record<string, unknown>[]): void {
  server.use(http.get(`${API}/security/events`, () => HttpResponse.json(rows)));
}

function devicesAre(...rows: readonly Record<string, unknown>[]): void {
  server.use(http.get(`${API}/security/devices`, () => HttpResponse.json(rows)));
}

function trustedBrowser(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: DEVICE_ID,
    device: 'Chrome on Windows',
    trustedAt: '2026-09-01T07:30:00Z',
    lastUsedAt: '2026-09-28T06:05:00Z',
    expiresAt: '2026-10-01T07:30:00Z',
    current: false,
    revoked: false,
    ...overrides,
  };
}

function renderPage(): void {
  render(
    <MemoryRouter initialEntries={['/security']}>
      <SecurityPage />
    </MemoryRouter>,
  );
}

describe('SecurityPage', () => {
  it('SHOWS THE SIGN-INS THE SERVER RECORDED', async () => {
    devicesAre();
    historyIs(
      { at: '2026-09-28T06:05:00Z', method: 'Password only, from a remembered browser', device: 'Chrome on Windows' },
      { at: '2026-09-20T18:40:00Z', method: 'Password and emailed code' },
    );

    renderPage();

    /*
     * The test the old screen could never have passed: its history came from an array that
     * emptied on every page load, so this list was blank however much had happened.
     */
    expect(
      await screen.findByText(/Password only, from a remembered browser · Chrome on Windows/),
    ).toBeInTheDocument();
    /* The second row has no device, and renders with no trailing separator. */
    expect(screen.getByText('Password and emailed code')).toBeInTheDocument();
  });

  it('NEVER CLAIMS WHERE THE CUSTOMER WAS', async () => {
    devicesAre(trustedBrowser());
    historyIs({ at: '2026-09-28T06:05:00Z', method: 'Password and emailed code' });

    renderPage();
    expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument();

    /*
     * THE WHOLE REASON THIS BATCH EXISTS. `sign_ins.location` was the string constant
     * "Kigali, Rwanda" at all four call sites that wrote it, with no geo-IP lookup behind
     * them, and this page printed it against every row — so an intruder in another country
     * rendered identically to the customer themselves.
     *
     * Asserted against the rendered markup, not a field, so re-adding a location anywhere
     * on this screen — a column, a placeholder, a default inside a sentence — fails here.
     */
    expect(document.body.innerHTML).not.toMatch(/Kigali/i);
    expect(document.body.innerHTML).not.toMatch(/Rwanda/i);
  });

  it('DOES NOT PROMISE A HISTORY IT DOES NOT HAVE', async () => {
    devicesAre();
    historyIs({ at: '2026-09-28T06:05:00Z', method: 'Password and emailed code' });

    renderPage();
    await screen.findByText('Password and emailed code');

    /*
     * The panel's subtitle used to read "Sign-ins, failed attempts and changes to your
     * payees". It showed sign-ins. Nothing in the service records a failed attempt, and a
     * heading that overstates a security log is worse than a narrow one: a customer reads
     * a clean list as evidence that nothing else happened.
     */
    expect(screen.queryByText(/failed attempts/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/changes to your payees/i)).not.toBeInTheDocument();
    /* And no ok/failed badge, which nothing could ever set to failed. */
    expect(screen.queryByText(/^failed$/i)).not.toBeInTheDocument();
  });

  it('WILL NOT OFFER TO REMOVE THE BROWSER IN USE', async () => {
    devicesAre(trustedBrowser({ current: true }));
    historyIs();

    renderPage();
    expect(await screen.findByText('This browser')).toBeInTheDocument();

    /*
     * The server refuses this, and the screen must not offer it. Removing the browser you
     * are sitting at works, and then requires an emailed code from a machine the customer
     * may have just told us they no longer control.
     */
    expect(screen.queryByRole('button', { name: /remove/i })).not.toBeInTheDocument();
  });

  it('REMOVES A BROWSER THE CUSTOMER DOES NOT RECOGNISE', async () => {
    devicesAre(trustedBrowser(), trustedBrowser({ id: OTHER_ID, current: true, device: 'Safari on Mac' }));
    historyIs();

    const revoked = vi.fn();
    server.use(
      http.delete(`${API}/security/devices/:id`, ({ params }) => {
        revoked(String(params['id']));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /remove/i }));

    /* The one that is NOT current, which is the only one with a button. */
    await waitFor(() => {
      expect(revoked).toHaveBeenCalledWith(DEVICE_ID);
    });
  });

  it('SHOWS A REMOVED BROWSER AS REMOVED RATHER THAN HIDING IT', async () => {
    devicesAre(trustedBrowser({ revoked: true, revokedReason: 'Removed by the customer' }));
    historyIs();

    renderPage();

    /*
     * V14 keeps revoked rows because "this browser was trusted and then it was not" is the
     * history somebody investigating an unauthorised sign-in needs — and because a
     * customer who just pressed Remove should be able to see that it took. A row that
     * vanishes answers neither.
     */
    expect(await screen.findByText('Removed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /remove/i })).not.toBeInTheDocument();
  });

  it('TELLS THE CUSTOMER THEIR BROWSERS WERE SIGNED OUT', async () => {
    devicesAre(trustedBrowser());
    historyIs();

    server.use(
      http.post(`${API}/security/password`, () => HttpResponse.json({ browsersSignedOut: 2 })),
    );

    renderPage();
    await screen.findByText('Chrome on Windows');

    await userEvent.type(screen.getByLabelText(/current password/i), 'Seeded1Password');
    await userEvent.type(screen.getByLabelText(/^new password$/i), 'Correct-Horse-9-Battery');
    await userEvent.type(
      screen.getByLabelText(/repeat the new password/i),
      'Correct-Horse-9-Battery',
    );
    await userEvent.click(screen.getByRole('button', { name: /change password/i }));

    /*
     * THE COPY THIS REPLACED SAID THE OPPOSITE: "other devices stay signed in until their
     * sessions end", which was true of a feature that did nothing. A password change now
     * revokes every browser that could skip the emailed code — that is the point of doing
     * it — and a customer not told meets a code request on their next sign-in with no
     * explanation for it.
     */
    const success = within(await screen.findByRole('status'));
    expect(
      success.getByText(/2 browsers that could sign in without an emailed code have been signed out/i),
    ).toBeInTheDocument();
    expect(document.body.innerHTML).not.toMatch(/stay signed in/i);
  });

  it('CLEARS THE PASSWORD FIELDS WHETHER IT WORKED OR NOT', async () => {
    devicesAre();
    historyIs();

    server.use(
      http.post(`${API}/security/password`, () =>
        HttpResponse.json(
          {
            timestamp: '2026-09-28T06:05:00Z',
            status: 422,
            code: 'VALIDATION_FAILED',
            message: 'That is not your current password. Check it and type it again.',
            correlationId: 'test',
            fieldErrors: [],
          },
          { status: 422 },
        ),
      ),
    );

    renderPage();

    await userEvent.type(screen.getByLabelText(/current password/i), 'wrong-but-long-enough');
    await userEvent.type(screen.getByLabelText(/^new password$/i), 'Correct-Horse-9-Battery');
    await userEvent.type(
      screen.getByLabelText(/repeat the new password/i),
      'Correct-Horse-9-Battery',
    );
    await userEvent.click(screen.getByRole('button', { name: /change password/i }));

    /*
     * ON FAILURE TOO. A retry means typing it again, deliberately: a password left in
     * component state after a failed attempt is a password waiting to be read out of a
     * heap snapshot.
     */
    await waitFor(() => {
      expect(screen.getByLabelText(/current password/i)).toHaveValue('');
    });
    expect(screen.getByLabelText(/^new password$/i)).toHaveValue('');
  });

  it('SAYS SO WHEN NO BROWSER IS TRUSTED', async () => {
    devicesAre();
    historyIs();

    renderPage();

    /*
     * The empty state says what the absence MEANS — a code every time — rather than "no
     * other devices are signed in", which described sessions this list has never held.
     */
    expect(await screen.findByText(/No browser can skip the emailed code/i)).toBeInTheDocument();
    expect(screen.getByText(/No sign-ins are recorded yet/i)).toBeInTheDocument();
  });
});
