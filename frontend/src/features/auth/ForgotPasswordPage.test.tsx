import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { authService } from '@/services';
import { ForgotPasswordPage } from './ForgotPasswordPage';

/**
 * THE PROPERTY UNDER TEST IS THAT THIS SCREEN CANNOT TELL YOU WHO BANKS HERE.
 *
 * The server answers 202 with an empty body for every address, so the only way to leak
 * which ones are real is in the browser — a different heading, a different tone, an extra
 * sentence, a success state for one and an error for the other. That makes this a test of
 * the WORDING, which is unusual and is the point: the security control on this page is
 * what it says.
 *
 * <p>The second test is the opposite failure. A screen that reports success when nothing
 * reached the server leaves a customer waiting for an email that is never coming, and
 * "always show success" is the obvious way to implement the first property badly.
 */
describe('ForgotPasswordPage', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function renderPage(): ReturnType<typeof userEvent.setup> {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>,
    );
    return user;
  }

  /** Submits an address and returns what the screen then says, with the address masked. */
  async function askAbout(address: string): Promise<string> {
    const user = renderPage();
    await user.type(screen.getByRole('textbox', { name: /email address/i }), address);
    await user.click(screen.getByRole('button', { name: /ask for a new password/i }));

    await waitFor(() => {
      expect(screen.getByText(/passed this to the bank/i)).toBeInTheDocument();
    });

    /*
     * The address itself is replaced before comparing. Echoing back what somebody typed is
     * fine — it is their own input — but it is the one legitimate difference between the
     * two screens, and leaving it in would make the comparison pass for the wrong reason.
     */
    const main = document.body.textContent;
    return main.split(address).join('<ADDRESS>');
  }

  it('SAYS THE SAME THING for an address that banks here and one that does not', async () => {
    /*
     * The service resolves either way, because the server answers either way. The test is
     * about what the screen does with that, not about the request.
     */
    const ask = vi.spyOn(authService, 'requestPasswordReset').mockResolvedValue(undefined);

    const known = await askAbout('real.customer@example.rw');
    document.body.innerHTML = '';
    const unknown = await askAbout('not.a.customer@example.rw');

    expect(unknown).toBe(known);
    expect(ask).toHaveBeenCalledTimes(2);

    /*
     * And it must not claim an email is on its way. It is not: a manager has to issue the
     * password first, so "we have sent you an email" would be false for a real customer as
     * well as for a stranger.
     */
    expect(known).not.toMatch(/we have sent you/i);
    expect(known).toMatch(/if .* is registered/i);
  });

  it('DOES NOT REPORT SUCCESS when the request never reached the bank', async () => {
    vi.spyOn(authService, 'requestPasswordReset').mockRejectedValue(
      new Error('the network went away'),
    );

    const user = renderPage();
    await user.type(
      screen.getByRole('textbox', { name: /email address/i }),
      'real.customer@example.rw',
    );
    await user.click(screen.getByRole('button', { name: /ask for a new password/i }));

    await waitFor(() => {
      expect(screen.queryByText(/passed this to the bank/i)).not.toBeInTheDocument();
    });

    /* And the form is still there to try again with. */
    expect(screen.getByRole('button', { name: /ask for a new password/i })).toBeInTheDocument();
  });

  it('ASKS FOR AN ADDRESS BEFORE CALLING ANYTHING', async () => {
    const ask = vi.spyOn(authService, 'requestPasswordReset').mockResolvedValue(undefined);

    const user = renderPage();
    await user.click(screen.getByRole('button', { name: /ask for a new password/i }));

    expect(await screen.findByText(/enter the email address/i)).toBeInTheDocument();
    expect(ask).not.toHaveBeenCalled();
  });

  it('SAYS A PERSON IS INVOLVED, so the wait does not look like a fault', () => {
    renderPage();

    /*
     * A customer who expects a reset link and gets a queue concludes the page is broken.
     * Stated before the button rather than after it.
     */
    expect(screen.getByText(/there is no reset link/i)).toBeInTheDocument();
    expect(screen.getByText(/current password keeps working/i)).toBeInTheDocument();
  });
});
