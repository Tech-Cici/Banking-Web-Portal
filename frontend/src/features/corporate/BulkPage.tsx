import { useState, type ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  FileUpload,
  humaniseStatus,
  PageHeader,
  ReviewPanel,
  Select,
  Skeleton,
  StatusBadge,
  toneForStatus,
  type Column,
  type UploadedFile,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { CORPORATE_PATHS, routeTo } from '@/routes/paths';
import { bulkService } from '@/services';
import type { BulkBatch } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import './corporate.css';

const TYPE_LABELS: Readonly<Record<string, string>> = {
  INTERNAL: 'Within this bank',
  EXTERNAL: 'To other banks',
  SALARY: 'Payroll',
  RRA: 'Tax',
  WALLET: 'Mobile wallets',
};

/** Shared amount cell: a withheld salary total is stated, never shown as zero. */
function amountCell(batch: BulkBatch): ReactElement {
  return batch.amountWithheld === true || batch.totalAmount === undefined ? (
    <span className="dash__amount--withheld">Withheld</span>
  ) : (
    <>{formatMoneyDto(batch.totalAmount)}</>
  );
}

interface BulkListProps {
  /** Salary shows only payroll batches; bulk shows everything else. */
  readonly salaryOnly: boolean;
}

/**
 * Batch listing, used for both Bulk operations and Salary.
 *
 * Salary is not a separate engine — the brief is explicit that it reuses the bulk one
 * with different permissions. So it is the same screen with a filter and its own
 * wording, and the privacy rule lives in one place rather than two.
 */
export function BulkListPage({ salaryOnly }: BulkListProps): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? 'none';
  const canCreate = session.can('BULK_CREATE');
  const canSeeSalaryDetail = session.can('SALARY_VIEW_DETAILS');

  const { state, reload } = useAsync(`bulk:${scope}`, (signal) => bulkService.list(signal));

  const columns: readonly Column<BulkBatch>[] = [
    {
      key: 'ref',
      header: 'Reference',
      render: (row) => (
        <Link to={routeTo.bulkDetail(row.id)} className="numeric">
          {row.reference}
        </Link>
      ),
    },
    {
      key: 'type',
      header: 'Kind',
      secondary: true,
      render: (row) => TYPE_LABELS[row.batchType] ?? row.batchType,
    },
    {
      key: 'records',
      header: 'Payments',
      numeric: true,
      render: (row) => String(row.totalRecords),
    },
    { key: 'amount', header: 'Total', numeric: true, render: amountCell },
    {
      key: 'by',
      header: 'Uploaded by',
      secondary: true,
      render: (row) => (
        <>
          {row.createdBy}
          <br />
          <span style={{ color: 'var(--color-text-muted)' }}>
            {formatDateTime(row.createdAt)}
          </span>
        </>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
          {row.status === 'PROCESSING' && (
            <p className="dash__row-meta">
              {row.successCount} sent · {row.failedCount} failed · {row.pendingCount} to go
            </p>
          )}
          {row.status === 'COMPLETED' && row.failedCount > 0 && (
            <p className="dash__row-meta">{row.failedCount} failed</p>
          )}
        </>
      ),
    },
  ];

  const rows =
    state.status === 'ready'
      ? state.data.filter((batch) =>
          salaryOnly ? batch.batchType === 'SALARY' : batch.batchType !== 'SALARY',
        )
      : [];

  return (
    <article>
      <PageHeader
        title={salaryOnly ? 'Salary batches' : 'Bulk operations'}
        lead={
          salaryOnly
            ? 'Payroll files. Amounts are only visible to people whose access includes salary detail.'
            : 'Files of many payments, uploaded once and approved together.'
        }
        action={canCreate ? <Link to={CORPORATE_PATHS.bulkNew}>Upload a file</Link> : undefined}
      />

      {salaryOnly && !canSeeSalaryDetail && (
        <Alert tone="info" title="Amounts are withheld from you">
          You can see the status and the number of employees so you can run payroll, but not
          the sums. A payroll total next to a headcount is close enough to individual pay.
        </Alert>
      )}

      {state.status === 'loading' && <Skeleton rows={5} label="Loading batches" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load the batches" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (rows.length === 0 ? (
          <EmptyState
            message={
              salaryOnly
                ? 'No payroll files have been uploaded for this company.'
                : 'No batches have been uploaded for this company.'
            }
          >
            {canCreate && (
              <p className="dash__note">
                <Link to={CORPORATE_PATHS.bulkNew}>Upload one</Link>
              </p>
            )}
          </EmptyState>
        ) : (
          <DataTable
            caption={salaryOnly ? 'Payroll batches' : 'Bulk payment batches'}
            columns={columns}
            rows={rows}
            rowKey={(row) => row.id}
            rowTone={(row) =>
              row.status === 'FAILED'
                ? 'error'
                : row.failedCount > 0 || row.status === 'PENDING_APPROVAL'
                  ? 'warning'
                  : 'default'
            }
          />
        ))}
    </article>
  );
}

/**
 * One batch: where it has got to, and what went wrong.
 *
 * A batch that is 118 of 120 successful is a batch with two problems somebody has to
 * chase, so the counts are the headline rather than a footnote. For a payroll file the
 * counts are shown even when the amounts are not — you cannot chase a stuck payment
 * without knowing one is stuck.
 */
