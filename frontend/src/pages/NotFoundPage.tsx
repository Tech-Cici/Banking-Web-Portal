import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { DEFAULT_AUTHENTICATED_PATH } from '@/routes/paths';

/** Rendered for any unmatched route. Reveals nothing about what does exist. */
export function NotFoundPage(): ReactElement {
  return (
    <article>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-2)' }}>
        Page not found
      </h1>
      <p style={{ color: 'var(--color-text-muted)', marginBottom: 'var(--space-6)' }}>
        The page you asked for does not exist, or you no longer have access to it.
      </p>
      <Link to={DEFAULT_AUTHENTICATED_PATH}>Return to the dashboard</Link>
    </article>
  );
}
