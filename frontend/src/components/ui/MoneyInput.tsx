import type { ChangeEvent, ReactElement } from 'react';
import { FormField } from './FormField';
import { useFieldIds } from './useFieldIds';
import { currencyScale } from '@/utils/money';
import './ui.css';

interface MoneyInputProps {
  readonly label: string;
  readonly currency: string;
  /** The decimal string exactly as typed. Never a number. */
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly disabled?: boolean;
}

/**
 * An amount field that keeps the amount as a string.
 *
 * The value never becomes a `number`, not even briefly. `parseFloat` on the way in and
 * `toFixed` on the way out is how 1,234,567.89 quietly becomes 1,234,567.88, and RWF
 * figures are large enough to lose integer precision outright.
 *
 * Typing is filtered rather than corrected: digits and at most one decimal point, capped
 * at the currency's ISO scale, so RWF takes no decimals at all and USD takes two. A field
 * that silently rewrites what someone typed is worse than one that refuses the keystroke
 * — they can see the refusal.
 *
 * `inputMode="decimal"` gives a phone the numeric keypad while keeping the field a text
 * input, because `type="number"` brings spinners, locale-dependent parsing and a scroll
 * wheel that can change an amount by accident.
 */
export function MoneyInput({
  label,
  currency,
  value,
  onChange,
  hint,
  error,
  disabled = false,
}: MoneyInputProps): ReactElement {
  const ids = useFieldIds(hint, error);
  const scale = currencyScale(currency);

  const handle = (event: ChangeEvent<HTMLInputElement>): void => {
    const raw = event.target.value.replace(/[^\d.]/g, '');

    // At most one decimal point: keep the first, drop the rest.
    const [whole = '', ...others] = raw.split('.');
    const fraction = others.join('');

    if (scale === 0) {
      onChange(whole);
      return;
    }

    onChange(others.length === 0 ? whole : `${whole}.${fraction.slice(0, scale)}`);
  };

  return (
    <FormField ids={ids} label={label} hint={hint} error={error} required>
      <div className="ui-money">
        <span className="ui-money__currency" aria-hidden="true">
          {currency}
        </span>
        <input
          id={ids.inputId}
          type="text"
          inputMode="decimal"
          autoComplete="off"
          className={error === undefined ? 'ui-input' : 'ui-input ui-input--invalid'}
          value={value}
          disabled={disabled}
          aria-invalid={error !== undefined}
          aria-describedby={ids.describedBy}
          /* The visible currency badge is decorative, so the amount carries it in its name. */
          aria-label={`${label} in ${currency}`}
          onChange={handle}
        />
      </div>
    </FormField>
  );
}
