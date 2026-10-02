import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  AccountSelector,
  Alert,
  ErrorNotice,
  MoneyInput,
  Select,
  Skeleton,
  TextField,
} from '@/components/ui';
import type { ReviewRow } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService, beneficiaryService, transferService } from '@/services';
import type { MovementReceipt, TransferKind } from '@/types/movement';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto, formatBalanceDto } from '@/utils/money';
import { MoneyFlowShell } from './MoneyFlowShell';
import { useMoneyFlow } from './useMoneyFlow';
import './money.css';

interface TransferPageProps {
  readonly kind: TransferKind;
}

const COPY: Readonly<Record<TransferKind, { title: string; lead: string }>> = {
  OWN: {
    title: 'Move money between my accounts',
    lead: 'Instant, and free. Both accounts must be yours.',
  },
  INTERNAL: {
    title: 'Send to someone at this bank',
    lead: 'Arrives within minutes. The beneficiary must already be on your list.',
  },
  EXTERNAL: {
    title: 'Send to another bank in Rwanda',
    lead: 'Usually same day. A transfer fee applies and is shown before you confirm.',
  },
  INTERNATIONAL: {
    title: 'Send money abroad',
    lead: 'Up to three working days. Fees and the exchange rate are quoted by the bank.',
  },
};

const BENEFICIARY_TYPES: Readonly<Record<TransferKind, readonly string[]>> = {
  OWN: [],
  INTERNAL: ['INTERNAL'],
  EXTERNAL: ['DOMESTIC'],
  INTERNATIONAL: ['INTERNATIONAL'],
};

/**
 * One screen, four rails.
 *
 * The difference between moving money between your own accounts and wiring it abroad is
 * which destination field appears and what the bank charges — not the shape of the
 * journey. So they share the flow, and each rail supplies its own wording and its own
 * beneficiary filter.
 *
 * The fee shown on the review step is THE BANK'S QUOTE, fetched before that step is
 * reached. Nothing here adds an amount to a fee: the brief forbids the client computing
 * an authoritative figure, and a fee schedule duplicated in the browser is a fee
 * schedule that will one day disagree with the bank's.
 */
