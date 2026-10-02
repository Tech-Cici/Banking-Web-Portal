import type { ReactElement, ReactNode } from 'react';
import './ui.css';

export interface Column<T> {
  readonly key: string;
  readonly header: string;
  /** Right-align and use tabular figures. Amounts, counts, dates. */
  readonly numeric?: boolean;
  /** Hidden below the narrow breakpoint. Use for detail the row can survive without. */
  readonly secondary?: boolean;
  readonly render: (row: T) => ReactNode;
}

interface DataTableProps<T> {
  /** Describes the table for a screen reader. Required — an unlabelled table is a maze. */
  readonly caption: string;
  readonly columns: readonly Column<T>[];
  readonly rows: readonly T[];
  readonly rowKey: (row: T) => string;
  /** Marks a row as needing attention, e.g. a failed payment. */
  readonly rowTone?: (row: T) => 'default' | 'warning' | 'error';
}

/**
 * A real `<table>` for tabular data.
 *
 * A grid of divs looks identical and is unusable with a screen reader, which relies on
 * the table's own semantics to say "row 4, Amount, RWF 3,400,000". Banking screens are
 * mostly tables of money, so this is not a detail to get wrong once and repeat.
 *
 * Columns marked `secondary` are dropped on narrow screens rather than squeezed. A
 * horizontally scrolling table of amounts hides the number people came to read.
 */
export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  rowTone,
}: DataTableProps<T>): ReactElement {
  return (
    <div className="ui-table-wrap">
      <table className="ui-table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={[
                  column.numeric === true ? 'ui-table__num' : '',
                  column.secondary === true ? 'ui-table__secondary' : '',
                ]
                  .filter(Boolean)
                  .join(' ')}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const tone = rowTone?.(row) ?? 'default';
            return (
              <tr key={rowKey(row)} className={tone === 'default' ? '' : `ui-table__row--${tone}`}>
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={[
                      column.numeric === true ? 'ui-table__num' : '',
                      column.secondary === true ? 'ui-table__secondary' : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                  >
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
