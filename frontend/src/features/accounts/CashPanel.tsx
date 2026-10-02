import { useState, type ReactElement } from 'react';
import { Alert, Button, MoneyInput, Panel, TextField } from '@/components/ui';
import { cashService } from '@/services';
import { ApiError } from '@/services/apiError';
import { IdempotencyScope } from '@/services/idempotency';
import type { Account } from '@/types/banking';
import type { CashReceipt } from '@/types/movement';
import { formatDateTime } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';

type Direction = 'deposit' | 'withdraw';

interface CashPanelProps {
  readonly account: Account;
  /** Called after a movement posts, so the page can re-read the account and its statement. */
  readonly onPosted: () => void;
}

/**
 * Paying into and taking out of one account.
 *
 * <p>THE FOUR RULES THIS SCREEN EXISTS TO KEEP, each of which is a way a portal loses
 * somebody's money:
 *
 * <ol>
 *   <li><b>One idempotency key per submission.</b> Held in an {@link IdempotencyScope} and
 *       reused for every retry of THAT submission, replaced only when the customer starts
 *       a genuinely new one. A fresh key on a retry is a second transaction.
 *   <li><b>The submit button is disabled while the request is in flight.</b> Two taps on a
 *       slow connection is the commonest way a deposit is made twice, and the key alone
 *       would not stop it if the second tap generated its own.
 *   <li><b>A timeout is PENDING CONFIRMATION, never a failure.</b> The money may well have
 *       moved. Telling the customer it did not is how they deposit it again.
 *   <li><b>The new balance comes from the receipt.</b> Nothing here adds the amount to the
 *       old balance. A client-side sum that disagrees with the server is a second opinion
 *       about somebody's money, and the customer has no way to tell which is right.
 * </ol>
 */
export function CashPanel({ account, onPosted }: CashPanelProps): ReactElement {
  const [direction, setDirection] = useState<Direction>('deposit');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState<CashReceipt | null>(null);
  const [failure, setFailure] = useState<unknown>(null);

  /*
   * ONE SCOPE FOR THE WHOLE PANEL, reset only when a movement completes or the customer
   * switches between paying in and taking out. Retrying a failed submission keeps the
   * key, which is the entire point: the server recognises the retry and returns the
   * original entry instead of moving the money again.
   */
  const [keys] = useState(() => new IdempotencyScope());

  const pending = failure instanceof ApiError && failure.kind === 'pendingConfirmation';

  /** A new transaction, not a retry of the last one. */
  const startFresh = (): void => {
    keys.reset();
    setAmount('');
    setNote('');
    setReceipt(null);
    setFailure(null);
  };

  const submit = (): void => {
    setBusy(true);
    setFailure(null);
    setReceipt(null);

    const call =
      direction === 'deposit' ? cashService.deposit : cashService.withdraw;

    void call(
      account.id,
      /*
       * The amount goes out as the STRING the customer typed. No parseFloat, no toFixed,
       * no rounding to the currency's scale here — MoneyInput already refuses a keystroke
       * the currency cannot express, and the server is the authority on the rest.
       */
      { amount, ...(note.trim() === '' ? {} : { description: note.trim() }) },
      keys.key,
    )
      .then((posted) => {
        setReceipt(posted);
        // The balance and the statement above are now stale; the page re-reads them.
        onPosted();
        keys.reset();
        setAmount('');
        setNote('');
      })
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  const verb = direction === 'deposit' ? 'Pay in' : 'Take out';

  return (
    <Panel title="Pay in or take out">
      <div className="dash__actions" style={{ marginBottom: 'var(--space-4)' }}>
        <Button
          variant={direction === 'deposit' ? 'primary' : 'secondary'}
          disabled={busy}
          onClick={() => {
            setDirection('deposit');
            startFresh();
          }}
        >
          Pay in
        </Button>
        <Button
          variant={direction === 'withdraw' ? 'primary' : 'secondary'}
          disabled={busy || !account.debitAllowed}
          onClick={() => {
            setDirection('withdraw');
            startFresh();
          }}
        >
          Take out
        </Button>
      </div>

      {direction === 'withdraw' && !account.debitAllowed && (
        <Alert tone="warning" title="Nothing can be taken out of this account">
          Money can still arrive, but this account cannot be debited while it is{' '}
          {account.status.toLowerCase()}.
        </Alert>
      )}

      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <MoneyInput
          label="Amount"
          currency={account.currency}
          value={amount}
          disabled={busy}
          onChange={setAmount}
          hint={
            /*
             * The AVAILABLE BALANCE as the server last sent it, shown for context and
             * never used as a rule. The screen does not refuse a withdrawal larger than
             * this figure: the balance may have moved since this page loaded, and a
             * client that decides what is affordable is a client that will eventually be
             * wrong in the customer's favour. The server refuses it, with a sentence.
             */
            account.availableBalance === undefined
              ? undefined
              : `Available: ${formatMoneyDto(account.availableBalance)}`
          }
          error={
            failure instanceof ApiError && failure.kind === 'validation'
              ? failure.fieldErrors.find((entry) => entry.field === 'amount')?.message
              : undefined
          }
        />

        <TextField
          label="What is this for?"
          value={note}
          required={false}
          maxLength={140}
          autoComplete="off"
          disabled={busy}
          hint="Optional. It appears on your statement."
          onChange={(event) => {
            setNote(event.target.value);
          }}
        />

        <div className="money__actions">
          <Button
            type="submit"
            loading={busy}
            /*
             * Disabled while in flight and while the amount is empty. The `loading` prop
             * already blocks the click; this is the second latch, because a double tap on
             * a slow connection is the commonest way a deposit happens twice.
             */
            disabled={busy || amount.trim() === '' || (direction === 'withdraw' && !account.debitAllowed)}
          >
            {verb} {amount.trim() === '' ? '' : `${account.currency} ${amount}`}
          </Button>
        </div>
      </form>

      {receipt !== null && (
        <Alert tone="success" title={verb === 'Pay in' ? 'Paid in' : 'Taken out'}>
          <p>
            {formatMoneyDto(receipt.amount)} on {formatDateTime(receipt.bookedAt)}.
          </p>
          <p>
            {/*
             * THE SERVER'S FIGURE. Not the old balance plus the amount — that sum would be
             * wrong the moment anything else touched the account, and wrong in a way the
             * customer would believe.
             */}
            Balance now {formatMoneyDto(receipt.balanceAfter)}.
          </p>
        </Alert>
      )}

      {pending && (
        <Alert tone="warning" title="Pending confirmation">
          <p>
            We did not hear back in time, so we cannot say yet whether this went through.
            The money may already have moved.
          </p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            Check the transactions below before trying again. If you do try again, use the
            button and not your browser&rsquo;s refresh &mdash; we will recognise it as the
            same request and will not move the money twice.
          </p>
          <div className="money__actions" style={{ marginTop: 'var(--space-3)' }}>
            {/* The SAME key, deliberately: this is a retry, not a new transaction. */}
            <Button variant="secondary" loading={busy} onClick={submit}>
              Try that again
            </Button>
            <Button variant="tertiary" disabled={busy} onClick={onPosted}>
              Refresh this account
            </Button>
          </div>
        </Alert>
      )}

      {failure instanceof ApiError && !pending && (
        <Alert
          tone="error"
          title={`We could not ${direction === 'deposit' ? 'pay that in' : 'take that out'}`}
          {...(failure.correlationId === undefined
            ? {}
            : { reference: failure.correlationId })}
        >
          <p>{failure.message}</p>
        </Alert>
      )}
    </Panel>
  );
}
