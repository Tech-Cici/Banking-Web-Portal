import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState, PageHeader } from '@/components/ui';
import { useSession } from '@/hooks/useSession';
import type { Permission } from '@/types/api';
import './money.css';

export interface HubChoice {
  readonly label: string;
  readonly note: string;
  readonly to: string;
  readonly anyOf: readonly Permission[];
}

interface HubPageProps {
  readonly title: string;
  readonly lead: string;
  readonly choices: readonly HubChoice[];
}

/**
 * A landing page that asks "which kind?" before collecting anything.
 *
 * Transfers and payments each cover several rails with different fees, speeds and
 * destination fields. Merging them into one form with a type dropdown means a form whose
 * fields rearrange under the cursor; splitting them means the customer answers the
 * easiest question first and then sees only what applies.
 *
 * Choices are filtered by permission, so an approver — who may not create payments —
 * gets an honest empty state rather than a menu of dead ends.
 */
export function HubPage({ title, lead, choices }: HubPageProps): ReactElement {
  const session = useSession();
  const allowed = choices.filter(
    (choice) => choice.anyOf.length === 0 || choice.anyOf.some((p) => session.can(p)),
  );

  return (
    <article>
      <PageHeader title={title} lead={lead} />

      {allowed.length === 0 ? (
        <EmptyState message="Your access does not include creating payments. Payments are prepared by a maker and released by an approver." />
      ) : (
        <ul className="money__choices">
          {allowed.map((choice) => (
            <li key={choice.to}>
              <Link to={choice.to} className="money__choice">
                <span className="money__choice-title">{choice.label}</span>
                <span className="money__choice-note">{choice.note}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}
