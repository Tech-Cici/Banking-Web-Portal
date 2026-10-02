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
  type BadgeTone,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { adminService } from '@/services';
import type { BeneficiaryReview, NameCheck } from '@/types/admin';
import { formatDateTime } from '@/utils/datetime';
import './staff.css';

const TYPE_LABELS: Readonly<Record<string, string>> = {
  INTERNAL: 'This bank',
  DOMESTIC: 'Another bank in Rwanda',
  INTERNATIONAL: 'Abroad',
  WALLET: 'Mobile wallet',
};

/**
 * HOW EACH VERDICT READS ON SCREEN.
 *
 * <p>The four are deliberately not a traffic light with a blank in it. `UNAVAILABLE` gets
 * its own neutral badge and its own words, because a reviewer shown an empty cell where a
 * comparison belongs reads it as a pass — and three quarters of payees are at another
 * institution, where no comparison is possible at all.
 *
 * <p>`PARTIAL` is a warning rather than a success. It is the commonest innocent case AND a
 * usable disguise, so the screen's job is to say which one happened and leave the decision
 * with the person.
 */
const CHECK_PRESENTATION: Readonly<
  Record<NameCheck, { readonly tone: BadgeTone; readonly label: string; readonly note: string }>
> = {
  MATCH: {
    tone: 'success',
    label: 'Names match',
    note: 'The name typed is the name the bank holds for this account.',
  },
  PARTIAL: {
    tone: 'warning',
    label: 'Partly matches',
    note: 'One name is a shorter form of the other. Usually how people write a name they know — and also what somebody adding a stranger’s account would type if they knew part of it.',
  },
  MISMATCH: {
    tone: 'error',
    label: 'Names do not match',
    note: 'The bank holds a different name for this account. Most often a mistyped digit; sometimes a payee the customer has been talked into adding.',
  },
  UNAVAILABLE: {
    tone: 'neutral',
    label: 'Cannot be checked',
    note: 'We hold no name for this destination, so no comparison was made. This is not a pass — judge it on what the customer can tell you.',
  },
};

/**
 * Saved payees waiting for a member of staff.
 *
 * <p>WHAT THIS SCREEN IS FOR, because it is not a sign-off. Adding a payee is the step a
 * scam needs — the script is "add this account and send the money now" — so a payee is not
 * payable until somebody here has compared the name the customer typed with the name the
 * bank holds for that account. Until this screen existed there was no such person: a payee
 * was created waiting and nothing ever moved it on.
 *
 * <p>THE COMPARISON IS THE JOB. Both names are in the table, side by side, with a verdict.
 * An earlier draft showed only what the customer typed, which gave the reviewer nothing to
 * check against and made Approve the only sensible button — an approval screen that cannot
 * verify anything costs the customer a wait and buys the bank nothing.
 *
 * <p>NOTHING IS DECIDED AUTOMATICALLY. A perfect name match does not clear a payee, because
 * somebody adding a stranger's account often knows that stranger's name — it is on the
 * invoice they were sent. The verdict informs a person; it does not replace one.
 *
 * <p>EITHER ROLE MAY DECIDE. See the note in `StaffLayout` on why that does not weaken the
 * four-eyes rule.
 */
