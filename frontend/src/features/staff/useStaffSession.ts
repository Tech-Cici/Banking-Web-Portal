import { useCallback, useEffect, useState } from 'react';
import { ApiError, staffAuthService } from '@/services';
import type { StaffUser } from '@/types/admin';
import { friendlyError } from '@/services/errorMessage';

/**
 * The signed-in member of bank staff.
 *
 * Deliberately NOT part of the customer `SessionProvider`. Staff hold no accounts and
 * move no money of their own; giving them a `SessionUser` would let a staff session
 * flow into screens written for customers, which is the one thing that must not happen.
 *
 * Local to this feature rather than a global provider, because only the staff portal
 * needs it and a provider mounted at the app root would fetch a staff session on every
 * customer page load.
 */

export type StaffStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error';

export interface StaffSession {
  readonly status: StaffStatus;
  readonly staff: StaffUser | null;
  readonly errorMessage: string | undefined;
  readonly isAdmin: boolean;
  readonly isManager: boolean;
  readonly refresh: () => void;
  readonly signOut: () => Promise<void>;
}

export function useStaffSession(): StaffSession {
  const [status, setStatus] = useState<StaffStatus>('loading');
  const [staff, setStaff] = useState<StaffUser | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | undefined>(undefined);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();

    // Promise callbacks rather than async/await, so state is provably set asynchronously.
    void staffAuthService
      .current(controller.signal)
      .then((member) => {
        if (controller.signal.aborted) return;
        setStaff(member);
        setStatus('authenticated');
        setErrorMessage(undefined);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setStaff(null);

        if (cause instanceof ApiError && cause.kind === 'unauthenticated') {
          setStatus('unauthenticated');
          return;
        }

        setStatus('error');

        const friendly = friendlyError(cause);
        setErrorMessage(
          friendly.nextStep === undefined
            ? friendly.summary
            : `${friendly.summary} ${friendly.nextStep}`,
        );
      });

    return () => {
      controller.abort();
    };
  }, [attempt]);

  const refresh = useCallback(() => {
    setAttempt((count) => count + 1);
  }, []);

  const signOut = useCallback(async (): Promise<void> => {
    // Clear locally first. Whatever the server says, they asked to be signed out.
    setStaff(null);
    setStatus('unauthenticated');
    try {
      await staffAuthService.signOut();
    } catch {
      // Already signed out client-side; nothing useful to report.
    }
  }, []);

  return {
    status,
    staff,
    errorMessage,
    isAdmin: staff?.role === 'ADMIN',
    isManager: staff?.role === 'MANAGER',
    refresh,
    signOut,
  };
}
