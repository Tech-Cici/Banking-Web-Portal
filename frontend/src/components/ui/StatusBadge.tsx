import type { ReactElement } from 'react';
import './ui.css';

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'error' | 'info';

interface StatusBadgeProps {
  readonly tone: BadgeTone;
  readonly label: string;
}

/**
 * A small status chip.
 *
 * Colour is never the only signal (blueprint section 23): the label carries the meaning,
 * and the tone only reinforces it. That is why this component takes a human label rather
 * than deriving one from the tone — a badge that reads "ACTIVE" in green and nothing else
 * is unreadable to a colourblind user and to a screen reader alike.
 *
 * `toneForStatus` and `humaniseStatus`, in `statusTone.ts`, turn an API status into the
 * two props this takes.
 */
export function StatusBadge({ tone, label }: StatusBadgeProps): ReactElement {
  return <span className={`ui-badge ui-badge--${tone}`}>{label}</span>;
}
