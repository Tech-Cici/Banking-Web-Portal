import { useState, type ReactElement } from 'react';
import { AccountSelector, Alert, Button, ErrorNotice, MoneyInput, Select, Skeleton } from '@/components/ui';
import type { ReviewRow } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { accountService, fxService } from '@/services';
import type { FxQuote, MovementReceipt } from '@/types/movement';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto, formatBalanceDto } from '@/utils/money';
import { ACCOUNT_CURRENCIES } from '@/types/admin';
import { MoneyFlowShell } from './MoneyFlowShell';
import { useMoneyFlow } from './useMoneyFlow';
import './money.css';

/*
 * THE BANK'S CURRENCIES, from the one list. This page had its own -- RWF, USD and EUR --
 * which quietly omitted GBP, a currency customers hold accounts in and the bank quotes a
 * rate for. A screen with its own copy of a list is a screen that falls behind it.
 */
const CURRENCIES = ACCOUNT_CURRENCIES;

/**
 * Buys one currency with another, at the bank's quoted rate.
 *
 * Both sides of the conversion come from the server. The screen NEVER multiplies an
 * amount by a rate to show the other side — that is the single most tempting client-side
 * calculation in a banking app and the one most likely to disagree with the deal the
 * customer actually gets, because the bank's rate carries spread and rounding rules the
 * browser does not know about.
 *
 * The rate expires. That is shown, and it is why the quote is re-fetched rather than
 * cached when the customer goes back to change something.
 */
export function FxPage(): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? session.user?.id ?? 'none';

  const accounts = useAsync(`fx:accounts:${scope}`, (signal) => accountService.list(signal));

  const [sourceId, setSourceId] = useState('');
  const [sellAmount, setSellAmount] = useState('');
  const [buyCurrency, setBuyCurrency] = useState('USD');

  /*
   * WHERE THE BOUGHT CURRENCY LANDS, chosen rather than guessed. The server used to work it
   * out from the currency alone, which for a customer holding two dollar accounts meant the
   * older of them -- and a fixed deposit is a locked term, not somewhere a conversion belongs
   * by default.
   */
  const [destinationId, setDestinationId] = useState('');
  const [quote, setQuote] = useState<FxQuote | null>(null);
  const [quoteError, setQuoteError] = useState<unknown>(null);
  const [quoting, setQuoting] = useState(false);

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];
  const source = list.find((account) => account.id === sourceId);
  const sellCurrency = source?.currency ?? 'RWF';

  /* Only an account held in the currency being bought can receive it. */
  const canReceive = list.filter(
    (account) => account.currency === buyCurrency && account.id !== sourceId,
  );

  const flow = useMoneyFlow<MovementReceipt>((idempotencyKey, signal) =>
    fxService.submit(
      {
        quoteId: quote?.quoteId ?? '',
        sourceAccountId: sourceId,
        destinationAccountId: destinationId,
      },
      idempotencyKey,
      signal,
    ),
  );

  const getQuote = (): void => {
    setQuoteError(null);
    setQuoting(true);

    void fxService
      .quote({ amount: sellAmount, currency: sellCurrency }, buyCurrency)
      .then(setQuote)
      .catch((cause: unknown) => {
        setQuote(null);
        setQuoteError(cause);
      })
      .finally(() => {
        setQuoting(false);
      });
  };

  const validate = (): string | undefined => {
    if (sourceId === '') return 'Choose the account to sell from.';
    if (sellAmount === '' || Number(sellAmount) <= 0) return 'Enter an amount to convert.';
    if (sellCurrency === buyCurrency) return 'Choose two different currencies.';
    if (destinationId === '') return 'Choose the account the money goes into.';
    if (quote === null) return 'Get a rate before continuing.';
    return undefined;
  };

  const reviewRows: readonly ReviewRow[] =
    quote === null
      ? []
      : [
          { label: 'From', value: `${source?.nickname ?? ''} ${source?.maskedNumber ?? ''}` },
          { label: 'You sell', value: formatMoneyDto(quote.sellAmount) },
          { label: 'You get', value: formatMoneyDto(quote.buyAmount) },
          { label: 'Rate', value: quote.rate },
          { label: 'Fee', value: formatMoneyDto(quote.fee) },
          { label: 'Rate held until', value: formatDateTime(quote.rateExpiresAt) },
        ];

  if (accounts.state.status === 'loading') return <Skeleton rows={6} label="Loading accounts" />;

  return (
    <MoneyFlowShell
      title="Foreign exchange"
      lead="Convert between your own accounts at the bank's quoted rate."
      flow={flow}
      validate={validate}
      confirmLabel={quote === null ? 'Convert' : `Convert ${formatMoneyDto(quote.sellAmount)}`}
      reviewRows={reviewRows}
      form={
        <>
          {quoteError !== null && (
            <ErrorNotice error={quoteError} action="get you a rate" />
          )}

          <AccountSelector
            label="Sell from"
            accounts={list}
            value={sourceId}
            debitOnly
            onChange={(id) => {
              setSourceId(id);
              setQuote(null);
            }}
          />

          <MoneyInput
            label="Amount to sell"
            currency={sellCurrency}
            value={sellAmount}
            onChange={(value) => {
              setSellAmount(value);
              // Any change invalidates the quote. A stale rate must never be submitted.
              setQuote(null);
            }}
            hint={
              source === undefined
                ? undefined
                : `Available: ${formatBalanceDto(source.availableBalance)}`
            }
          />

          <Select
            label="Buy"
            value={buyCurrency}
            options={CURRENCIES.filter((code) => code !== sellCurrency).map((code) => ({
              value: code,
              label: code,
            }))}
            onChange={(event) => {
              setBuyCurrency(event.target.value);
              /* The old destination is in the wrong currency now. */
              setDestinationId('');
              setQuote(null);
            }}
          />

          {/*
            WHICH ACCOUNT IT ARRIVES IN. Not derived from the currency: a customer holding
            voluntary savings and a fixed deposit in dollars has two real choices, and only
            they know which one they meant.
          */}
          <AccountSelector
            label="Into"
            accounts={canReceive}
            value={destinationId}
            onChange={setDestinationId}
          />

          {canReceive.length === 0 && (
            <Alert tone="info">
              You have no {buyCurrency} account for the money to arrive in. The bank has to open
              one before you can buy {buyCurrency}.
            </Alert>
          )}

          <div className="money__actions" style={{ marginTop: 'var(--space-4)' }}>
            <Button
              variant="secondary"
              loading={quoting}
              disabled={sourceId === '' || sellAmount === ''}
              onClick={getQuote}
            >
              Get a rate
            </Button>
          </div>

          {quote !== null && (
            <Alert tone="info" title={`You get ${formatMoneyDto(quote.buyAmount)}`}>
              Rate {quote.rate}, held until {formatDateTime(quote.rateExpiresAt)}. Both figures
              are quoted by the bank.
            </Alert>
          )}
        </>
      }
      renderResult={(receipt) => (
        <>
          <Alert
            tone={receipt.awaitingApproval ? 'info' : 'success'}
            title={receipt.awaitingApproval ? 'Sent for approval' : 'Converted'}
          >
            {receipt.destinationSummary}
          </Alert>
          <div className="money__receipt">
            <p className="money__reference numeric">{receipt.reference}</p>
            <p className="dash__row-meta">{formatDateTime(receipt.submittedAt)}</p>
          </div>
        </>
      )}
    />
  );
}
