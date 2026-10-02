import { render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { StaffDashboardPage } from './StaffDashboardPage';

/**
 * THE FIRST SCREEN A BANK EMPLOYEE SEES.
 *
 * WHAT IT USED TO BE, and the reason for most of these tests. Below five onboarding figures
 * sat a panel headed "Start over — Development only" with a red "Delete every customer and
 * application" button, under four sentences naming `VITE_LIVE_API`, the `dev`, `h2` and
 * `test` profiles, and the fact that the button used to say "mock bank". Under that came the
 * development mailbox. A bank manager should meet none of it.
 *
 * AND THE NUMBERS WERE THE WRONG NUMBERS. Five onboarding figures while the service has six
 * queues, so a manager saw almost entirely an administrator's work and nothing of their own
 * — no money waiting to be released, no passwords to re-issue, no payees, no cards.
 */

const API = '*/api/v1';

function signedInAs(role: 'ADMIN' | 'MANAGER'): void {
  server.use(
    http.get(`${API}/auth/staff/session`, () =>
      HttpResponse.json({
        id: 'staff-1',
        fullName: 'Immaculee Mukandayisenga',
        email: role === 'ADMIN' ? 'admin@zigama.local' : 'manager@zigama.local',
        role,
        branch: 'Head office',
        lastLoginAt: '2026-10-01T06:10:00Z',
      }),
    ),
  );
}

/** The overview figures, as the real service sends them. */
function summaryIs(overrides: Record<string, number> = {}): void {
  server.use(
    http.get(`${API}/admin/summary`, () =>
      HttpResponse.json({
        submitted: 0,
        awaitingApproval: 0,
        active: 0,
        rejected: 0,
        awaitingFirstSignIn: 0,
        transfersToRelease: 0,
        passwordRequests: 0,
        payeesToCheck: 0,
        cardsAndChequeBooks: 0,
        ...overrides,
      }),
    ),
  );
}

function renderPage(): void {
  render(
    <MemoryRouter initialEntries={['/staff']}>
      <StaffDashboardPage />
    </MemoryRouter>,
  );
}

describe('StaffDashboardPage', () => {
  it('SHOWS NO DEVELOPER CONTENT AT ALL', async () => {
    signedInAs('MANAGER');
    summaryIs({ active: 2 });

    renderPage();
    await screen.findByText(/What needs doing/i);

    /*
     * Asserted against the rendered markup rather than visible text, because the worst of
     * what was here was inside a paragraph and a code element. If any of it comes back —
     * as a panel, a tooltip, a note — this fails.
     */
    const html = document.body.innerHTML;
    for (const developerish of [
      'VITE_LIVE_API',
      'mock bank',
      'Development only',
      'Delete every customer',
      'Start over',
      'profile',
    ]) {
      expect(html).not.toMatch(new RegExp(developerish, 'i'));
    }

    /* And no development mailbox on the landing page. */
    expect(screen.queryByText(/Recent messages/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/what the bank has sent/i)).not.toBeInTheDocument();
  });

  it("PUTS A MANAGER'S OWN WORK FIRST", async () => {
    signedInAs('MANAGER');
    /* Nine registrations — an administrator's job — against one transfer, which is the
       manager's and is money already out of an account. */
    summaryIs({ submitted: 9, transfersToRelease: 1 });

    renderPage();

    const rows = await screen.findAllByRole('listitem');
    /*
     * THE ORDERING IS THE FIX. The old panel had one order for everybody, so a manager read
     * past four administrator figures to reach the one queue where the money is already in
     * flight. Money is not sorted below anything, including a backlog nine times its size.
     */
    expect(within(rows[0]!).getByText('Money to release')).toBeInTheDocument();
  });

  it("PUTS AN ADMINISTRATOR'S OWN WORK FIRST", async () => {
    signedInAs('ADMIN');
    summaryIs({ submitted: 1, transfersToRelease: 9 });

    renderPage();

    const rows = await screen.findAllByRole('listitem');
    /* The same data, the other role: registrations lead, because transfers are not theirs
       to release. The page is one component serving both. */
    expect(
      within(rows[0]!).getByText('Registrations to action'),
    ).toBeInTheDocument();
  });

  it("LABELS WHAT IS SOMEBODY ELSE'S JOB RATHER THAN HIDING IT", async () => {
    signedInAs('MANAGER');
    summaryIs({ submitted: 9 });

    renderPage();
    await screen.findByText('Registrations to action');

    /*
     * A manager who can see nine registrations stuck can go and find an administrator.
     * Hiding the figure would make the backlog somebody else's secret — so it is shown,
     * and labelled so nobody wonders why the link does nothing for them.
     */
    expect(screen.getByText(/An administrator does this one/i)).toBeInTheDocument();
  });

  it('SAYS PLAINLY WHEN THERE IS NOTHING WAITING', async () => {
    signedInAs('MANAGER');
    summaryIs({ active: 2 });

    renderPage();

    /* Every figure behind this sentence is a database count, so the screen can stand
       behind it — unlike "Nothing new", which on a staff queue reads as a fault. */
    expect(
      await screen.findByText(/Nothing is waiting for the bank right now/i),
    ).toBeInTheDocument();
  });

  it('SHOWS EVERY QUEUE THAT HAS SOMETHING IN IT', async () => {
    signedInAs('MANAGER');
    summaryIs({
      transfersToRelease: 2,
      awaitingApproval: 1,
      submitted: 3,
      passwordRequests: 1,
      payeesToCheck: 4,
      cardsAndChequeBooks: 5,
    });

    renderPage();
    await screen.findByText('Money to release');

    /* All six, which is the point: the old panel could only ever show onboarding. */
    for (const queue of [
      'Money to release',
      'Accounts to approve',
      'Registrations to action',
      'Password requests',
      'Payees to check',
      'Cards and cheque books',
    ]) {
      expect(screen.getByText(queue)).toBeInTheDocument();
    }
  });

  it('EXPLAINS THE TEMPORARY-PASSWORD FIGURE AS THE RISK IT IS', async () => {
    signedInAs('ADMIN');
    summaryIs({ active: 5, awaitingFirstSignIn: 3 });

    renderPage();

    /*
     * "Approved, not signed in yet" is a status. What it MEANS is that a temporary password
     * was handed over and nobody has taken ownership of the account — so a figure that
     * stops falling is a set of live credentials sitting on desks.
     */
    expect(
      await screen.findByText(/handed a password, not used yet/i),
    ).toBeInTheDocument();
  });
});
