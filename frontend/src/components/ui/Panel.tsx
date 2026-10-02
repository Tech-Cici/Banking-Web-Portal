import type { ReactElement, ReactNode } from 'react';
import './ui.css';

interface PanelProps {
  readonly title: string;
  /** Short line under the title explaining what the panel is for. */
  readonly subtitle?: string | undefined;
  /** Top-right slot, typically a link to the full screen for this data. */
  readonly action?: ReactNode;
  readonly children: ReactNode;
}

/**
 * A titled section of a dashboard.
 *
 * Rendered as a `<section>` with its heading wired to `aria-labelledby`, so the page is
 * navigable by landmark and a screen-reader user can jump between panels instead of
 * reading a single undifferentiated wall of figures.
 *
 * The heading level is fixed at `h2`: every dashboard has exactly one `h1` (the page
 * title) and panels sit directly beneath it. A configurable level invites a page that
 * skips from h1 to h4.
 */
export function Panel({ title, subtitle, action, children }: PanelProps): ReactElement {
  const headingId = `panel-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;

  return (
    <section className="ui-panel" aria-labelledby={headingId}>
      <header className="ui-panel__header">
        <div>
          <h2 className="ui-panel__title" id={headingId}>
            {title}
          </h2>
          {subtitle !== undefined && <p className="ui-panel__subtitle">{subtitle}</p>}
        </div>
        {action !== undefined && <div className="ui-panel__action">{action}</div>}
      </header>

      <div className="ui-panel__body">{children}</div>
    </section>
  );
}
