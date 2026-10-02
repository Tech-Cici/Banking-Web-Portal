import { use } from 'react';
import { SessionContext, type SessionState } from '@/contexts/sessionContext';

/**
 * The current session.
 *
 * Throws when used outside the provider, rather than returning a null-ish session that
 * would quietly render a signed-out dashboard.
 */
export function useSession(): SessionState {
  const session = use(SessionContext);

  if (session === null) {
    throw new Error('useSession must be used inside <SessionProvider>.');
  }

  return session;
}