export function TransferPage({ kind }: TransferPageProps): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? session.user?.id ?? 'none';

  const accounts = useAsync(`transfer:accounts:${scope}`, (signal) => accountService.list(signal));
  const beneficiaries = useAsync(`transfer:beneficiaries:${scope}`, (signal) =>
    beneficiaryService.list(signal),
  );

  const [sourceId, setSourceId] = useState('');
  const [destinationId, setDestinationId] = useState('');
  const [beneficiaryId, setBeneficiaryId] = useState('');
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const [quoteError, setQuoteError] = useState<unknown>(null);
  const [quote, setQuote] = useState<Awaited<ReturnType<typeof transferService.quote>> | null>(
    null,
  );

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];
  const source = list.find((account) => account.id === sourceId);
  const currency = source?.currency ?? 'RWF';

  /**
   * The payees this rail can pay, AND ONLY THE ONES STAFF HAVE CLEARED.
   *
   * THE STATUS FILTER IS THE FIX TO A REAL HOLE. This filtered on `beneficiaryType` alone,
   * so a payee still waiting to be checked appeared in the dropdown and could be paid —
   * on the one screen in the portal that moves money, while the standing-order screen
   * beside it filtered correctly. The customer was told the payee was being held and the
   * screen let them send to it anyway, which is precisely the "add this account and send
   * the money now" script the check exists to interrupt.
   *
   * AND THIS FILTER IS NOT THE CONTROL. The server refuses an unapproved payee in
   * `BeneficiaryService.destinationFor`, because anything a browser applies can be skipped
   * by not using the browser. This is here so the customer is not offered a choice that
   * would be refused.
   */
  const payees =
    beneficiaries.state.status === 'ready'
      ? beneficiaries.state.data.filter(
          (b) => BENEFICIARY_TYPES[kind].includes(b.beneficiaryType) && b.status === 'ACTIVE',
        )
      : [];

  /**
   * Payees of the right kind that are not usable yet, counted so the empty state can
   * explain itself.
   *
   * Somebody who added a payee ten minutes ago and finds the dropdown empty concludes the
   * portal has lost it. Saying "one of your payees is still being checked" is the
   * difference between a wait and a fault.
   */
  const waitingPayees =
    beneficiaries.state.status === 'ready'
      ? beneficiaries.state.data.filter(
          (b) =>
            BENEFICIARY_TYPES[kind].includes(b.beneficiaryType) &&
            b.status === 'PENDING_VERIFICATION',
        ).length
      : 0;

  const flow = useMoneyFlow<MovementReceipt>((idempotencyKey, signal) =>
    transferService.submit(
      {
        kind,
        sourceAccountId: sourceId,
        ...(kind === 'OWN' ? { destinationAccountId: destinationId } : { beneficiaryId }),
        amount: { amount, currency },
        reference,
        quoteId: quote?.quoteId ?? '',
      },
      idempotencyKey,
      signal,
    ),
  );

  /** Fetches the bank's quote, then moves to review. Review cannot be reached without one. */
  const toReview = (): void => {
    setQuoteError(null);

    void transferService
      .quote({
        kind,
        sourceAccountId: sourceId,
        ...(kind === 'OWN' ? { destinationAccountId: destinationId } : { beneficiaryId }),
        amount: { amount, currency },
        reference,
      })
      .then((fetched) => {
        setQuote(fetched);
        flow.toReview();
      })
      .catch((cause: unknown) => {
        setQuoteError(cause);
      });
  };

  const validate = (): string | undefined => {
    if (sourceId === '') return 'Choose the account the money comes from.';
    if (kind === 'OWN' && destinationId === '') return 'Choose the account the money goes to.';
    if (kind !== 'OWN' && beneficiaryId === '') return 'Choose who you are paying.';
    if (amount === '' || Number(amount) <= 0) return 'Enter an amount.';
    if (reference.trim() === '') return 'Add a reference so you can recognise this later.';
    return undefined;
  };

  const reviewRows: readonly ReviewRow[] =
    quote === null
      ? []
      : [
          { label: 'From', value: `${source?.nickname ?? ''} ${source?.maskedNumber ?? ''}` },
          { label: 'To', value: quote.destinationSummary },
          { label: 'Amount', value: formatMoneyDto(quote.amount) },
          { label: 'Fee', value: formatMoneyDto(quote.fee) },
          { label: 'Total to leave your account', value: formatMoneyDto(quote.totalDebit) },
          { label: 'Reference', value: reference },
        ];

  if (accounts.state.status === 'loading') return <Skeleton rows={6} label="Loading accounts" />;

  return (
    <MoneyFlowShell
      title={COPY[kind].title}
      lead={COPY[kind].lead}
      crumbs={[{ label: 'Transfers', to: RETAIL_PATHS.transfers }]}
      flow={{ ...flow, toReview }}
      validate={validate}
      confirmLabel={quote === null ? 'Send' : `Send ${formatMoneyDto(quote.totalDebit)}`}
      reviewRows={reviewRows}
      form={
        <>
          {quoteError !== null && (
            <ErrorNotice error={quoteError} action="work out the cost of this transfer" />
          )}

          {quote?.warnings.map((warning) => (
            <Alert key={warning} tone="warning">
              {warning}
            </Alert>
          ))}

          <AccountSelector
            label="From"
            accounts={list}
            value={sourceId}
            debitOnly
            excludeId={destinationId}
            onChange={setSourceId}
            error={flow.fieldErrors['sourceAccountId']}
          />

          {kind === 'OWN' ? (
            <AccountSelector
              label="To"
              accounts={list}
              value={destinationId}
              excludeId={sourceId}
              onChange={setDestinationId}
              error={flow.fieldErrors['destinationAccountId']}
            />
          ) : (
            <>
              <Select
                label="To"
                value={beneficiaryId}
                placeholder={payees.length === 0 ? 'No beneficiaries yet' : 'Choose a payee…'}
                options={payees.map((payee) => ({
                  value: payee.id,
                  label: `${payee.name} — ${payee.maskedDestination} (${payee.provider})`,
                }))}
                error={flow.fieldErrors['beneficiaryId']}
                onChange={(event) => {
                  setBeneficiaryId(event.target.value);
                }}
              />
              {payees.length === 0 &&
                (waitingPayees > 0 ? (
                  /*
                   * A DIFFERENT MESSAGE FOR A DIFFERENT PROBLEM. "You have no saved payees"
                   * is false and unhelpful for somebody who added one an hour ago: they
                   * would add it again, and be refused as a duplicate.
                   */
                  <Alert tone="info" title="Your payee is still being checked">
                    <p>
                      {waitingPayees === 1
                        ? 'A payee you saved is still being checked by the bank, so it cannot be paid yet.'
                        : `${String(waitingPayees)} payees you saved are still being checked by the bank, so they cannot be paid yet.`}{' '}
                      We will email you as soon as that is done.
                    </p>
                    <p style={{ marginTop: 'var(--space-2)' }}>
                      <Link to={RETAIL_PATHS.beneficiaries}>See your payees</Link>
                    </p>
                  </Alert>
                ) : (
                  <Alert tone="info">
                    You have no saved payees for this kind of transfer.{' '}
                    <Link to={RETAIL_PATHS.beneficiaryNew}>Add one first</Link>. A member of staff
                    checks a new payee against the account before it can be paid, and we email you
                    when it is done.
                  </Alert>
                ))}
            </>
          )}

          <MoneyInput
            label="Amount"
            currency={currency}
            value={amount}
            onChange={setAmount}
            error={flow.fieldErrors['amount']}
            hint={
              source === undefined
                ? undefined
                : `Available: ${formatBalanceDto(source.availableBalance)}. The fee is added on top and shown before you confirm.`
            }
          />

          <TextField
            label="Reference"
            value={reference}
            maxLength={35}
            hint="Shown on your statement and on the beneficiary's."
            error={flow.fieldErrors['reference']}
            onChange={(event) => {
              setReference(event.target.value);
            }}
          />
        </>
      }
      renderResult={(receipt) => (
        <>
          <Alert
            tone={receipt.awaitingApproval ? 'info' : 'success'}
            title={
              receipt.awaitingApproval ? 'Sent for approval' : 'Done — the money is on its way'
            }
          >
            {receipt.awaitingApproval
              ? 'This has been prepared and is waiting for an approver at your company. Nothing has left the account yet.'
              : `${formatMoneyDto(receipt.totalDebit)} has left ${receipt.sourceAccountMask}.`}
          </Alert>

          <div className="money__receipt">
            <p className="money__reference numeric">{receipt.reference}</p>
            <p className="dash__row-meta">
              {receipt.destinationSummary} · {formatDateTime(receipt.submittedAt)}
            </p>
          </div>
        </>
      )}
    />
  );
}
