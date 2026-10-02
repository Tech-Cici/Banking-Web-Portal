import { useState, type ReactElement } from 'react';
import { AccountSelector, Alert, ErrorNotice, MoneyInput, Select, Skeleton, TextField } from '@/components/ui';
import type { ReviewRow } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService, paymentService } from '@/services';
import type { BillerCategory, MovementReceipt, MovementQuote } from '@/types/movement';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto, formatBalanceDto } from '@/utils/money';
import { MoneyFlowShell } from './MoneyFlowShell';
import { useMoneyFlow } from './useMoneyFlow';
import './money.css';

interface PaymentPageProps {
  readonly category: BillerCategory;
  readonly title: string;
  readonly lead: string;
}

/**
 * Pays a bill.
 *
 * One component for all seven biller categories. They differ in the biller list and in
 * what the reference field is called — meter number, decoder number, declaration
 * reference — and the biller record carries both, so the screen does not need a branch
 * per utility.
 *
 * A biller with `amountFixed` (tax, today) does NOT let the customer type an amount.
 * The figure comes back on the quote, from the biller. A tax payment the payer can round
 * down is not a tax payment, and letting the field exist and then overriding it would be
 * a worse lie than not offering it.
 */
export function PaymentPage({ category, title, lead }: PaymentPageProps): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? session.user?.id ?? 'none';

  const accounts = useAsync(`pay:accounts:${scope}`, (signal) => accountService.list(signal));
  const billers = useAsync(`pay:billers:${category}`, (signal) =>
    paymentService.billers(category, signal),
  );

  const [sourceId, setSourceId] = useState('');
  const [billerId, setBillerId] = useState('');
  const [customerReference, setCustomerReference] = useState('');
  const [amount, setAmount] = useState('');
  const [quote, setQuote] = useState<MovementQuote | null>(null);
  const [quoteError, setQuoteError] = useState<unknown>(null);

  const accountList = accounts.state.status === 'ready' ? accounts.state.data : [];
  const billerList = billers.state.status === 'ready' ? billers.state.data : [];

  const source = accountList.find((account) => account.id === sourceId);
  const biller = billerList.find((item) => item.id === billerId);
  const currency = biller?.currency ?? 'RWF';

  const flow = useMoneyFlow<MovementReceipt>((idempotencyKey, signal) =>
    paymentService.submit(
      {
        billerId,
        sourceAccountId: sourceId,
        customerReference,
        amount: { amount, currency },
        quoteId: quote?.quoteId ?? '',
      },
      idempotencyKey,
      signal,
    ),
  );

  const toReview = (): void => {
    setQuoteError(null);

    void paymentService
      .quote({
        billerId,
        sourceAccountId: sourceId,
        customerReference,
        amount: { amount: amount === '' ? '0' : amount, currency },
      })
      .then((fetched) => {
        setQuote(fetched);
        /*
         * For a fixed-amount biller the quote is the first time we learn the figure, so
         * the local amount is replaced by the bank's. This is not a display nicety: the
         * submit sends this value, and it must be the quoted one.
         */
        if (biller?.amountFixed === true) setAmount(fetched.amount.amount);
        flow.toReview();
      })
      .catch((cause: unknown) => {
        setQuoteError(cause);
      });
  };

  const validate = (): string | undefined => {
    if (sourceId === '') return 'Choose the account to pay from.';
    if (billerId === '') return 'Choose who you are paying.';
    if (customerReference.trim() === '') return `Enter your ${biller?.accountLabel.toLowerCase() ?? 'reference'}.`;
    if (biller?.amountFixed !== true && (amount === '' || Number(amount) <= 0)) {
      return 'Enter an amount.';
    }
    return undefined;
  };

  const reviewRows: readonly ReviewRow[] =
    quote === null
      ? []
      : [
          { label: 'From', value: `${source?.nickname ?? ''} ${source?.maskedNumber ?? ''}` },
          { label: 'Paying', value: biller?.name ?? '' },
          { label: biller?.accountLabel ?? 'Reference', value: customerReference },
          { label: 'Amount', value: formatMoneyDto(quote.amount) },
          { label: 'Fee', value: formatMoneyDto(quote.fee) },
          { label: 'Total to leave your account', value: formatMoneyDto(quote.totalDebit) },
        ];

  if (accounts.state.status === 'loading' || billers.state.status === 'loading') {
    return <Skeleton rows={6} label="Loading payment options" />;
  }

  return (
    <MoneyFlowShell
      title={title}
      lead={lead}
      crumbs={[{ label: 'Payments', to: RETAIL_PATHS.payments }]}
      flow={{ ...flow, toReview }}
      validate={validate}
      confirmLabel={quote === null ? 'Pay' : `Pay ${formatMoneyDto(quote.totalDebit)}`}
      reviewRows={reviewRows}
      form={
        <>
          {quoteError !== null && (
            <ErrorNotice error={quoteError} action="work out the cost of this bill" />
          )}

          <AccountSelector
            label="Pay from"
            accounts={accountList}
            value={sourceId}
            debitOnly
            onChange={setSourceId}
            error={flow.fieldErrors['sourceAccountId']}
          />

          <Select
            label="Who are you paying?"
            value={billerId}
            options={billerList.map((item) => ({ value: item.id, label: item.name }))}
            error={flow.fieldErrors['billerId']}
            onChange={(event) => {
              setBillerId(event.target.value);
              setQuote(null);
            }}
          />

          {biller !== undefined && (
            <TextField
              label={biller.accountLabel}
              hint={biller.accountHint}
              value={customerReference}
              error={flow.fieldErrors['customerReference']}
              onChange={(event) => {
                setCustomerReference(event.target.value);
                setQuote(null);
              }}
            />
          )}

          {biller?.amountFixed === true ? (
            <Alert tone="info" title="The amount comes from the biller">
              {biller.name} sets the amount for this reference. It is shown on the next step,
              before anything is paid.
            </Alert>
          ) : (
            <MoneyInput
              label="Amount"
              currency={currency}
              value={amount}
              onChange={setAmount}
              error={flow.fieldErrors['amount']}
              hint={
                source === undefined
                  ? undefined
                  : `Available: ${formatBalanceDto(source.availableBalance)}`
              }
            />
          )}
        </>
      }
      renderResult={(receipt) => (
        <>
          <Alert
            tone={receipt.awaitingApproval ? 'info' : 'success'}
            title={receipt.awaitingApproval ? 'Sent for approval' : 'Paid'}
          >
            {receipt.awaitingApproval
              ? 'This payment is waiting for an approver at your company. Nothing has left the account yet.'
              : `${formatMoneyDto(receipt.totalDebit)} has left ${receipt.sourceAccountMask}.`}
          </Alert>

          <div className="money__receipt">
            <p className="money__reference numeric">{receipt.reference}</p>
            <p className="dash__row-meta">
              {receipt.destinationSummary} · {formatDateTime(receipt.submittedAt)}
            </p>
            <p className="dash__row-meta">
              Keep this reference. It is what the biller will ask for if the payment is
              queried.
            </p>
          </div>
        </>
      )}
    />
  );
}
