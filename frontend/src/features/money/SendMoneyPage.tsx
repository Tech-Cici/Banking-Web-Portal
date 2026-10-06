import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  AccountSelector,
  Alert,
  Button,
  ErrorNotice,
  MoneyInput,
  PageHeader,
  ReviewPanel,
  Skeleton,
  TextField,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService, ApiError, internalTransferService } from '@/services';
import type { InternalTransfer, ResolvedBeneficiary } from '@/types/movement';
import { formatBalanceDto } from '@/utils/money';
import './money.css';

type Destination = 'MINE' | 'SOMEBODY_ELSE';

/**
 * MOVING MONEY INSIDE ZIGAMA, against the service that exists.
 *
 * <p>WHY THIS IS A SEPARATE PAGE FROM {@code TransferPage}. That one serves four rails
 * through a quote step, a fee and a list of saved payees, and answers to mocks: there is
 * no fee schedule, no quote endpoint and no beneficiary table in the service. Pointing it
 * at the real API would have meant sending a quote id the server has never issued.
 *
 * <p>THE THING THIS PAGE MUST NOT DO is claim the money has arrived. Submitting debits the
 * sender immediately and the beneficiary is credited only when a manager approves, so the
 * only honest thing to show afterwards is that it is waiting — and that the money has
 * already left. A receipt reading "on its way" would be describing a state the service
 * does not have.
 */