export function BulkDetailPage(): ReactElement {
  const { batchId = '' } = useParams();
  const session = useSession();
  const scope = session.activeCorporate?.id ?? 'none';

  const { state } = useAsync(`bulk:${scope}`, (signal) => bulkService.list(signal));

  if (state.status === 'loading') return <Skeleton rows={6} label="Loading batch" />;

  const batch =
    state.status === 'ready' ? state.data.find((item) => item.id === batchId) : undefined;

  if (batch === undefined) {
    return (
      <article>
        <PageHeader title="Batch" crumbs={[{ label: 'Bulk', to: CORPORATE_PATHS.bulk }]} />
        <Alert tone="error" title="We could not find that batch">
          <Link to={CORPORATE_PATHS.bulk}>Back to batches</Link>
        </Alert>
      </article>
    );
  }

  const withheld = batch.amountWithheld === true || batch.totalAmount === undefined;

  return (
    <article className="corp__narrow">
      <PageHeader
        title={batch.reference}
        lead={`${TYPE_LABELS[batch.batchType] ?? batch.batchType} · uploaded by ${batch.createdBy}`}
        crumbs={[
          {
            label: batch.batchType === 'SALARY' ? 'Salary' : 'Bulk operations',
            to: batch.batchType === 'SALARY' ? CORPORATE_PATHS.salary : CORPORATE_PATHS.bulk,
          },
        ]}
        action={
          <StatusBadge tone={toneForStatus(batch.status)} label={humaniseStatus(batch.status)} />
        }
      />

      {batch.status === 'PENDING_APPROVAL' && (
        <Alert tone="warning" title="Nothing has been sent yet">
          This batch is waiting for approval. No money leaves the account until an approver
          releases it.
        </Alert>
      )}

      {batch.failedCount > 0 && (
        <Alert tone="error" title={`${String(batch.failedCount)} payments failed`}>
          These need to be corrected and sent again. The successful ones have gone and must
          not be re-sent.
        </Alert>
      )}

      <ReviewPanel
        caption="Batch details"
        rows={[
          { label: 'Reference', value: batch.reference },
          { label: 'Kind', value: TYPE_LABELS[batch.batchType] ?? batch.batchType },
          { label: 'Payments in the file', value: String(batch.totalRecords) },
          {
            label: 'Total',
            value: withheld
              ? 'Withheld — your access does not include salary amounts'
              : formatMoneyDto(batch.totalAmount),
          },
          { label: 'Sent successfully', value: String(batch.successCount) },
          { label: 'Failed', value: String(batch.failedCount) },
          { label: 'Still to go', value: String(batch.pendingCount) },
          { label: 'Uploaded by', value: batch.createdBy },
          { label: 'Uploaded', value: formatDateTime(batch.createdAt) },
        ]}
      />

      {/* Why this is not built: docs/OPEN-ITEMS.md. Kept out of the UI — a customer
          cannot act on a missing backend contract. */}
      <Alert tone="info" title="Individual payments are not listed yet">
        You can see the totals for this batch, but not the payments inside it.
      </Alert>
    </article>
  );
}

/**
 * Uploads a batch file.
 *
 * The file is not parsed in the browser. A bulk file is the bank's record of what was
 * instructed, and a client that reads it, totals it and shows a figure is a client whose
 * total can disagree with the one the payment engine acts on. It goes up; the server
 * validates it and reports back.
 */
export function BulkNewPage(): ReactElement {
  const [files, setFiles] = useState<readonly UploadedFile[]>([]);
  const [batchType, setBatchType] = useState('INTERNAL');
  const [error, setError] = useState<string | undefined>(undefined);

  return (
    <article className="corp__narrow">
      <PageHeader
        title="Upload a batch"
        lead="A CSV of payments, validated by the bank before anything is sent."
        crumbs={[{ label: 'Bulk operations', to: CORPORATE_PATHS.bulk }]}
      />

      <Select
        label="What kind of batch?"
        value={batchType}
        options={[
          { value: 'INTERNAL', label: 'Payments within this bank' },
          { value: 'EXTERNAL', label: 'Payments to other banks' },
          { value: 'SALARY', label: 'Payroll' },
          { value: 'WALLET', label: 'Mobile wallet payments' },
          { value: 'RRA', label: 'Tax (RRA)' },
        ]}
        onChange={(event) => {
          setBatchType(event.target.value);
        }}
      />

      <FileUpload
        label="Payment file"
        hint="One CSV, up to 5 MB."
        accept={['.csv']}
        maxSizeBytes={5 * 1024 * 1024}
        maxFiles={1}
        files={files}
        error={error}
        onChange={(next) => {
          setFiles(next);
          setError(undefined);
        }}
      />

      {batchType === 'SALARY' && (
        <Alert tone="warning" title="Payroll files are handled differently">
          Amounts in a payroll batch are only visible to colleagues whose access includes
          salary detail. Everyone else sees the status and the headcount.
        </Alert>
      )}

      {/* See docs/OPEN-ITEMS.md. The file is collected so the flow is real, but there
          is nowhere to send it yet — say that, and nothing about why. */}
      <Alert tone="info" title="Uploading is not available yet">
        You can choose a file, but it cannot be sent to the bank from here yet. Please
        submit payroll files the way you do today.
      </Alert>

      <div className="money__actions">
        <Button
          disabled={files.length === 0}
          onClick={() => {
            setError('There is no upload endpoint yet, so this file was not sent.');
          }}
        >
          Upload and validate
        </Button>
        <Link to={CORPORATE_PATHS.bulk} className="money__link">
          Cancel
        </Link>
      </div>
    </article>
  );
}
