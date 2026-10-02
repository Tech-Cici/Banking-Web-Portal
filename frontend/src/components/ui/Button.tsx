import type { ButtonHTMLAttributes, ReactElement, ReactNode } from 'react';
import './ui.css';

export type ButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'danger';

interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className'> {
  readonly variant?: ButtonVariant;
  readonly loading?: boolean;
  readonly block?: boolean;
  readonly children: ReactNode;
}

/**
 * Button.
 *
 * `loading` disables the button as well as showing a spinner. That is the double-submit
 * guard the blueprint requires (section 3): a submit button that still accepts clicks
 * while a request is in flight is how a customer sends the same payment twice.
 *
 * `type` defaults to "button" rather than the HTML default of "submit", because an
 * accidental implicit submit in a multi-step form skips validation on later steps.
 */
export function Button({
  variant = 'primary',
  loading = false,
  block = false,
  disabled = false,
  type = 'button',
  children,
  ...rest
}: ButtonProps): ReactElement {
  const classes = ['ui-btn', `ui-btn--${variant}`, block ? 'ui-btn--block' : '']
    .filter(Boolean)
    .join(' ');

  return (
    <button
      {...rest}
      type={type === 'submit' ? 'submit' : type === 'reset' ? 'reset' : 'button'}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading}
    >
      {loading && <span className="ui-btn__spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}
