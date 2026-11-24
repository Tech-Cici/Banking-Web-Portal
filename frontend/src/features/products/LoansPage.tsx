import type { ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  EmptyState,
  humaniseStatus,
  PageHeader,
  Panel,
  ReviewPanel,
  Skeleton,
  StatusBadge,
  toneForStatus,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { loanService } from '@/services';
import { daysUntil, formatDate } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import './products.css';

/**
 * Loans the customer holds.
 *
 * Every figure on this screen is the bank's. There is no computed payoff amount, no
 * projected interest and no "you could save X by paying early" — a settlement figure
 * depends on the exact date, accrued interest and any early-settlement charge, and a
 * number this screen invented would be contradicted at the counter.
 */
export function LoansPage(): ReactElement {
  const { state, reload } = useAsync('loans', (signal) => loanService.list(signal));

  return (
    <article>
      <PageHeader
        title="Loans"
        lead="Balances and instalments as the bank has them today."
        /*
          APPLYING IS REACHABLE WHETHER OR NOT THEY ALREADY HAVE A LOAN. It used to appear
          only in the empty state, so a customer who had ever borrowed — including one whose
          loan was fully paid off — had no route to a new application except typing the URL.
        */
        action={
          <span className="products__head-actions">
            <Link to={RETAIL_PATHS.loanRequest}>Apply for a loan</Link>
            <Link to={RETAIL_PATHS.loanSimulation}>Work out a repayment</Link>
          </span>
        }
      />

      {state.status === 'loading' && <Skeleton rows={5} label="Loading loans" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your loans" reference={state.reference}>
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
          <EmptyState message="You have no loans with the bank.">
            <p className="dash__note">
              <Link to={RETAIL_PATHS.loanRequest}>Apply for one</Link> or{' '}
              <Link to={RETAIL_PATHS.loanSimulation}>try the calculator</Link>.
            </p>
          </EmptyState>
        ) : (
          <div className="products__grid">
            {state.data.map((loan) => {
              const days = daysUntil(loan.nextDueDate);

              return (
                <Panel
                  key={loan.id}
                  title={loan.product}
                  subtitle={loan.reference}
                  action={
                    <StatusBadge
                      tone={toneForStatus(loan.status)}
                      label={humaniseStatus(loan.status)}
                    />
                  }
                >
                  <ReviewPanel
                    rows={[
                      { label: 'Outstanding', value: formatMoneyDto(loan.outstandingBalance) },
                      { label: 'Original amount', value: formatMoneyDto(loan.principal) },
                      { label: 'Instalment', value: formatMoneyDto(loan.installment) },
                      {
                        label: 'Next due',
                        value:
                          days === null
                            ? formatDate(loan.nextDueDate)
                            : days < 0
                              ? `${formatDate(loan.nextDueDate)} — overdue`
                              : `${formatDate(loan.nextDueDate)} (in ${String(days)} days)`,
                      },
                      { label: 'Interest rate', value: `${loan.interestRatePercent}% a year` },
                    ]}
                  />

                  {loan.status === 'IN_ARREARS' && (
                    <Alert tone="error" title="This loan is behind">
                      Call the bank to agree a plan. Arrears affect your credit record.
                    </Alert>
                  )}

                  <div className="dash__actions">
                    <Link to={routeTo.loanDetail(loan.id)} className="dash__action">
                      Details
                    </Link>
                    {loan.status !== 'SETTLED' && (
                      <Link to={routeTo.loanRepay(loan.id)} className="dash__action">
                        Make a payment
                      </Link>
                    )}
                    <Link to={routeTo.loanStatements(loan.id)} className="dash__action">
                      Loan statement
                    </Link>
                  </div>
                </Panel>
              );
            })}
          </div>
        ))}
    </article>
  );
}

/** One loan in full. Same figures, no extra maths, plus the schedule the bank publishes. */
export function LoanDetailPage(): ReactElement {
  const { loanId = '' } = useParams();
  const { state } = useAsync('loans', (signal) => loanService.list(signal));

  if (state.status === 'loading') return <Skeleton rows={6} label="Loading loan" />;

  const loan = state.status === 'ready' ? state.data.find((item) => item.id === loanId) : undefined;

  if (loan === undefined) {
    return (
      <article>
        <PageHeader title="Loan" crumbs={[{ label: 'Loans', to: RETAIL_PATHS.loans }]} />
        <Alert tone="error" title="We could not find that loan">
          <Link to={RETAIL_PATHS.loans}>Back to loans</Link>
        </Alert>
      </article>
    );
  }

  return (
    <article className="products__narrow">
      <PageHeader
        title={loan.product}
        lead={loan.reference}
        crumbs={[{ label: 'Loans', to: RETAIL_PATHS.loans }]}
        action={
          <StatusBadge tone={toneForStatus(loan.status)} label={humaniseStatus(loan.status)} />
        }
      />

      <ReviewPanel
        caption="Loan details"
        rows={[
          { label: 'Outstanding balance', value: formatMoneyDto(loan.outstandingBalance) },
          { label: 'Original amount', value: formatMoneyDto(loan.principal) },
          { label: 'Instalment', value: formatMoneyDto(loan.installment) },
          { label: 'Next due', value: formatDate(loan.nextDueDate) },
          { label: 'Interest rate', value: `${loan.interestRatePercent}% a year` },
          { label: 'Currency', value: loan.currency },
        ]}
      />

      {/*
        THE SETTLEMENT FIGURE IS NOW THE BANK'S TO GIVE, so this no longer sends the customer
        to a counter for it. It is still not the outstanding balance — it is that balance plus
        the interest owed on it — and the payment screen states both before anything is paid.
      */}
      <Alert tone="info" title="Clearing it early">
        The amount that clears this loan today is more than the outstanding balance: interest
        is owed on what you have borrowed. The payment screen shows the exact figure.
      </Alert>

      <div className="dash__actions">
        {loan.status !== 'SETTLED' && (
          <Link to={routeTo.loanRepay(loan.id)} className="dash__action">
            Make a payment
          </Link>
        )}
        <Link to={routeTo.loanStatements(loan.id)} className="dash__action">
          Loan statement
        </Link>
      </div>
    </article>
  );
}
