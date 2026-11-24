import { useState, type ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  AccountSelector,
  Alert,
  Button,
  ErrorNotice,
  MoneyInput,
  Skeleton,
} from '@/components/ui';
import type { ReviewRow } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { accountService, loanService } from '@/services';
import type { LoanRepayment, LoanRepaymentPreview } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import { formatBalanceDto, formatMoneyDto } from '@/utils/money';
import { MoneyFlowShell } from './MoneyFlowShell';
import { useMoneyFlow } from './useMoneyFlow';
import './money.css';

/**
 * Pays money off a loan.
 *
 * THE SPLIT IS THE BANK'S, AND THE SCREEN ASKS FOR IT. A payment divides into interest and
 * capital, the division depends on what is outstanding at this exact moment, and only the
 * part that is capital reduces the debt. That is the figure customers query, so it is shown
 * before they confirm -- computed by the server, which is the same function the ledger then
 * applies, rather than by this page, which would be a second copy free to disagree.
 *
 * NOTHING HERE IS CALCULATED. Not the settlement figure, not the interest, not the balance
 * afterwards. Every amount on the screen arrived from the API as a decimal string.
 */
export function LoanRepayPage(): ReactElement {
  const { loanId = '' } = useParams();
  const session = useSession();
  const scope = session.user?.id ?? 'none';

  const loans = useAsync('loans', (signal) => loanService.list(signal));
  const settlement = useAsync(`loan:settlement:${loanId}`, (signal) =>
    loanService.settlement(loanId, signal),
  );
  const accounts = useAsync(`loan:accounts:${scope}`, (signal) => accountService.list(signal));

  const [fromId, setFromId] = useState('');
  const [amount, setAmount] = useState('');
  const [preview, setPreview] = useState<LoanRepaymentPreview | null>(null);
  const [previewError, setPreviewError] = useState<unknown>(null);
  const [previewing, setPreviewing] = useState(false);

  const loan =
    loans.state.status === 'ready'
      ? loans.state.data.find((item) => item.id === loanId)
      : undefined;

  const figures = settlement.state.status === 'ready' ? settlement.state.data : undefined;
  const currency = figures?.currency ?? loan?.currency ?? 'RWF';

  /*
   * ONLY A CURRENT ACCOUNT IN THE LOAN'S OWN CURRENCY. The same rule the server enforces:
   * savings are where money is kept rather than paid from, and a repayment does not convert
   * on the way -- an exchange rate is the bank's to quote, not something this screen invents.
   */
  const payableFrom =
    accounts.state.status === 'ready'
      ? accounts.state.data.filter(
          (account) => account.accountType === 'CURRENT' && account.currency === currency,
        )
      : [];

  const from = payableFrom.find((account) => account.id === fromId);

  const flow = useMoneyFlow<LoanRepayment>((idempotencyKey, signal) =>
    loanService.repay(
      loanId,
      { amount: { amount, currency }, fromAccountId: fromId },
      idempotencyKey,
      signal,
    ),
  );

  const clearPreview = (): void => {
    setPreview(null);
    setPreviewError(null);
  };

  const workOutSplit = (): void => {
    setPreviewError(null);
    setPreviewing(true);

    void loanService
      .previewRepayment(loanId, { amount, currency })
      .then(setPreview)
      .catch((cause: unknown) => {
        setPreview(null);
        setPreviewError(cause);
      })
      .finally(() => {
        setPreviewing(false);
      });
  };

  const validate = (): string | undefined => {
    if (fromId === '') return 'Choose the account the money comes from.';
    if (amount === '' || Number(amount) <= 0) return 'Enter an amount to pay.';
    if (preview === null) return 'Work out the split before continuing.';
    return undefined;
  };

  const reviewRows: readonly ReviewRow[] =
    preview === null
      ? []
      : [
          { label: 'From', value: `${from?.nickname ?? ''} ${from?.maskedNumber ?? ''}` },
          { label: 'Loan', value: loan?.reference ?? loanId },
          { label: 'You pay', value: formatMoneyDto(preview.amount) },
          { label: 'Of which interest', value: formatMoneyDto(preview.interest) },
          { label: 'Of which capital', value: formatMoneyDto(preview.capital) },
          { label: 'Balance now', value: formatMoneyDto(preview.outstandingBefore) },
          { label: 'Balance after', value: formatMoneyDto(preview.outstandingAfter) },
        ];

  if (loans.state.status === 'loading' || settlement.state.status === 'loading') {
    return <Skeleton rows={6} label="Loading loan" />;
  }

  if (loan === undefined || figures === undefined) {
    return (
      <article className="money">
        <Alert tone="error" title="We could not find that loan">
          <Link to={RETAIL_PATHS.loans}>Back to loans</Link>
        </Alert>
      </article>
    );
  }

  if (loan.status === 'SETTLED') {
    return (
      <article className="money">
        <Alert tone="success" title="This loan is settled">
          <p>There is nothing left to pay on {loan.reference}.</p>
          <p className="money__note">
            <Link to={routeTo.loanStatements(loan.id)}>See its statement</Link>
          </p>
        </Alert>
      </article>
    );
  }

  return (
    <MoneyFlowShell
      title="Pay off a loan"
      lead={`${loan.product} — ${loan.reference}`}
      crumbs={[
        { label: 'Loans', to: RETAIL_PATHS.loans },
        { label: loan.reference, to: routeTo.loanDetail(loan.id) },
      ]}
      flow={flow}
      validate={validate}
      confirmLabel={
        preview === null ? 'Pay' : `Pay ${formatMoneyDto(preview.amount)}`
      }
      reviewRows={reviewRows}
      form={
        <>
          {previewError !== null && (
            <ErrorNotice error={previewError} action="work out that payment" />
          )}

          <Alert tone="info" title={`Outstanding ${formatMoneyDto(figures.outstanding)}`}>
            The instalment is {formatMoneyDto(figures.instalment)}. Paying{' '}
            {formatMoneyDto(figures.settlementFigure)} clears the loan today — that is the
            balance plus the interest owed on it, so it is more than the balance alone.
          </Alert>

          <AccountSelector
            label="Pay from"
            accounts={payableFrom}
            value={fromId}
            debitOnly
            onChange={setFromId}
            hint={
              from === undefined
                ? `Your ${currency} current account.`
                : `Available: ${formatBalanceDto(from.availableBalance)}`
            }
          />

          {payableFrom.length === 0 && (
            <Alert tone="warning">
              A loan is repaid from a current account held in {currency}, and you have none the
              bank will let this come out of. Call the bank before paying.
            </Alert>
          )}

          <MoneyInput
            label="Amount to pay"
            currency={currency}
            value={amount}
            onChange={(value) => {
              setAmount(value);
              /* Any change invalidates the split: it was worked out for the old amount. */
              clearPreview();
            }}
          />

          <div className="money__actions" style={{ marginTop: 'var(--space-3)' }}>
            <Button
              variant="secondary"
              disabled={previewing}
              onClick={() => {
                setAmount(figures.instalment.amount);
                clearPreview();
              }}
            >
              Use the instalment
            </Button>
            <Button
              variant="secondary"
              disabled={previewing}
              onClick={() => {
                setAmount(figures.settlementFigure.amount);
                clearPreview();
              }}
            >
              Clear the loan
            </Button>
          </div>

          <div className="money__actions" style={{ marginTop: 'var(--space-4)' }}>
            <Button
              variant="secondary"
              loading={previewing}
              disabled={amount === ''}
              onClick={workOutSplit}
            >
              Work out the split
            </Button>
          </div>

          {preview !== null && (
            <Alert
              tone="info"
              title={
                preview.settlesLoan
                  ? 'This clears the loan'
                  : `${formatMoneyDto(preview.capital)} comes off what you owe`
              }
            >
              {formatMoneyDto(preview.interest)} of this payment is interest, which is the price
              of having borrowed and does not reduce the balance.{' '}
              {preview.settlesLoan
                ? 'Nothing would be left to pay.'
                : `${formatMoneyDto(preview.outstandingAfter)} would be left.`}
            </Alert>
          )}
        </>
      }
      renderResult={(repayment) => (
        <>
          <Alert
            tone="success"
            title={`Paid ${formatMoneyDto(repayment.amount)}`}
          >
            {formatMoneyDto(repayment.capital)} came off the balance and{' '}
            {formatMoneyDto(repayment.interest)} was interest.{' '}
            {/*
              THE "SETTLED" TEST IS THE SERVER'S, taken from the split it worked out before
              the payment. Comparing the amount string to "0" would be this screen deciding
              whether a debt is cleared, and it would be wrong for any currency whose zero is
              written "0.00".
            */}
            {preview?.settlesLoan === true
              ? 'The loan is settled.'
              : `${formatMoneyDto(repayment.outstandingAfter)} is left to pay.`}
          </Alert>

          <div className="money__receipt">
            <p className="dash__row-meta">{formatDateTime(repayment.paidAt)}</p>
          </div>

          <p className="money__note">
            <Link to={routeTo.loanStatements(loan.id)}>See the loan statement</Link>
          </p>
        </>
      )}
    />
  );
}
