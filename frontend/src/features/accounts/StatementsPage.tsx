import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  humaniseStatus,
  PageHeader,
  Skeleton,
  StatusBadge,
  toneForStatus,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { statementService } from '@/services';
import type { Statement } from '@/types/movement';
import { formatDate, formatDateTime } from '@/utils/datetime';
import './accounts.css';

function sizeLabel(bytes: number | undefined): string {
  if (bytes === undefined) return '—';
  return bytes < 1024 * 1024
    ? `${String(Math.round(bytes / 1024))} KB`
    : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Statements that have been requested, and their state.
 *
 * A statement is produced by a batch, so "requested" and "ready" are different things
 * and the list says which. A download link that appears before the file exists is a
 * link that 404s, so it only appears on READY.
 */
export function StatementsPage(): ReactElement {
  const { state, reload } = useAsync('statements', (signal) => statementService.list(signal));
  const [note, setNote] = useState<string | null>(null);

  const columns: readonly Column<Statement>[] = [
    {
      key: 'account',
      header: 'Account',
      render: (row) => <span className="numeric">{row.accountMask}</span>,
    },
    {
      key: 'period',
      header: 'Period',
      render: (row) => `${formatDate(row.periodFrom)} – ${formatDate(row.periodTo)}`,
    },
    { key: 'format', header: 'Format', secondary: true, render: (row) => row.format },
    {
      key: 'requested',
      header: 'Requested',
      secondary: true,
      render: (row) => formatDateTime(row.requestedAt),
    },
    { key: 'size', header: 'Size', numeric: true, secondary: true, render: (row) => sizeLabel(row.sizeBytes) },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
      ),
    },
    {
      key: 'action',
      header: 'Download',
      render: (row) =>
        row.status === 'READY' ? (
          <Button
            variant="tertiary"
            onClick={() => {
              setNote(
                `The download endpoint is not built yet, so ${row.accountMask} for ${formatDate(row.periodFrom)} could not be fetched.`,
              );
            }}
          >
            Download
          </Button>
        ) : (
          <span style={{ color: 'var(--color-text-muted)' }}>Not ready</span>
        ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Statements"
        lead="Statements are produced overnight. You will be notified when one is ready."
        action={<Link to={RETAIL_PATHS.accounts}>Request one from an account</Link>}
      />

      {/* The endpoint must be authenticated and must not hand out a public URL; see
          docs/OPEN-ITEMS.md. None of that belongs on the customer's screen. */}
      {note !== null && (
        <Alert tone="info" title="Downloads are not available yet">
          <p>{note}</p>
        </Alert>
      )}

      {state.status === 'loading' && <Skeleton rows={5} label="Loading statements" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your statements" reference={state.reference}>
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
          <EmptyState message="You have not requested any statements yet.">
            <p className="dash__note">
              <Link to={RETAIL_PATHS.accounts}>Choose an account to request one</Link>
            </p>
          </EmptyState>
        ) : (
          <DataTable
            caption="Statements you have requested"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            rowTone={(row) => (row.status === 'FAILED' ? 'error' : 'default')}
          />
        ))}
    </article>
  );
}
