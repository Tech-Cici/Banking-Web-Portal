import type { ReactElement, SelectHTMLAttributes } from 'react';
import { FormField } from './FormField';
import { useFieldIds } from './useFieldIds';
import './ui.css';

export interface SelectOption {
  readonly value: string;
  readonly label: string;
}

interface SelectProps extends Omit<
  SelectHTMLAttributes<HTMLSelectElement>,
  'id' | 'className' | 'aria-invalid' | 'aria-describedby' | 'children'
> {
  readonly label: string;
  readonly options: readonly SelectOption[];
  readonly placeholder?: string;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly required?: boolean;
}

/**
 * Native select.
 *
 * Native on purpose: it is keyboard- and screen-reader-correct for free, and on a phone it
 * opens the platform picker. A searchable combobox is only needed where lists are long —
 * banks and beneficiaries (blueprint 2.2) — and that arrives with those screens.
 */
export function Select({
  label,
  options,
  placeholder = 'Select…',
  hint,
  error,
  required = true,
  ...rest
}: SelectProps): ReactElement {
  const ids = useFieldIds(hint, error);

  return (
    <FormField ids={ids} label={label} hint={hint} error={error} required={required}>
      <select
        {...rest}
        id={ids.inputId}
        className={error === undefined ? 'ui-select' : 'ui-select ui-select--invalid'}
        aria-invalid={error !== undefined}
        aria-describedby={ids.describedBy}
        required={required}
      >
        <option value="">{placeholder}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </FormField>
  );
}
