import type { ReactElement, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, PageHeader, ReviewPanel, Stepper } from '@/components/ui';
import type { Crumb } from '@/components/ui';
import type { ReviewRow } from '@/components/ui';
import { RETAIL_PATHS } from '@/routes/paths';
import type { MoneyFlow } from './useMoneyFlow';
import './money.css';

interface MoneyFlowShellProps<TResult> {
  readonly title: string;
  readonly lead?: string;
  readonly crumbs?: readonly Crumb[];
  readonly flow: MoneyFlow<TResult>;
  /** The step-one fields. */
  readonly form: ReactNode;
  /** Exactly what is about to happen, in the customer's words. */
  readonly reviewRows: readonly ReviewRow[];
  /** Blocks the move to review — returns a reason, or undefined when the form is good. */
  readonly validate: () => string | undefined;
  /** Rendered on success. */
  readonly renderResult: (result: TResult) => ReactNode;
  /** Wording on the confirm button, e.g. "Send RWF 40,000". */
  readonly confirmLabel: string;
}

const STEPS = ['Details', 'Review', 'Result'] as const;

/**
 * The frame every money-moving screen is built in.
 *
 * Three steps, in one order, with no way to skip the middle one. The review step is not
 * a nicety — it is the last moment a customer can catch a wrong account number, and the
 * brief requires it before anything is sent.
 *
 * The result step is where most of the care is. Three outcomes, three different screens:
 *
 *  - Done. Show the reference and offer a genuinely new transaction, never a "resend".
 *  - Pending. The outcome is UNKNOWN. This screen says so in plain words, points at the
 *    transaction list, and offers no button that would send anything.
 *  - Failed. Only a refusal the customer can act on offers a way back to the form; a
 *    failure whose first attempt is unaccounted for does not.
 */
export function MoneyFlowShell<TResult>({
  title,
  lead,
  crumbs,
  flow,
  form,
  reviewRows,
  validate,
  renderResult,
  confirmLabel,
}: MoneyFlowShellProps<TResult>): ReactElement {
  const blocked = flow.step === 'form' ? validate() : undefined;
  const stepIndex = flow.step === 'form' ? 0 : flow.step === 'review' ? 1 : 2;

  return (
    <article className="money">
      <PageHeader title={title} {...(lead === undefined ? {} : { lead })} {...(crumbs === undefined ? {} : { crumbs })} />

      <Stepper steps={[...STEPS]} current={stepIndex} />

      {flow.step === 'form' && (
        <form
          className="money__form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            if (validate() === undefined) flow.toReview();
          }}
        >
          {form}

          {blocked !== undefined && (
            <p className="money__blocked" aria-live="polite">
              {blocked}
            </p>
          )}

          <Button type="submit" disabled={blocked !== undefined}>
            Continue to review
          </Button>
        </form>
      )}

      {flow.step === 'review' && (
        <>
          <Alert tone="warning" title="Check these details">
            Money moves as soon as you confirm. The bank cannot reverse a payment sent to the
            wrong account.
          </Alert>

          <ReviewPanel rows={reviewRows} />

          <div className="money__actions">
            <Button loading={flow.busy} onClick={flow.confirm}>
              {confirmLabel}
            </Button>
            <Button variant="secondary" disabled={flow.busy} onClick={flow.backToForm}>
              Change details
            </Button>
          </div>
        </>
      )}

      {flow.step === 'result' && flow.outcome !== null && (
        <div className="money__result">
          {flow.outcome.kind === 'done' && renderResult(flow.outcome.result)}

          {flow.outcome.kind === 'pending' && (
            <Alert
              tone="warning"
              title="We do not know yet whether this went through"
              reference={flow.outcome.reference}
            >
              <p>{flow.outcome.message}</p>
              <p className="money__note">
                <strong>Do not send it again.</strong> The payment may already have been made.
                Check your transactions in a few minutes — if it is not there by then, call the
                bank and quote the reference above.
              </p>
              <p className="money__note">
                <Link to={RETAIL_PATHS.accounts}>Check my accounts</Link>
              </p>
            </Alert>
          )}

          {flow.outcome.kind === 'failed' && (
            <Alert tone="error" title="This was not sent" reference={flow.outcome.reference}>
              <p>{flow.outcome.message}</p>
              {flow.outcome.retryable ? (
                <p className="money__note">
                  Nothing has left your account. Correct the details and try again.
                </p>
              ) : (
                <p className="money__note">
                  Please check your transactions before trying again, and quote the reference
                  above if you call the bank.
                </p>
              )}
            </Alert>
          )}

          <div className="money__actions">
            {flow.outcome.kind === 'failed' && flow.outcome.retryable && (
              <Button onClick={flow.backToForm}>Change details</Button>
            )}
            {flow.outcome.kind === 'done' && (
              <Button variant="secondary" onClick={flow.startOver}>
                Make another payment
              </Button>
            )}
            <Link to={RETAIL_PATHS.dashboard} className="money__link">
              Back to dashboard
            </Link>
          </div>
        </div>
      )}
    </article>
  );
}
