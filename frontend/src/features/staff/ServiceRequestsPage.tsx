import { useState, type ReactElement } from 'react';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  ErrorNotice,
  PageHeader,
  Panel,
  ReviewPanel,
  Select,
  Skeleton,
  StatusBadge,
  TextArea,
  TextField,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { adminService } from '@/services';
import type { ServiceRequestQueueItem } from '@/types/admin';
import { LOAN_PRODUCT_OPTIONS } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import { PledgedCollateral } from './PledgedCollateral';
import './staff.css';

const KIND_LABELS: Readonly<Record<string, string>> = {
  CARD: 'Card',
  CHEQUE_BOOK: 'Cheque book',
};

/**
 * Cards and cheque books customers have asked for.
 *
 * <p>WHY THIS SCREEN HAD TO EXIST BEFORE EITHER REQUEST FORM MEANT ANYTHING. Both forms
 * posted to an endpoint only the browser's own mock answered, which pushed a row onto an
 * array that starts empty on every page load. The request reached nobody. The customer was
 * shown a reference that stopped existing when they refreshed, told it would take about
 * five working days, and sent to a branch picked from a list the front end had invented.
 * All three were made up by the UI; none came from the bank.
 *
 * <p>SO THE COLLECTION POINT IS TYPED HERE, as free text, by the person who has the thing
 * in their hand. There is no dropdown because the portal has no branch list and must not
 * invent a second one. Until somebody fills this in, the customer's own screen shows an em
 * dash rather than a place, which is the honest answer to "where do I collect it?" before
 * the bank has made it.
 *
 * <p>ONLY OPEN REQUESTS APPEAR. A collected or declined request is finished work, and a
 * queue that keeps showing finished work stops being a list of what to do. The customer
 * keeps seeing theirs on their own cards and cheque-books pages, where the history is the
 * point.
 *
 * <p>EITHER ROLE MAY ACT. See the note in `StaffLayout` on why that does not weaken the
 * four-eyes rule.
 */
