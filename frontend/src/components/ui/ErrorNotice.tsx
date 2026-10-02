import { use, type ReactElement } from 'react';
import { SessionContext } from '@/contexts/sessionContext';
import { friendlyError } from '@/services/errorMessage';
import { Alert } from './Alert';
import { Button } from './Button';
import './ui.css';

interface ErrorNoticeProps {
  /**
   * Whatever was caught. Not narrowed on purpose — a `catch` gives you `unknown`, and
   * forcing every screen to narrow first is how twenty different fallback sentences got
   * written.
   */
  readonly error: unknown;

  /**
   * What the person was trying to do, in their words, finishing the sentence
   * "We could not …" — for example `"send your payment"` or `"load your accounts"`.
   *
   * Required, because "Error" as a heading tells a customer nothing about what state
   * they are now in or whether their money moved.
   */
  readonly action: string;

  /**
   * Offered only when repeating the request is safe. A conflict or an unknown payment
   * outcome must never show a retry button, so this is ignored in those cases rather
   * than left to each caller to remember.
   */
  readonly onRetry?: (() => void) | undefined;

  /** Label for the retry button, when the default does not read well. */
  readonly retryLabel?: string;
}

/**
 * The one way this app tells someone something went wrong.
 *
 * Three things, always in the same order: what happened, what to do about it, and the
 * reference to quote if they need to call. Screens no longer compose those themselves,
 * so the quality of an error message stops depending on which page you are standing on.
 */
export function ErrorNotice({
  error,
  action,
  onRetry,
  retryLabel = 'Try again',
}: ErrorNoticeProps): ReactElement {
  /*
   * Read directly rather than through `useSession`, which throws outside the provider.
   * This component renders on public pages and in tests, and an error notice that
   * crashes while reporting an error is the worst possible failure mode.
   *
   * The session decides one thing only: whether a 401 reads as "you have been signed
   * out" or as a plain refusal. Deciding it here rather than asking each caller is the
   * whole point — twenty screens would not each remember.
   */
  const session = use(SessionContext);
  const hadSession = session?.status === 'authenticated';

  const friendly = friendlyError(error, undefined, { hadSession });

  /*
   * The retry button is gated on `canRetry`, not on whether the caller passed a handler.
   * Repeating a payment whose outcome is unknown is the single most expensive mistake
   * this screen could invite, so the decision lives here rather than in each caller.
   */
  const showRetry = onRetry !== undefined && friendly.canRetry;

  return (
    <Alert tone="error" title={`We could not ${action}`} reference={friendly.reference}>
      <p>{friendly.summary}</p>

      {friendly.nextStep !== undefined && (
        <p className="ui-alert__next">{friendly.nextStep}</p>
      )}

      {showRetry && (
        <p className="ui-alert__action">
          <Button variant="secondary" onClick={onRetry}>
            {retryLabel}
          </Button>
        </p>
      )}
    </Alert>
  );
}
