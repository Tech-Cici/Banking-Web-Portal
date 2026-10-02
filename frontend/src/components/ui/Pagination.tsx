import type { ReactElement } from 'react';
import { Button } from './Button';
import './ui.css';

interface PaginationProps {
  /** Zero-based, as the API pages. */
  readonly page: number;
  readonly totalPages: number;
  readonly totalElements: number;
  readonly onPage: (page: number) => void;
  readonly busy?: boolean;
}

/**
 * Previous / next paging.
 *
 * Deliberately not a numbered page list. Statement pages run into the hundreds, numbered
 * links make a wall of digits nobody clicks, and the count is what people actually want:
 * "117 transactions" answers the question that a page number does not.
 *
 * The position line is a live region, so a screen-reader user hears the page change
 * instead of pressing Next into silence.
 */
export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
  busy = false,
}: PaginationProps): ReactElement | null {
  if (totalPages <= 1) return null;

  return (
    <nav className="ui-pager" aria-label="Pagination">
      <Button
        variant="secondary"
        disabled={busy || page <= 0}
        onClick={() => {
          onPage(page - 1);
        }}
      >
        Previous
      </Button>

      <p className="ui-pager__status" aria-live="polite">
        Page {page + 1} of {totalPages} · {totalElements} in total
      </p>

      <Button
        variant="secondary"
        disabled={busy || page >= totalPages - 1}
        onClick={() => {
          onPage(page + 1);
        }}
      >
        Next
      </Button>
    </nav>
  );
}