export function ServiceRequestsPage(): ReactElement {
  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<ServiceRequestQueueItem | null>(null);
  const [collectionPoint, setCollectionPoint] = useState('');

  /*
   * WHAT THE BANK IS LENDING, which is not necessarily what was asked for. These open on the
   * customer's figures and are editable, because approving a loan for less than the ask is an
   * ordinary decision and retyping the whole thing to make it would invite a slip.
   *
   * THE RATE OPENS EMPTY, deliberately. It is the one figure the customer did not propose and
   * the bank has not published for this loan, so a default here would be a rate nobody agreed
   * sitting in a box somebody is about to approve.
   */
  const [loanProduct, setLoanProduct] = useState('');
  const [loanAmount, setLoanAmount] = useState('');
  const [loanCurrency, setLoanCurrency] = useState('RWF');
  const [loanMonths, setLoanMonths] = useState('');
  const [loanRate, setLoanRate] = useState('');

  /*
   * WHAT IS ON THE CARD IN THE MEMBER OF STAFF'S HAND. Blank for a cheque book, which has
   * none of it. This is the only way a card is ever recorded: the portal does not generate
   * a number, a brand or an expiry date, so if nobody types them the customer's cards page
   * stays empty — which is the honest state, and was exactly the gap this fixes.
   */
  const [cardLastFour, setCardLastFour] = useState('');
  const [cardBrand, setCardBrand] = useState('');
  const [cardType, setCardType] = useState<'DEBIT' | 'PREPAID'>('DEBIT');
  const [cardExpiryMonth, setCardExpiryMonth] = useState('');
  const [cardExpiryYear, setCardExpiryYear] = useState('');

  const [declining, setDeclining] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const { state, reload } = useAsync(`service-requests:${String(version)}`, (signal) =>
    adminService.serviceRequests(signal),
  );

  /*
   * ALL FOUR OR NONE. They describe one object, and a request carrying two of them would
   * be stored as a card nobody can identify. The server refuses a half-filled set as well;
   * this only stops the member of staff finding out by being shown an error.
   *
   * A cheque book has no card details, so it is ready as soon as the collection point is in.
   */
  const month = Number(cardExpiryMonth);
  const year = Number(cardExpiryYear);
  const cardDetailsReady =
    selected?.requestType !== 'CARD' ||
    (/^\d{4}$/.test(cardLastFour) &&
      cardBrand.trim() !== '' &&
      month >= 1 &&
      month <= 12 &&
      /^\d{4}$/.test(cardExpiryYear) &&
      year >= 2000 &&
      year <= 2100);

  const close = (): void => {
    setSelected(null);
    setCollectionPoint('');
    setCardLastFour('');
    setCardBrand('');
    setCardType('DEBIT');
    setCardExpiryMonth('');
    setCardExpiryYear('');
    setDeclining(false);
    setReason('');
  };

  /**
   * Runs a decision and reloads the queue.
   *
   * `busy` gates every button for the whole round trip. The server already refuses a
   * second decision on a settled request — two staff members would otherwise overwrite
   * each other's name and collection point, so the audit row would credit whoever was
   * slower and the customer could have been emailed two different places. This only stops
   * one person double-tapping into a conflict about a decision that in fact succeeded.
   */
  const act = (
    run: () => Promise<ServiceRequestQueueItem>,
    message: (settled: ServiceRequestQueueItem) => string,
  ): void => {
    setBusy(true);
    setFailure(null);

    void run()
      .then((settled) => {
        setDone(message(settled));
        close();
        setVersion((count) => count + 1);
      })
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  const columns: readonly Column<ServiceRequestQueueItem>[] = [
    {
      key: 'ref',
      header: 'Reference',
      render: (row) => <span className="numeric">{row.reference}</span>,
    },
    {
      key: 'customer',
      header: 'Customer',
      render: (row) => (
        <>
          <strong>{row.customerName}</strong>
          <br />
          <span className="numeric">{row.customerNumber}</span>
        </>
      ),
    },
    {
      key: 'what',
      header: 'What to make',
      render: (row) => (
        <>
          {row.details}
          <br />
          <span className="numeric">{row.accountMask}</span>
        </>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) =>
        row.status === 'SUBMITTED' ? (
          <StatusBadge tone="warning" label="To make" />
        ) : (
          <>
            <StatusBadge tone="info" label="Waiting to be collected" />
            {/*
              The collection point belongs beside the status rather than in its own column:
              it is empty on every SUBMITTED row, and a column that is blank for most of
              the queue reads as missing data instead of as "not yet".
            */}
            {row.collectionPoint !== undefined && (
              <p className="dash__row-meta">at {row.collectionPoint}</p>
            )}
          </>
        ),
    },
    {
      key: 'asked',
      header: 'Asked',
      secondary: true,
      render: (row) => formatDateTime(row.submittedAt),
    },
    {
      key: 'action',
      header: 'Action',
      render: (row) => (
        <Button
          variant="secondary"
          onClick={() => {
            setSelected(row);
            setCollectionPoint(row.collectionPoint ?? '');
            setLoanProduct(row.loanProduct ?? '');
            setLoanAmount(row.loanAmount ?? '');
            setLoanCurrency(row.loanCurrency ?? 'RWF');
            setLoanMonths(row.loanMonths === undefined ? '' : String(row.loanMonths));
            setLoanRate('');
            setDeclining(false);
            setReason('');
            setDone(null);
            setFailure(null);
          }}
        >
          {/*
            A LOAN IS NEITHER MADE NOR HANDED OVER. "Mark ready" and "Hand over" describe an
            object on a counter; a loan is approved and the money appears in the account.
          */}
          {row.requestType === 'LOAN_APPLICATION'
            ? 'Review application'
            : row.status === 'SUBMITTED'
              ? 'Mark ready'
              : 'Hand over'}
        </Button>
      ),
    },
  ];

  const rows = state.status === 'ready' ? state.data : [];
  const toMake = rows.filter((row) => row.status === 'SUBMITTED').length;

  return (
    <article>
      <PageHeader
        title="Cards and cheque books"
        lead="What customers have asked the bank to make, and what is waiting on the counter to be collected."
      />

      {done !== null && (
        <Alert tone="success" title="Recorded">
          {done}
        </Alert>
      )}

      {failure !== null && <ErrorNotice error={failure} action="record that" />}

      {state.status === 'loading' && <Skeleton rows={4} label="Loading the requests" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load the requests" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (rows.length === 0 ? (
          <EmptyState message="Nothing has been asked for." />
        ) : (
          <>
            <p className="dash__signin">
              {toMake === 0
                ? 'Nothing left to make. Everything below is waiting to be collected.'
                : `${String(toMake)} to make · ${String(rows.length - toMake)} waiting to be collected`}
            </p>
            <DataTable
              caption="Cards and cheque books still open"
              columns={columns}
              rows={rows}
              rowKey={(row) => row.id}
            />
          </>
        ))}

      {selected !== null && (
        <div className="staff__review">
          <Panel
            title={`${KIND_LABELS[selected.requestType] ?? selected.requestType} for ${selected.customerName}`}
            subtitle={
              selected.requestType === 'LOAN_APPLICATION'
                ? 'Approve what the bank will lend. The money is paid into the account straight away.'
                : selected.status === 'SUBMITTED'
                  ? 'Mark it ready once it exists and you know where it is. That is what emails the customer.'
                  : 'Record that you handed it over, with identification checked.'
            }
          >
            <ReviewPanel
              rows={[
                { label: 'Reference', value: selected.reference },
                { label: 'Customer', value: selected.customerName },
                { label: 'Customer number', value: selected.customerNumber },
                { label: 'What to make', value: selected.details },
                { label: 'Account', value: selected.accountMask },
                { label: 'Asked', value: formatDateTime(selected.submittedAt) },
                ...(selected.collectionPoint === undefined
                  ? []
                  : [{ label: 'Collect from', value: selected.collectionPoint }]),
              ]}
            />

            {selected.requestType === 'LOAN_APPLICATION' && !declining && (
              <>
                {/*
                  WHAT IS SECURING IT COMES BEFORE THE FORM THAT APPROVES IT. A panel of
                  evidence placed under the approve button is a panel read after the
                  decision, which is not reading it.
                */}
                <PledgedCollateral requestId={selected.id} />

                {/*
                  WHAT WAS ASKED FOR IS STILL ON SCREEN, in the review panel above, so the
                  figures below can be changed without losing what they are being compared
                  to. A form that overwrites the request with the decision leaves nobody able
                  to see that a decision was made.
                */}
                <Select
                  label="Loan product"
                  value={loanProduct}
                  options={LOAN_PRODUCT_OPTIONS}
                  placeholder="Choose the product"
                  onChange={(event) => {
                    setLoanProduct(event.target.value);
                  }}
                />

                <TextField
                  label={`Amount to lend (${loanCurrency})`}
                  hint="What the bank is lending, which need not be what was asked for."
                  value={loanAmount}
                  inputMode="decimal"
                  onChange={(event) => {
                    setLoanAmount(event.target.value.replace(/[^\d.]/g, ''));
                  }}
                />

                <TextField
                  label="Over how many months?"
                  value={loanMonths}
                  inputMode="numeric"
                  maxLength={3}
                  onChange={(event) => {
                    setLoanMonths(event.target.value.replace(/\D/g, ''));
                  }}
                />

                <TextField
                  label="Interest rate (% a year)"
                  hint="The rate agreed with this customer. It is stored on the loan, so a rate agreed today is not changed by the bank's published rates later."
                  value={loanRate}
                  inputMode="decimal"
                  maxLength={8}
                  onChange={(event) => {
                    setLoanRate(event.target.value.replace(/[^\d.]/g, ''));
                  }}
                />

                <div className="money__actions">
                  <Button
                    loading={busy}
                    disabled={
                      loanProduct === '' ||
                      loanAmount.trim() === '' ||
                      Number(loanAmount) <= 0 ||
                      loanMonths.trim() === '' ||
                      Number(loanMonths) <= 0 ||
                      loanRate.trim() === ''
                    }
                    onClick={() => {
                      act(
                        () =>
                          adminService.grantLoan(selected.id, {
                            product: loanProduct,
                            amount: loanAmount.trim(),
                            currency: loanCurrency,
                            months: Number(loanMonths),
                            annualRatePercent: loanRate.trim(),
                          }),
                        (settled) =>
                          `${settled.reference} is granted. ${loanCurrency} ${loanAmount.trim()} has been paid into ${settled.accountMask} and ${settled.customerName} has been told.`,
                      );
                    }}
                  >
                    Approve and pay into the account
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() => {
                      setDeclining(true);
                      setReason('');
                    }}
                  >
                    Decline this application
                  </Button>
                  <Button variant="tertiary" disabled={busy} onClick={close}>
                    Close
                  </Button>
                </div>
              </>
            )}

            {selected.requestType !== 'LOAN_APPLICATION' &&
              selected.status === 'SUBMITTED' &&
              !declining && (
                <>
                  <TextField
                    label="Where should the customer collect it?"
                    hint="Typed, not chosen from a list: the portal has no branch list of its own and must not guess one. The customer is emailed these words exactly, so write where a person would actually go."
                    value={collectionPoint}
                    maxLength={120}
                    onChange={(event) => {
                      setCollectionPoint(event.target.value);
                    }}
                  />

                  {selected.requestType === 'CARD' && (
                    <>
                      <TextField
                        label="Last four digits of the card"
                        hint="Four digits only. There is nowhere on the server to put a full card number, and pasting one is refused rather than shortened."
                        value={cardLastFour}
                        maxLength={4}
                        inputMode="numeric"
                        onChange={(event) => {
                          setCardLastFour(event.target.value.replace(/\D/g, ''));
                        }}
                      />
                      <TextField
                        label="Brand"
                        hint="As printed on the card, e.g. Visa or Mastercard. Typed, not chosen from a list the portal invented."
                        value={cardBrand}
                        maxLength={32}
                        onChange={(event) => {
                          setCardBrand(event.target.value);
                        }}
                      />
                      <Select
                        label="Card type"
                        value={cardType}
                        options={[
                          { value: 'DEBIT', label: 'Debit' },
                          { value: 'PREPAID', label: 'Prepaid' },
                        ]}
                        onChange={(event) => {
                          setCardType(event.target.value === 'PREPAID' ? 'PREPAID' : 'DEBIT');
                        }}
                      />
                      <TextField
                        label="Expiry month"
                        hint="1 to 12, as printed."
                        value={cardExpiryMonth}
                        maxLength={2}
                        inputMode="numeric"
                        onChange={(event) => {
                          setCardExpiryMonth(event.target.value.replace(/\D/g, ''));
                        }}
                      />
                      <TextField
                        label="Expiry year"
                        hint="All four digits, so 26 cannot be stored where 2026 was meant."
                        value={cardExpiryYear}
                        maxLength={4}
                        inputMode="numeric"
                        onChange={(event) => {
                          setCardExpiryYear(event.target.value.replace(/\D/g, ''));
                        }}
                      />
                    </>
                  )}

                  <div className="money__actions">
                    <Button
                      loading={busy}
                      disabled={collectionPoint.trim() === '' || !cardDetailsReady}
                      onClick={() => {
                        act(
                          () =>
                            adminService.markServiceRequestReady(
                              selected.id,
                              collectionPoint.trim(),
                              selected.requestType === 'CARD'
                                ? {
                                    lastFour: cardLastFour,
                                    brand: cardBrand.trim(),
                                    cardType,
                                    expiryMonth: Number(cardExpiryMonth),
                                    expiryYear: Number(cardExpiryYear),
                                  }
                                : undefined,
                            ),
                          (settled) =>
                            `${settled.customerName} has been emailed that ${settled.reference} is ready at ${settled.collectionPoint ?? collectionPoint.trim()}.`,
                        );
                      }}
                    >
                      Mark ready and email the customer
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={busy}
                      onClick={() => {
                        setDeclining(true);
                        setReason('');
                      }}
                    >
                      Decline this request
                    </Button>
                    <Button variant="tertiary" disabled={busy} onClick={close}>
                      Close
                    </Button>
                  </div>
                </>
              )}

            {selected.status === 'READY' && !declining && (
              <>
                {/*
                  SAID EVERY TIME, not once in a training note. Handing a card to whoever
                  turns up with a reference is the whole attack: the reference travels by
                  email and is six characters long.
                */}
                <Alert tone="warning" title="Check who you are handing it to">
                  <p>
                    Check photo identification against <strong>{selected.customerName}</strong>,
                    customer number <span className="numeric">{selected.customerNumber}</span>,
                    before handing this over. A reference on its own is not identification.
                  </p>
                </Alert>

                <div className="money__actions">
                  <Button
                    loading={busy}
                    onClick={() => {
                      act(
                        () => adminService.markServiceRequestCollected(selected.id),
                        (settled) =>
                          `${settled.reference} is recorded as collected by ${settled.customerName}.`,
                      );
                    }}
                  >
                    Identification checked — record as collected
                  </Button>
                  <Button variant="tertiary" disabled={busy} onClick={close}>
                    Close
                  </Button>
                </div>
              </>
            )}

            {declining && (
              <div style={{ width: '100%' }}>
                <TextArea
                  label="Why are you declining this?"
                  hint="The customer is shown this on their own list and emailed it, word for word. Write what they need in order to put it right."
                  value={reason}
                  rows={3}
                  onChange={(event) => {
                    setReason(event.target.value);
                  }}
                />
                <div className="money__actions">
                  <Button
                    variant="danger"
                    loading={busy}
                    disabled={reason.trim() === ''}
                    onClick={() => {
                      act(
                        () => adminService.declineServiceRequest(selected.id, reason.trim()),
                        (settled) =>
                          `${settled.customerName} has been emailed your reason, and ${settled.reference} is closed.`,
                      );
                    }}
                  >
                    Decline and email the reason
                  </Button>
                  <Button
                    variant="tertiary"
                    disabled={busy}
                    onClick={() => {
                      setDeclining(false);
                      setReason('');
                    }}
                  >
                    Back
                  </Button>
                </div>
              </div>
            )}
          </Panel>
        </div>
      )}
    </article>
  );
}