export function SendMoneyPage(): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? session.user?.id ?? 'none';

  const accounts = useAsync(`send:accounts:${scope}`, (signal) => accountService.list(signal));

  const [destination, setDestination] = useState<Destination>('MINE');
  const [sourceId, setSourceId] = useState('');
  const [ownDestinationId, setOwnDestinationId] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');

  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [sent, setSent] = useState<InternalTransfer | null>(null);

  /*
   * WHO THIS FORM WAS FILLED IN FOR, when that is no longer who is signed in.
   *
   * A browser has ONE session cookie, not one per tab. So a tab left open on this page
   * while somebody signs in as a different customer elsewhere — another tab, or the same
   * tab in another window — keeps showing the first customer's accounts and sends with
   * the second customer's session. The server refuses it, correctly and with a 404 that
   * says the source account could not be found, and the customer is left staring at an
   * account that is plainly on their screen.
   *
   * Found in a real log: a sign-in at 16:36:14, then twenty-one seconds later a send from
   * an account belonging to the customer who had been signed in a minute earlier.
   */
  const [signedInAsSomebodyElse, setSignedInAsSomebodyElse] = useState<string | null>(null);

  /*
   * A REVIEW STEP, because the mistake this flow makes is unrecoverable.
   *
   * Every other guard here catches something the server can also catch. Sending the right
   * amount to the wrong account is the one nothing catches: the number is valid, the money
   * is there, and the manager approving it has no idea who the sender meant to pay. So the
   * name comes back from the server and is shown beside the amount before anything moves.
   */
  const [payee, setPayee] = useState<ResolvedBeneficiary | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const [checking, setChecking] = useState(false);

  /*
   * ONE KEY PER FILLED-IN FORM, minted when the form is first shown and kept until the
   * transfer succeeds. Minting it inside the submit handler would give a different key to
   * every click, which is the same as having none: the retry the key exists to absorb
   * would arrive looking like a second instruction.
   */
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];
  const source = list.find((account) => account.id === sourceId);
  const currency = source?.currency ?? 'RWF';

  const validate = (): string | undefined => {
    if (sourceId === '') return 'Choose the account the money comes from.';
    if (destination === 'MINE' && ownDestinationId === '') {
      return 'Choose the account the money goes to.';
    }
    if (destination === 'SOMEBODY_ELSE' && accountNumber.trim() === '') {
      return 'Enter the account number you are sending to.';
    }
    if (amount.trim() === '') return 'Enter an amount.';
    if (reference.trim() === '') return 'Add a reference so you can recognise this later.';
    return undefined;
  };

  const localProblem = validate();

  /**
   * Moves to the review step, fetching the beneficiary's name on the way.
   *
   * <p>For one of the customer's own accounts there is nobody to look up — the account is
   * already named in the picker — so it goes straight to review. For somebody else's, the
   * lookup is what makes the review worth having, and a failure here stops the flow: a
   * review that could not name the payee is the screen this step exists to avoid.
   */
  const toReview = (): void => {
    setFailure(null);
    setFieldErrors({});

    if (destination === 'MINE') {
      setReviewing(true);
      return;
    }

    setChecking(true);
    void internalTransferService
      .resolve(accountNumber.trim())
      .then((found) => {
        setPayee(found);
        setReviewing(true);
      })
      .catch(setFailure)
      .finally(() => {
        setChecking(false);
      });
  };

  /** The account the money is going to, named for the review panel. */
  const destinationLabel = (): string => {
    if (destination === 'MINE') {
      const target = list.find((account) => account.id === ownDestinationId);
      return target === undefined
        ? 'One of your accounts'
        : `${target.nickname} ${target.maskedNumber} — your account`;
    }
    return payee === null
      ? accountNumber
      : `${payee.holderName} — ${payee.maskedNumber}`;
  };

  const submit = (): void => {
    setBusy(true);
    setFailure(null);
    setFieldErrors({});

    void internalTransferService
      .submit(
        {
          sourceAccountId: sourceId,
          ...(destination === 'MINE'
            ? { destinationAccountId: ownDestinationId }
            : { destinationAccountNumber: accountNumber.trim() }),
          amount,
          reference,
        },
        idempotencyKey,
      )
      .then((transfer) => {
        setSent(transfer);
        setReviewing(false);
        setPayee(null);
        // A new instruction needs a new key; this one has been spent.
        setIdempotencyKey(crypto.randomUUID());
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiError) {
          setFieldErrors(Object.fromEntries(cause.fieldErrors.map((v) => [v.field, v.message])));

          /*
           * "We could not find the account you are sending from" has one cause that is
           * not the customer's fault and not a fault in this screen: they are no longer
           * the customer this page was drawn for. Asked of the server rather than
           * guessed — if the session has genuinely changed, that is the message worth
           * showing, and if it has not, the ordinary refusal stands.
           */
          if (cause.kind === 'notFound') {
            void session.refreshAndGet().then((current) => {
              if (current !== null && current.id !== scope) {
                setSignedInAsSomebodyElse(current.fullName);
              }
            });
          }
        }
        setFailure(cause);
      })
      .finally(() => {
        setBusy(false);
      });
  };

  if (accounts.state.status === 'loading') return <Skeleton rows={6} label="Loading accounts" />;

  /* ------------------------------------------------------------ the receipt */

  if (sent !== null) {
    return (
      <article className="money__narrow">
        <PageHeader
          title="Sent for approval"
          crumbs={[{ label: 'Transfers', to: RETAIL_PATHS.transfers }]}
        />

        {/*
          NOT "the money is on its way" and not a tick. The money has left this account
          and has NOT arrived: a manager decides, and until they do the beneficiary has
          nothing. Saying otherwise is the one thing this screen must never do.
        */}
        <Alert tone="info" title="The money has left your account and is waiting for approval">
          <p>
            {sent.currency} {sent.amount} is on hold. A manager at the bank has to release it
            before it reaches the account you sent it to.
          </p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            If it is refused, the money comes straight back and you will see the reason on
            your statement.
          </p>
        </Alert>

        <div className="money__receipt">
          <p className="money__reference numeric">{sent.reference}</p>
          <p className="dash__row-meta">Waiting for approval · {sent.status}</p>
        </div>

        <div className="money__actions">
          <Link to={RETAIL_PATHS.transfers} className="dash__action">
            Back to transfers
          </Link>
          <Button
            variant="secondary"
            onClick={() => {
              setSent(null);
              setAmount('');
              setReference('');
              setPayee(null);
              setReviewing(false);
            }}
          >
            Send another
          </Button>
        </div>
      </article>
    );
  }

  /* --------------------------------------------------------------- the form */

  return (
    <article className="money__narrow">
      <PageHeader
        title="Send money"
        lead="To another account in this demo. Every transfer is released by a manager."
        crumbs={[{ label: 'Transfers', to: RETAIL_PATHS.transfers }]}
      />

      {/*
        BEFORE the ordinary error, and instead of leaving it to stand alone. "We could not
        find the account you are sending from" is true and useless when the account is
        visible on screen; this says what actually happened.
      */}
      {signedInAsSomebodyElse !== null && (
        <Alert tone="warning" title="You are signed in as somebody else now">
          <p>
            This page was filled in for a different customer, and the browser is now signed
            in as {signedInAsSomebodyElse}. A browser holds one sign-in at a time, so
            another tab or window signing in replaced this one.
          </p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            Nothing was sent. Reload the page to start again as {signedInAsSomebodyElse}.
          </p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button
              variant="secondary"
              onClick={() => {
                /*
                 * A full reload rather than a refetch. Every piece of state on this page
                 * belongs to the previous customer — the chosen account, the typed
                 * number, the idempotency key — and clearing them one at a time is how
                 * one gets left behind.
                 */
                window.location.reload();
              }}
            >
              Reload the page
            </Button>
          </p>
        </Alert>
      )}

      {failure !== null && <ErrorNotice error={failure} action="send that money" />}

      <Alert tone="info" title="The money leaves your account when you send it">
        It is held until a manager approves it, so you will see your balance drop straight
        away and the beneficiary will not see it until then. That is deliberate &mdash; it
        stops the same money being promised twice.
      </Alert>

      {/*
        THE REVIEW STEP. Shown instead of the form, not below it: a reviewer scrolling
        past the summary to a still-editable form is reviewing nothing, and the point of
        this screen is that the figures cannot change between being read and being sent.
      */}
      {reviewing && (
        <>
          <ReviewPanel
            caption="Check this before it goes"
            rows={[
              {
                label: 'From',
                value: `${source?.nickname ?? ''} ${source?.maskedNumber ?? ''}`,
              },
              { label: 'To', value: destinationLabel() },
              { label: 'Amount', value: `${currency} ${amount}` },
              { label: 'Reference', value: reference },
            ]}
          />

          {destination === 'SOMEBODY_ELSE' && payee !== null && (
            <Alert tone="warning" title={`Is ${payee.holderName} the right person?`}>
              This is the name the bank holds for {payee.maskedNumber}. If it is not who you
              meant to pay, go back and check the number &mdash; once a manager releases
              this, the bank cannot pull it back.
            </Alert>
          )}

          <Alert tone="info" title="What happens when you send this">
            <p>
              {currency} {amount} leaves your account straight away and is held. A manager
              at the bank reviews it, and only then does it reach the account above.
            </p>
            <p style={{ marginTop: 'var(--space-3)' }}>
              If they refuse it, the money comes back and the reason appears on your
              statement.
            </p>
          </Alert>

          <div className="money__actions">
            <Button
              loading={busy}
              onClick={() => {
                submit();
              }}
            >
              Send for approval
            </Button>
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() => {
                setReviewing(false);
              }}
            >
              Go back and change it
            </Button>
          </div>
        </>
      )}

      <form
        hidden={reviewing}
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (localProblem === undefined) toReview();
        }}
      >
        <AccountSelector
          label="From"
          accounts={list}
          value={sourceId}
          debitOnly
          excludeId={destination === 'MINE' ? ownDestinationId : ''}
          onChange={setSourceId}
          error={fieldErrors['sourceAccountId']}
        />

        <fieldset className="stmt__fieldset">
          <legend className="stmt__legend">Sending to</legend>

          {/*
            Two choices, because they need two different fields. One of the customer's own
            accounts can only be sent as an ID — the client is never given a full account
            number, so a picker has nothing else to send. Anybody else's needs the number
            typed in full.
          */}
          <div className="stmt__presets">
            <button
              type="button"
              className="stmt__preset"
              aria-pressed={destination === 'MINE'}
              disabled={busy}
              onClick={() => {
                setDestination('MINE');
                setFailure(null);
              }}
            >
              One of my accounts
            </button>
            <button
              type="button"
              className="stmt__preset"
              aria-pressed={destination === 'SOMEBODY_ELSE'}
              disabled={busy}
              onClick={() => {
                setDestination('SOMEBODY_ELSE');
                setOwnDestinationId('');
                setFailure(null);
              }}
            >
              Someone else&rsquo;s account
            </button>
          </div>

          {destination === 'MINE' ? (
            <AccountSelector
              label="To"
              accounts={list}
              value={ownDestinationId}
              excludeId={sourceId}
              onChange={setOwnDestinationId}
              error={fieldErrors['destinationAccountId']}
            />
          ) : (
            <>
              <TextField
                label="Their account number"
                value={accountNumber}
                /*
                 * `inputMode` rather than type="number": an account number is a string of
                 * digits, not a quantity, and a number input strips leading zeros.
                 */
                inputMode="numeric"
                autoComplete="off"
                maxLength={34}
                disabled={busy}
                error={fieldErrors['destinationAccountNumber']}
                hint="The full number, not the masked one. We check it exists before anything moves."
                onChange={(event) => {
                  setAccountNumber(event.target.value);
                }}
              />

              {/*
                NO SEPARATE "CHECK" BUTTON. The name is fetched when the customer presses
                Continue and shown on the review step, so checking is not a thing they can
                skip — an optional check on the one mistake nothing else catches is a
                check most people will not make.
              */}
            </>
          )}
        </fieldset>

        <MoneyInput
          label="Amount"
          currency={currency}
          value={amount}
          onChange={setAmount}
          error={fieldErrors['amount']}
          hint={
            source === undefined
              ? undefined
              : `Available: ${formatBalanceDto(source.availableBalance)}. There is no transfer fee inside this demo.`
          }
        />

        <TextField
          label="Reference"
          value={reference}
          maxLength={140}
          disabled={busy}
          hint="Shown on your statement and on theirs."
          error={fieldErrors['reference']}
          onChange={(event) => {
            setReference(event.target.value);
          }}
        />

        <div className="money__actions">
          <Button
            type="submit"
            loading={checking}
            /*
             * Disabled while the form is incomplete, so the customer finds out before the
             * request rather than after. It no longer submits: it fetches the payee's name
             * and moves to the review step, where the actual send lives.
             */
            disabled={localProblem !== undefined}
          >
            Continue
          </Button>
          <Link to={RETAIL_PATHS.transfers} className="money__link">
            Cancel
          </Link>
        </div>

        {localProblem !== undefined && sourceId !== '' && (
          <p className="dash__note" style={{ marginTop: 'var(--space-3)' }}>
            {localProblem}
          </p>
        )}
      </form>
    </article>
  );
}
