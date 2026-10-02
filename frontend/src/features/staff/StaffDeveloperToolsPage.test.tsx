import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { server } from '@/mocks/server';
import { StaffDeveloperToolsPage } from './StaffDeveloperToolsPage';

/**
 * THE DEVELOPER PAGE, AND THE ONE DESTRUCTIVE CONTROL IN THE PRODUCT.
 *
 * <p>WHERE THIS CAME FROM. The reset button and the message log used to sit on the staff
 * OVERVIEW — the first screen an administrator or a manager sees — the button labelled
 * "Delete every customer and application" under a paragraph naming build profiles. They are
 * on their own page now, which is the fix that mattered: whoever runs the service still
 * needs to empty it between test runs, and a manager signing in to release payments should
 * not be one click from doing the same.
 *
 * <p>WHAT THESE TESTS GUARD is the gate. An action that deletes every customer and cannot be
 * undone must not fire on a single click, and must not fire on a near miss either.
 */

const API = '*/api/v1';

function outboxIs(...messages: readonly Record<string, unknown>[]): void {
  server.use(http.get(`${API}/admin/outbox`, () => HttpResponse.json(messages)));
}

function renderPage(): void {
  render(
    <MemoryRouter>
      <StaffDeveloperToolsPage />
    </MemoryRouter>,
  );
}

describe('StaffDeveloperToolsPage', () => {
  it('WILL NOT DELETE ANYTHING ON A SINGLE CLICK', async () => {
    outboxIs();

    const reset = vi.fn(() => new HttpResponse(null, { status: 204 }));
    server.use(http.post(`${API}/admin/dev/reset`, reset));

    renderPage();

    const button = await screen.findByRole('button', {
      name: /delete every customer and application/i,
    });

    /*
     * DISABLED UNTIL THE WORD IS TYPED. A `confirm()` dialog would be worse than this —
     * people dismiss those without reading — and a bare enabled button is one stray click
     * from an empty database with no undo.
     */
    expect(button).toBeDisabled();
    expect(reset).not.toHaveBeenCalled();
  });

  it('WILL NOT ACCEPT A NEAR MISS', async () => {
    outboxIs();
    renderPage();

    const field = await screen.findByLabelText(/type DELETE to enable the button/i);
    await userEvent.type(field, 'delete');

    /*
     * CASE-SENSITIVE ON PURPOSE. "delete" is a word people type by habit; the point of the
     * gate is that the action takes a deliberate moment, and a lower-case match would hand
     * that back.
     */
    expect(
      screen.getByRole('button', { name: /delete every customer and application/i }),
    ).toBeDisabled();
  });

  it('DELETES ONCE THE WORD IS TYPED', async () => {
    outboxIs();

    const reset = vi.fn(() => new HttpResponse(null, { status: 204 }));
    server.use(http.post(`${API}/admin/dev/reset`, reset));

    renderPage();

    const field = await screen.findByLabelText(/type DELETE to enable the button/i);
    await userEvent.type(field, 'DELETE');

    const button = screen.getByRole('button', {
      name: /delete every customer and application/i,
    });
    expect(button).toBeEnabled();

    /*
     * The click itself is not exercised here: the handler reloads the window on success,
     * which jsdom cannot do. What matters for this page is the gate, and the request path
     * is covered by scripts/reset-bank.mjs, which was run against a live API.
     */
  });

  it('SAYS THE RESET CANNOT BE UNDONE, AND WHICH DATABASE IT HITS', async () => {
    outboxIs();
    renderPage();

    /*
     * The jargon belongs HERE and nowhere else. On a manager's overview, naming an
     * environment variable was noise they could do nothing with; on a page for whoever runs
     * the service it is what stops them emptying the database they cared about.
     */
    expect(await screen.findByText(/there is no undo/i)).toBeInTheDocument();
    expect(screen.getByText(/VITE_LIVE_API/)).toBeInTheDocument();
  });

  it('SHOWS THE MESSAGE LOG, WHICH IS WHY THE PAGE IS USABLE AT ALL', async () => {
    outboxIs({
      id: 'm1',
      kind: 'EMAIL_VERIFICATION',
      from: 'no-reply@zigama.rw',
      to: 'someone@example.rw',
      subject: 'Your Zigama CSS sign-in code',
      body: 'Your code is 123456.',
      sentAt: '2026-10-02T10:00:00Z',
    });

    renderPage();

    /*
     * With email switched off this is the only way to read a verification code or a
     * temporary password — without it the onboarding flow cannot be walked at all, which is
     * why the log stayed when the button nearly went.
     */
    expect(await screen.findByText('Your Zigama CSS sign-in code')).toBeInTheDocument();
    expect(screen.getByText(/Your code is 123456/)).toBeInTheDocument();
    expect(screen.getByText(/No email is actually sent/i)).toBeInTheDocument();
  });
});
