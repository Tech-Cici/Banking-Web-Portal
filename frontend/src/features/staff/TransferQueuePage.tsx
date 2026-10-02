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
  TextArea,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { adminService } from '@/services';
import type { PendingTransfer } from '@/types/admin';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import { useStaffSession } from './useStaffSession';
import './staff.css';

/**
 * Money customers have sent that a manager has to release.
 *
 * WHY THIS IS ITS OWN SCREEN. The server has had these endpoints since transfers were
 * built, and no screen called them — so a customer could send money, be told truthfully
 * that it was waiting for a manager, and no manager had anywhere to see it. The queue
 * existed and was invisible, which is the worst of the three possible states: the money
 * had already left the sender's balance.
 *
 * It is NOT merged into "Awaiting approval". That queue is logins an administrator
 * created; this one is money already out of somebody's account. They need different
 * words on the buttons and they run at completely different volumes — a bank opens a few
 * accounts a week and moves money all day.
 *
 * THE MONEY IS ALREADY GONE FROM THE SENDER, and this screen says so rather than
 * implying a decision starts the movement. Approving credits the beneficiary; rejecting
 * returns it. There is no third option and no way to leave it half-done.
 */
export function TransferQueuePage(): ReactElement {
  const session = useStaffSession();

  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<PendingTransfer | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const { state, reload } = useAsync(`transfer-queue:${String(version)}`, (signal) =>
    adminService.transferQueue(signal),
  );

  const amountOf = (transfer: PendingTransfer): string =>
    formatMoneyDto({ amount: transfer.amount, currency: transfer.currency });

  const close = (): void => {
    setSelected(null);
    setRejecting(false);
    setReason('');
  };

  /**
   * Runs a decision and reloads the queue.
   *
   * `busy` gates both buttons for the whole round trip. The server already refuses a
   * second decision on a decided transfer, so a double-tap cannot move money twice —
   * this only stops the manager seeing a confusing refusal for a decision that did in
   * fact succeed.
   */
  const decide = (
    run: () => Promise<PendingTransfer>,
    message: (transfer: PendingTransfer) => string,
  ): void => {
    setBusy(true);
    setFailure(null);

    void run()
      .then((decided) => {
        setDone(message(decided));
        close();
        setVersion((count) => count + 1);
      })
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  const columns: readonly Column<PendingTransfer>[] = [
    {
      key: 'amount',
      header: 'Amount',
      numeric: true,
      render: (row) => <strong>{amountOf(row)}</strong>,
    },
    {
      key: 'from',
      header: 'From',
      render: (row) => <span className="numeric">{row.sourceMask}</span>,
    },
    {
      key: 'to',
      header: 'To',
      render: (row) => <span className="numeric">{row.destinationMask}</span>,
    },
    {
      key: 'reference',
      header: 'Reference',
      render: (row) => row.reference,
    },
    {
      key: 'submitted',
      header: 'Submitted',
      secondary: true,
      render: (row) => formatDateTime(row.submittedAt),
    },
    {
      key: 'action',
      header: 'Decide',
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
        title="Money awaiting release"
        lead="Transfers customers have sent. The money has already left their accounts and is on hold until you decide."
      />

      {done !== null && (
        <Alert tone="success" title="Decision recorded">
          {done}
        </Alert>
      )}

      {failure !== null && <ErrorNotice error={failure} action="record that decision" />}

      {!session.isManager && (
        <Alert tone="info" title="Only a manager can release money">
          You can see this queue, but releasing a transfer is a manager&rsquo;s job. The server
          enforces this too.
        </Alert>
      )}

      {state.status === 'loading' && <Skeleton rows={5} label="Loading the transfer queue" />}

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
          <EmptyState message="No transfers are waiting. Nobody's money is on hold." />
        ) : (
          <DataTable
            caption="Transfers awaiting a manager's decision"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            /*
             * Every row is a warning tone, because every row is somebody's money sitting
             * outside their account. A neutral row invites a manager to scan past it.
             */
            rowTone={() => 'warning'}
          />
        ))}

      {selected !== null && (
        <div className="staff__review">
          <Panel
            title={`Release ${amountOf(selected)}?`}
            subtitle="Approving credits the beneficiary immediately."
          >
            <ReviewPanel
              rows={[
                { label: 'Amount', value: amountOf(selected) },
                { label: 'From', value: selected.sourceMask },
                { label: 'To', value: selected.destinationMask },
                { label: 'Reference', value: selected.reference },
                { label: 'Submitted', value: formatDateTime(selected.submittedAt) },
              ]}
            />

            {/*
              Both outcomes stated before either button, because a manager should not
              have to remember which way round the money goes. The wording avoids
              "cancel" for a rejection: cancelling sounds like nothing happened, and
              something did — a debit was posted and a reversal is about to be.
            */}
            <Alert tone="warning" title="What each decision does">
              <p>
                <strong>Approving</strong> credits {selected.destinationMask} with{' '}
                {amountOf(selected)} in the same breath as this request. It is not reversible
                from this screen.
              </p>
              <p style={{ marginTop: 'var(--space-2)' }}>
                <strong>Rejecting</strong> returns the money to {selected.sourceMask} and shows
                the sender the reason you write. Their statement will carry both the original
                debit and the return, because that is what happened.
              </p>
            </Alert>

            <div className="money__actions">
              {!rejecting && (
                <>
                  <Button
                    loading={busy}
                    disabled={!session.isManager}
                    onClick={() => {
                      decide(
                        () => adminService.approveTransfer(selected.id),
                        (transfer) =>
                          `${formatMoneyDto({
                            amount: transfer.amount,
                            currency: transfer.currency,
                          })} has reached ${transfer.destinationMask}.`,
                      );
                    }}
                  >
                    Approve and release the money
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
              <Button variant="tertiary" disabled={busy} onClick={close}>
                Close
              </Button>
            </div>

            {rejecting && (
              <div className="corp__reject">
                <TextArea
                  label="Why is this being refused?"
                  hint="The sender is shown this wording on their statement, so write it for them rather than for the file."
                  value={reason}
                  rows={3}
                  required
                  onChange={(event) => {
                    setReason(event.target.value);
                  }}
                />
                <div className="money__actions">
                  <Button
                    variant="danger"
                    loading={busy}
                    /*
                     * Required by the server too. Checking it here means the manager
                     * finds out before the request rather than after it.
                     */
                    disabled={reason.trim() === '' || !session.isManager}
                    onClick={() => {
                      decide(
                        () => adminService.rejectTransfer(selected.id, reason.trim()),
                        (transfer) =>
                          `${formatMoneyDto({
                            amount: transfer.amount,
                            currency: transfer.currency,
                          })} has gone back to ${transfer.sourceMask}.`,
                      );
                    }}
                  >
                    Confirm refusal and return the money
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
          </Panel>
        </div>
      )}
    </article>
  );
}
