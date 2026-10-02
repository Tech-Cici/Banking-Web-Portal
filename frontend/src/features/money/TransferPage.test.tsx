import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { SessionProvider } from '@/contexts/SessionProvider';
import { server } from '@/mocks/server';
import { TransferPage } from './TransferPage';

/**
 * AN UNAPPROVED PAYEE MUST NOT BE OFFERED AS A DESTINATION.
 *
 * <p>THE BUG THIS EXISTS FOR, because it was live and it was the dangerous kind. The payee
 * dropdown on this screen filtered on the payee's TYPE and nothing else, so a payee that
 * bank staff had not checked appeared in it and could be paid — on the one screen in the
 * portal that moves money. The standing-order screen beside it filtered correctly, which is
 * what made it look deliberate. Meanwhile the customer was being told, on three separate
 * screens, that a new payee is held before it can be paid.
 *
 * <p>That combination is worse than either half. A customer who reads "your payee is being
 * checked" and then finds they can pay it concludes the warning is decorative — and the
 * whole purpose of holding a new payee is to interrupt "add this account and send the money
 * now", which is the script behind most authorised-push-payment fraud.
 *
 * <p>THIS FILTER IS NOT THE CONTROL and these tests do not claim it is. The server refuses
 * an unapproved payee in `BeneficiaryService.destinationFor`, and `BeneficiaryApprovalTest`
 * is where that is proved. What is tested here is that the customer is not offered a choice
 * the server would refuse, and — just as important — that the screen explains itself rather
 * than looking broken.
 */

const API = '*/api/v1';

const ACTIVE_PAYEE = {
  id: 'ben-active',
  name: 'Teta Eliana',
  beneficiaryType: 'INTERNAL',
  provider: 'Same bank',
  maskedDestination: '**** 8902',
  currency: 'RWF',
  status: 'ACTIVE',
};

const WAITING_PAYEE = {
  id: 'ben-waiting',
  name: 'Patrick Habimana',
  beneficiaryType: 'INTERNAL',
  provider: 'Same bank',
  maskedDestination: '**** 4417',
  currency: 'RWF',
  status: 'PENDING_VERIFICATION',
};

const REFUSED_PAYEE = {
  id: 'ben-refused',
  name: 'Somebody Else',
  beneficiaryType: 'INTERNAL',
  provider: 'Same bank',
  maskedDestination: '**** 1200',
  currency: 'RWF',
  status: 'REFUSED',
  refusedReason: 'The name does not match this account.',
};

function payeesAre(...payees: readonly unknown[]): void {
  server.use(http.get(`${API}/beneficiaries`, () => HttpResponse.json(payees)));
}

/**
 * A signed-in customer with one account.
 *
 * The REAL `SessionProvider` is used, fed by a mocked `/session` — not a hand-rolled fake
 * context. A stub provider would let this file keep passing after a change to how the
 * session is shaped, which is exactly the kind of drift a screen test should catch.
 */
function signedIn(): void {
  server.use(
    http.get(`${API}/session`, () =>
      HttpResponse.json({
        user: {
          id: 'cust-1',
          fullName: 'Ciara Teta Muzora',
          email: 'ciara@example.rw',
          userType: 'RETAIL',
          permissions: ['RETAIL_ACCOUNT_VIEW', 'TRANSFER_CREATE', 'BENEFICIARY_MANAGE'],
          corporates: [],
        },
        activeCorporateId: null,
        mustChangePassword: false,
      }),
    ),
    http.get(`${API}/accounts`, () =>
      HttpResponse.json([
        {
          id: 'acc-1',
          nickname: 'Current account',
          accountType: 'CURRENT',
          maskedNumber: '**** 0192',
          currency: 'RWF',
          status: 'ACTIVE',
        },
      ]),
    ),
  );
}

function renderInternalTransfer(): void {
  signedIn();
  render(
    <MemoryRouter>
      <SessionProvider>
        <TransferPage kind="INTERNAL" />
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('TransferPage payee list', () => {
  it('DOES NOT OFFER A PAYEE THAT IS STILL BEING CHECKED', async () => {
    payeesAre(ACTIVE_PAYEE, WAITING_PAYEE);
    renderInternalTransfer();

    /* The approved one is there... */
    await waitFor(() => {
      expect(screen.getByRole('option', { name: /Teta Eliana/ })).toBeInTheDocument();
    });

    /*
     * ...and the waiting one is not an option at all, rather than being present and
     * disabled. A disabled option still tells somebody this is the screen where that payee
     * gets paid, and invites them to find out why it will not select.
     */
    expect(screen.queryByRole('option', { name: /Patrick Habimana/ })).not.toBeInTheDocument();
  });

  it('DOES NOT OFFER A PAYEE THE BANK REFUSED', async () => {
    payeesAre(ACTIVE_PAYEE, REFUSED_PAYEE);
    renderInternalTransfer();

    await waitFor(() => {
      expect(screen.getByRole('option', { name: /Teta Eliana/ })).toBeInTheDocument();
    });
    expect(screen.queryByRole('option', { name: /Somebody Else/ })).not.toBeInTheDocument();
  });

  it('SAYS THE PAYEE IS BEING CHECKED rather than claiming there are none', async () => {
    /*
     * THE FAILURE THIS CATCHES IS A WORDING ONE, and it matters. With only a waiting payee
     * the list is empty, and the old empty state said "You have no saved payees" with a
     * link to add one. Somebody who added a payee an hour ago would add it again — and be
     * refused as a duplicate, with no explanation of where the first one went.
     */
    payeesAre(WAITING_PAYEE);
    renderInternalTransfer();

    /*
     * The panel's TITLE specifically. A loose /still being checked/ matches the heading and
     * the sentence under it, and a two-element match is an error rather than a pass.
     */
    expect(await screen.findByText(/^Your payee is still being checked$/i)).toBeInTheDocument();
    expect(screen.getByText(/cannot be paid yet/i)).toBeInTheDocument();
    expect(screen.queryByText(/no saved payees/i)).not.toBeInTheDocument();
  });

  it('STILL SAYS THERE ARE NONE when there really are none', async () => {
    payeesAre();
    renderInternalTransfer();

    expect(await screen.findByText(/no saved payees/i)).toBeInTheDocument();
    /*
     * And the wording describes the real control. It used to promise a "cooling-off
     * period", which is a clock; nothing anywhere ran one, and what actually happens is
     * that a member of staff looks at it.
     */
    expect(screen.getByText(/member of\s+staff checks a new payee/i)).toBeInTheDocument();
  });

  it('NEVER PROMISES A COOLING-OFF PERIOD, because there is no clock', async () => {
    payeesAre(ACTIVE_PAYEE, WAITING_PAYEE, REFUSED_PAYEE);
    renderInternalTransfer();

    await waitFor(() => {
      expect(screen.getByRole('option', { name: /Teta Eliana/ })).toBeInTheDocument();
    });

    /*
     * A WORDING ASSERTION ON PURPOSE, and the same one as in the test above from the other
     * side. "Cooling-off period" told customers to wait for time to pass; staff approval is
     * what actually releases a payee, and a customer waiting for the wrong thing is a
     * customer who telephones.
     */
    expect(document.body.textContent).not.toMatch(/cooling.off/i);
  });
});
