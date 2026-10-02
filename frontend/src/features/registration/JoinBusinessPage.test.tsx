import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { JoinBusinessPage } from './JoinBusinessPage';

/**
 * Covers the join-a-business flow against the mock API.
 *
 * These go through the real API client and real error normalisation — only the HTTP
 * response is mocked — so a regression in either shows up here.
 */

function renderPage(): { user: ReturnType<typeof userEvent.setup> } {
  const user = userEvent.setup();
  render(
    <MemoryRouter>
      <JoinBusinessPage />
    </MemoryRouter>,
  );
  return { user };
}

async function fillValidForm(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.type(screen.getByLabelText(/company code/i), 'ACME2026');
  await user.type(screen.getByLabelText(/full name/i), 'Test Person');
  await user.type(screen.getByLabelText(/national id number/i), '1234567890123456');
  await user.type(screen.getByLabelText(/staff or employee number/i), 'EMP-42');
  await user.type(screen.getByLabelText(/work email/i), 'test.person@example.rw');
  await user.type(screen.getByLabelText(/mobile number/i), '0781234567');
  await user.selectOptions(screen.getByLabelText(/requested role/i), 'MAKER');
  await user.type(
    screen.getByLabelText(/reason for access/i),
    'I prepare supplier payments each month.',
  );
}

describe('JoinBusinessPage', () => {
  it('starts on the first step with no form errors shown', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: /join a business/i })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('blocks progress and reports every invalid field', async () => {
    const { user } = renderPage();

    await user.click(screen.getByRole('button', { name: /continue to review/i }));

    expect(
      await screen.findByText(/company code must be at least 4 characters/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/national id number is required/i)).toBeInTheDocument();
    expect(screen.getByText(/work email is required/i)).toBeInTheDocument();
    // Still on step one.
    expect(screen.queryByRole('heading', { name: /review your request/i })).not.toBeInTheDocument();
  });

  it('marks invalid fields with aria-invalid so they are announced', async () => {
    const { user } = renderPage();
    await user.click(screen.getByRole('button', { name: /continue to review/i }));

    await waitFor(() => {
      expect(screen.getByLabelText(/company code/i)).toHaveAttribute('aria-invalid', 'true');
    });
  });

  it('rejects a phone number that is not a Rwandan mobile', async () => {
    const { user } = renderPage();
    await user.type(screen.getByLabelText(/mobile number/i), '0881234567');
    await user.click(screen.getByRole('button', { name: /continue to review/i }));

    expect(await screen.findByText(/valid rwandan mobile number/i)).toBeInTheDocument();
  });

  it('advances to review and shows what the administrator will see', async () => {
    const { user } = renderPage();
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /continue to review/i }));

    expect(
      await screen.findByRole('heading', { name: /review your request/i }),
    ).toBeInTheDocument();
    // Company code is normalised to upper case before display and submission.
    expect(screen.getByText('ACME2026')).toBeInTheDocument();
    expect(screen.getByText(/maker/i)).toBeInTheDocument();
  });

  it('will not submit until the declaration is confirmed', async () => {
    const { user } = renderPage();
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /continue to review/i }));
    await user.click(await screen.findByRole('button', { name: /send request/i }));

    expect(await screen.findByText(/please confirm before sending/i)).toBeInTheDocument();
    expect(screen.queryByText(/request sent/i)).not.toBeInTheDocument();
  });

  it('submits and reports the request as pending approval', async () => {
    const { user } = renderPage();
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /continue to review/i }));
    await user.click(await screen.findByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /send request/i }));

    expect(await screen.findByText(/request sent/i)).toBeInTheDocument();
    expect(screen.getByText(/awaiting administrator approval/i)).toBeInTheDocument();
    // Contact details are masked on the confirmation screen.
    expect(screen.queryByText('test.person@example.rw')).not.toBeInTheDocument();
  });

  it('surfaces a server refusal with its reference, without advancing', async () => {
    const { user } = renderPage();
    await user.type(screen.getByLabelText(/company code/i), 'REJECTED');
    await user.type(screen.getByLabelText(/full name/i), 'Test Person');
    await user.type(screen.getByLabelText(/national id number/i), '1234567890123456');
    await user.type(screen.getByLabelText(/staff or employee number/i), 'EMP-42');
    await user.type(screen.getByLabelText(/work email/i), 'test.person@example.rw');
    await user.type(screen.getByLabelText(/mobile number/i), '0781234567');
    await user.selectOptions(screen.getByLabelText(/requested role/i), 'VIEWER');
    await user.type(screen.getByLabelText(/reason for access/i), 'Read-only reporting access.');

    await user.click(screen.getByRole('button', { name: /continue to review/i }));
    await user.click(await screen.findByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /send request/i }));

    expect(await screen.findByText(/not accepting new access requests/i)).toBeInTheDocument();
    // The reference now says what it is for, rather than being a bare labelled code that
    // a customer reads as noise and never quotes.
    expect(screen.getByText(/quote reference/i)).toBeInTheDocument();
    expect(screen.queryByText(/awaiting administrator approval/i)).not.toBeInTheDocument();
  });
});
