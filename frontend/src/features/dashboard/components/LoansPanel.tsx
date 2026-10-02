import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { EmptyState, humaniseStatus, StatusBadge, toneForStatus } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { loanService } from '@/services';
import type { Loan } from '@/types/banking';
import { daysUntil, formatDate } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';

/**
 * Loans, with the next instalment made prominent.
 *
 * Figures are shown exactly as the bank sent them. No payoff amount is computed here and
 * no interest is projected: a settlement figure quoted by a web page and then contradicted
 * at the counter is a complaint, and the brief forbids the client calculating one.
 *
 * `daysUntil` is the one derived value, and it is a calendar count, not money.
 */
export function LoansPanel({ scopeKey }: { readonly scopeKey: string }): ReactElement {
  const { state, reload } = useAsync(`${scopeKey}:loans`, (signal) => loanService.list(signal));

  return (
    <AsyncPanel
      title="Loans"
      state={state}
      reload={reload}
      action={<Link to={RETAIL_PATHS.loans}>Manage</Link>}
    >
      {(loans: readonly Loan[]) =>
        loans.length === 0 ? (
          <EmptyState message="You have no loans with the bank.">
            <p className="dash__note">
              <Link to={RETAIL_PATHS.loanSimulation}>Try the loan calculator</Link>
            </p>
          </EmptyState>
        ) : (
          <ul className="dash__rows">
            {loans.map((loan) => {
              const days = daysUntil(loan.nextDueDate);

              return (
                <li key={loan.id} className="dash__row">
                  <div className="dash__row-main">
                    <p className="dash__row-title">{loan.product}</p>
                    <p className="dash__row-meta numeric">{loan.reference}</p>
                    <p className="dash__row-meta">
                      Next instalment {formatMoneyDto(loan.installment)} on{' '}
                      {formatDate(loan.nextDueDate)}
                      {days !== null && days >= 0 && ` (in ${String(days)} days)`}
                      {days !== null && days < 0 && ' — overdue'}
                    </p>
                  </div>

                  <div className="dash__row-side">
                    <p className="dash__amount">{formatMoneyDto(loan.outstandingBalance)}</p>
                    <p className="dash__row-meta">outstanding</p>
                    <StatusBadge
                      tone={toneForStatus(loan.status)}
                      label={humaniseStatus(loan.status)}
                    />
                  </div>
                </li>
              );
            })}
          </ul>
        )
      }
    </AsyncPanel>
  );
}
