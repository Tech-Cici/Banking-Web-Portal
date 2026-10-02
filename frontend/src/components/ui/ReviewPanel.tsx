import type { ReactElement, ReactNode } from 'react';
import './ui.css';

export interface ReviewRow {
  readonly label: string;
  /**
   * Usually a string, and a node where a row needs a control beside the value — a copy
   * button, a reveal.
   *
   * <p>Widened from `string`, which was right while every row was plain text. It is NOT
   * an invitation to compute values here: the comment below still holds, and a row whose
   * node re-derives a figure the server supplied is the thing that rule forbids. A node
   * is for a control, not for arithmetic.
   */
  readonly value: ReactNode;
}

interface ReviewPanelProps {
  readonly rows: readonly ReviewRow[];
  readonly caption?: string;
}

/**
 * Label/value summary shown before a submission.
 *
 * The same component the transaction engine will use for the standard review panel
 * (blueprint section 9.1). Rendered as a description list so the label/value pairing is
 * conveyed structurally, not just visually.
 *
 * Rows are pre-formatted by the caller: this component never formats money, and never
 * re-derives a value the server supplied.
 */
export function ReviewPanel({ rows, caption }: ReviewPanelProps): ReactElement {
  return (
    <div className="ui-review">
      {caption !== undefined && (
        <p className="ui-review__label" style={{ marginBottom: 'var(--space-2)' }}>
          {caption}
        </p>
      )}
      <dl style={{ margin: 0 }}>
        {rows.map((row) => (
          <div key={row.label} className="ui-review__row">
            <dt className="ui-review__label">{row.label}</dt>
            <dd className="ui-review__value" style={{ margin: 0 }}>
              {row.value}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
