import type { ReactElement, ReactNode } from 'react';
import type { FieldIds } from './useFieldIds';
import './ui.css';

interface FormFieldProps {
  readonly ids: FieldIds;
  readonly label: string;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly required?: boolean;
  /** Renders the control. */
  readonly children: ReactNode;
}

/**
 * Label + control + hint + error.
 *
 * The label is always a real `<label>` pointing at the control — never a placeholder
 * standing in for one, which the blueprint rules out explicitly (section 2.1) and which
 * disappears the moment a customer starts typing.
 *
 * Optional fields are marked rather than required ones: on these forms most fields are
 * required, so marking the exception is quieter and clearer.
 */
export function FormField({
  ids,
  label,
  hint,
  error,
  required = true,
  children,
}: FormFieldProps): ReactElement {
  return (
    <div className="ui-field">
      <label className="ui-field__label" htmlFor={ids.inputId}>
        {label}
        {!required && <span className="ui-field__optional"> (optional)</span>}
      </label>

      {children}

      {hint !== undefined && (
        <p className="ui-field__hint" id={ids.hintId}>
          {hint}
        </p>
      )}

      {error !== undefined && (
        <p className="ui-field__error" id={ids.errorId}>
          {/* Icon + text, never colour alone (blueprint section 23). */}
          <span aria-hidden="true">⚠</span>
          <span>{error}</span>
        </p>
      )}
    </div>
  );
}
