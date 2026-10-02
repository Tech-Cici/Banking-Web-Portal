import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactElement,
  type ReactNode,
} from 'react';
import {
  ApiError,
  authService,
  onUnauthenticated,
  sessionService,
  type SessionResponse,
} from '@/services';
import type { Permission } from '@/types/api';
import type { CorporateMembership, SessionUser } from '@/types/banking';
import { SessionContext, type SessionState, type SessionStatus } from './sessionContext';
import { friendlyError } from '@/services/errorMessage';

interface SessionProviderProps {
  readonly children: ReactNode;
}

interface InternalState {
  readonly status: SessionStatus;
  readonly user: SessionUser | null;
  readonly activeCorporateId: string | null;
  readonly errorMessage: string | undefined;
  readonly mustChangePassword: boolean;
}

const INITIAL: InternalState = {
  status: 'loading',
  user: null,
  activeCorporateId: null,
  errorMessage: undefined,
  mustChangePassword: false,
};

/**
 * Loads the session once and exposes it to the app.
 *
 * Also subscribes to the API client's 401 signal, so a session that expires mid-use puts
 * the whole app into `unauthenticated` rather than leaving one failed widget on a screen
 * that still looks signed in.
 */
export function SessionProvider({ children }: SessionProviderProps): ReactElement {
  const [state, setState] = useState<InternalState>(INITIAL);

  /** Applies a successful session response. */
  const applySession = useCallback((response: SessionResponse): void => {
    setState({
      status: 'authenticated',
      user: response.user,
      activeCorporateId: response.activeCorporateId,
      errorMessage: undefined,
      mustChangePassword: response.mustChangePassword,
    });
  }, []);

  /** Normalises a failure into either an unauthenticated or an error session. */
  const applyFailure = useCallback((cause: unknown, fallback: string): void => {
    if (cause instanceof ApiError && cause.kind === 'unauthenticated') {
      setState({ ...INITIAL, status: 'unauthenticated' });
      return;
    }

    /*
     * `fallback` names the action that failed ("We could not switch company"); the
     * translator supplies why and what to do. Previously the server's own text won,
     * which for any 5xx meant the same twelve generic words on every screen.
     */
    const friendly = friendlyError(cause, fallback);

    setState({
      ...INITIAL,
      status: 'error',
      errorMessage:
        friendly.nextStep === undefined
          ? friendly.summary
          : `${friendly.summary} ${friendly.nextStep}`,
    });
  }, []);

  /*
   * Promise callbacks rather than async/await inside the effect.
   *
   * State is only ever set from .then/.catch, which keeps it plainly asynchronous.
   * An async function called from an effect body cannot be proven not to setState
   * synchronously, and react-hooks/set-state-in-effect rejects it.
   */
  const load = useCallback(
    (signal?: AbortSignal): Promise<void> =>
      sessionService
        .current(signal)
        .then(applySession)
        .catch((cause: unknown) => {
          if (signal?.aborted === true) return;
          applyFailure(cause, 'We could not load your session.');
        }),
    [applySession, applyFailure],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => {
      controller.abort();
    };
  }, [load]);

  /*
   * A 401 from a protected endpoint ends the session everywhere.
   *
   * Guarded on there being a session in the first place. The client already filters
   * public paths, but this is the half of the check that cannot be got wrong by adding
   * an endpoint: you cannot be signed out if you were never signed in, and a stray
   * broadcast otherwise flips an anonymous visitor into a "you have been signed out"
   * state on a page that never needed a session.
   */
  useEffect(
    () =>
      onUnauthenticated(() => {
        setState((current) =>
          current.status === 'authenticated' ? { ...INITIAL, status: 'unauthenticated' } : current,
        );
      }),
    [],
  );

  const switchCorporate = useCallback(
    async (corporateId: string): Promise<void> => {
      /*
       * Back to loading before the request, not after it resolves.
       *
       * The blueprint is explicit (section 6.2): while switching company, the previous
       * company's data must never still be on screen. Clearing first makes that structural
       * rather than something every consuming screen has to remember.
       */
      setState((current) => ({ ...current, status: 'loading' }));

      try {
        applySession(await sessionService.switchCorporate(corporateId));
      } catch (cause) {
        applyFailure(cause, 'We could not switch company.');
      }
    },
    [applySession, applyFailure],
  );

  const refresh = useCallback(async (): Promise<void> => {
    await load();
  }, [load]);

  const refreshAndGet = useCallback(async (): Promise<SessionUser | null> => {
    try {
      const response = await sessionService.current();
      applySession(response);
      return response.user;
    } catch (cause) {
      applyFailure(cause, 'We could not load your session.');
      return null;
    }
  }, [applySession, applyFailure]);

  const signOut = useCallback(async (): Promise<void> => {
    // Clear locally first. Whatever the server says, the user asked to be signed out.
    setState({ ...INITIAL, status: 'unauthenticated' });

    try {
      await authService.logout();
    } catch {
      // Already signed out client-side; nothing useful to tell the user.
    }
  }, []);

  const value = useMemo<SessionState>(() => {
    const user = state.user;
    const activeCorporate: CorporateMembership | null =
      user === null || state.activeCorporateId === null
        ? null
        : (user.corporates.find((c) => c.id === state.activeCorporateId) ?? null);

    return {
      status: state.status,
      user,
      activeCorporate,
      errorMessage: state.errorMessage,
      mustChangePassword: state.mustChangePassword,
      can: (permission: Permission): boolean => user?.permissions.includes(permission) ?? false,
      switchCorporate,
      signOut,
      refresh,
      refreshAndGet,
    };
  }, [state, switchCorporate, signOut, refresh, refreshAndGet]);

  return <SessionContext value={value}>{children}</SessionContext>;
}
