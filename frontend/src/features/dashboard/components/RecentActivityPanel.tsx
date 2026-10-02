import type { ReactElement } from 'react';
import { AsyncPanel } from '@/components/AsyncPanel';
import { ActivityFeed } from '@/features/activity';
import { useAsync } from '@/hooks/useAsync';
import { accountService } from '@/services';
import type { Transaction } from '@/types/banking';

interface RecentActivityPanelProps {
  readonly scopeKey: string;
  readonly limit?: number;
  readonly title?: string;
}

/**
 * The newest movements across every visible account, in plain language.
 *
 * <p>THE ROWS ARE NOT WRITTEN HERE. They come from {@code ActivityFeed}, which the
 * notifications log uses too — the two screens differ in how many movements they ask for,
 * not in what a movement says, and two implementations of the same row is how a dashboard
 * and a log end up disagreeing about what happened.
 *
 * <p>This panel used to print the server's raw description and a category: "Transfer to
 * **** 7898" and "CASH". It named an account rather than a person, so the one question a
 * customer asks of their own activity — who was this? — was the one it could not answer.
 */
export function RecentActivityPanel({
  scopeKey,
  limit = 6,
  title = 'Recent activity',
}: RecentActivityPanelProps): ReactElement {
  const { state, reload } = useAsync(`${scopeKey}:recent:${String(limit)}`, (signal) =>
    accountService.recentTransactions(limit, signal),
  );

  return (
    <AsyncPanel title={title} state={state} reload={reload} skeletonRows={5}>
      {(rows: readonly Transaction[]) => (
        <ActivityFeed
          movements={rows}
          emptyMessage="Nothing has been posted to these accounts yet."
        />
      )}
    </AsyncPanel>
  );
}
