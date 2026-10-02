import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { EmptyState } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { internalTransferService } from '@/services';
import type { InternalTransfer } from '@/types/movement';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import '@/features/accounts/tiles.css';

/**
 * Money that has left this customer's accounts and arrived nowhere.
 *
 * <p>WHY THIS PANEL EXISTS AT ALL. Submitting a transfer debits the sender straight away
 * and the beneficiary is not credited until a manager releases it, so between those two
 * moments the money is simply gone from the balance with nothing on the dashboard saying
 * where. That gap is the single most alarming thing this portal can show somebody, and it
 * was showing it.
 *
 * <p>NO TOTAL. Each amount is printed exactly as the server stated it and nothing is
 * added up here. Summing them would mean either adding across currencies — inventing an
 * exchange rate — or printing a figure the bank never stated, which is the rule the
 * balance panels already keep. The count is a count of instructions, not of money.
 */

interface HeldPanelProps {
  /** Reloads when the active company changes. */
  readonly scopeKey: string;
}

export function HeldPanel({ scopeKey }: HeldPanelProps): ReactElement {
  const { state, reload } = useAsync(`${scopeKey}:held`, (signal) =>
    internalTransferService.pending(signal),
  );

  return (
    <AsyncPanel
      title="Waiting for the bank"
      subtitle="Money you have sent that a manager has not released yet."
      state={state}
      reload={reload}
      skeletonRows={3}
      action={<Link to={RETAIL_PATHS.transfers}>All transfers</Link>}
    >
      {(pending: readonly InternalTransfer[]) =>
        pending.length === 0 ? (
          /*
           * Worded as a fact about the money, not as "no results". "Nothing pending"
           * leaves a customer wondering whether the page failed to load; this says the
           * thing they wanted to know.
           */
          <EmptyState message="None of your money is on hold." />
        ) : (
          <div>
            <p className="held__figure">
              {pending.length} {pending.length === 1 ? 'transfer' : 'transfers'}
            </p>
            <p className="held__label">
              Already taken from your balance. Each one reaches the other account only when
              a manager releases it, and comes back if they refuse it.
            </p>

            <ul className="held__rows">
              {pending.map((transfer) => (
                <li key={transfer.id} className="held__row">
                  <span>
                    {transfer.reference}
                    <br />
                    <span style={{ color: 'var(--color-text-muted)' }}>
                      Sent {formatDateTime(transfer.submittedAt)}
                    </span>
                  </span>
                  <strong className="numeric">
                    {formatMoneyDto({ amount: transfer.amount, currency: transfer.currency })}
                  </strong>
                </li>
              ))}
            </ul>
          </div>
        )
      }
    </AsyncPanel>
  );
}
