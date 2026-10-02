import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/services';
import type { ApiErrorKind } from '@/services';

/**
 * Loads data for a panel.
 *
 * Every dashboard panel fetches independently, and one failing panel must not blank the
 * page — a customer whose notifications endpoint is down should still see their balances.
 * So this returns a per-panel state rather than throwing to an error boundary.
 *
 * It calls the service layer, never `fetch`: the API client is the only place that talks
 * to the network (brief section 39).
 */

export interface AsyncLoading {
  readonly status: 'loading';
}

export interface AsyncReady<T> {
  readonly status: 'ready';
  readonly data: T;
}

export interface AsyncFailed {
  readonly status: 'error';
  /** Safe, displayable text. Never a stack or an upstream payload. */
  readonly message: string;
  /** Present when the server sent one, so support can trace the call. */
  readonly reference: string | undefined;
  readonly kind: ApiErrorKind | 'unknown';
  /**
   * The failure itself, kept so the screen can say what actually went wrong.
   *
   * Flattening it to `message` threw away everything needed to write a useful sentence —
   * whether the device was offline, how long a rate limit has left to run, whether
   * retrying is even safe — and left every panel printing the same "could not load this".
   */
  readonly cause: unknown;
}

export type AsyncState<T> = AsyncLoading | AsyncReady<T> | AsyncFailed;

export interface UseAsyncResult<T> {
  readonly state: AsyncState<T>;
  /** Re-runs the loader. Used by the retry button on a failed panel. */
  readonly reload: () => void;
}

const LOADING: AsyncLoading = { status: 'loading' };

/**
 * @param key   Changes whenever the data should be reloaded — typically the active
 *              company id. A key change shows the loading state again rather than leaving
 *              the previous company's figures on screen while the new ones arrive.
 * @param load  Receives an AbortSignal and must pass it to the service call.
 */
export function useAsync<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
): UseAsyncResult<T> {
  /*
   * The loader is held in a ref, not in the dependency list.
   *
   * Callers write the loader inline, so it is a new function on every render. In the
   * dependency array that is an infinite fetch loop; in a ref it is simply the latest
   * loader, which is what we want when the effect does eventually run.
   */
  const loadRef = useRef(load);

  /*
   * Assigned in an effect, not during render. Writing to a ref while rendering is a
   * mutation React is free to discard when it re-renders speculatively, and the lint
   * rule that forbids it is right to.
   *
   * Declared BEFORE the loading effect below, so on any commit that changes the key the
   * fresh loader is in place before the load runs.
   */
  useEffect(() => {
    loadRef.current = load;
  });

  /*
   * The key is stored alongside the state.
   *
   * When it changes, the state belonging to the old key is stale, and the render below
   * reports `loading` without setting state synchronously inside an effect — which
   * react-hooks/set-state-in-effect rejects, for good reason.
   */
  const [entry, setEntry] = useState<{ key: string; state: AsyncState<T> }>({
    key,
    state: LOADING,
  });

  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();

    // Promise callbacks rather than async/await, so state is provably set asynchronously.
    void loadRef
      .current(controller.signal)
      .then((data) => {
        if (controller.signal.aborted) return;
        setEntry({ key, state: { status: 'ready', data } });
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;

        const failure: AsyncFailed =
          cause instanceof ApiError
            ? {
                status: 'error',
                message: cause.message,
                reference: cause.correlationId,
                kind: cause.kind,
                cause,
              }
            : {
                status: 'error',
                message: 'We could not load this right now.',
                reference: undefined,
                kind: 'unknown',
                cause,
              };

        setEntry({ key, state: failure });
      });

    return () => {
      controller.abort();
    };
  }, [key, attempt]);

  const reload = useCallback(() => {
    setAttempt((count) => count + 1);
  }, []);

  return { state: entry.key === key ? entry.state : LOADING, reload };
}
