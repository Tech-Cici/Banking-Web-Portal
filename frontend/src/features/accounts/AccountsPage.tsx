import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  Alert,
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
import { AccountTiles } from './AccountTiles';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { accountService } from '@/services';
import type { Account } from '@/types/banking';
import { formatAge } from '@/utils/datetime';
import { formatBalanceDto } from '@/utils/money';
import './accounts.css';

/**
 * Every account the signed-in user can see.
 *
 * A table, not cards: the point of this screen (unlike the dashboard) is comparing
 * accounts, and columns are what make figures comparable. Available and current sit in
 * separate columns for the same reason — a customer whose card was declined is here to
 * find out which of the two numbers the shop saw.
 */
export function AccountsPage(): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? session.user?.id ?? 'none';
  const { state, reload } = useAsync(`accounts:${scope}`, (signal) => accountService.list(signal));

  const columns: readonly Column<Account>[] = [
    {
      key: 'name',
      header: 'Account',
      render: (account) => (
        <>
          <Link to={routeTo.accountDetail(account.id)}>{account.nickname}</Link>
          <br />
          <span className="numeric" style={{ color: 'var(--color-text-muted)' }}>
            {account.maskedNumber}
          </span>
        </>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      secondary: true,
      render: (account) => account.accountType.replace(/_/g, ' ').toLowerCase(),
    },
    {
      key: 'available',
      header: 'Available',
      numeric: true,
      render: (account) => formatBalanceDto(account.availableBalance),
    },
    {
      key: 'current',
      header: 'Current',
      numeric: true,
      secondary: true,
      render: (account) => formatBalanceDto(account.currentBalance),
    },
    {
      key: 'updated',
      header: 'Updated',
      secondary: true,
      render: (account) => (account.balanceAsOf === undefined ? '—' : formatAge(account.balanceAsOf)),
    },
    {
      key: 'status',
      header: 'Status',
      render: (account) => (
        <StatusBadge
          tone={toneForStatus(account.status)}
          label={humaniseStatus(account.status)}
        />
      ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Accounts"
        lead="Balances come from the core banking system and are a snapshot, not a live feed."
      />

      {state.status === 'loading' && <Skeleton rows={6} label="Loading accounts" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your accounts" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <button type="button" className="ui-btn ui-btn--secondary" onClick={reload}>
              Try again
            </button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (state.data.length === 0 ? (
          <EmptyState message="No accounts are linked to this profile." />
        ) : (
          <>
            {/*
              TILES FIRST, THE TABLE UNDER THEM, and both on purpose.
              
              The tiles are how somebody finds the account they came for — scanned, not
              read. The table is how somebody compares them: available against current,
              side by side, sortable by eye. Replacing the table with tiles would have
              taken away the only place those two figures sit next to each other for every
              account at once.
            */}
            <AccountTiles accounts={state.data} />

            <h2 className="account__section">Every account, side by side</h2>

            <DataTable
              caption="Your accounts, with available and current balances"
              columns={columns}
              rows={state.data}
              rowKey={(account) => account.id}
              rowTone={(account) => (account.status === 'ACTIVE' ? 'default' : 'warning')}
            />

            <p style={{ marginTop: 'var(--space-6)' }}>
              <Link to={RETAIL_PATHS.statements}>Statements</Link>
            </p>
          </>
        ))}
    </article>
  );
}
