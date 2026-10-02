import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
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
import { routeTo, STAFF_PATHS } from '@/routes/paths';
import { adminService, newIdempotencyKey } from '@/services';
import {
  describeAccountMasks,
  describeCustomerAccounts,
  type Customer,
} from '@/types/admin';
import { formatDateTime } from '@/utils/datetime';
import { useStaffSession } from './useStaffSession';
import './staff.css';

/**
 * Accounts an admin has created and a manager has to sign off.
 *
 * The second pair of eyes on the most dangerous action in the system. Approving here
 * activates the login and sends the customer the email that tells them to sign in, so
 * the screen shows who created it and when before it shows a button.
 *
 * A manager cannot approve an account they created themselves. The server refuses it;
 * this screen says so up front rather than letting them find out by being rejected.
 */
export function ApprovalsPage(): ReactElement {
  const session = useStaffSession();

  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<Customer | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const { state, reload } = useAsync(`pending:${String(version)}`, (signal) =>
    adminService.customers('PENDING_APPROVAL', signal),
  );

  const decide = (
    run: (key: string) => Promise<Customer>,
    message: (c: Customer) => string,
  ): void => {
    setBusy(true);
    setFailure(null);

    void run(newIdempotencyKey())
      .then((updated) => {
        setDone(message(updated));
        setSelected(null);
        setRejecting(false);
        setReason('');
        setVersion((count) => count + 1);
      })
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  const columns: readonly Column<Customer>[] = [
    {
      key: 'who',
      header: 'Customer',
      render: (row) => (
        <>
          {row.fullName}
          <br />
          <span style={{ color: 'var(--color-text-muted)' }}>{row.email}</span>
        </>
      ),
    },
    {
      key: 'number',
      header: 'Customer number',
      secondary: true,
      render: (row) => <span className="numeric">{row.customerNumber}</span>,
    },
    {
      key: 'account',
      header: 'Account',
      render: (row) => <span className="numeric">{describeAccountMasks(row.accountMasks)}</span>,
    },
    {
      key: 'profile',
      header: 'Profile',
      secondary: true,
      render: (row) => (row.userType === 'CORPORATE' ? 'Business' : 'Personal'),
    },
    {
      key: 'by',
      header: 'Created by',
      render: (row) => (
        <>
          {row.createdByName}
          <br />
          <span style={{ color: 'var(--color-text-muted)' }}>{formatDateTime(row.createdAt)}</span>
        </>
      ),
    },
    {
      key: 'action',
      header: 'Review',
      render: (row) => (
        <Button
          variant="secondary"
          onClick={() => {
            setSelected(row);
            setRejecting(false);
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
        title="Awaiting approval"
        lead="Accounts an administrator has created. Nothing works until you approve it."
      />

      {/*
        A SIGNPOST, because this page's title is the one a manager reads as "everything
        waiting for me" and it is not. Transfers wait in their own queue, and somebody
        looking for a customer's payment here found an empty table and reasonably
        concluded the payment had vanished — while the money sat on hold.
      */}
      <p className="staff__note">
        Looking for a customer&rsquo;s transfer? Money waiting for release is in{' '}
        <Link to={STAFF_PATHS.transferQueue}>Money to release</Link>. This page is only about
        logins.
      </p>

      {done !== null && (
        <Alert tone="success" title="Decision recorded">
          {done}
        </Alert>
      )}

      {failure !== null && <ErrorNotice error={failure} action="record that decision" />}

      {!session.isManager && (
        <Alert tone="info" title="Only a manager can approve">
          You can see this queue, but approving is a manager&rsquo;s job — the person who creates an
          account is never the person who releases it.
        </Alert>
      )}

      {state.status === 'loading' && <Skeleton rows={5} label="Loading the approval queue" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load the queue" reference={state.reference}>
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
          <EmptyState message="Nothing is waiting for approval. The queue is clear." />
        ) : (
          <DataTable
            caption="Accounts awaiting approval"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
          />
        ))}

      {selected !== null && (
        <div className="staff__review">
          <Panel
            title={`Review ${selected.fullName}`}
            subtitle="Approving activates the login and emails the customer."
          >
            <ReviewPanel
              rows={[
                { label: 'Name', value: selected.fullName },
                { label: 'Email', value: selected.email },
                { label: 'Phone', value: selected.phone },
                { label: 'Customer number', value: selected.customerNumber },
                { label: 'Account', value: describeAccountMasks(selected.accountMasks) },
                {
                  /*
                   * The manager approving should see what the branch asked for. Without
                   * this they are signing off a login with no idea which accounts the
                   * customer was told they were getting.
                   */
                  label: 'Accounts',
                  value: describeCustomerAccounts(selected.accounts),
                },
                {
                  label: 'Profile',
                  value: selected.userType === 'CORPORATE' ? 'Business' : 'Personal',
                },
                { label: 'Created by', value: selected.createdByName },
                { label: 'Created', value: formatDateTime(selected.createdAt) },
                { label: 'Status', value: humaniseStatus(selected.status) },
              ]}
            />

            {/*
              A route to what the applicant actually submitted.

              The warning below tells the manager to check the identity details, and for a
              while this panel did not show any: not the account number, not the national
              ID, not the date of birth. An instruction to check something the screen does
              not display is worse than no instruction, because it reads as though the
              check has been made possible.

              A link rather than a copy of those fields. They belong to the registration,
              and a second rendering of a national ID is a second place it can go stale or
              be seen.
            */}
            <p className="staff__note">
              <Link to={routeTo.staffApplication(selected.applicationId)}>
                Open the registration they submitted
              </Link>{' '}
              to see the account number, national ID and date of birth to check against the bank's
              records.
            </p>

            {selected.createdByName === session.staff?.fullName ? (
              <Alert tone="warning" title="You created this account">
                Someone else has to approve it. That is the whole point of the second check: the
                person who issued the credentials is not the person who confirms they should exist.
              </Alert>
            ) : (
              <>
                <Alert tone="warning" title="What approving does">
                  The customer will be able to sign in with the temporary password they were given,
                  and an email goes out telling them so. Approve only once somebody has confirmed
                  against the bank&rsquo;s records that this is an existing client and that the
                  details they submitted are theirs. Nothing in this system has checked that — your
                  approval is the record that it was done.
                </Alert>

                <div className="money__actions">
                  {!rejecting && (
                    <>
                      <Button
                        loading={busy}
                        disabled={!session.isManager}
                        onClick={() => {
                          decide(
                            (key) => adminService.approve(selected.id, key),
                            (c) => `${c.fullName} can now sign in, and has been emailed.`,
                          );
                        }}
                      >
                        Approve and email the customer
                      </Button>
                      <Button
                        variant="danger"
                        disabled={busy || !session.isManager}
                        onClick={() => {
                          setRejecting(true);
                        }}
                      >
                        Reject
                      </Button>
                    </>
                  )}
                  <Button
                    variant="tertiary"
                    disabled={busy}
                    onClick={() => {
                      setSelected(null);
                      setRejecting(false);
                    }}
                  >
                    Close
                  </Button>
                </div>

                {rejecting && (
                  <div className="corp__reject">
                    <TextArea
                      label="Why is this being rejected?"
                      hint="The applicant is sent this wording, so write it for them rather than for the file."
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
                            (key) => adminService.reject(selected.id, reason.trim(), key),
                            (c) => `${c.fullName} was rejected and has been emailed the reason.`,
                          );
                        }}
                      >
                        Confirm rejection
                      </Button>
                      <Button
                        variant="tertiary"
                        disabled={busy}
                        onClick={() => {
                          setRejecting(false);
                        }}
                      >
                        Cancel
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )}
          </Panel>
        </div>
      )}
    </article>
  );
}

/**
 * Every customer the bank has, whatever stage they are at — and where a manager freezes
 * one.
 *
 * FREEZING IS THE MOST IMMEDIATELY CONSEQUENTIAL BUTTON ON THIS SCREEN. It stops a person
 * reaching their own money, within the second, including a session they already have open.
 * So it asks for a written reason before it will submit, it is a manager's action only,
 * and it never reads as routine.
 *
 * The reason is not a formality. It is kept on the customer record after the account is
 * restored, which is what makes a freeze reviewable later — and since freezing needs only
 * one manager's signature, that record is the whole control against it being misused.
 * Deliberately not shown to the customer: a freeze may concern an investigation they must
 * not be tipped off about.
 *
 * Everything here is also enforced on the server, which is what actually decides. An admin
 * who reaches this page sees the controls disabled; an admin who calls the endpoint
 * directly is refused by it.
 */
export function StaffCustomersPage(): ReactElement {
  const session = useStaffSession();

  const [version, setVersion] = useState(0);
  /** The customer a freeze or unfreeze is being composed for, and which way round. */
  const [acting, setActing] = useState<{ customer: Customer; freeze: boolean } | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const { state, reload } = useAsync(`all-customers:${String(version)}`, (signal) =>
    adminService.customers(undefined, signal),
  );

  const submit = (): void => {
    if (acting === null) return;

    const { customer, freeze } = acting;
    const written = reason.trim();
    if (written === '') return;

    setBusy(true);
    setFailure(null);

    /*
     * A fresh idempotency key per submission, and the button is disabled while the call
     * is in flight — a double-click must not produce two freeze records.
     */
    const key = newIdempotencyKey();
    const call = freeze
      ? adminService.freeze(customer.id, written, key)
      : adminService.unfreeze(customer.id, written, key);

    void call
      .then((updated) => {
        setDone(
          freeze
            ? `${updated.fullName} is frozen. Any session they had open has been ended.`
            : `${updated.fullName} can sign in again, with the password they already had.`,
        );
        setActing(null);
        setReason('');
        setVersion((count) => count + 1);
      })
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  const columns: readonly Column<Customer>[] = [
    {
      key: 'who',
      header: 'Customer',
      render: (row) => (
        <>
          {row.fullName}
          <br />
          <span style={{ color: 'var(--color-text-muted)' }}>{row.email}</span>
        </>
      ),
    },
    {
      key: 'number',
      header: 'Customer number',
      render: (row) => <span className="numeric">{row.customerNumber}</span>,
    },
    {
      key: 'profile',
      header: 'Profile',
      secondary: true,
      render: (row) => (row.userType === 'CORPORATE' ? 'Business' : 'Personal'),
    },
    {
      key: 'created',
      header: 'Created',
      secondary: true,
      render: (row) => `${row.createdByName} · ${formatDateTime(row.createdAt)}`,
    },
    {
      key: 'approved',
      header: 'Approved',
      secondary: true,
      render: (row) =>
        row.approvedByName === undefined
          ? '—'
          : `${row.approvedByName} · ${formatDateTime(row.approvedAt ?? '')}`,
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
          {row.status === 'ACTIVE' && row.mustChangePassword && (
            <p className="dash__row-meta">Has not signed in yet.</p>
          )}
          {row.rejectionReason !== undefined && (
            <p className="dash__row-meta">{row.rejectionReason}</p>
          )}
          {/*
           * Keyed on the STATUS, not on the presence of freezeReason. The freeze record is
           * kept after an account is restored — restoring access should not erase the fact
           * that access was once removed — so reading the reason to decide what to show
           * would mark every previously frozen customer as frozen.
           *
           * Staff-only, all of it. The customer is never shown this.
           */}
          {row.status === 'SUSPENDED' && (
            <p className="dash__row-meta">
              {row.freezeReason ?? 'No reason was recorded.'}
              {row.frozenBy !== undefined && (
                <>
                  <br />
                  {row.frozenBy}
                  {row.frozenAt !== undefined && ` · ${formatDateTime(row.frozenAt)}`}
                </>
              )}
            </p>
          )}
        </>
      ),
    },
    {
      key: 'accounts',
      header: 'Accounts',
      render: (row) => (
        <>
          {/*
            TWO DIFFERENT THINGS, shown as two lines rather than merged.

            `accountMasks` is accounts that exist. `requestedAccounts` is what a branch
            asked for and core banking has not acted on. Collapsing them would put an
            administrator back where they were when they opened a current and a savings
            account and every screen said "no accounts linked to this profile" — true
            about the first, silent about the second.
          */}
          <span className="numeric">{describeAccountMasks(row.accountMasks)}</span>
          {row.accounts.length > 0 && (
            <p className="dash__row-meta">
              {row.accounts
                .map((entry) => `${entry.accountType} · ${entry.currency}`)
                .join(', ')}
            </p>
          )}
        </>
      ),
    },
    {
      key: 'access',
      header: 'Access',
      render: (row) => {
        /*
         * Only an ACTIVE account can be frozen and only a SUSPENDED one restored. A
         * PENDING_APPROVAL account has no access to remove, and a REJECTED one never had
         * any — the server refuses both transitions, and offering a button that would be
         * refused is how a screen teaches staff to distrust it.
         */
        if (row.status !== 'ACTIVE' && row.status !== 'SUSPENDED') return '—';

        const freeze = row.status === 'ACTIVE';

        return (
          <Button
            variant={freeze ? 'danger' : 'secondary'}
            disabled={!session.isManager || busy}
            onClick={() => {
              setActing({ customer: row, freeze });
              setReason('');
              setDone(null);
              setFailure(null);
            }}
          >
            {freeze ? 'Freeze' : 'Restore access'}
          </Button>
        );
      },
    },
  ];

  return (
    <article>
      <PageHeader title="Customers" lead="Every account, whatever stage it has reached." />

      {session.status === 'authenticated' && !session.isManager && (
        <Alert tone="info" title="Only a manager can freeze an account">
          You are signed in as an administrator, so the access controls are disabled. The
          server enforces this too.
        </Alert>
      )}

      {done !== null && (
        <Alert tone="success" title="Done">
          {done}
        </Alert>
      )}

      {/*
       * No onRetry. The panel below stays open with the reason still in it, so the
       * manager retries by pressing the button again — with a fresh idempotency key,
       * rather than a retry control replaying the previous request.
       */}
      {failure !== null && <ErrorNotice error={failure} action="change that account's access" />}

      {acting !== null && (
        <Panel
          title={
            acting.freeze
              ? `Freeze ${acting.customer.fullName}`
              : `Restore access for ${acting.customer.fullName}`
          }
        >
          {acting.freeze ? (
            <Alert tone="warning" title="This takes effect immediately">
              <p>
                {acting.customer.fullName} will not be able to sign in, and any session they
                have open right now ends on their next action. They are emailed that their
                access has been suspended — <strong>not</strong> the reason you give below.
              </p>
            </Alert>
          ) : (
            <Alert tone="warning" title="This restores access to their money">
              <p>
                {acting.customer.fullName} will be able to sign in again immediately, using
                the password they already had — freezing did not change it. Satisfy yourself
                that whatever caused the freeze has been resolved.
              </p>
            </Alert>
          )}

          <TextArea
            label={acting.freeze ? 'Why is this account being frozen?' : 'Why is access being restored?'}
            hint="Kept on the customer's record, and shown only to bank staff. Write it for the colleague who reviews this in six months."
            value={reason}
            rows={3}
            required
            onChange={(event) => {
              setReason(event.target.value);
            }}
          />

          <div className="money__actions">
            <Button
              variant={acting.freeze ? 'danger' : 'primary'}
              loading={busy}
              /*
               * A reason is required by the server; requiring it here too means the
               * manager finds out before the request rather than after it.
               */
              disabled={reason.trim() === '' || !session.isManager}
              onClick={submit}
            >
              {acting.freeze ? 'Freeze this account' : 'Restore access'}
            </Button>
            <Button
              variant="tertiary"
              disabled={busy}
              onClick={() => {
                setActing(null);
                setReason('');
              }}
            >
              Cancel
            </Button>
          </div>
        </Panel>
      )}

      {state.status === 'loading' && <Skeleton rows={5} label="Loading customers" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load the customers" reference={state.reference}>
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
          <EmptyState message="No accounts have been created yet." />
        ) : (
          <DataTable
            caption="All customers"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            rowTone={(row) =>
              /*
               * SUSPENDED reads as an error row, like REJECTED. A frozen account is not a
               * mild state — somebody cannot reach their money — and it must not be
               * possible to miss while scanning the list.
               */
              row.status === 'REJECTED' || row.status === 'SUSPENDED'
                ? 'error'
                : row.status === 'PENDING_APPROVAL'
                  ? 'warning'
                  : 'default'
            }
          />
        ))}
    </article>
  );
}
