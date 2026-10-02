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
  Skeleton,
  StatusBadge,
  TextArea,
  TextField,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { adminService } from '@/services';
import type { ServiceRequestQueueItem } from '@/types/admin';
import { formatDateTime } from '@/utils/datetime';
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
  const [declining, setDeclining] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const { state, reload } = useAsync(`service-requests:${String(version)}`, (signal) =>
    adminService.serviceRequests(signal),
  );

  const close = (): void => {
    setSelected(null);
    setCollectionPoint('');
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
            setDeclining(false);
            setReason('');
            setDone(null);
            setFailure(null);
          }}
        >
          {row.status === 'SUBMITTED' ? 'Mark ready' : 'Hand over'}
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
              selected.status === 'SUBMITTED'
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

            {selected.status === 'SUBMITTED' && !declining && (
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

                <div className="money__actions">
                  <Button
                    loading={busy}
                    disabled={collectionPoint.trim() === ''}
                    onClick={() => {
                      act(
                        () =>
                          adminService.markServiceRequestReady(
                            selected.id,
                            collectionPoint.trim(),
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
