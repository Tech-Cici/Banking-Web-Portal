import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, DataTable, EmptyState, ErrorNotice, PageHeader, Skeleton, StatusBadge, humaniseStatus, toneForStatus, type Column } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { standingOrderService } from '@/services';
import type { StandingOrder } from '@/types/movement';
import { formatDate } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import './money.css';

const FREQUENCY: Readonly<Record<string, string>> = {
  WEEKLY: 'Every week',
  MONTHLY: 'Every month',
  QUARTERLY: 'Every three months',
};

/**
 * Payments the bank makes on a schedule, and the controls to stop them.
 *
 * Pausing is offered inline because the reason someone opens this screen in a hurry is
 * to stop a payment before it leaves. Cancelling is deliberately NOT inline: it is
 * permanent, it needs the detail screen's confirmation, and a destructive control one
 * mis-tap away from Pause is a control that will be hit by accident.
 */
export function StandingOrdersPage(): ReactElement {
  const session = useSession();
  const canCreate = session.can('TRANSFER_CREATE') || session.can('CORPORATE_TRANSFER_CREATE');

  const [version, setVersion] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);

  const { state, reload } = useAsync(`standing-orders:${String(version)}`, (signal) =>
    standingOrderService.list(signal),
  );

  const togglePause = (id: string): void => {
    setBusyId(id);
    setFailure(null);

    void standingOrderService
      .togglePause(id)
      .then(() => {
        setVersion((count) => count + 1);
      })
      .catch((cause: unknown) => {
        setFailure(cause);
      })
      .finally(() => {
        setBusyId(null);
      });
  };

  const columns: readonly Column<StandingOrder>[] = [
    {
      key: 'to',
      header: 'Paying',
      render: (row) => (
        <>
          <Link to={routeTo.standingOrderDetail(row.id)}>{row.destinationSummary}</Link>
          <br />
          <span className="numeric" style={{ color: 'var(--color-text-muted)' }}>
            from {row.sourceAccountMask} · {row.reference}
          </span>
        </>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      numeric: true,
      render: (row) => formatMoneyDto(row.amount),
    },
    {
      key: 'when',
      header: 'How often',
      secondary: true,
      render: (row) => FREQUENCY[row.frequency] ?? row.frequency,
    },
    {
      key: 'next',
      header: 'Next payment',
      render: (row) => (row.status === 'ACTIVE' ? formatDate(row.nextRunOn) : '—'),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
      ),
    },
    {
      key: 'action',
      header: 'Action',
      render: (row) =>
        row.status === 'ENDED' ? (
          <span style={{ color: 'var(--color-text-muted)' }}>Ended</span>
        ) : (
          <Button
            variant="secondary"
            loading={busyId === row.id}
            onClick={() => {
              togglePause(row.id);
            }}
          >
            {row.status === 'PAUSED' ? 'Resume' : 'Pause'}
          </Button>
        ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Standing orders"
        lead="Payments the bank makes for you on a schedule."
        action={
          canCreate ? <Link to={RETAIL_PATHS.standingOrderNew}>Set one up</Link> : undefined
        }
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="change that standing order" />
      )}

      {state.status === 'loading' && <Skeleton rows={5} label="Loading standing orders" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your standing orders" reference={state.reference}>
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
          <EmptyState message="You have no standing orders.">
            {canCreate && (
              <p className="dash__note">
                <Link to={RETAIL_PATHS.standingOrderNew}>Set one up</Link>
              </p>
            )}
          </EmptyState>
        ) : (
          <DataTable
            caption="Your standing orders"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            rowTone={(row) => (row.status === 'PAUSED' ? 'warning' : 'default')}
          />
        ))}
    </article>
  );
}
