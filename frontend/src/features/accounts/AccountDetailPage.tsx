import { useState, type ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { CashPanel } from './CashPanel';
import {
  Alert,
  Button,
  humaniseStatus,
  PageHeader,
  ReviewPanel,
  Skeleton,
  StatusBadge,
  toneForStatus,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { accountService } from '@/services';
import { BalanceBar } from './BalanceBar';
import { failureTitle, friendlyError } from '@/services/errorMessage';
import type { Account } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto, formatBalanceDto } from '@/utils/money';

/**
 * One account: its balances, its details and the things you can do to it.
 *
 * THE HOLDER CAN SEE THEIR OWN NUMBER, behind a deliberate tap.
 *
 * This page used to say the opposite: masked here as everywhere else, no reveal control,
 * nothing to reveal. That was over-applied and it broke something — the transfer form
 * tells a sender to type "the full number, not the masked one", so a customer who could
 * never see their own number could not be paid. A passbook number is not a secret from
 * the person whose passbook it is.
 *
 * It is still not shown by default. The mask is what belongs on a glanceable page that
 * gets screenshotted and read over shoulders; the number arrives only when asked for, in
 * its own request, and a
 * screen that could show one is a screen that can leak one into a screenshot.
 */
export function AccountDetailPage(): ReactElement {
  const { accountId = '' } = useParams();
  const session = useSession();
  const canTransfer = session.can('TRANSFER_CREATE') || session.can('CORPORATE_TRANSFER_CREATE');

  const { state, reload } = useAsync(`account:${accountId}`, (signal) =>
    accountService.byId(accountId, signal),
  );

  const recent = useAsync(`account:${accountId}:tx`, (signal) =>
    accountService.transactions(accountId, { size: 5 }, signal),
  );

  /*
   * Fetched on demand rather than with the account, so the full number reaches the
   * client only when the customer asks for it — one payload, requested on purpose,
   * instead of sitting in every account response and every devtools tab.
   */
  const [fullNumber, setFullNumber] = useState<string | null>(null);
  const [revealing, setRevealing] = useState(false);
  const [revealFailed, setRevealFailed] = useState<unknown>(null);
  const [copied, setCopied] = useState(false);

  const reveal = (): void => {
    setRevealing(true);
    setRevealFailed(null);

    void accountService
      .fullNumber(accountId)
      .then((found) => {
        setFullNumber(found.accountNumber);
      })
      .catch(setRevealFailed)
      .finally(() => {
        setRevealing(false);
      });
  };

  const copy = (): void => {
    if (fullNumber === null) return;

    /*
     * Wrapped, and with a fallback that is honest about failing. The clipboard is refused
     * outright in some contexts, and a button that silently does nothing is worse than
     * one that says it could not.
     */
    void navigator.clipboard
      .writeText(fullNumber)
      .then(() => {
        setCopied(true);
        window.setTimeout(() => {
          setCopied(false);
        }, 2000);
      })
      .catch(() => {
        setCopied(false);
      });
  };

  if (state.status === 'loading') return <Skeleton rows={6} label="Loading account" />;

  if (state.status === 'error') {
    return (
      <article>
        <PageHeader title="Account" crumbs={[{ label: 'Accounts', to: RETAIL_PATHS.accounts }]} />
        <Alert tone="error" title="We could not open this account" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Link to={RETAIL_PATHS.accounts}>Back to accounts</Link>
          </p>
        </Alert>
      </article>
    );
  }

  const account: Account = state.data;

  return (
    <article>
      <PageHeader
        title={account.nickname}
        lead={`${account.accountType.replace(/_/g, ' ').toLowerCase()} · ${account.maskedNumber}`}
        crumbs={[{ label: 'Accounts', to: RETAIL_PATHS.accounts }]}
        action={
          <StatusBadge
            tone={toneForStatus(account.status)}
            label={humaniseStatus(account.status)}
          />
        }
      />

      {!account.debitAllowed && (
        <Alert tone="warning" title="This account cannot be debited">
          Money can still arrive, but nothing can be sent from it while it is{' '}
          {account.status.toLowerCase()}. Call the bank to reactivate it.
        </Alert>
      )}

      {revealFailed !== null &&
        (() => {
          /*
           * THROUGH THE SHARED TRANSLATOR, not a sentence written here.
           *
           * This alert used to read "Please try again" whatever had happened, which is
           * the exact failure friendlyError exists to prevent: a dropped connection, an
           * account that is not yours and an endpoint the running server does not have
           * are three different problems, and one of them is not fixed by trying again.
           * It also dropped the support reference, so a customer reporting this had
           * nothing to quote and nobody could find the request in the log.
           */
          const friendly = friendlyError(revealFailed);

          return (
            <Alert
              tone="error"
              title={failureTitle('show that number')}
              {...(friendly.reference === undefined ? {} : { reference: friendly.reference })}
            >
              <p>{friendly.summary}</p>
              {friendly.nextStep !== undefined && (
                <p style={{ marginTop: 'var(--space-2)' }}>{friendly.nextStep}</p>
              )}
              <p style={{ marginTop: 'var(--space-2)' }}>
                The masked number below is still correct.
              </p>
            </Alert>
          );
        })()}

      {/*
        THE PROPORTION, BEFORE THE TABLE OF FIGURES.
        
        "Why can't I spend all of my own money" is the commonest question a contact centre
        takes, and the answer was previously one row in a list of six. It renders nothing
        when the two balances agree, so an ordinary account is not given a full bar and a
        sentence saying nothing.
      */}
      <BalanceBar account={account} />

      <ReviewPanel
        caption="Account details"
        rows={[
          { label: 'Available balance', value: formatBalanceDto(account.availableBalance) },
          { label: 'Current balance', value: formatBalanceDto(account.currentBalance) },
          { label: 'Currency', value: account.currency },
          {
            label: 'Account number',
            value:
              fullNumber === null ? (
                <span className="acct__reveal">
                  <span className="numeric">{account.maskedNumber}</span>
                  <Button variant="tertiary" loading={revealing} onClick={reveal}>
                    Show full number
                  </Button>
                </span>
              ) : (
                <span className="acct__reveal">
                  <strong className="numeric">{fullNumber}</strong>
                  <Button variant="tertiary" onClick={copy}>
                    {copied ? 'Copied' : 'Copy'}
                  </Button>
                </span>
              ),
          },
          {
            label: 'Balance as of',
            /*
             * Never a date when there is no balance. A timestamp beside "Not available"
             * reads as "this was the balance at that moment", which is the opposite of
             * what it means.
             */
            value:
              account.balanceAsOf === undefined
                ? 'Not available'
                : formatDateTime(account.balanceAsOf),
          },
        ]}
      />

      {/*
        * Paying in and taking out, below the balance and above the statement — the order
        * somebody reads them in: what have I got, what am I doing, did it land.
        */}
      <CashPanel
        account={account}
        onPosted={() => {
          /*
           * BOTH, and neither is optional. The balance and the statement are two views of
           * the same ledger; refreshing one would leave the screen showing a new balance
           * beside a statement that does not explain it.
           */
          reload();
          recent.reload();
        }}
      />

      <div className="dash__actions" style={{ marginBottom: 'var(--space-6)' }}>
        <Link to={routeTo.accountTransactions(account.id)} className="dash__action">
          All transactions
        </Link>
        <Link to={routeTo.accountStatementNew(account.id)} className="dash__action">
          Request a statement
        </Link>
        {canTransfer && account.debitAllowed && (
          <Link to={RETAIL_PATHS.transferOwn} className="dash__action">
            Move money
          </Link>
        )}
      </div>

      <AsyncPanel
        title="Latest transactions"
        state={recent.state}
        reload={recent.reload}
        action={<Link to={routeTo.accountTransactions(account.id)}>See all</Link>}
      >
        {(page) => (
          <ul className="dash__rows">
            {page.content.map((row) => (
              <li key={row.id} className="dash__row">
                <div className="dash__row-main">
                  <p className="dash__row-title">{row.description}</p>
                  <p className="dash__row-meta">
                    {formatDateTime(row.bookedAt)} · {row.reference}
                  </p>
                </div>
                <div className="dash__row-side">
                  <p
                    className={
                      row.direction === 'CREDIT'
                        ? 'dash__amount dash__amount--credit'
                        : 'dash__amount'
                    }
                  >
                    {row.direction === 'CREDIT' ? '+' : '−'} {formatMoneyDto(row.amount)}
                  </p>
                  <StatusBadge
                    tone={toneForStatus(row.status)}
                    label={humaniseStatus(row.status)}
                  />
                </div>
              </li>
            ))}
          </ul>
        )}
      </AsyncPanel>
    </article>
  );
}
