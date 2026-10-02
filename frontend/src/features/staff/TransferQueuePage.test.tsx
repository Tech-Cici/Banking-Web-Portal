import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { TransferQueuePage } from './TransferQueuePage';

/**
 * THE MANAGER CAN SEE, AND DECIDE, MONEY THAT IS ON HOLD.
 *
 * THE HISTORY. The server grew `/admin/transfers` and its approve and reject endpoints
 * when transfers were built, and no screen ever called them. A customer sent money, was
 * told correctly that it was waiting for a manager, and every staff screen showed an
 * empty queue — so the only honest reading of the portal was that the payment had
 * disappeared, while the debit sat on the sender's statement.
 *
 * So the first test here is the one that would have caught it: a transfer the server
 * returns must appear on this page. The rest guard the decision itself, which moves
 * somebody's money.
 */

const API = '*/api/v1';

const TRANSFER_ID = 'b0c1d2e3-4f56-4789-abcd-ef0123456789';

function signedInAs(role: 'MANAGER' | 'ADMIN'): void {
  server.use(
    http.get(`${API}/auth/staff/session`, () =>
      HttpResponse.json({
        id: 'staff-7',
        fullName: 'Immaculee Mukandayisenga',
        email: 'manager@zigama.local',
        role,
        branch: 'Kigali',
        lastLoginAt: '2026-09-30T06:10:00Z',
      }),
    ),
  );
}

/** One transfer on hold, as the server describes it: masks, and a decimal string. */
function oneTransferWaiting(): void {
  server.use(
    http.get(`${API}/admin/transfers`, () =>
      HttpResponse.json([
        {
          id: TRANSFER_ID,
          sourceMask: '**** 7890',
          destinationMask: '**** 4321',
          amount: '12000',
          currency: 'RWF',
          reference: 'school fees',
          submittedAt: '2026-09-30T08:20:00Z',
        },
      ]),
    ),
  );
}

function renderPage(): void {
  render(
    <MemoryRouter initialEntries={['/staff/transfers']}>
      <TransferQueuePage />
    </MemoryRouter>,
  );
}

describe('TransferQueuePage', () => {
  it('SHOWS A TRANSFER THAT IS WAITING FOR A MANAGER', async () => {
    signedInAs('MANAGER');
    oneTransferWaiting();

    renderPage();

    /*
     * The amount is asserted as the FORMATTED figure, not as the raw string the server
     * sent. A manager deciding on somebody's money reads "RWF 12,000"; if this screen
     * ever printed the bare "12000" beside a currency code the test should fail, because
     * a misread magnitude is the whole risk on this page.
     */
    expect(await screen.findByText('RWF 12,000')).toBeInTheDocument();
    expect(screen.getByText('school fees')).toBeInTheDocument();
    expect(screen.getByText('**** 7890')).toBeInTheDocument();
    expect(screen.getByText('**** 4321')).toBeInTheDocument();
  });

  it('SAYS THE QUEUE IS EMPTY WITHOUT CLAIMING NOBODY SENT ANYTHING', async () => {
    signedInAs('MANAGER');
    server.use(http.get(`${API}/admin/transfers`, () => HttpResponse.json([])));

    renderPage();

    expect(await screen.findByText(/nobody's money is on hold/i)).toBeInTheDocument();
  });

  it('APPROVES A TRANSFER AND SAYS WHERE THE MONEY WENT', async () => {
    signedInAs('MANAGER');
    oneTransferWaiting();

    let approvedId: string | null = null;

    server.use(
      http.post(`${API}/admin/transfers/:id/approve`, ({ params }) => {
        approvedId = String(params.id);
        return HttpResponse.json({
          id: TRANSFER_ID,
          sourceMask: '**** 7890',
          destinationMask: '**** 4321',
          amount: '12000',
          currency: 'RWF',
          reference: 'school fees',
          submittedAt: '2026-09-30T08:20:00Z',
        });
      }),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /review/i }));
    await userEvent.click(
      await screen.findByRole('button', { name: /approve and release the money/i }),
    );

    await waitFor(() => {
      expect(approvedId).toBe(TRANSFER_ID);
    });

    /*
     * "has reached" rather than "was approved". The credit is posted before the response
     * comes back, so the arrival is a fact by the time this renders — and the sender's
     * screen has been careful all along never to claim arrival before it happened.
     */
    expect(await screen.findByText(/RWF 12,000 has reached \*\*\*\* 4321/)).toBeInTheDocument();
  });

  it('WILL NOT REFUSE A TRANSFER WITHOUT A REASON', async () => {
    signedInAs('MANAGER');
    oneTransferWaiting();

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /review/i }));
    await userEvent.click(await screen.findByRole('button', { name: /^reject$/i }));

    /*
     * The reason reaches the customer, and the server rejects a blank one. A screen that
     * let the request leave would turn a required field into a round trip and an error
     * the manager has to interpret.
     */
    const confirm = await screen.findByRole('button', {
      name: /confirm refusal and return the money/i,
    });
    expect(confirm).toBeDisabled();

    await userEvent.type(
      screen.getByLabelText(/why is this being refused/i),
      'Beneficiary details do not match our records.',
    );
    expect(confirm).toBeEnabled();
  });

  it('DISABLES THE DECISION FOR AN ADMINISTRATOR', async () => {
    signedInAs('ADMIN');
    oneTransferWaiting();

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /review/i }));

    /*
     * An admin may look — seeing what is on hold is part of answering a telephone — but
     * releasing money is a manager's signature. The server refuses them as well; this
     * asserts the screen does not invite the attempt.
     */
    expect(
      await screen.findByRole('button', { name: /approve and release the money/i }),
    ).toBeDisabled();
    expect(screen.getByText(/only a manager can release money/i)).toBeInTheDocument();
  });
});
