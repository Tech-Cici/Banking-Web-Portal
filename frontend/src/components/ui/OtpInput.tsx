import { useEffect, useState, type ReactElement } from 'react';
import { FormField } from './FormField';
import { useFieldIds } from './useFieldIds';
import './ui.css';

interface OtpInputProps {
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  /** Length comes from the server, never assumed (blueprint 5.2). */
  readonly length: number;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly disabled?: boolean;
}

/**
 * One-time code entry.
 *
 * Accepts digits only and strips anything else, so a pasted "123 456" or "Code: 123456"
 * still works. `autocomplete="one-time-code"` lets iOS and Android offer the code straight
 * from the SMS.
 *
 * The value is never logged, never put in a URL and never persisted — the blueprint
 * forbids all three (sections 5.2 and 24).
 */
export function OtpInput({
  label,
  value,
  onChange,
  length,
  hint,
  error,
  disabled = false,
}: OtpInputProps): ReactElement {
  const ids = useFieldIds(hint, error);

  return (
    <FormField ids={ids} label={label} hint={hint} error={error}>
      <input
        id={ids.inputId}
        className={error === undefined ? 'ui-otp' : 'ui-otp ui-otp--invalid'}
        type="text"
        inputMode="numeric"
        autoComplete="one-time-code"
        // A code is not a word: stop the browser correcting or capitalising it.
        autoCorrect="off"
        autoCapitalize="off"
        spellCheck={false}
        maxLength={length}
        value={value}
        disabled={disabled}
        aria-invalid={error !== undefined}
        aria-describedby={ids.describedBy}
        onChange={(event) => {
          onChange(event.target.value.replace(/\D/g, '').slice(0, length));
        }}
      />
    </FormField>
  );
}

interface ResendTimerProps {
  /** Seconds the server says must elapse before another code may be requested. */
  readonly seconds: number;
  readonly onResend: () => void;
  readonly disabled?: boolean;
}

/**
 * Resend control with a countdown.
 *
 * The countdown is display only. The server decides whether a resend is allowed — the
 * blueprint is explicit that refreshing the page must not reset the timer (section 5.2),
 * so this must never be the thing that enforces it.
 */
export function ResendTimer({
  seconds,
  onResend,
  disabled = false,
}: ResendTimerProps): ReactElement {
  const [remaining, setRemaining] = useState(seconds);
  const [issuedFor, setIssuedFor] = useState(seconds);

  // Restart the countdown when the server issues a new window. Adjusting state during
  // render is React's documented pattern for this; doing it in an effect causes a
  // cascading render (react-hooks/set-state-in-effect).
  if (issuedFor !== seconds) {
    setIssuedFor(seconds);
    setRemaining(seconds);
  }

  useEffect(() => {
    if (remaining <= 0) return undefined;

    const timer = window.setTimeout(() => {
      setRemaining((current) => current - 1);
    }, 1000);

    return () => {
      window.clearTimeout(timer);
    };
  }, [remaining]);

  return (
    <div className="ui-otp-resend">
      {remaining > 0 ? (
        <span aria-live="polite">You can request a new code in {remaining}s</span>
      ) : (
        <button
          type="button"
          className="ui-btn ui-btn--tertiary"
          onClick={onResend}
          disabled={disabled}
        >
          Send a new code
        </button>
      )}
    </div>
  );
}
