import type { ReactElement } from 'react';
import './ui.css';

interface StepperProps {
  readonly steps: readonly string[];
  /** Zero-based index of the step being shown. */
  readonly current: number;
  readonly label?: string;
}

/**
 * Progress through a multi-step flow.
 *
 * This is the shared stepper the blueprint asks for (section 2.2) and the same component
 * the reusable transaction engine will use for Entry → Review → Confirm → Result in
 * Phase 9. Built here because registration needs it first.
 *
 * Completed steps are marked with a tick and the word "completed" in the accessible name,
 * so progress is not conveyed by colour alone.
 */
export function Stepper({ steps, current, label = 'Progress' }: StepperProps): ReactElement {
  return (
    <nav aria-label={label}>
      <ol className="ui-stepper">
        {steps.map((step, index) => {
          const done = index < current;
          const isCurrent = index === current;
          const className = [
            'ui-stepper__item',
            done ? 'ui-stepper__item--done' : '',
            isCurrent ? 'ui-stepper__item--current' : '',
          ]
            .filter(Boolean)
            .join(' ');

          return (
            <li
              key={step}
              className={className}
              {...(isCurrent ? { 'aria-current': 'step' as const } : {})}
            >
              <span className="ui-stepper__marker" aria-hidden="true">
                {done ? '✓' : index + 1}
              </span>
              <span>
                {step}
                <span className="sr-only">
                  {done ? ' — completed' : isCurrent ? ' — current step' : ' — not started'}
                </span>
              </span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
