import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { EmptyState } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService } from '@/services';
import type { Account } from '@/types/banking';
import { AccountTiles } from '@/features/accounts/AccountTiles';

interface AccountsPanelProps {
  /** Reloads when the active company changes. */
  readonly scopeKey: string;
  readonly title: string;
  readonly subtitle?: string;
}

/**
 * Balances for every account the signed-in user can see.
 *
 * The API decides which accounts those are — personal ones for a retail customer, the
 * selected company's for a corporate user — so this component is the same for all three
 * roles and holds no filtering logic of its own. Filtering in the client is how one
 * company ends up seeing another's balances.
 *
 * There is deliberately NO combined total across accounts. Summing balances in the
 * browser produces a figure the bank never stated, and the brief forbids the client
 * computing an authoritative balance. When the product wants a headline total, the
 * backend should send one.
 */
export function AccountsPanel({ scopeKey, title, subtitle }: AccountsPanelProps): ReactElement {
  const { state, reload } = useAsync(scopeKey, (signal) => accountService.list(signal));

  return (
    <AsyncPanel
      title={title}
      subtitle={subtitle}
      state={state}
      reload={reload}
      skeletonRows={4}
      action={<Link to={RETAIL_PATHS.accounts}>All accounts</Link>}
    >
      {(accounts: readonly Account[]) =>
        accounts.length === 0 ? (
          <EmptyState message="No accounts are linked to this profile yet." />
        ) : (
          /*
           * TILES, not the list this used to render.
           *
           * The list gave every account the same weight and the same vertical space, so
           * finding one meant reading all of them. The tile row is scanned rather than
           * read, and every honest caveat the list carried moved with it — an unknown
           * balance still says so instead of showing a zero, and uncleared funds are
           * still called out.
           */
          <AccountTiles accounts={accounts} />
        )
      }
    </AsyncPanel>
  );
}
