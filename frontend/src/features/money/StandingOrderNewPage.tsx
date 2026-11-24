import { useState, type ReactElement } from 'react';
import { AccountSelector, Alert, MoneyInput, Select, Skeleton, TextField } from '@/components/ui';
import type { ReviewRow } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService, standingOrderService } from '@/services';
import { STANDING_ORDER_FREQUENCY_LABELS } from '@/types/movement';
import type { StandingOrder, StandingOrderFrequency } from '@/types/movement';
import { formatDate, formatDateTime } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import { MoneyFlowShell } from './MoneyFlowShell';
import { useMoneyFlow } from './useMoneyFlow';
import './money.css';

/**
 * Sets up a recurring payment between two of the customer's own accounts.
 *
 * It goes through the same review-then-confirm flow as a one-off, with an idempotency
 * key, because creating the same standing order twice is worse than sending one payment
 * twice: it keeps happening every period until somebody notices.
 *
 * The end date is optional and clearly marked so. An order with no end is the normal
 * case (a monthly sweep into savings) and forcing a date invites a wrong one.
 *
 * WHAT THIS SCREEN WILL NOT LET YOU BUILD, and the server refuses independently: a payment
 * out of a savings product. Money going out goes out of the current account. The picker
 * below offers only that, so nobody fills in a whole form and meets the refusal at the end.
 */
