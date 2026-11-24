import type { ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  humaniseStatus,
  PageHeader,
  Panel,
  ReviewPanel,
  Skeleton,
  StatusBadge,
  toneForStatus,
} from '@/components/ui';
import type { Column } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { loanService } from '@/services';
import type { LoanRepayment } from '@/types/banking';
import { formatDate } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import './products.css';

/**
 * One loan's statement: the terms it was granted on, and every payment made against it.
 *
 * WHY THERE IS NO FORWARD SCHEDULE. A loan here stores its instalment, its rate and the
 * date the next one falls due -- not a term. A table of future instalments would therefore
 * be this screen's projection rather than the bank's schedule, and a customer reading a row
 * dated eleven months out would take it for a commitment. What the bank actually knows is
 * shown; what it does not is not invented.
 *
 * EVERY FIGURE IS THE SERVER'S, including the balance after each payment, which is recorded
 * on the payment itself rather than recomputed by adding the column up. A statement line
 * that can be checked on its own is the point of recording it.
 */
export function LoanStatementPage(): ReactElement {
  const { loanId = '' } = useParams();

  const loans = useAsync('loans', (signal) => loanService.list(signal));
  const settlement = useAsync(`loan:settlement:${loanId}`, (signal) =>
    loanService.settlement(loanId, signal),
  );
  const history = useAsync(`loan:repayments:${loanId}`, (signal) =>
    loanService.repayments(loanId, signal),
  );

  const loan =
    loans.state.status === 'ready'
      ? loans.state.data.find((item) => item.id === loanId)
      : undefined;

  if (loans.state.status === 'loading') {
    return <Skeleton rows={8} label="Loading loan statement" />;
  }

  if (loan === undefined) {
    return (
      <article>
        <PageHeader title="Loan statement" crumbs={[{ label: 'Loans', to: RETAIL_PATHS.loans }]} />
        <Alert tone="error" title="We could not find that loan">
          <Link to={RETAIL_PATHS.loans}>Back to loans</Link>
        </Alert>
      </article>
    );
  }

  const columns: readonly Column<LoanRepayment>[] = [
    {
      key: 'paidAt',
      header: 'Date',
      render: (row) => formatDate(row.paidAt),
    },
    {
      key: 'amount',
      header: 'Paid',
      numeric: true,
      render: (row) => formatMoneyDto(row.amount),
    },
    {
      key: 'interest',
      header: 'Of which interest',
      numeric: true,
      secondary: true,
      render: (row) => formatMoneyDto(row.interest),
    },
    {
      key: 'capital',
      header: 'Of which capital',
      numeric: true,
      secondary: true,
      render: (row) => formatMoneyDto(row.capital),
    },
    {
      key: 'outstandingAfter',
      header: 'Still owed',
      numeric: true,
      render: (row) => formatMoneyDto(row.outstandingAfter),
    },
  ];

  return (
    <article className="products__narrow">
      <PageHeader
        title="Loan statement"
        lead={`${loan.product} — ${loan.reference}`}
        crumbs={[
          { label: 'Loans', to: RETAIL_PATHS.loans },
          { label: loan.reference, to: routeTo.loanDetail(loan.id) },
        ]}
        action={
          <StatusBadge tone={toneForStatus(loan.status)} label={humaniseStatus(loan.status)} />
        }
      />

      <ReviewPanel
        caption="The terms this loan was granted on"
        rows={[
          { label: 'Borrowed', value: formatMoneyDto(loan.principal) },
          { label: 'Interest rate', value: `${loan.interestRatePercent}% a year` },
          { label: 'Monthly instalment', value: formatMoneyDto(loan.installment) },
          { label: 'Still outstanding', value: formatMoneyDto(loan.outstandingBalance) },
          {
            label: 'Next instalment due',
            value: loan.status === 'SETTLED' ? 'Settled' : formatDate(loan.nextDueDate),
          },
        ]}
      />

      {/*
        THE SETTLEMENT FIGURE SITS BESIDE THE BALANCE, not in place of it. They are different
        numbers -- the second includes the interest owed on the first -- and a customer who
        pays the balance expecting the loan to close is the reason this says so.
      */}
      {settlement.state.status === 'ready' && loan.status !== 'SETTLED' && (
        <Alert
          tone="info"
          title={`${formatMoneyDto(settlement.state.data.settlementFigure)} clears this loan today`}
        >
          That is the outstanding balance plus the interest owed on it, so it is more than the
          balance alone.
        </Alert>
      )}

      <Panel title="Payments made" subtitle="Oldest first, as the bank recorded them.">
        {history.state.status === 'loading' && <Skeleton rows={4} label="Loading payments" />}

        {history.state.status === 'error' && (
          <Alert
            tone="error"
            title="We could not load the payments"
            reference={history.state.reference}
          >
            <p>{history.state.message}</p>
            <p style={{ marginTop: 'var(--space-3)' }}>
              <Button variant="secondary" onClick={history.reload}>
                Try again
              </Button>
            </p>
          </Alert>
        )}

        {history.state.status === 'ready' &&
          (history.state.data.length === 0 ? (
            <EmptyState message="Nothing has been paid against this loan yet.">
              <p className="dash__note">
                The first payment will appear here as soon as it is made.
              </p>
            </EmptyState>
          ) : (
            <DataTable
              caption={`Payments made against loan ${loan.reference}`}
              columns={columns}
              rows={history.state.data}
              rowKey={(row) => row.id}
            />
          ))}
      </Panel>

      {loan.status !== 'SETTLED' && (
        <div className="dash__actions">
          <Link to={routeTo.loanRepay(loan.id)} className="dash__action">
            Make a payment
          </Link>
        </div>
      )}
    </article>
  );
}
