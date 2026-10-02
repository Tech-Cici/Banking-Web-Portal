import type { ReactElement } from 'react';
import { useSession } from '@/hooks/useSession';

/**
 * Switches which company the user is acting for.
 *
 * Renders nothing for someone who belongs to one company: a switcher with a single option
 * is furniture, and it implies there is something to choose.
 *
 * The switch goes through the session, which clears company-scoped state before the new
 * data arrives. That ordering matters more than it looks — one company's balances left on
 * screen under another company's name is the kind of thing that ends up in a regulator's
 * letter.
 *
 * The role shown is the one held IN THAT COMPANY. The same person can be an approver at
 * one and a viewer at another, and the permissions follow the company, not the login.
 */
export function CompanySwitcher(): ReactElement | null {
  const session = useSession();
  const memberships = session.user?.corporates ?? [];

  if (memberships.length < 2) return null;

  const activeId = session.activeCorporate?.id;
  const switching = session.status === 'loading';

  return (
    <div className="dash__company">
      <span className="dash__company-label" id="company-switcher-label">
        Acting for
      </span>

      <div className="dash__company-list" role="group" aria-labelledby="company-switcher-label">
        {memberships.map((company) => (
          <button
            key={company.id}
            type="button"
            className="dash__company-btn"
            aria-pressed={company.id === activeId}
            disabled={switching || company.id === activeId}
            onClick={() => {
              void session.switchCorporate(company.id);
            }}
          >
            {company.name}
            <span className="dash__row-meta"> · {company.role.toLowerCase()}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
