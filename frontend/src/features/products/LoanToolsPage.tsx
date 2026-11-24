import { useCallback, useState, type ReactElement } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AccountSelector, Alert, Button, ErrorNotice, MoneyInput, PageHeader, ReviewPanel, Select, Skeleton, TextArea } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService, loanService } from '@/services';
import type { LoanSimulation } from '@/services';
import type { CollateralEntry } from '@/types/banking';
import { CollateralSection } from './CollateralSection';
import { LOAN_PRODUCT_OPTIONS } from '@/types/banking';
import { formatMoneyDto } from '@/utils/money';
import './products.css';

const TERMS = [6, 12, 24, 36, 48, 60];

/**
 * Indicative repayment figures for a loan the customer is considering.
 *
 * The maths is done BY THE BANK, not here. It would be four lines of JavaScript to
 * amortise a loan in the browser, and that is exactly the trap: the bank's figure
 * includes its own rounding, fees and day-count conventions, and a calculator that
 * disagrees with the offer letter by a few hundred francs destroys trust in both.
 *
 * So this screen collects three inputs, asks the server, and displays what comes back —
 * clearly labelled indicative, because an indication is what it is.
 */
export function LoanSimulationPage(): ReactElement {
  const [amount, setAmount] = useState('');
  const [months, setMonths] = useState(12);
  const [product, setProduct] = useState('PERSONAL');

  const [result, setResult] = useState<LoanSimulation | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);

  const run = (): void => {
    setBusy(true);
    setFailure(null);

    void loanService
      .simulate({ amount: { amount, currency: 'RWF' }, months, product })
      .then(setResult)
      .catch((cause: unknown) => {
        setResult(null);
        setFailure(
          cause,
        );
      })
      .finally(() => {
        setBusy(false);
      });
  };

  return (
    <article className="products__narrow">
      <PageHeader
        title="Work out a repayment"
        lead="An indication only. The bank's offer is what counts."
        crumbs={[{ label: 'Loans', to: RETAIL_PATHS.loans }]}
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="work out that repayment" />
      )}

      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (amount !== '' && Number(amount) > 0) run();
        }}
      >
        <MoneyInput label="How much do you want to borrow?" currency="RWF" value={amount} onChange={setAmount} />

        <Select
          label="Over how long?"
          value={String(months)}
          options={TERMS.map((term) => ({
            value: String(term),
            label: `${String(term)} months`,
          }))}
          onChange={(event) => {
            setMonths(Number(event.target.value));
          }}
        />

        <Select
          label="What kind of loan?"
          value={product}
          options={LOAN_PRODUCT_OPTIONS}
          onChange={(event) => {
            setProduct(event.target.value);
          }}
        />

        <Button type="submit" loading={busy} disabled={amount === '' || Number(amount) <= 0}>
          Work it out
        </Button>
      </form>

      {result !== null && (
        <>
          <ReviewPanel
            caption="Indicative figures"
            rows={[
              { label: 'You borrow', value: formatMoneyDto(result.principal) },
              { label: 'Monthly repayment', value: formatMoneyDto(result.monthlyInstallment) },
              { label: 'Interest rate', value: `${result.interestRatePercent}% a year` },
              { label: 'Total interest', value: formatMoneyDto(result.totalInterest) },
              { label: 'Total to repay', value: formatMoneyDto(result.totalRepayable) },
              { label: 'Over', value: `${String(result.months)} months` },
            ]}
          />

          <Alert tone="warning" title="This is an indication, not an offer">
            The rate you are offered depends on an assessment the bank has not done yet.
            Nothing here is a commitment by either side.
          </Alert>

          <div className="money__actions">
            <Link to={RETAIL_PATHS.loanRequest} className="dash__action">
              Apply for this loan
            </Link>
          </div>
        </>
      )}
    </article>
  );
}

/**
 * Applies for a loan.
 *
 * A service request, not a transaction: nothing moves, the bank assesses it and comes
 * back. So the screen ends by saying what happens next and how long it takes, which is
 * the question every applicant actually has.
 */
