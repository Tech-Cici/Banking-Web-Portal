import type { ReactElement } from 'react';
import './ui.css';

interface SkeletonProps {
  /** How many placeholder rows to draw. */
  readonly rows?: number;
  /** Describes what is loading, for assistive technology. */
  readonly label?: string;
}

/**
 * Loading placeholder.
 *
 * Deliberately shaped like rows rather than a spinner: a dashboard that reflows from a
 * centred spinner into a grid makes the page jump under the pointer. The region is
 * `aria-busy` and polite, so a screen reader hears one "Loading balances" rather than a
 * stream of partial updates.
 *
 * The bars carry no numbers. A skeleton that fakes an amount — even greyed out — is one
 * screenshot away from being read as a balance.
 */
export function Skeleton({ rows = 3, label = 'Loading' }: SkeletonProps): ReactElement {
  return (
    <div className="ui-skeleton" aria-busy="true" aria-live="polite" aria-label={label}>
      {Array.from({ length: rows }, (_unused, index) => (
        <span key={index} className="ui-skeleton__bar" />
      ))}
    </div>
  );
}