export function StandingOrderNewPage(): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? session.user?.id ?? 'none';

  const accounts = useAsync(`so:accounts:${scope}`, (signal) => accountService.list(signal));

  /*
   * TODAY IN KIGALI, not today in UTC. Between midnight and 02:00 local the two are
   * different days, and the browser's UTC date would offer a "first payment on" the server
   * reads as yesterday -- a date the customer picked and the server then refused.
   */
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Africa/Kigali' });

  const [sourceId, setSourceId] = useState('');
  const [destinationId, setDestinationId] = useState('');
  const [amount, setAmount] = useState('');
  const [frequency, setFrequency] = useState<StandingOrderFrequency>('MONTHLY');
  const [startOn, setStartOn] = useState(today);
  const [endsOn, setEndsOn] = useState('');
  const [reference, setReference] = useState('');

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];

  /*
   * ONLY THE CURRENT ACCOUNT MAY FUND ONE. The server enforces this and would refuse
   * anything else; offering a savings product here would mean letting somebody fill in an
   * amount, a schedule and a reference before being told no.
   */
  const payFrom = list.filter((account) => account.accountType === 'CURRENT');

  const source = list.find((account) => account.id === sourceId);
  const destination = list.find((account) => account.id === destinationId);
  const currency = source?.currency ?? 'RWF';

  /* A period shorter than a day has no date to start on: it starts when you set it up. */
  const startsImmediately = frequency === 'EVERY_2_MINUTES';

  const flow = useMoneyFlow<StandingOrder>((idempotencyKey, signal) =>
    standingOrderService.create(
      {
        sourceAccountId: sourceId,
        destinationAccountId: destinationId,
        amount: { amount, currency },
        frequency,
        /* No start date on a sub-daily order: the server starts it at once. Sending one
           would be sending a value the server is right to ignore. */
        ...(startsImmediately ? {} : { startOn }),
        ...(endsOn === '' ? {} : { endsOn }),
        reference,
      },
      idempotencyKey,
      signal,
    ),
  );

  const validate = (): string | undefined => {
    if (sourceId === '') return 'Choose the account the money comes from.';
    if (destinationId === '') return 'Choose the account the money goes into.';
    if (destinationId === sourceId) return 'Choose two different accounts.';
    if (amount === '' || Number(amount) <= 0) return 'Enter an amount.';
    if (!startsImmediately && startOn === '') return 'Choose when the first payment goes out.';
    if (!startsImmediately && endsOn !== '' && Date.parse(endsOn) <= Date.parse(startOn)) {
      return 'The end date must be after the first payment.';
    }
    if (reference.trim() === '') return 'Add a reference.';
    return undefined;
  };

  /*
   * BOTH SIDES ARE NAMED BY PRODUCT. Every account this customer holds carries the same
   * customer number, so both masks read "**** 0001" and a review screen showing only masks
   * would say the money goes from **** 0001 to **** 0001.
   */
  const describe = (account: typeof source): string =>
    account === undefined ? '' : `${account.nickname} ${account.maskedNumber}`;

  const reviewRows: readonly ReviewRow[] = [
    { label: 'From', value: describe(source) },
    { label: 'Into', value: describe(destination) },
    { label: 'Amount each time', value: amount === '' ? '' : formatMoneyDto({ amount, currency }) },
    { label: 'How often', value: STANDING_ORDER_FREQUENCY_LABELS[frequency] },
    {
      label: 'First payment',
      value: startsImmediately ? 'As soon as you confirm' : formatDate(startOn),
    },
    { label: 'Ends', value: endsOn === '' ? 'No end date' : formatDate(endsOn) },
    { label: 'Reference', value: reference },
  ];

  if (accounts.state.status === 'loading') return <Skeleton rows={6} label="Loading accounts" />;

  return (
    <MoneyFlowShell
      title="Set up a standing order"
      lead="The bank makes this payment for you until you stop it."
      crumbs={[{ label: 'Standing orders', to: RETAIL_PATHS.standingOrders }]}
      flow={flow}
      validate={validate}
      confirmLabel="Set up this standing order"
      reviewRows={reviewRows}
      form={
        <>
          <AccountSelector
            label="From"
            accounts={payFrom}
            value={sourceId}
            debitOnly
            excludeId={destinationId}
            hint="Standing orders are paid from your current account."
            onChange={setSourceId}
            error={flow.fieldErrors['sourceAccountId']}
          />

          <AccountSelector
            label="Into"
            accounts={list}
            value={destinationId}
            excludeId={sourceId}
            onChange={setDestinationId}
            error={flow.fieldErrors['destinationAccountId']}
          />

          {payFrom.length === 0 && (
            <Alert tone="info">
              A standing order is paid from a current account, and there is not one on this
              profile yet.
            </Alert>
          )}

          <MoneyInput
            label="Amount each time"
            currency={currency}
            value={amount}
            onChange={setAmount}
            error={flow.fieldErrors['amount']}
          />

          <Select
            label="How often"
            value={frequency}
            options={(
              Object.keys(STANDING_ORDER_FREQUENCY_LABELS) as readonly StandingOrderFrequency[]
            ).map((value) => ({ value, label: STANDING_ORDER_FREQUENCY_LABELS[value] }))}
            onChange={(event) => {
              setFrequency(event.target.value as StandingOrderFrequency);
            }}
          />

          {/*
            THE FIELD STAYS WHERE IT IS when the two-minute frequency is chosen, and the hint
            says what will actually happen instead. Removing it would move everything below it
            the moment somebody changed a dropdown, and a form that rearranges itself under
            the cursor is how a person ends up typing an amount into a reference.
          */}
          <TextField
            label="First payment on"
            type="date"
            min={today}
            required={!startsImmediately}
            hint={
              startsImmediately
                ? 'Not used for a two-minute order — the first payment goes out as soon as you confirm.'
                : undefined
            }
            value={startOn}
            error={flow.fieldErrors['startOn']}
            onChange={(event) => {
              setStartOn(event.target.value);
            }}
          />

          <TextField
            label="Stop after"
            type="date"
            required={false}
            min={startOn}
            hint="Leave blank to keep paying until you cancel it."
            value={endsOn}
            onChange={(event) => {
              setEndsOn(event.target.value);
            }}
          />

          <TextField
            label="Reference"
            value={reference}
            maxLength={35}
            hint="Shown on every payment this order makes."
            error={flow.fieldErrors['reference']}
            onChange={(event) => {
              setReference(event.target.value);
            }}
          />
        </>
      }
      renderResult={(order) => (
        <>
          <Alert tone="success" title="Standing order set up">
            The first payment of {formatMoneyDto(order.amount)} is due{' '}
            {formatDateTime(order.nextRunAt)}.
          </Alert>
          <div className="money__receipt">
            <p className="money__reference numeric">{order.reference}</p>
            <p className="dash__row-meta">{order.destinationSummary}</p>
          </div>
        </>
      )}
    />
  );
}