export function LoanRequestPage(): ReactElement {
  const navigate = useNavigate();
  const accounts = useAsync('loan-request:accounts', (signal) => accountService.list(signal));

  const [amount, setAmount] = useState('');
  const [months, setMonths] = useState(12);
  const [product, setProduct] = useState('PERSONAL');
  const [disburseTo, setDisburseTo] = useState('');
  const [purpose, setPurpose] = useState('');
  const [collateral, setCollateral] = useState<CollateralEntry | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [reference, setReference] = useState<string | null>(null);

  /*
   * WHAT THE BANK PUBLISHES, including which products need security. Fetched rather than
   * listed here so the form and the rule cannot drift apart, and so the sentence shown to
   * the customer is the one the server composed from the rule it will actually apply.
   */
  const products = useAsync('loan-request:products', (signal) => loanService.products(signal));

  const published = products.state.status === 'ready' ? products.state.data : [];
  const chosenProduct = published.find((item) => item.code === product);

  /*
   * STABLE, BECAUSE THE SECTION REPORTS ITS PLEDGE FROM AN EFFECT. A fresh function on every
   * render would be a new dependency every render, and the effect would run forever.
   */
  const onCollateralChange = useCallback((entry: CollateralEntry | null) => {
    setCollateral(entry);
  }, []);

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];

  /*
   * WHETHER SECURITY IS NEEDED IS NOT DECIDED HERE. The button does not compare the amount
   * to a threshold -- that is the server's rule, and a copy of it in the browser is a second
   * rule to keep in step. If something is required and missing, the application comes back
   * refused with a sentence saying so, which is the one place the rule lives.
   */
  const ready = amount !== '' && Number(amount) > 0 && disburseTo !== '' && purpose.trim() !== '';

  const submit = (): void => {
    setBusy(true);
    setFailure(null);

    void loanService
      .apply({
        amount: { amount, currency: 'RWF' },
        months,
        product,
        disburseToAccountId: disburseTo,
        purpose: purpose.trim(),
        ...(collateral === null ? {} : { collateral: [collateral] }),
      })
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

  if (accounts.state.status === 'loading') return <Skeleton rows={6} label="Loading accounts" />;

  if (reference !== null) {
    return (
      <article className="products__narrow">
        <PageHeader title="Application sent" crumbs={[{ label: 'Loans', to: RETAIL_PATHS.loans }]} />
        <Alert tone="success" title="We have your application">
          <p>
            Your reference is <strong className="numeric">{reference}</strong>. Keep it — the
            bank will quote it when they contact you.
          </p>
          <p className="money__note">
            An assessment usually takes two to three working days. You will be notified in the
            app and by SMS. Nothing has been credited to your account and no commitment has
            been made by either side.
          </p>
        </Alert>
        <div className="money__actions">
          <Button
            onClick={() => {
              void navigate(RETAIL_PATHS.loans);
            }}
          >
            Back to loans
          </Button>
        </div>
      </article>
    );
  }

  return (
    <article className="products__narrow">
      <PageHeader
        title="Apply for a loan"
        lead="The bank assesses every application. Sending this is not a commitment."
        crumbs={[{ label: 'Loans', to: RETAIL_PATHS.loans }]}
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="send your loan application" />
      )}

      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) submit();
        }}
      >
        <MoneyInput label="How much?" currency="RWF" value={amount} onChange={setAmount} />

        <Select
          label="Over how long?"
          value={String(months)}
          options={TERMS.map((term) => ({ value: String(term), label: `${String(term)} months` }))}
          onChange={(event) => {
            setMonths(Number(event.target.value));
          }}
        />

        <Select
          label="What kind of loan?"
          value={product}
          options={LOAN_PRODUCT_OPTIONS}
          onChange={(event) => {
            setProduct(event.target.value);
          }}
        />

        <AccountSelector
          label="Pay it into"
          accounts={list}
          value={disburseTo}
          onChange={setDisburseTo}
        />

        <TextArea
          label="What is it for?"
          hint="A sentence is enough. It helps the assessment."
          value={purpose}
          rows={3}
          onChange={(event) => {
            setPurpose(event.target.value);
          }}
        />

        <CollateralSection
          note={chosenProduct?.collateralNote}
          onChange={onCollateralChange}
        />

        <div className="money__actions">
          <Button type="submit" loading={busy} disabled={!ready}>
            Send application
          </Button>
          <Link to={RETAIL_PATHS.loanSimulation} className="money__link">
            Work out a repayment first
          </Link>
        </div>
      </form>
    </article>
  );
}
