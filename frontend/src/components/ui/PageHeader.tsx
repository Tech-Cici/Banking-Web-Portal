import type { ReactElement, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import './ui.css';

export interface Crumb {
  readonly label: string;
  readonly to: string;
}

interface PageHeaderProps {
  readonly title: string;
  readonly lead?: string;
  /** Trail back to where this screen sits. The current page is not a crumb. */
  readonly crumbs?: readonly Crumb[];
  /** Primary action for the screen, top right. */
  readonly action?: ReactNode;
}

/**
 * The heading block every screen opens with.
 *
 * One `h1` per page, always first in the main landmark, so a screen reader announces
 * where it has landed. The breadcrumb is a real `nav` with its own label rather than a
 * row of links, because "back to where I was" is a navigation task and assistive
 * technology should be able to jump straight to it.
 */
export function PageHeader({ title, lead, crumbs, action }: PageHeaderProps): ReactElement {
  return (
    <header className="ui-page-head">
      {crumbs !== undefined && crumbs.length > 0 && (
        <nav aria-label="Breadcrumb" className="ui-crumbs">
          <ol>
            {crumbs.map((crumb) => (
              <li key={crumb.to}>
                <Link to={crumb.to}>{crumb.label}</Link>
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div className="ui-page-head__row">
        <div>
          <h1 className="ui-page-head__title">{title}</h1>
          {lead !== undefined && <p className="ui-page-head__lead">{lead}</p>}
        </div>
        {action !== undefined && <div>{action}</div>}
      </div>
    </header>
  );
}
