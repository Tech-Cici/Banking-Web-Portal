import type { InputHTMLAttributes, ReactElement, ReactNode } from 'react';
import { FormField } from './FormField';
import { useFieldIds } from './useFieldIds';
import './ui.css';

type NativeInputProps = Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'id' | 'className' | 'aria-invalid' | 'aria-describedby'
>;

interface TextFieldProps extends NativeInputProps {
  readonly label: string;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly required?: boolean;
  /**
   * A control to sit INSIDE the field, at its trailing edge.
   *
   * <p>The same slot `PasswordField` uses for its Show/Hide toggle, lifted out so an
   * ordinary field can have one too — the sign-in card puts its submit button there.
   *
   * <p>Whatever goes in here must carry its own accessible name: it is a second control
   * sharing one field's label, and "button" is all a screen reader gets otherwise.
   */
  readonly adornment?: ReactNode;
}

/** Single-line text input with label, hint and accessible error wiring. */
export function TextField({
  label,
  hint,
  error,
  required = true,
  adornment,
  ...rest
}: TextFieldProps): ReactElement {
  const ids = useFieldIds(hint, error);

  const input = (
    <input
      {...rest}
      id={ids.inputId}
      className={error === undefined ? 'ui-input' : 'ui-input ui-input--invalid'}
      aria-invalid={error !== undefined}
      aria-describedby={ids.describedBy}
      required={required}
    />
  );

  return (
    <FormField ids={ids} label={label} hint={hint} error={error} required={required}>
      {adornment === undefined ? (
        input
      ) : (
        <div className="ui-input-wrap">
          {input}
          {adornment}
        </div>
      )}
    </FormField>
  );
}

interface TextAreaProps extends Omit<
  InputHTMLAttributes<HTMLTextAreaElement>,
  'id' | 'className' | 'aria-invalid' | 'aria-describedby'
> {
  readonly label: string;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly required?: boolean;
  readonly rows?: number;
}

export function TextArea({
  label,
  hint,
  error,
  required = true,
  rows = 4,
  ...rest
}: TextAreaProps): ReactElement {
  const ids = useFieldIds(hint, error);

  return (
    <FormField ids={ids} label={label} hint={hint} error={error} required={required}>
      <textarea
        {...rest}
        rows={rows}
        id={ids.inputId}
        className={error === undefined ? 'ui-textarea' : 'ui-textarea ui-textarea--invalid'}
        aria-invalid={error !== undefined}
        aria-describedby={ids.describedBy}
        required={required}
      />
    </FormField>
  );
}