export function BeneficiaryReviewsPage(): ReactElement {
  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<BeneficiaryReview | null>(null);
  const [refusing, setRefusing] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const { state, reload } = useAsync(`beneficiary-reviews:${String(version)}`, (signal) =>
    adminService.beneficiaryReviews(signal),
  );

  const close = (): void => {
    setSelected(null);
    setRefusing(false);
    setReason('');
  };

  /**
   * Runs a decision and reloads the queue.
   *
   * `busy` gates both buttons for the whole round trip. The server already refuses a
   * second decision on a settled payee — two reviewers would otherwise overwrite each
   * other's name on the audit row — so this only stops one person double-tapping into a
   * confusing conflict about a decision that in fact succeeded.
   */
  const decide = (
    run: () => Promise<BeneficiaryReview>,
    message: (settled: BeneficiaryReview) => string,
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

  const columns: readonly Column<BeneficiaryReview>[] = [
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
      key: 'typed',
      header: 'Payee they typed',
      render: (row) => (
        <>
          {row.name}
          <br />
          <span className="numeric">{row.maskedDestination}</span>
        </>
      ),
    },
    {
      key: 'held',
      header: 'Name we hold',
      /*
       * The column that makes this a check. An em dash rather than an empty cell when
       * there is no name: a blank looks like a rendering fault or an answer of "nothing
       * wrong", and this is neither.
       */
      render: (row) =>
        row.heldName === undefined ? (
          <span className="staff__muted">— not held here</span>
        ) : (
          <strong>{row.heldName}</strong>
        ),
    },
    {
      key: 'check',
      header: 'Check',
      render: (row) => (
        <StatusBadge
          tone={CHECK_PRESENTATION[row.nameCheck].tone}
          label={CHECK_PRESENTATION[row.nameCheck].label}
        />
      ),
    },
    {
      key: 'where',
      header: 'Where',
      secondary: true,
      render: (row) =>
        `${TYPE_LABELS[row.beneficiaryType] ?? row.beneficiaryType} · ${row.provider}`,
    },
    {
      key: 'added',
      header: 'Added',
      secondary: true,
      render: (row) => formatDateTime(row.addedAt),
    },
    {
      key: 'action',
      header: 'Decide',
      render: (row) => (
        <Button
          variant="secondary"
          onClick={() => {
            setSelected(row);
            setRefusing(false);
            setReason('');
            setDone(null);
            setFailure(null);
          }}
        >
          Review
        </Button>
      ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Payees to check"
        lead="Customers cannot send money to a payee until somebody here has compared the name they typed with the name we hold for that account."
      />

      {done !== null && (
        <Alert tone="success" title="Decision recorded">
          {done}
        </Alert>
      )}

      {failure !== null && <ErrorNotice error={failure} action="record that decision" />}

      {state.status === 'loading' && <Skeleton rows={4} label="Loading the payees to check" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load the payees" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (state.data.length === 0 ? (
          <EmptyState message="No payees are waiting to be checked." />
        ) : (
          <DataTable
            caption="Saved payees waiting for a member of staff"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            rowTone={(row) => (row.nameCheck === 'MISMATCH' ? 'warning' : 'default')}
          />
        ))}

      {selected !== null && (
        <div className="staff__review">
          <Panel
            title={`Can ${selected.customerName} pay ${selected.name}?`}
            subtitle="Approving lets them send money to this account. Refusing tells them why."
          >
            <ReviewPanel
              rows={[
                { label: 'Customer', value: selected.customerName },
                { label: 'Customer number', value: selected.customerNumber },
                { label: 'Payee as they typed it', value: selected.name },
                {
                  label: 'Name we hold for this account',
                  value: selected.heldName ?? 'We hold no name for this destination',
                },
                { label: 'Account', value: selected.maskedDestination },
                {
                  label: 'Where',
                  value: `${TYPE_LABELS[selected.beneficiaryType] ?? selected.beneficiaryType} · ${selected.provider}`,
                },
                { label: 'Currency', value: selected.currency },
                { label: 'Added', value: formatDateTime(selected.addedAt) },
              ]}
            />

            <Alert
              tone={
                CHECK_PRESENTATION[selected.nameCheck].tone === 'success' ? 'success' : 'warning'
              }
              title={CHECK_PRESENTATION[selected.nameCheck].label}
            >
              <p>{CHECK_PRESENTATION[selected.nameCheck].note}</p>
              {/*
                SAID ON EVERY VERDICT, INCLUDING A MATCH. The name matching is a help, not
                a decision: a person adding somebody else's account usually knows whose it
                is, because it is written on whatever they were sent. So a match means the
                number probably has no typo in it — not that the customer meant to pay this
                person, and not that nobody talked them into it.
              */}
              <p style={{ marginTop: 'var(--space-2)' }}>
                A matching name does not mean the payment is intended. If anything about the request
                looks pressured or rushed, speak to the customer before approving.
              </p>
            </Alert>

            {selected.nameCheck === 'UNAVAILABLE' && (
              <Alert tone="info" title="Sending to this payee is not available yet">
                We have no connection to other banks, wallets or banks abroad, so money cannot be
                sent to this payee whatever is decided here. Approving it only means the customer
                will be able to use it once that rail opens.
              </Alert>
            )}

            <div className="money__actions">
              {!refusing && (
                <>
                  <Button
                    loading={busy}
                    onClick={() => {
                      decide(
                        () => adminService.approveBeneficiary(selected.id),
                        (settled) =>
                          `${settled.customerName} can now pay ${settled.name}, and has been emailed to say so.`,
                      );
                    }}
                  >
                    Approve this payee
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() => {
                      setRefusing(true);
                      /*
                       * The commonest refusal, pre-filled rather than typed from scratch
                       * forty times a day. Editable, and only offered where it is true.
                       */
                      setReason(
                        selected.nameCheck === 'MISMATCH'
                          ? 'The name you gave does not match the name we hold for that account. Please check the account number and add the payee again.'
                          : '',
                      );
                    }}
                  >
                    Refuse this payee
                  </Button>
                  <Button variant="tertiary" disabled={busy} onClick={close}>
                    Close
                  </Button>
                </>
              )}

              {refusing && (
                <div style={{ width: '100%' }}>
                  <TextArea
                    label="Why are you refusing this payee?"
                    hint="The customer is shown this on their payee list and emailed it, word for word. Write what they need in order to put it right."
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
                        decide(
                          () => adminService.refuseBeneficiary(selected.id, reason.trim()),
                          (settled) =>
                            `${settled.customerName} has been emailed your reason, and cannot pay ${settled.name}.`,
                        );
                      }}
                    >
                      Refuse and email the reason
                    </Button>
                    <Button
                      variant="tertiary"
                      disabled={busy}
                      onClick={() => {
                        setRefusing(false);
                        setReason('');
                      }}
                    >
                      Back
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </Panel>
        </div>
      )}
    </article>
  );
}
