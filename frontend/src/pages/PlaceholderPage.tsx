import type { ReactElement } from 'react';
import { Alert } from '@/components/ui';

interface PlaceholderPageProps {
  readonly title: string;
  /**
   * Which development phase implements this page.
   *
   * Kept as a prop because it documents the route table at the call site, where whoever
   * is picking up the work will read it. It is NOT rendered — see below.
   */
  readonly phase: string;
  /** Blueprint section that specifies it, so the spec is one lookup away. Not rendered. */
  readonly blueprintSection: string;
}

/**
 * Stands in for a page that has not been built yet.
 *
 * This used to print the project plan at whoever arrived: "Not implemented yet",
 * "Scheduled for: Phase 3", "Specified in: blueprint section 5.3". One of these routes
 * is `/forgot-password`, which the sign-in page links to — so a customer who could not
 * remember their password was shown a phase number and a specification reference.
 *
 * Someone who cannot get into their bank account needs to know what to do instead. They
 * do not need to know which phase of a delivery plan would have helped them, and the
 * bank does not benefit from publishing which of its screens are unbuilt.
 *
 * So the phase and section stay as props — useful where the routes are declared — and
 * the page tells the person the one thing they can act on. The remaining placeholder
 * routes are listed in docs/OPEN-ITEMS.md.
 *
 * None of these fetch data or render fixtures: a placeholder showing invented balances
 * would be worse than an empty one.
 */
export function PlaceholderPage({ title }: PlaceholderPageProps): ReactElement {
  return (
    <article>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-2)' }}>{title}</h1>

      <Alert tone="info" title="This is not available online yet">
        <p>
          You cannot do this through internet banking at the moment. Please call the number
          printed on the back of your card, or visit any branch, and the bank can do it for
          you.
        </p>
      </Alert>
    </article>
  );
}
