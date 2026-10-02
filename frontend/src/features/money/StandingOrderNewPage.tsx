import { useState, type ReactElement } from 'react';
import { AccountSelector, Alert, MoneyInput, Select, Skeleton, TextField } from '@/components/ui';
import type { ReviewRow } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { accountService, beneficiaryService, standingOrderService } from '@/services';
import type { StandingOrder, StandingOrderFrequency } from '@/types/movement';
import { formatDate } from '@/utils/datetime';
import { formatMoneyDto } from '@/utils/money';
import { MoneyFlowShell } from './MoneyFlowShell';
import { useMoneyFlow } from './useMoneyFlow';
import './money.css';

/**
 * Sets up a recurring payment.
 *
 * It goes through the same review-then-confirm flow as a one-off, with an idempotency
 * key, because creating the same standing order twice is worse than sending one payment
 * twice: it keeps happening every month until somebody notices.
 *
 * The end date is optional and clearly marked so. An order with no end is the normal
 * case (rent, a subscription) and forcing a date invites a wrong one.
 */
export function StandingOrderNewPage(): ReactElement {
  const session = useSession();
  const scope = session.activeCorporate?.id ?? session.user?.id ?? 'none';

  const accounts = useAsync(`so:accounts:${scope}`, (signal) => accountService.list(signal));
  const beneficiaries = useAsync(`so:beneficiaries:${scope}`, (signal) =>
    beneficiaryService.list(signal),
  );

  const today = new Date().toISOString().slice(0, 10);

  const [sourceId, setSourceId] = useState('');
  const [beneficiaryId, setBeneficiaryId] = useState('');
  const [amount, setAmount] = useState('');
  const [frequency, setFrequency] = useState<StandingOrderFrequency>('MONTHLY');
  const [startOn, setStartOn] = useState(today);
  const [endsOn, setEndsOn] = useState('');
  const [reference, setReference] = useState('');

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];
  const payees =
    beneficiaries.state.status === 'ready'
      ? beneficiaries.state.data.filter((b) => b.status === 'ACTIVE')
      : [];

  const source = list.find((account) => account.id === sourceId);
  const payee = payees.find((item) => item.id === beneficiaryId);
  const currency = source?.currency ?? 'RWF';

  const flow = useMoneyFlow<StandingOrder>((idempotencyKey, signal) =>
    standingOrderService.create(
      {
        sourceAccountId: sourceId,
        beneficiaryId,
        amount: { amount, currency },
        frequency,
        startOn,
        ...(endsOn === '' ? {} : { endsOn }),
        reference,
      },
      idempotencyKey,
      signal,
    ),
  );

  const validate = (): string | undefined => {
    if (sourceId === '') return 'Choose the account the money comes from.';
    if (beneficiaryId === '') return 'Choose who is being paid.';
    if (amount === '' || Number(amount) <= 0) return 'Enter an amount.';
    if (startOn === '') return 'Choose when the first payment goes out.';
    if (endsOn !== '' && Date.parse(endsOn) <= Date.parse(startOn)) {
      return 'The end date must be after the first payment.';
    }
    if (reference.trim() === '') return 'Add a reference.';
    return undefined;
  };

  const reviewRows: readonly ReviewRow[] = [
    { label: 'From', value: `${source?.nickname ?? ''} ${source?.maskedNumber ?? ''}` },
    { label: 'To', value: payee === undefined ? '' : `${payee.name} — ${payee.maskedDestination}` },
    { label: 'Amount each time', value: amount === '' ? '' : formatMoneyDto({ amount, currency }) },
    {
      label: 'How often',
      value:
        frequency === 'WEEKLY'
          ? 'Every week'
          : frequency === 'QUARTERLY'
            ? 'Every three months'
            : 'Every month',
    },
    { label: 'First payment', value: formatDate(startOn) },
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
            accounts={list}
            value={sourceId}
            debitOnly
            onChange={setSourceId}
            error={flow.fieldErrors['sourceAccountId']}
          />

          <Select
            label="To"
            value={beneficiaryId}
            placeholder={payees.length === 0 ? 'No payees available' : 'Choose a payee…'}
            options={payees.map((item) => ({
              value: item.id,
              label: `${item.name} — ${item.maskedDestination}`,
            }))}
            error={flow.fieldErrors['beneficiaryId']}
            onChange={(event) => {
              setBeneficiaryId(event.target.value);
            }}
          />

          {payees.length === 0 && (
            <Alert tone="info">
              Only a payee a member of staff has already checked can be put on a standing order. If
              you have just added one, we will email you when it is ready.
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
            options={[
              { value: 'WEEKLY', label: 'Every week' },
              { value: 'MONTHLY', label: 'Every month' },
              { value: 'QUARTERLY', label: 'Every three months' },
            ]}
            onChange={(event) => {
              setFrequency(event.target.value as StandingOrderFrequency);
            }}
          />

          <TextField
            label="First payment on"
            type="date"
            min={today}
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
            The first payment of {formatMoneyDto(order.amount)} goes out on{' '}
            {formatDate(order.nextRunOn)}.
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
