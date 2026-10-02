import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { server } from '@/mocks/server';
import { ServiceRequestsPage } from './ServiceRequestsPage';

/**
 * STAFF CAN SEE, AND FULFIL, WHAT CUSTOMERS HAVE ASKED FOR.
 *
 * THE HISTORY, which is why the first test is the one it is. Both request forms posted to
 * an endpoint only the browser's own mock answered, and that mock pushed the row onto an
 * array which starts empty on every page load. The request reached nobody. No staff screen
 * existed. The customer was shown a reference that stopped existing when they refreshed,
 * told the thing would take about five working days, and sent to a branch chosen from a
 * list of six the front end had invented.
 *
 * So the tests here are, in order: a request the server returns must APPEAR; the collection
 * point must be TYPED and must reach the server; and the screen must never print a place
 * the bank has not given it.
 */

const API = '*/api/v1';

const CARD_ID = '7c9e6679-7425-40de-944b-e07fc1f90ae7';
const BOOK_ID = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';

function signedInAsStaff(): void {
  server.use(
    http.get(`${API}/auth/staff/session`, () =>
      HttpResponse.json({
        id: 'staff-3',
        fullName: 'Immaculee Mukandayisenga',
        email: 'manager@zigama.local',
        role: 'MANAGER',
        branch: 'Kigali',
        lastLoginAt: '2026-09-30T06:10:00Z',
      }),
    ),
  );
}

/**
 * The queue as the server describes it.
 *
 * <p>NOTE WHAT IS ABSENT from the SUBMITTED row: `collectionPoint`. Absent, not null — the
 * service sets `default-property-inclusion: non_null`, so a request nobody has produced yet
 * has no such key at all. A fixture that sent `collectionPoint: null` would be testing a
 * response shape the server never emits, and would hide exactly the bug this screen exists
 * to prevent.
 */
function queueHolds(...rows: readonly Record<string, unknown>[]): void {
  server.use(http.get(`${API}/admin/service-requests`, () => HttpResponse.json(rows)));
}

function cardToMake(): Record<string, unknown> {
  return {
    id: CARD_ID,
    reference: 'CRD-K4MZ7P',
    customerId: 'cust-1',
    customerNumber: 'ZG3100001',
    customerName: 'Ciara Teta Muzora',
    email: 'teta@example.rw',
    requestType: 'CARD',
    details: 'Debit card for **** 7890',
    accountMask: '**** 7890',
    status: 'SUBMITTED',
    submittedAt: '2026-09-28T08:20:00Z',
  };
}

function bookWaitingToBeCollected(): Record<string, unknown> {
  return {
    id: BOOK_ID,
    reference: 'CHQ-TB29VX',
    customerId: 'cust-2',
    customerNumber: 'ZG3100002',
    customerName: 'Jean Claude Nkurunziza',
    email: 'jc@example.rw',
    requestType: 'CHEQUE_BOOK',
    details: 'Cheque book, 50 leaves, for **** 4321',
    accountMask: '**** 4321',
    status: 'READY',
    submittedAt: '2026-09-29T09:00:00Z',
    collectionPoint: 'Head office counter 3',
  };
}

function renderPage(): void {
  render(
    <MemoryRouter initialEntries={['/staff/requests']}>
      <ServiceRequestsPage />
    </MemoryRouter>,
  );
}

