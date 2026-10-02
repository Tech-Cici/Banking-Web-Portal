import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { EmptyState, humaniseStatus, StatusBadge, toneForStatus } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { CORPORATE_PATHS, routeTo } from '@/routes/paths';
import { bulkService } from '@/services';
import type { BulkBatch } from '@/types/banking';
import { formatAge } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';

/**
 * Bulk and salary batches for the active company.
 *
 * Salary privacy is enforced by the API — a user without SALARY_VIEW_DETAILS is not sent
 * the total, because a payroll total next to a headcount is close enough to individual
 * pay. This panel's job is to SAY SO rather than leave a blank, so a maker who cannot see
 * the figure knows the data exists and why it is not theirs, instead of filing a bug.
 *
 * Counts are shown to everyone: you cannot chase a stuck batch without knowing how many
 * rows are still pending.
 */
export function BatchesPanel({ scopeKey }: { readonly scopeKey: string }): ReactElement {
  const session = useSession();
  const { state, reload } = useAsync(`${scopeKey}:batches`, (signal) => bulkService.list(signal));

  const canCreate = session.can('BULK_CREATE');

  return (
    <AsyncPanel
      title="Batches"
      subtitle="Bulk payments and payroll files."
      state={state}
      reload={reload}
      skeletonRows={4}
      action={<Link to={CORPORATE_PATHS.bulk}>All batches</Link>}
    >
      {(batches: readonly BulkBatch[]) =>
        batches.length === 0 ? (
          <EmptyState message="No batches have been uploaded for this company.">
            {canCreate && (
              <p className="dash__note">
                <Link to={CORPORATE_PATHS.bulkNew}>Upload a batch</Link>
              </p>
            )}
          </EmptyState>
        ) : (
          <ul className="dash__rows">
            {batches.map((batch) => (
              <li key={batch.id} className="dash__row">
                <div className="dash__row-main">
                  <p className="dash__row-title">
                    <Link to={routeTo.bulkDetail(batch.id)} className="numeric">
                      {batch.reference}
                    </Link>{' '}
                    <StatusBadge tone="neutral" label={batch.batchType.toLowerCase()} />
                  </p>
                  <p className="dash__row-meta">
                    {batch.totalRecords} payments · by {batch.createdBy} ·{' '}
                    {formatAge(batch.createdAt)}
                  </p>
                  {batch.status === 'PROCESSING' && (
                    <p className="dash__row-meta">
                      {batch.successCount} sent · {batch.failedCount} failed ·{' '}
                      {batch.pendingCount} still to go
                    </p>
                  )}
                  {batch.status === 'COMPLETED' && batch.failedCount > 0 && (
                    <p className="dash__row-meta">
                      {batch.failedCount} of {batch.totalRecords} payments failed and need
                      attention.
                    </p>
                  )}
                </div>

                <div className="dash__row-side">
                  {batch.amountWithheld === true || batch.totalAmount === undefined ? (
                    <p className="dash__amount--withheld">
                      Amount withheld
                      <br />
                      <span className="dash__row-meta">payroll — not visible to you</span>
                    </p>
                  ) : (
                    <p className="dash__amount">{formatMoneyDto(batch.totalAmount)}</p>
                  )}
                  <StatusBadge
                    tone={toneForStatus(batch.status)}
                    label={humaniseStatus(batch.status)}
                  />
                </div>
              </li>
            ))}
          </ul>
        )
      }
    </AsyncPanel>
  );
}
