import type { ReactElement, ReactNode } from 'react';
import './ui.css';

interface EmptyStateProps {
  /** What is not here, stated plainly. */
  readonly message: string;
  /** Optional next step — a link or button. */
  readonly children?: ReactNode;
}

/**
 * Says that a panel has nothing in it, and why that is fine.
 *
 * An empty panel and a failed panel must never look the same. A customer who reads
 * "Nothing waiting for your approval" when the request actually failed will assume the
 * queue is clear and go home, so failures render an {@link Alert} instead of this.
 */
export function EmptyState({ message, children }: EmptyStateProps): ReactElement {
  return (
    <div className="ui-empty">
      <p className="ui-empty__message">{message}</p>
      {children}
    </div>
  );
}
