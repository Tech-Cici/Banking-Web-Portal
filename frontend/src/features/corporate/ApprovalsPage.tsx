import { useState, type ReactElement } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Alert, Button, DataTable, EmptyState, ErrorNotice, PageHeader, ReviewPanel, Skeleton, StatusBadge, TextArea, humaniseStatus, toneForStatus, type Column } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { approvalService, newIdempotencyKey } from '@/services';
import { CORPORATE_PATHS, routeTo } from '@/routes/paths';
import type { ApprovalItem } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import './corporate.css';

/**
 * The full approval queue for the company being acted for.
 *
 * A list, not a decision surface. Approving from a row means approving something you
 * have skimmed, and four-eyes exists precisely so that a payment is looked at properly
 * the second time — so the decision lives on the detail screen, one deliberate click
 * away, where the whole payment is on screen at once.
 */
export function ApprovalsPage(): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? 'none';
  const canApprove = session.can('APPROVAL_APPROVE');

  const { state, reload } = useAsync(`approvals:${scope}`, (signal) =>
    approvalService.list(signal),
  );

  const columns: readonly Column<ApprovalItem>[] = [
    {
      key: 'what',
      header: 'Payment',
      render: (row) => (
        <>
          <Link to={routeTo.approvalDetail(row.id)}>{row.beneficiarySummary}</Link>
          <br />
          <span className="numeric" style={{ color: 'var(--color-text-muted)' }}>
            from {row.sourceAccountMask}
          </span>
        </>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      secondary: true,
      render: (row) => <StatusBadge tone="neutral" label={row.itemType.toLowerCase()} />,
    },
    {
      key: 'maker',
      header: 'Prepared by',
      render: (row) => (
        <>
          {row.makerName}
          <br />
          <span style={{ color: 'var(--color-text-muted)' }}>
            {formatDateTime(row.submittedAt)}
          </span>
        </>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      numeric: true,
      render: (row) =>
        row.amount === undefined || row.amountWithheld === true ? (
          <span className="dash__amount--withheld">Withheld</span>
        ) : (
          formatMoneyDto(row.amount)
        ),
    },
    {
      key: 'progress',
      header: 'Approvals',
      numeric: true,
      render: (row) => `${String(row.approvalsCollected)} of ${String(row.approvalsRequired)}`,
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
          {row.warnings.length > 0 && (
            <p className="dash__row-meta">{row.warnings.length} thing to check</p>
          )}
        </>
      ),
    },
  ];

  return (
    <article>
      <PageHeader
        title={canApprove ? 'Approvals' : 'Submitted for approval'}
        lead={
          canApprove
            ? 'Payments prepared by colleagues, waiting for a second pair of eyes.'
            : 'Work you have sent on. Someone else has to release it.'
        }
      />

      {state.status === 'loading' && <Skeleton rows={6} label="Loading the approval queue" />}

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
          <EmptyState
            message={
              canApprove
                ? 'Nothing is waiting for approval. The queue is clear.'
                : 'You have nothing waiting for approval.'
            }
          />
        ) : (
          <DataTable
            caption="Payments awaiting approval"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            rowTone={(row) => (row.warnings.length > 0 ? 'warning' : 'default')}
          />
        ))}
    </article>
  );
}

/**
 * One item, in full, with the decision.
 *
 * Everything the approver needs is on one screen before either button: who prepared it,
 * when, from which account, to whom, how much, and every warning the bank attached. An
 * approval made without that is a rubber stamp, and a rubber stamp is not a control.
 */
