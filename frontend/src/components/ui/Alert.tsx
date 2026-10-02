import type { ReactElement, ReactNode } from 'react';
import './ui.css';

export type AlertTone = 'info' | 'success' | 'warning' | 'error';

interface AlertProps {
  readonly tone: AlertTone;
  readonly title?: string;
  readonly children: ReactNode;
  /** Correlation id from the server, shown so a customer can quote it to support. */
  readonly reference?: string | undefined;
}

const ICONS: Record<AlertTone, string> = {
  info: 'i',
  success: '✓',
  warning: '!',
  error: '⚠',
};

/**
 * Inline message.
 *
 * Errors and warnings get `role="alert"` so they are announced; info and success use a
 * polite status region so they do not interrupt. Each tone carries an icon as well as its
 * colour (blueprint section 23).
 */
export function Alert({ tone, title, children, reference }: AlertProps): ReactElement {
  const assertive = tone === 'error' || tone === 'warning';

  return (
    <div
      className={`ui-alert ui-alert--${tone}`}
      role={assertive ? 'alert' : 'status'}
      aria-live={assertive ? 'assertive' : 'polite'}
    >
      <span className="ui-alert__icon" aria-hidden="true">
        {ICONS[tone]}
      </span>
      <div>
        {title !== undefined && <p className="ui-alert__title">{title}</p>}
        <div>{children}</div>
        {reference !== undefined && (
          /*
           * A bare "Reference: 8f3c-21" means nothing to a customer, so it gets read as
           * noise and is never quoted — which defeats the only reason it is on screen.
           * Saying what it is for is the difference between a support call that can be
           * traced and one that cannot.
           */
          <p className="ui-alert__reference">
            If you call us about this, quote reference{' '}
            <span className="numeric">{reference}</span>
          </p>
        )}
      </div>
    </div>
  );
}
