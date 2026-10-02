import { useState, type ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  humaniseStatus,
  PageHeader,
  Pagination,
  Select,
  Skeleton,
  StatusBadge,
  TextField,
  toneForStatus,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { accountService, type TransactionQuery } from '@/services';
import { describeMovement } from '@/features/activity';
import type { Transaction } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import './accounts.css';

/**
 * Transaction history for one account, with filters and paging.
 *
 * The filters are APPLIED ON THE SERVER, not to a list already in the browser. An
 * account has years of history; fetching it all to filter it client-side would be slow
 * on a good connection and impossible on a bad one, and it would quietly search only
 * the page you happen to have.
 *
 * Filters are held in component state and committed on Apply rather than on every
 * keystroke. A request per character is a request per character.
 */
export function TransactionsPage(): ReactElement {
  const { accountId = '' } = useParams();

  const [draft, setDraft] = useState<TransactionQuery>({});
  const [query, setQuery] = useState<TransactionQuery>({});
  const [page, setPage] = useState(0);

  /* The key carries the filters, so changing them refetches rather than reusing a
     cached page that answered a different question. */
  const key = `tx:${accountId}:${JSON.stringify(query)}:${String(page)}`;

  const { state, reload } = useAsync(key, (signal) =>
    accountService.transactions(accountId, { ...query, page, size: 20 }, signal),
  );

  const columns: readonly Column<Transaction>[] = [
    {
      key: 'when',
      header: 'Date',
      render: (row) => formatDateTime(row.bookedAt),
    },
    {
      key: 'what',
      header: 'Description',
      /*
       * THE SENTENCE FIRST, the server's own words under it.
       *
       * This column used to show only the description, which named an account and not a
       * person: "Transfer to **** 7898". The same wording function the dashboard and the
       * notifications log use now supplies the headline, so a statement, a dashboard row
       * and a notification describe one event in one way.
       *
       * The description is kept, verbatim, because it is where the server says things the
       * headline must not paraphrase — "Sent, awaiting approval" is the difference between
       * money on its way and money delivered.
       */
      render: (row) => {
        const line = describeMovement(row);

        return (
          <>
            {line.headline}
            <br />
            <span style={{ color: 'var(--color-text-muted)' }}>{line.detail}</span>
            <br />
            <span className="numeric" style={{ color: 'var(--color-text-muted)' }}>
              {row.reference}
            </span>
          </>
        );
      },
    },
    { key: 'category', header: 'Category', secondary: true, render: (row) => row.category },
    {
      key: 'amount',
      header: 'Amount',
      numeric: true,
      render: (row) => (
        <span className={row.direction === 'CREDIT' ? 'dash__amount--credit' : ''}>
          {row.direction === 'CREDIT' ? '+' : '−'} {formatMoneyDto(row.amount)}
        </span>
      ),
    },
    {
      key: 'balance',
      header: 'Balance',
      numeric: true,
      secondary: true,
      render: (row) =>
        row.runningBalance === undefined ? '—' : formatMoneyDto(row.runningBalance),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
      ),
    },
  ];

  const apply = (): void => {
    setPage(0);
    setQuery(draft);
  };

  return (
    <article>
      <PageHeader
        title="Transactions"
        crumbs={[
          { label: 'Accounts', to: RETAIL_PATHS.accounts },
          { label: 'Account', to: routeTo.accountDetail(accountId) },
        ]}
        action={<Link to={routeTo.accountStatementNew(accountId)}>Request a statement</Link>}
      />

      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault();
          apply();
        }}
      >
        <TextField
          label="From"
          type="date"
          required={false}
          value={draft.from ?? ''}
          onChange={(event) => {
            setDraft((current) => ({ ...current, from: event.target.value }));
          }}
        />
        <TextField
          label="To"
          type="date"
          required={false}
          value={draft.to ?? ''}
          onChange={(event) => {
            setDraft((current) => ({ ...current, to: event.target.value }));
          }}
        />
        <Select
          label="Direction"
          required={false}
          placeholder="Money in and out"
          value={draft.direction ?? ''}
          options={[
            { value: 'CREDIT', label: 'Money in' },
            { value: 'DEBIT', label: 'Money out' },
          ]}
          onChange={(event) => {
            const value = event.target.value;
            setDraft((current) => {
              /*
               * `exactOptionalPropertyTypes` means "no direction" is the ABSENCE of the
               * key, not the key set to undefined. Deleting it keeps the query object
               * honest, so the service never serialises `direction=undefined`.
               */
              const { direction: _cleared, ...rest } = current;
              return value === 'CREDIT' || value === 'DEBIT'
                ? { ...rest, direction: value }
                : rest;
            });
          }}
        />
        <TextField
          label="Search"
          required={false}
          hint="Description or reference"
          value={draft.search ?? ''}
          onChange={(event) => {
            setDraft((current) => ({ ...current, search: event.target.value }));
          }}
        />

        <div className="filters__actions">
          <Button type="submit">Apply</Button>
          <Button
            variant="tertiary"
            onClick={() => {
              setDraft({});
              setQuery({});
              setPage(0);
            }}
          >
            Clear
          </Button>
        </div>
      </form>

      {state.status === 'loading' && <Skeleton rows={8} label="Loading transactions" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load these transactions" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (state.data.content.length === 0 ? (
          <EmptyState message="No transactions match those filters." />
        ) : (
          <>
            <DataTable
              caption="Transactions on this account"
              columns={columns}
              rows={state.data.content}
              rowKey={(row) => row.id}
              rowTone={(row) =>
                row.status === 'FAILED' || row.status === 'REJECTED'
                  ? 'error'
                  : row.status === 'PENDING_CONFIRMATION'
                    ? 'warning'
                    : 'default'
              }
            />
            <Pagination
              page={state.data.page}
              totalPages={state.data.totalPages}
              totalElements={state.data.totalElements}
              onPage={setPage}
            />
          </>
        ))}
    </article>
  );
}
