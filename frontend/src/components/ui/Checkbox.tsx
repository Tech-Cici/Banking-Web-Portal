import type { InputHTMLAttributes, ReactElement, ReactNode } from 'react';
import { useFieldIds } from './useFieldIds';
import './ui.css';

interface CheckboxProps extends Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'id' | 'className' | 'type' | 'aria-invalid' | 'aria-describedby'
> {
  readonly label: ReactNode;
  readonly error?: string | undefined;
}

/** Checkbox with the label as the clickable target and an accessible error. */
export function Checkbox({ label, error, ...rest }: CheckboxProps): ReactElement {
  const ids = useFieldIds(undefined, error);

  return (
    <div className="ui-field">
      <label className="ui-checkbox" htmlFor={ids.inputId}>
        <input
          {...rest}
          type="checkbox"
          id={ids.inputId}
          aria-invalid={error !== undefined}
          aria-describedby={ids.describedBy}
        />
        <span>{label}</span>
      </label>

      {error !== undefined && (
        <p className="ui-field__error" id={ids.errorId}>
          <span aria-hidden="true">⚠</span>
          <span>{error}</span>
        </p>
      )}
    </div>
  );
}
