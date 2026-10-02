import { useState, type ReactElement } from 'react';
import { FormField } from './FormField';
import { useFieldIds } from './useFieldIds';
import './ui.css';

export interface PasswordRule {
  readonly id: string;
  readonly label: string;
  readonly test: (value: string) => boolean;
}

interface PasswordFieldProps {
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly autoComplete: 'new-password' | 'current-password';
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly disabled?: boolean;
  /**
   * Policy rules to display. The real policy belongs to the bank and must also be
   * enforced server-side; showing it here is the blueprint's "password policy should be
   * shown before submit" (section 5.3), not the enforcement point.
   */
  readonly rules?: readonly PasswordRule[];
  readonly name?: string;
}

/**
 * Password entry with a show/hide toggle and a live requirements checklist.
 *
 * Paste is deliberately NOT blocked: the blueprint requires password managers to keep
 * working (section 5.1), and blocking paste pushes people towards weaker, typeable
 * passwords.
 *
 * The value is never logged and never leaves this component except through `onChange`.
 */
export function PasswordField({
  label,
  value,
  onChange,
  autoComplete,
  hint,
  error,
  disabled = false,
  rules,
  name,
}: PasswordFieldProps): ReactElement {
  const [visible, setVisible] = useState(false);
  const ids = useFieldIds(hint, error);

  return (
    <FormField ids={ids} label={label} hint={hint} error={error}>
      <div className="ui-input-wrap">
        <input
          id={ids.inputId}
          {...(name === undefined ? {} : { name })}
          className={error === undefined ? 'ui-input' : 'ui-input ui-input--invalid'}
          type={visible ? 'text' : 'password'}
          autoComplete={autoComplete}
          value={value}
          disabled={disabled}
          aria-invalid={error !== undefined}
          aria-describedby={ids.describedBy}
          onChange={(event) => {
            onChange(event.target.value);
          }}
        />
        <button
          type="button"
          className="ui-btn ui-btn--tertiary ui-input__adornment"
          onClick={() => {
            setVisible((shown) => !shown);
          }}
          // The label states the action; aria-pressed conveys current state.
          aria-pressed={visible}
        >
          {visible ? 'Hide' : 'Show'}
        </button>
      </div>

      {rules !== undefined && rules.length > 0 && (
        <ul
          className="ui-field__hint"
          style={{ listStyle: 'none', padding: 0, marginTop: 'var(--space-3)' }}
        >
          {rules.map((rule) => {
            const met = rule.test(value);
            return (
              <li key={rule.id} style={{ color: met ? 'var(--color-success)' : undefined }}>
                {/* Icon plus text, and the state is in the text for screen readers. */}
                <span aria-hidden="true">{met ? '✓ ' : '• '}</span>
                {rule.label}
                <span className="sr-only">{met ? ' — met' : ' — not met yet'}</span>
              </li>
            );
          })}
        </ul>
      )}
    </FormField>
  );
}