describe('ServiceRequestsPage', () => {
  it('SHOWS A REQUEST THAT IS WAITING FOR THE BANK', async () => {
    signedInAsStaff();
    queueHolds(cardToMake());

    renderPage();

    /*
     * The test that would have caught the original hole. Before this screen existed a
     * customer could ask for a card and every staff screen showed nothing.
     */
    expect(await screen.findByText('Debit card for **** 7890')).toBeInTheDocument();
    expect(screen.getByText('CRD-K4MZ7P')).toBeInTheDocument();
    expect(screen.getByText('Ciara Teta Muzora')).toBeInTheDocument();
  });

  it('SENDS THE TYPED COLLECTION POINT, AND WILL NOT SUBMIT WITHOUT ONE', async () => {
    signedInAsStaff();
    queueHolds(cardToMake());

    const sent = vi.fn();
    server.use(
      http.post(`${API}/admin/service-requests/:id/ready`, async ({ params, request }) => {
        sent({ id: String(params['id']), body: await request.json() });
        return HttpResponse.json({ ...cardToMake(), status: 'READY', collectionPoint: 'Huye counter 2' });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /mark ready/i }));

    const submit = screen.getByRole('button', { name: /mark ready and email the customer/i });
    /*
     * DISABLED UNTIL SOMEBODY TYPES A PLACE. The server rejects a blank one, but a screen
     * that offers the button and then shows an error has already let a member of staff
     * believe the customer was emailed.
     */
    expect(submit).toBeDisabled();

    await userEvent.type(
      screen.getByLabelText(/where should the customer collect it/i),
      'Huye counter 2',
    );
    await userEvent.click(submit);

    await waitFor(() => {
      expect(sent).toHaveBeenCalledWith({
        id: CARD_ID,
        body: { collectionPoint: 'Huye counter 2' },
      });
    });
  });

  it('NEVER NAMES A PLACE THE BANK HAS NOT GIVEN IT', async () => {
    signedInAsStaff();
    queueHolds(cardToMake(), bookWaitingToBeCollected());

    renderPage();
    expect(await screen.findByText('Debit card for **** 7890')).toBeInTheDocument();

    /* Opened, so the collection-point field itself is in the document and not just the
       table — the field is the likeliest place for one of these to come back. */
    await userEvent.click(screen.getAllByRole('button', { name: /mark ready/i })[0] as Element);
    expect(screen.getByLabelText(/where should the customer collect it/i)).toHaveValue('');

    /*
     * THE SIX INVENTED BRANCHES, asserted against the rendered MARKUP rather than against
     * visible text. This screen replaced a form that offered them as a dropdown, and the
     * ways one could come back are mostly invisible to `getByText`: a placeholder, an
     * option value, a pre-filled default, a title attribute. Matching the markup catches
     * all of those. It is a blunt assertion on purpose — the requirement really is that
     * these words appear nowhere at all.
     */
    for (const invented of ['Nyarugenge', 'Kimironko', 'Remera', 'Musanze', 'Rubavu', 'Huye']) {
      expect(document.body.innerHTML).not.toMatch(new RegExp(invented, 'i'));
    }

    /* What staff DID type is shown, because that one is the bank's own answer. */
    await userEvent.click(screen.getByRole('button', { name: /^close$/i }));
    expect(screen.getByText(/at Head office counter 3/)).toBeInTheDocument();
  });

  it('ASKS FOR IDENTIFICATION BEFORE RECORDING A HAND-OVER', async () => {
    signedInAsStaff();
    queueHolds(bookWaitingToBeCollected());

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /hand over/i }));

    /*
     * SAID EVERY TIME, not once in a training note: the reference travels by email and is
     * six characters long, so handing the thing to whoever quotes it is the whole attack.
     * The customer's number is in the warning because that is what staff check against.
     */
    /*
     * SCOPED TO THE WARNING, which is the point of the assertion. The customer's number is
     * also in the table row and in the summary panel, so an unscoped query passes even if
     * the warning never names anybody — and a warning that says "check identification"
     * without saying against whom is the one that gets skimmed.
     */
    const warning = within(screen.getByRole('alert'));
    expect(warning.getByText(/check photo identification/i)).toBeInTheDocument();
    expect(warning.getByText('ZG3100002')).toBeInTheDocument();
    expect(warning.getByText('Jean Claude Nkurunziza')).toBeInTheDocument();
  });

  it('DOES NOT OFFER TO MARK A READY REQUEST READY AGAIN', async () => {
    signedInAsStaff();
    queueHolds(bookWaitingToBeCollected());

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /hand over/i }));

    /*
     * The server refuses a second READY — two staff members would otherwise overwrite each
     * other's collection point, after the customer had been emailed the first one. The
     * screen must not offer the action that produces that conflict.
     */
    expect(
      screen.queryByRole('button', { name: /mark ready and email the customer/i }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText(/where should the customer collect it/i),
    ).not.toBeInTheDocument();
  });

  it('REQUIRES A REASON TO DECLINE', async () => {
    signedInAsStaff();
    queueHolds(cardToMake());

    const sent = vi.fn();
    server.use(
      http.post(`${API}/admin/service-requests/:id/decline`, async ({ request }) => {
        sent(await request.json());
        return HttpResponse.json({ ...cardToMake(), status: 'DECLINED' });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /mark ready/i }));
    await userEvent.click(screen.getByRole('button', { name: /decline this request/i }));

    const confirm = screen.getByRole('button', { name: /decline and email the reason/i });
    /* "The bank said no" guarantees a telephone call; a reason is often something the
       customer can put right themselves. */
    expect(confirm).toBeDisabled();

    await userEvent.type(
      screen.getByLabelText(/why are you declining this/i),
      'The account was closed before the card was printed.',
    );
    await userEvent.click(confirm);

    await waitFor(() => {
      expect(sent).toHaveBeenCalledWith({
        reason: 'The account was closed before the card was printed.',
      });
    });
  });

  it('SAYS SO WHEN THERE IS NOTHING TO DO', async () => {
    signedInAsStaff();
    queueHolds();

    renderPage();

    expect(await screen.findByText(/nothing has been asked for/i)).toBeInTheDocument();
  });
});
