import { useState, type ReactElement } from 'react';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  ErrorNotice,
  PageHeader,
  Panel,
  ReviewPanel,
  Skeleton,
  StatusBadge,
  TextArea,
  humaniseStatus,
  toneForStatus,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { adminService } from '@/services';
import type { PasswordRequest } from '@/types/admin';
import { formatDateTime } from '@/utils/datetime';
import { useStaffSession } from './useStaffSession';
import './staff.css';

/**
 * Customers who have forgotten their password and asked the bank for a new one.
 *
 * <p>WHY A HUMAN IS IN THIS LOOP AT ALL. The portal has no self-service reset: no token in
 * an email, no page that sets a password from a link. A reset link would be a second
 * credential-bearing path into every account at the bank, and this queue is the
 * alternative — the request arrives here, a manager decides, and the password is issued
 * through exactly the machinery that issues one at approval. V15 in the backend records
 * the trade.
 *
 * <p>WHAT A MANAGER IS ACTUALLY DECIDING, because the button is one click and the
 * consequence is not: issuing puts a working credential in whatever inbox is on the
 * customer's record. If the person who asked is not the customer, this screen is the last
 * place that can be noticed. So the queue shows who and when and nothing else useful for
 * guessing, and refusing is an equal, reasoned option rather than a way out of the screen.
 */
