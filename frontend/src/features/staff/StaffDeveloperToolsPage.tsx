import { useState, type ReactElement } from 'react';
import { AsyncPanel } from '@/components/AsyncPanel';
import { Alert, Button, EmptyState, PageHeader, Panel, TextField } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { adminService } from '@/services';
import { formatDateTime } from '@/utils/datetime';
import './staff.css';

/**
 * DEVELOPER TOOLS. NOT A BANKING SCREEN.
 *
 * <p>WHY THIS PAGE EXISTS: both of the things on it used to be on the staff OVERVIEW — the
 * first screen an administrator or a manager sees when they sign in.
 *
 * <p>One was a panel headed "Start over — Development only" with a red "Delete every
 * customer and application" button, under four sentences naming `VITE_LIVE_API`, the `dev`,
 * `h2` and `test` profiles and the fact that the button used to say "mock bank". The other
 * was the development mailbox, listing every message the bank would have sent. Neither is
 * something a bank employee should meet on their landing page.
 *
 * <p>THE RESET STAYED, BY REQUEST, and it is here rather than on the overview. That is the
 * distinction that matters: whoever runs this service needs to empty it between test runs,
 * and a bank manager signing in to release payments should not be one click from doing the
 * same. There is a terminal equivalent too — `npm run reset-bank` — for use without a
 * browser.
 *
 * <p>IT ASKS YOU TO TYPE THE WORD. A single click is the wrong amount of effort for an
 * action that deletes every customer and cannot be undone, and a `confirm()` dialog is
 * worse than nothing — people dismiss those without reading. Typing DELETE is the smallest
 * thing that cannot happen by accident.
 *
 * <p>THE MESSAGE LOG EARNS ITS PLACE for a different reason: with email switched off this
 * is the only way to read a verification code or a temporary password, so without it the
 * onboarding flow cannot be walked at all. It is read-only.
 *
 * <p>REACHABLE ONLY OUTSIDE PRODUCTION. `StaffLayout` hides the link when the build is a
 * production one, and the backend refuses the reset outside the `dev`, `h2` and `test`
 * profiles — so it cannot reach production even if somebody types the URL. Two independent
 * checks, because a hidden link on its own is not a control.
 */
export function StaffDeveloperToolsPage(): ReactElement {
  const [resetting, setResetting] = useState(false);
  /* Typed, not clicked. See the note above on why a confirm() dialog would be worse. */
  const [confirmation, setConfirmation] = useState('');
  const outbox = useAsync('staff:outbox', (signal) => adminService.outbox(signal));

  return (
    <article>
      <PageHeader
        title="Developer tools"
        lead="For whoever is running this service. Nothing here is part of the bank's day-to-day work."
      />

      <Panel title="Sent messages" subtitle="Everything the bank would have emailed, newest last.">
        <Alert tone="warning" title="No email is actually sent">
          There is no mail server wired up. Rather than pretend a message went out, every one
          is recorded here — which also makes it visible exactly what a customer receives,
          including the fact that the approval email does <strong>not</strong> contain their
          password.
        </Alert>

        <AsyncPanel title="Messages" state={outbox.state} reload={outbox.reload} skeletonRows={5}>
          {(messages) =>
            messages.length === 0 ? (
              <EmptyState message="Nothing has been sent yet. Approve an account and its email appears here." />
            ) : (
              <ul className="staff__mail">
                {messages.map((message) => (
                  <li key={message.id} className="staff__mail-item">
                    <p className="staff__mail-subject">{message.subject}</p>
                    <p className="dash__row-meta">
                      from {message.from} · to {message.to} · {formatDateTime(message.sentAt)}
                    </p>
                    <pre className="staff__mail-body">{message.body}</pre>
                  </li>
                ))}
              </ul>
            )
          }
        </AsyncPanel>
      </Panel>

      <div className="dash__section">
        <Panel title="Start over" subtitle="Empties the bank so the flow can be walked from nothing.">
          <p className="dash__note">
            Removes every application, customer, sign-in record, payee, request and message.
            Staff logins are kept — deleting those would lock you out of this page.
          </p>

          <Alert tone="error" title="This deletes from whichever database the API is using">
            <p>
              With <code>admin</code> in <code>VITE_LIVE_API</code> that is the real
              PostgreSQL database, not a fixture. The backend refuses this outside the{' '}
              <code>dev</code>, <code>h2</code> and <code>test</code> profiles, so it cannot
              reach production — but it will empty your local bank, and there is no undo.
            </p>
          </Alert>

          <TextField
            label="Type DELETE to enable the button"
            hint="A single click is the wrong amount of effort for something this size."
            value={confirmation}
            autoComplete="off"
            onChange={(event) => {
              setConfirmation(event.target.value);
            }}
          />

          <div className="money__actions">
            <Button
              variant="danger"
              loading={resetting}
              /* Case-sensitive on purpose: "delete" is a word people type by habit. */
              disabled={confirmation !== 'DELETE'}
              onClick={() => {
                setResetting(true);
                void import('@/services/devReset')
                  .then(async ({ resetMockBank }) => {
                    await resetMockBank();
                    window.location.reload();
                  })
                  .finally(() => {
                    setResetting(false);
                  });
              }}
            >
              Delete every customer and application
            </Button>
          </div>
        </Panel>
      </div>
    </article>
  );
}
