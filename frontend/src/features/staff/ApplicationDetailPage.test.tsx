import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { ApplicationDetailPage } from './ApplicationsPage';

/**
 * WHAT THIS SCREEN OFFERS, PER KIND OF APPLICATION — and it must match what the server
 * will accept, not approximate it.
 *
 * THE HISTORY, because both mistakes are easy to make again.
 *
 * First the screen offered the account form for every kind. An administrator read a
 * company's details, typed an account number, chose a type, typed an opening balance of
 * 9,000,000, pressed the button, and was told "We could not create that account" — after
 * all of it, for a reason that was never about anything they had entered.
 *
 * Then the form was withheld from every non-personal application, which was right while
 * the service could only build a personal login and wrong the moment it could build a
 * company. The administrator was left reading a company's details on a screen whose only
 * action had quietly disappeared.
 *
 * So all three cases are asserted: a company gets the form, a personal customer gets the
 * form, and joining an existing business — which the service still cannot do — gets an
 * explanation instead. Any one of these alone passes for a guard that is stuck open or
 * stuck shut.
 */

const API = '*/api/v1';

type Kind = 'PERSONAL' | 'BUSINESS' | 'JOIN_BUSINESS';

const COMPANY_NAME = 'Inyange Industries Ltd';

function signedInAsAdmin(): void {
  server.use(
    http.get(`${API}/auth/staff/session`, () =>
      HttpResponse.json({
        id: 'staff-1',
        fullName: 'Ella Uwajuru Singizwa',
        email: 'admin@zigama.local',
        role: 'ADMIN',
        branch: 'Kigali',
        lastLoginAt: '2026-09-27T18:42:00Z',
      }),
    ),
  );
}

/** One application, of whichever kind, waiting for an administrator. */
function applicationOfKind(kind: Kind): void {
  const personal = kind === 'PERSONAL';

  server.use(
    http.get(`${API}/admin/applications/:id`, () =>
      HttpResponse.json({
        id: '7089f189-35a9-46a0-8b21-c0351e638db7',
        reference: personal ? 'REG-DD7FD302' : 'BRA-MUKZ74U5',
        kind,
        status: 'SUBMITTED',
        displayName: personal ? 'Ciara Teta MUZORA' : COMPANY_NAME,
        email: 'contact@example.rw',
        phone: '0781999888',
        emailVerified: true,
        submittedAt: '2026-09-28T05:54:00Z',
        details: personal
          ? []
          : [
              { label: 'TIN', value: '104857392' },
              { label: 'Signatory 1', value: 'WIBABARA Justine · FINANCE_MANAGER' },
            ],
      }),
    ),
  );
}

function renderPage(): void {
  render(
    <MemoryRouter initialEntries={['/staff/applications/7089f189-35a9-46a0-8b21-c0351e638db7']}>
      <Routes>
        <Route path="/staff/applications/:id" element={<ApplicationDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ApplicationDetailPage', () => {
  it('OFFERS THE ACCOUNT FORM FOR A COMPANY APPLICATION', async () => {
    signedInAsAdmin();
    applicationOfKind('BUSINESS');
    renderPage();

    await waitFor(() => {
      expect(screen.getByLabelText(/account 1 number/i)).toBeInTheDocument();
    });

    expect(
      screen.getByRole('button', { name: /create the company and its first login/i }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/opening balance/i)).toBeInTheDocument();

    // The company's own details are still there to check the accounts against.
    expect(screen.getByText('104857392')).toBeInTheDocument();
  });

  it('SAYS THE ACCOUNTS BELONG TO THE COMPANY AND THE CONTACT GETS THE LOGIN', async () => {
    signedInAsAdmin();
    applicationOfKind('BUSINESS');
    renderPage();

    /*
     * The substance of the corporate case, not decoration. An administrator typing an
     * account number into this form is entering a COMPANY's account against a person's
     * name, and if the screen does not say so the reasonable reading is that they are
     * giving the contact a personal account — which is what the old, wrong version of
     * this flow would actually have done.
     */
    await waitFor(() => {
      expect(screen.getByText(/belong to the company, not to any one person/i)).toBeInTheDocument();
    });

    /*
     * getAllBy, because the phrase sits inside a paragraph that is itself inside the
     * alert — both elements match a text query, and asserting on "exactly one" would be
     * asserting on the markup rather than on what the administrator reads.
     */
    expect(screen.getAllByText(/first administrator/i).length).toBeGreaterThan(0);

    /*
     * And the limit, stated on the screen where somebody would otherwise assume
     * otherwise: the other signatories on the application are not being given anything.
     * Leaving that out is how a company ends up believing three people can approve
     * payments when one can.
     */
    expect(screen.getAllByText(/other signatories listed above get/i).length).toBeGreaterThan(0);
  });

  it('offers the form for a personal application, with the personal wording', async () => {
    signedInAsAdmin();
    applicationOfKind('PERSONAL');
    renderPage();

    await waitFor(() => {
      expect(screen.getByLabelText(/account 1 number/i)).toBeInTheDocument();
    });

    expect(
      screen.getByRole('button', { name: /create the login and assign these accounts/i }),
    ).toBeInTheDocument();

    // None of the company copy leaks onto a personal application.
    expect(screen.queryByText(/belong to the company/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/corporate banking team/i)).not.toBeInTheDocument();
  });

  it('WITHHOLDS THE FORM FOR JOINING AN EXISTING BUSINESS, AND SAYS WHERE TO GO', async () => {
    signedInAsAdmin();
    applicationOfKind('JOIN_BUSINESS');
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/corporate banking team/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/stays in the queue/i)).toBeInTheDocument();

    /*
     * Absent, not disabled. A disabled button still tells an administrator this is the
     * screen where it happens, and they would reasonably go looking for what to fill in
     * first.
     */
    expect(screen.queryByLabelText(/account 1 number/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /add another account/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /create the/i })).not.toBeInTheDocument();
  });
});
