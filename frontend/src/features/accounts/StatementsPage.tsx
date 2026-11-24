import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  PageHeader,
  Skeleton,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { ApiError, statementService } from '@/services';
import type { Statement } from '@/types/movement';
import { formatDate, formatDateTime } from '@/utils/datetime';
import { saveFile } from '@/utils/download';
import './accounts.css';

function sizeLabel(bytes: number): string {
  return bytes < 1024 * 1024
    ? `${String(Math.round(bytes / 1024))} KB`
    : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Statements this customer has already taken, and a way to take each one again.
 *
 * <p>NO STATUS COLUMN. There was one, because statements were going to be produced by an
 * overnight batch and "requested" and "ready" were different things. They are produced inside
 * the request now, so every row here is a document that exists, and a status that always read
 * READY would be a column inviting somebody to believe it meant something.
 *
 * <p>DOWNLOADING RE-RENDERS rather than fetching a stored file. The ledger is append-only, so
 * the same period produces the same document; see V28 for why nothing is kept on disk.
 */
export function StatementsPage(): ReactElement {
  const { state, reload } = useAsync('statements', (signal) => statementService.list(signal));
  const [note, setNote] = useState<string | null>(null);

  /*
   * THE ROW BEING FETCHED, not a bare boolean. A spinner on every button at once would say
   * the whole table was working; holding the id puts it on the one the customer pressed.
   */
  const [downloading, setDownloading] = useState<string | null>(null);

  const download = async (row: Statement): Promise<void> => {
    setDownloading(row.id);
    setNote(null);
    try {
      saveFile(await statementService.download(row.id));
    } catch (cause) {
      setNote(
        cause instanceof ApiError
          ? cause.message
          : 'We could not download that statement. Please try again.',
      );
    } finally {
      setDownloading(null);
    }
  };

  const columns: readonly Column<Statement>[] = [
    {
      key: 'account',
      header: 'Account',
      render: (row) => row.accountLabel,
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
    {
      key: 'size',
      header: 'Size',
      numeric: true,
      secondary: true,
      render: (row) => sizeLabel(row.sizeBytes),
    },
    {
      key: 'action',
      header: 'Download',
      render: (row) => (
        <Button
          variant="tertiary"
          loading={downloading === row.id}
          disabled={downloading !== null}
          onClick={() => {
            void download(row);
          }}
        >
          Download
        </Button>
      ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Statements"
        lead="Every statement you have taken. Downloading one produces it again from your transaction history."
        action={<Link to={RETAIL_PATHS.accounts}>Take a new one from an account</Link>}
      />

      {note !== null && (
        <Alert tone="error" title="We could not download that statement">
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
          <EmptyState message="You have not taken any statements yet.">
            <p className="dash__note">
              <Link to={RETAIL_PATHS.accounts}>Choose an account to take one</Link>
            </p>
          </EmptyState>
        ) : (
          <DataTable
            caption="Statements you have taken"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
          />
        ))}
    </article>
  );
}
