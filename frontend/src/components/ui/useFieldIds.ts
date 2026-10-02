import { useId } from 'react';

export interface FieldIds {
  readonly inputId: string;
  readonly hintId: string | undefined;
  readonly errorId: string | undefined;
  /** Value for the control's aria-describedby. */
  readonly describedBy: string | undefined;
}

/**
 * Generates the ids that tie a control to its hint and error text.
 *
 * Doing this centrally is what makes "errors linked to fields and announced"
 * (blueprint section 23) automatic rather than something each form remembers.
 */
export function useFieldIds(hint: string | undefined, error: string | undefined): FieldIds {
  const base = useId();
  const hintId = hint === undefined ? undefined : `${base}-hint`;
  const errorId = error === undefined ? undefined : `${base}-error`;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;

  return { inputId: `${base}-input`, hintId, errorId, describedBy };
}