export function PasswordRequestsPage(): ReactElement {
  const session = useStaffSession();

  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<PasswordRequest | null>(null);
  const [refusing, setRefusing] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const { state, reload } = useAsync(`password-requests:${String(version)}`, (signal) =>
    adminService.passwordRequests(signal),
  );

  const close = (): void => {
    setSelected(null);
    setRefusing(false);
    setReason('');
  };

  /**
   * Runs a decision and reloads the queue.
   *
   * `busy` gates both buttons for the whole round trip. The server already refuses a
   * second decision on a settled request — two managers would otherwise both issue a
   * password, and the second would silently invalidate the first — so this only stops one
   * manager double-tapping into a confusing refusal for a decision that succeeded.
   */
  const decide = (
    run: () => Promise<PasswordRequest>,
    message: (request: PasswordRequest) => string,
  ): void => {
    setBusy(true);
    setFailure(null);

    void run()
      .then((settled) => {
        setDone(message(settled));
        close();
        setVersion((count) => count + 1);
      })
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  const columns: readonly Column<PasswordRequest>[] = [
    {
      key: 'name',
      header: 'Customer',
      render: (row) => (
        <>
          <strong>{row.fullName}</strong>
          <br />
          <span className="numeric">{row.customerNumber}</span>
        </>
      ),
    },
    {
      key: 'email',
      header: 'Email the password goes to',
      render: (row) => row.email,
    },
    {
      key: 'status',
      header: 'Account',
      /*
       * THE ACCOUNT'S STATUS, not the request's — every row here is pending by
       * definition. It is in the table rather than only in the review panel because a
       * frozen customer cannot sign in whatever password they are given, and the server
       * refuses to issue one: a manager should see that before clicking, not from the
       * error it returns.
       */
      render: (row) => (
        <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
      ),
    },
    {
      key: 'asked',
      header: 'Asked',
      secondary: true,
      render: (row) => formatDateTime(row.requestedAt),
    },
    {
      key: 'action',
      header: 'Decide',
      render: (row) => (
        <Button
          variant="secondary"
          onClick={() => {
            setSelected(row);
            setRefusing(false);
            setReason('');
            setDone(null);
            setFailure(null);
          }}
        >
          Review
        </Button>
      ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Password requests"
        lead="Customers who could not sign in and asked us for a new password. Issuing one emails them a temporary password they must replace straight away."
      />

      {done !== null && (
        <Alert tone="success" title="Decision recorded">
          {done}
        </Alert>
      )}

      {failure !== null && <ErrorNotice error={failure} action="record that decision" />}

      {!session.isManager && (
        <Alert tone="info" title="Only a manager can issue a password">
          You can see this queue, but issuing a password is a manager&rsquo;s job — the same rule as
          approving a new account, and for the same reason. The server enforces this too.
        </Alert>
      )}

      {state.status === 'loading' && <Skeleton rows={4} label="Loading the password requests" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load the requests" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (state.data.length === 0 ? (
          <EmptyState message="Nobody is waiting for a password." />
        ) : (
          <DataTable
            caption="Customers waiting for a new password"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
          />
        ))}

      {selected !== null && (
        <div className="staff__review">
          <Panel
            title={`Issue a new password to ${selected.fullName}?`}
            subtitle="The password is emailed to the address below and nowhere else."
          >
            <ReviewPanel
              rows={[
                { label: 'Customer', value: selected.fullName },
                { label: 'Customer number', value: selected.customerNumber },
                { label: 'Email', value: selected.email },
                { label: 'Account status', value: humaniseStatus(selected.status) },
                { label: 'Asked', value: formatDateTime(selected.requestedAt) },
              ]}
            />

            {/*
              CHECK WHO YOU ARE TALKING TO. This is the only control in the flow: there is
              no identity check in the software, because the software cannot do one — the
              person who typed an address into a public form is unverified by definition.
              Saying so plainly is more use to a manager than a reassuring button.
            */}
            <Alert tone="warning" title="Satisfy yourself this is the customer first">
              <p>
                Anybody can type an email address into the public form, so this request is not proof
                of who asked. Confirm it the way the bank confirms a caller before you issue
                anything.
              </p>
              <p style={{ marginTop: 'var(--space-2)' }}>
                <strong>Issuing</strong> emails a temporary password to {selected.email}. It
                expires, and the customer must replace it before they can use the service. Any
                browsers that were allowed to skip the sign-in code are reset, so they will be asked
                for a code again.
              </p>
              <p style={{ marginTop: 'var(--space-2)' }}>
                <strong>Refusing</strong> emails them the reason you write and changes nothing about
                their existing password.
              </p>
            </Alert>

            {selected.status !== 'ACTIVE' && (
              <Alert tone="error" title="This customer cannot sign in at the moment">
                Their account is {humaniseStatus(selected.status).toLowerCase()}, so a new password
                would not let them in and the server will refuse to issue one. Deal with the
                account&rsquo;s status first.
              </Alert>
            )}

            <div className="money__actions">
              {!refusing && (
                <>
                  <Button
                    loading={busy}
                    disabled={!session.isManager || selected.status !== 'ACTIVE'}
                    onClick={() => {
                      decide(
                        () => adminService.issuePassword(selected.id),
                        (settled) =>
                          `A temporary password has been emailed to ${settled.email}. They must replace it before they can use the service.`,
                      );
                    }}
                  >
                    Issue and email a password
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy || !session.isManager}
                    onClick={() => {
                      setRefusing(true);
                    }}
                  >
                    Refuse this request
                  </Button>
                  <Button variant="tertiary" disabled={busy} onClick={close}>
                    Close
                  </Button>
                </>
              )}

              {refusing && (
                <div style={{ width: '100%' }}>
                  <TextArea
                    label="Why are you refusing this?"
                    hint="The customer is emailed this, word for word. Write what they need in order to put it right."
                    value={reason}
                    rows={3}
                    onChange={(event) => {
                      setReason(event.target.value);
                    }}
                  />
                  <div className="money__actions">
                    <Button
                      variant="danger"
                      loading={busy}
                      disabled={reason.trim() === ''}
                      onClick={() => {
                        decide(
                          () => adminService.refusePassword(selected.id, reason.trim()),
                          (settled) => `${settled.fullName} has been emailed your reason.`,
                        );
                      }}
                    >
                      Refuse and email the reason
                    </Button>
                    <Button
                      variant="tertiary"
                      disabled={busy}
                      onClick={() => {
                        setRefusing(false);
                        setReason('');
                      }}
                    >
                      Back
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </Panel>
        </div>
      )}
    </article>
  );
}
