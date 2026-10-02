import { useCallback, useState } from 'react';
import { ApiError } from '@/services';
import { friendlyError } from '@/services/errorMessage';

export interface SubmitState {
  readonly pending: boolean;
  /** What went wrong, in plain language. */
  readonly message: string | undefined;
  /** What to do about it. Absent when there is nothing useful to say. */
  readonly nextStep: string | undefined;
  /** Correlation id, shown so the customer can quote it to support. */
  readonly reference: string | undefined;
  /** Server-supplied field errors, merged into the form's own. */
  readonly fieldErrors: Readonly<Record<string, string>>;
}

const IDLE: SubmitState = {
  pending: false,
  message: undefined,
  nextStep: undefined,
  reference: undefined,
  fieldErrors: {},
};

interface UseSubmitState {
  readonly state: SubmitState;
  /** Runs `task`, tracking pending state and normalising any failure. */
  readonly run: <T>(task: () => Promise<T>) => Promise<T | undefined>;
  readonly reset: () => void;
}

/**
 * Wraps a service call with pending state and normalised error handling.
 *
 * Every registration step needs the same three things — disable the button while in
 * flight, show a safe message on failure, surface the reference id — and repeating that
 * in six places is how they end up subtly different.
 *
 * On failure it returns `undefined` rather than throwing, so callers advance only on a
 * real result. Nothing about the request or its payload is logged.
 */
export function useSubmitState(): UseSubmitState {
  const [state, setState] = useState<SubmitState>(IDLE);

  const reset = useCallback(() => {
    setState(IDLE);
  }, []);

  const run = useCallback(async <T>(task: () => Promise<T>): Promise<T | undefined> => {
    setState({ ...IDLE, pending: true });

    try {
      const result = await task();
      setState(IDLE);
      return result;
    } catch (cause) {
      /*
       * The server's own text is kept only where it is worth keeping — a rule that
       * refused the request knows why. For an outage or a dropped connection it was
       * previously echoed verbatim, which is how "We could not complete your request"
       * came to be the answer to every different problem.
       */
      /*
       * `hadSession: false` — registration is, by definition, something you do before
       * you have an account. A 401 here is the bank refusing the request, never a
       * session ending, and the default wording ("You have been signed out") is
       * nonsense to someone who has never signed in. This is the bug that put exactly
       * that sentence on the personal registration form.
       */
      const friendly = friendlyError(cause, undefined, { hadSession: false });

      setState({
        pending: false,
        message: friendly.summary,
        nextStep: friendly.nextStep,
        reference: friendly.reference,
        fieldErrors: cause instanceof ApiError ? cause.fieldErrorMap : {},
      });
      return undefined;
    }
  }, []);

  return { state, run, reset };
}
