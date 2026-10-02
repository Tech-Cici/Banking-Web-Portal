import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { CardsPanel } from './CardsPanel';

/**
 * THE DASHBOARD HAS TO ANSWER "WHAT HAPPENED TO MY CARD REQUEST?"
 *
 * THE REPORT THAT PRODUCED THIS TEST. A customer asked for a card. Staff marked it ready
 * and typed the counter. The email arrived, quoting the reference and the collection point.
 * The dashboard's Cards panel still said <em>You have no cards yet.</em>
 *
 * THAT SENTENCE WAS TRUE AND USELESS. The portal has no card-issuing connection, so there
 * genuinely was no card to list — the panel was answering a question nobody had asked. What
 * the customer wanted was the request, which this panel had no call for and no row for.
 *
 * So the first test is the reported case, and the second guards the sentence that used to
 * be the only thing on the panel.
 */

const API = '*/api/v1';

function cardsAre(...rows: readonly Record<string, unknown>[]): void {
  server.use(http.get(`${API}/cards`, () => HttpResponse.json(rows)));
}

function requestsAre(...rows: readonly Record<string, unknown>[]): void {
  server.use(http.get(`${API}/service-requests`, () => HttpResponse.json(rows)));
}

/**
 * A ready card request, as the real service sends it.
 *
 * NOTE WHAT IS ABSENT on the submitted variant below: `collectionPoint`. Absent, not null —
 * the service omits null fields, and a request nobody has produced yet has no such key.
 */
function readyCard(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'c1e7a2b4-5d6f-4081-92a3-b4c5d6e7f809',
    reference: 'CRD-GEQVMB',
    requestType: 'CARD',
    details: 'Debit card for **** 7890',
    status: 'READY',
    submittedAt: '2026-10-01T09:05:00Z',
    collectionPoint: 'Remera branch',
    ...overrides,
  };
}

function renderPanel(): void {
  render(
    <MemoryRouter>
      <CardsPanel scopeKey="test" />
    </MemoryRouter>,
  );
}

describe('CardsPanel', () => {
  it('SHOWS A READY CARD REQUEST EVEN THOUGH THERE IS NO CARD', async () => {
    cardsAre();
    requestsAre(readyCard());

    renderPanel();

    /*
     * The reported bug, as an assertion. Before this the panel made one call, to /cards,
     * and rendered the empty state — so a card sitting on a counter with the customer's
     * name on it was invisible on the page they look at first.
     */
    expect(await screen.findByText('Debit card for **** 7890')).toBeInTheDocument();
    expect(screen.getByText('CRD-GEQVMB')).toBeInTheDocument();
    expect(screen.getByText(/Ready to collect at Remera branch/)).toBeInTheDocument();
    expect(screen.queryByText(/You have no cards yet/i)).not.toBeInTheDocument();
  });

  it('STILL SAYS SO WHEN THERE IS GENUINELY NOTHING', async () => {
    cardsAre();
    requestsAre();

    renderPanel();

    /*
     * The empty state has to survive. "You have no cards yet" is the right answer when the
     * customer has neither a card nor a request, and the fix must not replace a true
     * sentence with a permanently-populated panel.
     */
    expect(await screen.findByText(/You have no cards yet/i)).toBeInTheDocument();
  });

  it('NAMES NO PLACE UNTIL STAFF HAVE TYPED ONE', async () => {
    cardsAre();
    requestsAre(readyCard({ status: 'SUBMITTED', collectionPoint: undefined }));

    renderPanel();
    expect(await screen.findByText('CRD-GEQVMB')).toBeInTheDocument();

    /*
     * THE SIX INVENTED BRANCHES, asserted against the rendered markup. This panel sits next
     * to a flow that used to offer them as a dropdown; a default slipping back in here as a
     * placeholder would send somebody across Kigali for a card that is not there.
     */
    for (const invented of ['Nyarugenge', 'Kimironko', 'Remera', 'Musanze', 'Rubavu', 'Huye']) {
      expect(document.body.innerHTML).not.toMatch(new RegExp(invented, 'i'));
    }
    expect(screen.getByText(/The bank is making it/)).toBeInTheDocument();
  });

  it('DROPS A COLLECTED REQUEST, BECAUSE IT IS A CARD NOW', async () => {
    cardsAre({
      id: 'card-1',
      maskedPan: '**** 4321',
      brand: 'Visa',
      cardType: 'DEBIT',
      status: 'ACTIVE',
      linkedAccountMask: '**** 7890',
      expiryMonth: 7,
      expiryYear: 2029,
    });
    requestsAre(readyCard({ status: 'COLLECTED' }));

    renderPanel();

    /* Listed above as a card. Showing the request too would be the same card twice. */
    expect(await screen.findByText('**** 4321')).toBeInTheDocument();
    expect(screen.queryByText('CRD-GEQVMB')).not.toBeInTheDocument();
  });

  it('SHOWS A DECLINED REQUEST WITH THE REASON', async () => {
    cardsAre();
    requestsAre(
      readyCard({
        status: 'DECLINED',
        collectionPoint: undefined,
        declineReason: 'The account was closed before the card was printed.',
      }),
    );

    renderPanel();

    /*
     * The reason, not just the status. The customer is usually the only party who can act
     * on it, and a bare "Declined" on a dashboard guarantees a telephone call.
     */
    expect(
      await screen.findByText('The account was closed before the card was printed.'),
    ).toBeInTheDocument();
  });
});
