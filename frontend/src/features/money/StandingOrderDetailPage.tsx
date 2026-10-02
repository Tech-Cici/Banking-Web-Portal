import { useState, type ReactElement } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Alert, Button, ErrorNotice, PageHeader, ReviewPanel, Skeleton, StatusBadge, humaniseStatus, toneForStatus } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { standingOrderService } from '@/services';
import { formatDate } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import './money.css';

const FREQUENCY: Readonly<Record<string, string>> = {
  WEEKLY: 'Every week',
  MONTHLY: 'Every month',
  QUARTERLY: 'Every three months',
};

/**
 * One standing order, with the controls that change it.
 *
 * Cancelling asks for confirmation in place rather than in a modal. A modal steals
 * focus, is awkward on a phone, and — for something irreversible — is exactly the
 * pattern people dismiss on reflex. An inline panel that states the consequence has to
 * be read.
 */
export function StandingOrderDetailPage(): ReactElement {
  const { id = '' } = useParams();
  const navigate = useNavigate();

  const [version, setVersion] = useState(0);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);

  const { state } = useAsync(`so:${id}:${String(version)}`, (signal) =>
    standingOrderService.byId(id, signal),
  );

  const act = (run: () => Promise<unknown>, after?: () => void): void => {
    setBusy(true);
    setFailure(null);

    void run()
      .then(() => {
        if (after === undefined) setVersion((count) => count + 1);
        else after();
      })
      .catch((cause: unknown) => {
        setFailure(cause);
      })
      .finally(() => {
        setBusy(false);
      });
  };

  if (state.status === 'loading') return <Skeleton rows={6} label="Loading standing order" />;

  if (state.status === 'error') {
    return (
      <article>
        <PageHeader
          title="Standing order"
          crumbs={[{ label: 'Standing orders', to: RETAIL_PATHS.standingOrders }]}
        />
        <Alert tone="error" title="We could not open this standing order" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Link to={RETAIL_PATHS.standingOrders}>Back to standing orders</Link>
          </p>
        </Alert>
      </article>
    );
  }

  const order = state.data;

  return (
    <article className="money">
      <PageHeader
        title={order.destinationSummary}
        lead={`${FREQUENCY[order.frequency] ?? order.frequency} · ${formatMoneyDto(order.amount)}`}
        crumbs={[{ label: 'Standing orders', to: RETAIL_PATHS.standingOrders }]}
        action={
          <StatusBadge tone={toneForStatus(order.status)} label={humaniseStatus(order.status)} />
        }
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="change that standing order" />
      )}

      <ReviewPanel
        caption="Standing order details"
        rows={[
          { label: 'Reference', value: order.reference },
          { label: 'From', value: order.sourceAccountMask },
          { label: 'To', value: order.destinationSummary },
          { label: 'Amount each time', value: formatMoneyDto(order.amount) },
          { label: 'How often', value: FREQUENCY[order.frequency] ?? order.frequency },
          {
            label: 'Next payment',
            value: order.status === 'ACTIVE' ? formatDate(order.nextRunOn) : 'Not scheduled',
          },
          { label: 'Ends', value: order.endsOn === undefined ? 'No end date' : formatDate(order.endsOn) },
        ]}
      />

      {order.status !== 'ENDED' && (
        <div className="money__actions">
          <Button
            variant="secondary"
            loading={busy && !confirming}
            onClick={() => {
              act(() => standingOrderService.togglePause(order.id));
            }}
          >
            {order.status === 'PAUSED' ? 'Resume payments' : 'Pause payments'}
          </Button>

          {!confirming && (
            <Button
              variant="danger"
              onClick={() => {
                setConfirming(true);
              }}
            >
              Cancel this standing order
            </Button>
          )}
        </div>
      )}

      {confirming && (
        <Alert tone="warning" title="Cancel this standing order?">
          <p>
            No further payments will be made to {order.destinationSummary}. This cannot be
            undone — you would have to set the order up again. Pausing keeps it and stops the
            payments.
          </p>
          <div className="money__actions">
            <Button
              variant="danger"
              loading={busy}
              onClick={() => {
                act(
                  () => standingOrderService.cancel(order.id),
                  () => {
                    void navigate(RETAIL_PATHS.standingOrders, { replace: true });
                  },
                );
              }}
            >
              Yes, cancel it
            </Button>
            <Button
              variant="tertiary"
              disabled={busy}
              onClick={() => {
                setConfirming(false);
              }}
            >
              Keep it
            </Button>
          </div>
        </Alert>
      )}
    </article>
  );
}
