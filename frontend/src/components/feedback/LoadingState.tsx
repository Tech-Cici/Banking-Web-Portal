import type { ReactElement } from 'react';

interface LoadingStateProps {
  readonly label?: string;
}

/**
 * Minimal loading indicator.
 *
 * PHASE 1 PLACEHOLDER. Phase 2 replaces this with the design system's skeleton components
 * (blueprint section 2.2), which show the shape of the content being loaded rather than a
 * generic message.
 *
 * `role="status"` and `aria-live` are not decoration: a screen-reader user otherwise gets
 * silence while the page waits.
 */
export function LoadingState({ label = 'Loading…' }: LoadingStateProps): ReactElement {
  return (
    <div role="status" aria-live="polite" style={{ padding: 'var(--space-6)' }}>
      {label}
    </div>
  );
}