export function ApprovalDetailPage(): ReactElement {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const session = useSession();

  const scope = session.activeCorporate?.id ?? 'none';
  const canApprove = session.can('APPROVAL_APPROVE');
  const canReject = session.can('APPROVAL_REJECT');

  const { state } = useAsync(`approvals:${scope}`, (signal) => approvalService.list(signal));

  const [busy, setBusy] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  /* One key per decision on this item, reused across retries of that same decision. */
  const [key] = useState(() => newIdempotencyKey());

  const item =
    state.status === 'ready' ? state.data.find((entry) => entry.id === id) : undefined;

  const decide = (run: () => Promise<ApprovalItem>): void => {
    setBusy(true);
    setFailure(null);

    void run()
      .then((updated) => {
        setDone(
          updated.status === 'APPROVED'
            ? 'Approved and released.'
            : updated.status === 'REJECTED'
              ? 'Rejected and returned to the maker.'
              : `Your approval is recorded — ${String(updated.approvalsCollected)} of ${String(updated.approvalsRequired)} collected. It needs another before it goes.`,
        );
      })
      /*
       * The "we do not know whether it went through" case used to be special-cased here
       * with its own wording. It is now handled centrally, so every screen that can end
       * in an unknown outcome says the same thing — and none of them can forget to.
       */
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  if (state.status === 'loading') return <Skeleton rows={6} label="Loading the payment" />;

  if (item === undefined) {
    return (
      <article>
        <PageHeader
          title="Approval"
          crumbs={[{ label: 'Approvals', to: CORPORATE_PATHS.approvals }]}
        />
        <Alert tone="error" title="We could not find that item">
          <p>It may already have been approved or rejected by someone else.</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Link to={CORPORATE_PATHS.approvals}>Back to the queue</Link>
          </p>
        </Alert>
      </article>
    );
  }

  const own = item.makerId === session.user?.id;

  return (
    <article className="corp__narrow">
      <PageHeader
        title={item.beneficiarySummary}
        lead={`${item.itemType.toLowerCase()} prepared by ${item.makerName}`}
        crumbs={[{ label: 'Approvals', to: CORPORATE_PATHS.approvals }]}
        action={
          <StatusBadge tone={toneForStatus(item.status)} label={humaniseStatus(item.status)} />
        }
      />

      {done !== null && (
        <Alert tone="success" title="Decision recorded">
          <p>{done}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button
              onClick={() => {
                void navigate(CORPORATE_PATHS.approvals);
              }}
            >
              Back to the queue
            </Button>
          </p>
        </Alert>
      )}

      {failure !== null && <ErrorNotice error={failure} action="record that decision" />}

      {item.warnings.map((warning) => (
        <Alert key={warning} tone="warning" title="Check this">
          {warning}
        </Alert>
      ))}

      <ReviewPanel
        caption="Payment details"
        rows={[
          { label: 'Type', value: item.itemType },
          { label: 'From', value: item.sourceAccountMask },
          { label: 'To', value: item.beneficiarySummary },
          {
            label: 'Amount',
            value:
              item.amount === undefined || item.amountWithheld === true
                ? 'Withheld — your access does not include salary amounts'
                : formatMoneyDto(item.amount),
          },
          { label: 'Prepared by', value: item.makerName },
          { label: 'Submitted', value: formatDateTime(item.submittedAt) },
          {
            label: 'Approvals',
            value: `${String(item.approvalsCollected)} of ${String(item.approvalsRequired)} collected`,
          },
        ]}
      />

      {own && (
        <Alert tone="info" title="You prepared this one">
          Someone else has to approve it. That is the point of four eyes: the person who
          sets a payment up is never the person who releases it.
        </Alert>
      )}

      {!canApprove && !own && (
        <Alert tone="info">
          Your access lets you see this queue but not act on it.
        </Alert>
      )}

      {done === null && canApprove && !own && (
        <>
          <div className="money__actions">
            <Button
              loading={busy && !rejecting}
              onClick={() => {
                decide(() => approvalService.approve(item.id, key));
              }}
            >
              Approve this payment
            </Button>

            {canReject && !rejecting && (
              <Button
                variant="danger"
                disabled={busy}
                onClick={() => {
                  setRejecting(true);
                }}
              >
                Reject
              </Button>
            )}
          </div>

          {rejecting && (
            <div className="corp__reject">
              <TextArea
                label="Why is this being rejected?"
                hint="The maker sees this. Say what needs fixing so they can correct it."
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
                    decide(() => approvalService.reject(item.id, reason.trim(), key));
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
    </article>
  );
}
