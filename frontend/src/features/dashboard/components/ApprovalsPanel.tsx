import { useCallback, useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { Alert, Button, EmptyState, StatusBadge, TextArea } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { CORPORATE_PATHS, routeTo } from '@/routes/paths';
import { approvalService, newIdempotencyKey } from '@/services';
import type { ApprovalItem } from '@/types/banking';
import { formatAge } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import { friendlyError } from '@/services/errorMessage';

/**
 * The maker–checker queue, rendered for whichever side of it the user is on.
 *
 * One component, two very different screens, and the difference comes from permissions
 * rather than from a role name:
 *
 *  - APPROVAL_APPROVE  → a work queue. Each item carries Approve and Reject.
 *  - APPROVAL_VIEW only → a status list. The maker watches their own submissions move,
 *    and there is nothing to press, because the entire point of four-eyes is that the
 *    person who prepared the payment cannot release it.
 *
 * Self-approval is refused by the server regardless. It is disabled here as well, with
 * the reason stated, so an approver is not left guessing why a button failed.
 */

interface ApprovalsPanelProps {
  /** Reloads when the active company changes. */
  readonly scopeKey: string;
}

/** Per-item request state, so one pending approval never freezes the whole queue. */
interface ItemState {
  readonly busy: boolean;
  readonly error?: string;
  readonly reference?: string;
  readonly done?: string;
  /**
   * One idempotency key per item, created on first press and reused for every retry of
   * that same decision. A fresh key on retry would count as a second approval.
   */
  readonly key: string;
}

export function ApprovalsPanel({ scopeKey }: ApprovalsPanelProps): ReactElement {
  const session = useSession();
  const canApprove = session.can('APPROVAL_APPROVE');
  const canReject = session.can('APPROVAL_REJECT');
  const canSeeSalaryDetail = session.can('SALARY_VIEW_DETAILS');

  const { state, reload } = useAsync(`${scopeKey}:approvals`, (signal) =>
    approvalService.list(signal),
  );

  const [items, setItems] = useState<Readonly<Record<string, ItemState>>>({});
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reason, setReason] = useState('');

  const keyFor = useCallback(
    (id: string): string => items[id]?.key ?? newIdempotencyKey(),
    [items],
  );

  const run = useCallback(
    async (id: string, decide: (key: string) => Promise<ApprovalItem>): Promise<void> => {
      const key = keyFor(id);
      setItems((current) => ({ ...current, [id]: { busy: true, key } }));

      try {
        const updated = await decide(key);
        setItems((current) => ({
          ...current,
          [id]: {
            busy: false,
            key,
            done:
              updated.status === 'APPROVED'
                ? 'Approved and released.'
                : updated.status === 'REJECTED'
                  ? 'Rejected and returned to the maker.'
                  : `Your approval is recorded — ${String(updated.approvalsCollected)} of ${String(updated.approvalsRequired)} collected.`,
          },
        }));
      } catch (cause) {
        /*
         * A timeout on a decision is NOT a failure — the approval may well have been
         * recorded, and inviting an immediate retry is how an item collects two
         * approvals from one person. That judgement now lives in the shared translator,
         * so it cannot be got right here and forgotten on the next screen.
         */
        const friendly = friendlyError(cause);

        setItems((current) => ({
          ...current,
          [id]: {
            busy: false,
            key,
            error:
              friendly.nextStep === undefined
                ? friendly.summary
                : `${friendly.summary} ${friendly.nextStep}`,
            ...(friendly.reference === undefined ? {} : { reference: friendly.reference }),
          },
        }));
      }
    },
    [keyFor],
  );

  const onApprove = (id: string): void => {
    void run(id, (key) => approvalService.approve(id, key));
  };

  const onReject = (id: string): void => {
    const text = reason.trim();
    if (text === '') return;
    setRejecting(null);
    setReason('');
    void run(id, (key) => approvalService.reject(id, text, key));
  };

  const title = canApprove ? 'Waiting for your approval' : 'Submitted for approval';
  const subtitle = canApprove
    ? 'Two sets of eyes: you release what someone else prepared.'
    : 'Work you have sent on. Someone else has to release it.';

  return (
    <AsyncPanel
      title={title}
      subtitle={subtitle}
      state={state}
      reload={reload}
      skeletonRows={4}
      action={<Link to={CORPORATE_PATHS.approvals}>Full queue</Link>}
    >
      {(queue: readonly ApprovalItem[]) =>
        queue.length === 0 ? (
          <EmptyState
            message={
              canApprove
                ? 'Nothing is waiting for you. The queue is clear.'
                : 'You have nothing waiting for approval.'
            }
          />
        ) : (
          <ul className="dash__rows">
            {queue.map((item) => {
              const own = item.makerId === session.user?.id;
              const local = items[item.id];
              const withheld = item.itemType === 'SALARY' && !canSeeSalaryDetail;

              return (
                <li key={item.id} className="dash__row">
                  <div className="dash__row-main">
                    <p className="dash__row-title">
                      {item.beneficiarySummary}{' '}
                      <StatusBadge tone="neutral" label={item.itemType.toLowerCase()} />
                    </p>
                    <p className="dash__row-meta numeric">
                      from {item.sourceAccountMask} · prepared by {item.makerName} ·{' '}
                      {formatAge(item.submittedAt)}
                    </p>
                    <p className="dash__row-meta">
                      {item.approvalsCollected} of {item.approvalsRequired} approvals collected
                    </p>

                    {item.warnings.map((warning) => (
                      <p key={warning} className="dash__row-meta">
                        <StatusBadge tone="warning" label="Check" /> {warning}
                      </p>
                    ))}
                  </div>

                  <div className="dash__row-side">
                    {withheld || item.amount === undefined ? (
                      <p className="dash__amount--withheld">Amount withheld</p>
                    ) : (
                      <p className="dash__amount">{formatMoneyDto(item.amount)}</p>
                    )}

                    {local?.done !== undefined && (
                      <p className="dash__row-meta">{local.done}</p>
                    )}

                    {local?.error !== undefined && (
                      <Alert tone="warning" reference={local.reference}>
                        {local.error}
                      </Alert>
                    )}

                    {canApprove && local?.done === undefined && (
                      <div className="dash__row-actions">
                        <Button
                          variant="primary"
                          loading={local?.busy === true}
                          disabled={own}
                          onClick={() => {
                            onApprove(item.id);
                          }}
                        >
                          Approve
                        </Button>

                        {canReject && (
                          <Button
                            variant="secondary"
                            disabled={local?.busy === true}
                            onClick={() => {
                              setRejecting(rejecting === item.id ? null : item.id);
                              setReason('');
                            }}
                          >
                            Reject
                          </Button>
                        )}
                      </div>
                    )}

                    {own && canApprove && (
                      <p className="dash__row-meta">
                        You prepared this one, so someone else has to approve it.
                      </p>
                    )}

                    {!canApprove && (
                      <p className="dash__row-meta">
                        <Link to={routeTo.approvalDetail(item.id)}>View</Link>
                      </p>
                    )}
                  </div>

                  {rejecting === item.id && (
                    <div style={{ flexBasis: '100%' }}>
                      <TextArea
                        label="Why is this being rejected?"
                        hint="The maker sees this, so say what needs fixing."
                        value={reason}
                        rows={2}
                        onChange={(event) => {
                          setReason(event.target.value);
                        }}
                      />
                      <div className="dash__row-actions">
                        <Button
                          variant="danger"
                          disabled={reason.trim() === ''}
                          onClick={() => {
                            onReject(item.id);
                          }}
                        >
                          Confirm rejection
                        </Button>
                        <Button
                          variant="tertiary"
                          onClick={() => {
                            setRejecting(null);
                          }}
                        >
                          Cancel
                        </Button>
                      </div>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )
      }
    </AsyncPanel>
  );
}
