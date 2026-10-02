import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AccountSelector, Alert, Button, DataTable, EmptyState, ErrorNotice, PageHeader, Select, Skeleton, StatusBadge, humaniseStatus, toneForStatus, type Column } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService, serviceRequestService } from '@/services';
import type { ServiceRequest } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import './products.css';

/*
 * NO BRANCH LIST, for the reason recorded at the top of CardsPage: the six names that used
 * to be here were invented by the front end, and the bank has never supplied a branch
 * list. Staff name the collection point once the book exists.
 */

/**
 * Cheque books the customer has asked for, and where each has got to.
 *
 * <p>THIS READS THE REAL SERVICE-REQUEST LIST, not a `/cheque-books` inventory. That
 * endpoint existed only in the browser's mock and returned a fabricated list of issued
 * books; the portal has no cheque inventory and no way to know of one. What it does know
 * is what the customer asked for and what staff did about it, which is a row in
 * `service_requests` — so that is what this table shows, and the mocked endpoint is gone.
 */
export function ChequeBooksPage(): ReactElement {
  const { state, reload } = useAsync('cheque-book-requests', (signal) =>
    serviceRequestService.list(signal),
  );

  /* One endpoint serves both kinds, so this screen shows its own. */
  const chequeBooks =
    state.status === 'ready' ? state.data.filter((row) => row.requestType === 'CHEQUE_BOOK') : [];

  const columns: readonly Column<ServiceRequest>[] = [
    {
      key: 'ref',
      header: 'Reference',
      render: (row) => <span className="numeric">{row.reference}</span>,
    },
    { key: 'details', header: 'What you asked for', render: (row) => row.details },
    {
      key: 'collect',
      header: 'Collect from',
      secondary: true,
      /*
       * AN EM DASH UNTIL THE BANK HAS SAID. This column used to render a branch the
       * customer had picked from a list the front end invented. There is nothing to show
       * until a member of staff has produced the book and typed where it is.
       */
      render: (row) => row.collectionPoint ?? '—',
    },
    {
      key: 'requested',
      header: 'Requested',
      secondary: true,
      render: (row) => formatDateTime(row.submittedAt),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
          {row.status === 'SUBMITTED' && (
            <p className="dash__row-meta">
              The bank is making it. We will email you when it is ready.
            </p>
          )}
          {row.status === 'READY' && (
            <p className="dash__row-meta">Ready to collect. Bring photo identification.</p>
          )}
          {row.status === 'DECLINED' && (
            <p className="dash__row-meta">
              {row.declineReason ?? 'We were not able to action this.'}
            </p>
          )}
        </>
      ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Cheque books"
        lead="Cheque books are printed centrally and collected in person."
        action={<Link to={RETAIL_PATHS.chequeBookNew}>Request one</Link>}
      />

      {state.status === 'loading' && <Skeleton rows={4} label="Loading cheque books" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your cheque books" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (chequeBooks.length === 0 ? (
          <EmptyState message="You have not requested a cheque book.">
            <p className="dash__note">
              <Link to={RETAIL_PATHS.chequeBookNew}>Request one</Link>
            </p>
          </EmptyState>
        ) : (
          <DataTable
            caption="Cheque books you have requested"
            columns={columns}
            rows={chequeBooks}
            rowKey={(row) => row.id}
            rowTone={(row) => (row.status === 'DECLINED' ? 'error' : 'default')}
          />
        ))}
    </article>
  );
}

/** Requests a cheque book. */
export function ChequeBookNewPage(): ReactElement {
  const accounts = useAsync('cheque:accounts', (signal) => accountService.list(signal));

  const [accountId, setAccountId] = useState('');
  const [leaves, setLeaves] = useState(25);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [reference, setReference] = useState<string | null>(null);

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];

  /*
   * Only current accounts get cheque books. Offering a savings account and failing at
   * the counter wastes a trip, so the list is narrowed here as well as on the server.
   */
  const eligible = list.filter((account) => account.accountType === 'CURRENT');

  const submit = (): void => {
    setBusy(true);
    setFailure(null);

    void serviceRequestService
      .raise({ requestType: 'CHEQUE_BOOK', accountId, leaves })
      .then((created) => {
        setReference(created.reference);
      })
      .catch((cause: unknown) => {
        setFailure(cause);
      })
      .finally(() => {
        setBusy(false);
      });
  };

  if (accounts.state.status === 'loading') return <Skeleton rows={5} label="Loading accounts" />;

  if (reference !== null) {
    return (
      <article className="products__narrow">
        <PageHeader
          title="Cheque book requested"
          crumbs={[{ label: 'Cheque books', to: RETAIL_PATHS.chequeBooks }]}
        />
        <Alert tone="success" title="We have your request">
          <p>
            Your reference is <strong className="numeric">{reference}</strong>.
          </p>
          {/*
            "Printing takes about a week" was a constant in this file, not a figure the bank
            gave us, and the branch came from the invented list. Both are gone.
          */}
          <p className="money__note">
            We will email you when it is ready, and tell you where to collect it. Bring photo
            identification when you do.
          </p>
          <p className="money__note">
            You can check on it any time under <strong>Cheque books</strong>.
          </p>
        </Alert>
        <div className="money__actions">
          <Link to={RETAIL_PATHS.chequeBooks} className="dash__action">
            Back to cheque books
          </Link>
        </div>
      </article>
    );
  }

  return (
    <article className="products__narrow">
      <PageHeader
        title="Request a cheque book"
        crumbs={[{ label: 'Cheque books', to: RETAIL_PATHS.chequeBooks }]}
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="send your cheque book request" />
      )}

      {eligible.length === 0 ? (
        <EmptyState message="Cheque books are only issued on current accounts, and you do not have one." />
      ) : (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            if (accountId !== '') submit();
          }}
        >
          <AccountSelector
            label="For which account?"
            accounts={eligible}
            value={accountId}
            onChange={setAccountId}
          />

          <Select
            label="How many leaves?"
            value={String(leaves)}
            options={[
              { value: '25', label: '25 leaves' },
              { value: '50', label: '50 leaves' },
              { value: '100', label: '100 leaves' },
            ]}
            onChange={(event) => {
              setLeaves(Number(event.target.value));
            }}
          />

          <div className="money__actions">
            <Button type="submit" loading={busy} disabled={accountId === ''}>
              Request cheque book
            </Button>
            <Link to={RETAIL_PATHS.chequeBooks} className="money__link">
              Cancel
            </Link>
          </div>
        </form>
      )}
    </article>
  );
}
