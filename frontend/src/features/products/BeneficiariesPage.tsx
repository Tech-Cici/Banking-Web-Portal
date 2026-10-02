import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  ErrorNotice,
  PageHeader,
  Skeleton,
  StatusBadge,
  humaniseStatus,
  toneForStatus,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { beneficiaryService } from '@/services';
import type { Beneficiary } from '@/types/banking';
import './products.css';

const TYPE_LABELS: Readonly<Record<string, string>> = {
  INTERNAL: 'This bank',
  DOMESTIC: 'Another bank in Rwanda',
  INTERNATIONAL: 'Abroad',
  WALLET: 'Mobile wallet',
};

/**
 * Saved payees.
 *
 * A payee that is not yet usable is shown with the reason spelled out rather than quietly
 * greyed, because the hold is the bank's main defence against "add this account and pay it
 * right now" — and a customer who understands what they are waiting for is far less likely
 * to be talked into working around it.
 *
 * WHAT THEY ARE WAITING FOR IS A PERSON, which is what this screen used to get wrong. It
 * said "cooling-off period", which describes a clock; there was no clock, and in fact
 * nothing at all moved a payee on. A member of bank staff now compares the name typed here
 * with the name the bank holds for that account, and that comparison is the only thing that
 * releases it.
 */
export function BeneficiariesPage(): ReactElement {
  const session = useSession();
  const canManage = session.can('BENEFICIARY_MANAGE');

  const [version, setVersion] = useState(0);
  const [removing, setRemoving] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);

  const { state, reload } = useAsync(`beneficiaries:${String(version)}`, (signal) =>
    beneficiaryService.list(signal),
  );

  const remove = (id: string): void => {
    setRemoving(id);
    setFailure(null);

    void beneficiaryService
      .remove(id)
      .then(() => {
        setVersion((count) => count + 1);
      })
      .catch((cause: unknown) => {
        setFailure(cause);
      })
      .finally(() => {
        setRemoving(null);
      });
  };

  const columns: readonly Column<Beneficiary>[] = [
    {
      key: 'name',
      header: 'Payee',
      render: (row) => (
        <>
          {row.name}
          <br />
          <span className="numeric" style={{ color: 'var(--color-text-muted)' }}>
            {row.maskedDestination}
          </span>
        </>
      ),
    },
    {
      key: 'where',
      header: 'Where',
      secondary: true,
      render: (row) =>
        `${TYPE_LABELS[row.beneficiaryType] ?? row.beneficiaryType} · ${row.provider}`,
    },
    { key: 'currency', header: 'Currency', secondary: true, render: (row) => row.currency },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
          {row.status === 'PENDING_VERIFICATION' && (
            /*
             * WHO is doing it and WHAT ENDS IT. The old wording — "in its cooling-off
             * period" — described a clock, and no clock existed: nothing moved a payee on,
             * ever. A customer waiting for time to pass when they should be waiting for a
             * person is a customer who telephones on the second day.
             */
            <p className="dash__row-meta">
              A member of staff is checking this against the account. We will email you when it is
              done, and you cannot pay it until then.
            </p>
          )}
          {row.status === 'REFUSED' && (
            /*
             * THE REASON, in the reviewer's own words, on the screen as well as in the
             * email. The commonest one is "the name does not match this account", which the
             * customer fixes in a minute by checking the number — a bare "refused" badge
             * sends them to a branch instead.
             */
            <p className="dash__row-meta">
              {row.refusedReason ?? 'We were not able to approve this payee.'}
            </p>
          )}
        </>
      ),
    },
    {
      key: 'action',
      header: 'Remove',
      render: (row) =>
        canManage ? (
          <Button
            variant="tertiary"
            loading={removing === row.id}
            onClick={() => {
              remove(row.id);
            }}
          >
            Remove
          </Button>
        ) : (
          <span style={{ color: 'var(--color-text-muted)' }}>—</span>
        ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Beneficiaries"
        lead="People and businesses you can pay. A member of staff checks a new payee against the account before you can send money to it."
        action={canManage ? <Link to={RETAIL_PATHS.beneficiaryNew}>Add a payee</Link> : undefined}
      />

      {failure !== null && <ErrorNotice error={failure} action="remove that payee" />}

      {state.status === 'loading' && <Skeleton rows={5} label="Loading beneficiaries" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your payees" reference={state.reference}>
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
          <EmptyState message="You have not saved any payees yet.">
            {canManage && (
              <p className="dash__note">
                <Link to={RETAIL_PATHS.beneficiaryNew}>Add your first payee</Link>
              </p>
            )}
          </EmptyState>
        ) : (
          <DataTable
            caption="Your saved payees"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            rowTone={(row) => (row.status === 'ACTIVE' ? 'default' : 'warning')}
          />
        ))}
    </article>
  );
}
