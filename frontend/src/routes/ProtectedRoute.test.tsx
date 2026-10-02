import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ProtectedRoute } from './ProtectedRoute';
import type { SessionState } from '@/contexts/sessionContext';

/**
 * RELOADING A SIGNED-IN PAGE MUST NOT LOOK LIKE BEING SIGNED OUT.
 *
 * THE REPORT: "when I reload a page as a signed-in person it redirects me to the sign-in
 * page". It had two causes and they needed different fixes.
 *
 * ONE WAS REAL AND IN THIS FILE. The guard's last condition was `status !== 'authenticated'`,
 * which lumped 'error' in with 'unauthenticated' — so whenever the session could not be
 * CHECKED (the API restarting, a dropped connection, a 500) the customer was sent to a bare
 * sign-in form. Wrong twice: it asserts they are signed out when nobody knows, and it sends
 * them somewhere signing in cannot work either, because the service that would authenticate
 * them is the one that is down. It also discarded the explanation `SessionProvider` had
 * already composed.
 *
 * THE OTHER WAS THE SERVER. Sessions lived in Tomcat's memory, so every API restart really
 * did end them — fixed by migration V21 and guarded by SessionsAreInTheDatabaseTest.
 *
 * The session is stubbed here rather than driven through the provider, because what is
 * under test is the guard's decision for each status and nothing else.
 */

const BASE: SessionState = {
  status: 'loading',
  user: null,
  activeCorporate: null,
  errorMessage: undefined,
  mustChangePassword: false,
  can: () => false,
  /* Resolved promises rather than empty async bodies, which the lint rules reject — and
     rightly: an empty async function is usually a half-written one. Nothing in this file
     calls them except the retry test, which supplies its own. */
  switchCorporate: () => Promise.resolve(),
  signOut: () => Promise.resolve(),
  refresh: () => Promise.resolve(),
  refreshAndGet: () => Promise.resolve(null),
};

const mockSession = vi.hoisted(() => ({ current: null as SessionState | null }));

vi.mock('@/hooks/useSession', () => ({
  useSession: (): SessionState => {
    if (mockSession.current === null) throw new Error('no session stubbed');
    return mockSession.current;
  },
}));

function renderAt(session: SessionState, path = '/accounts'): void {
  mockSession.current = session;

  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<ProtectedRoute />}>
          <Route path="/accounts" element={<p>The protected page</p>} />
        </Route>
        <Route path="/login" element={<p>Sign-in form</p>} />
        <Route path="/auth/new-password" element={<p>Change your password</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ProtectedRoute', () => {
  it('DOES NOT SEND A CUSTOMER TO SIGN IN WHEN THE SESSION CANNOT BE CHECKED', () => {
    renderAt({
      ...BASE,
      status: 'error',
      errorMessage: 'The connection to the bank failed.',
    });

    /*
     * The bug, as an assertion. Reproduced before the fix by signing in and restarting the
     * API: the page went to /login, no /session request was even recorded (the fetch failed
     * at the network layer), and the sign-in screen said nothing about why.
     */
    expect(screen.queryByText('Sign-in form')).not.toBeInTheDocument();
    expect(screen.getByText(/We cannot reach the bank right now/i)).toBeInTheDocument();
    expect(screen.getByText(/Your session has not ended/i)).toBeInTheDocument();
    expect(screen.getByText('The connection to the bank failed.')).toBeInTheDocument();
  });

  it('OFFERS A RETRY, NOT A PASSWORD PROMPT', () => {
    const refresh = vi.fn(() => Promise.resolve());
    renderAt({ ...BASE, status: 'error', refresh });

    /*
     * Signing in cannot help when the service that would authenticate them is unreachable,
     * and asking somebody to type their password at a dead API is how they conclude their
     * credentials have stopped working.
     */
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
    expect(screen.queryByText('Sign-in form')).not.toBeInTheDocument();
  });

  it('WAITS WHILE THE SESSION IS STILL BEING FETCHED', () => {
    renderAt({ ...BASE, status: 'loading' });

    /* Redirecting here would bounce every signed-in customer to sign-in on every refresh,
       because a reload always starts with no session in memory. */
    expect(screen.queryByText('Sign-in form')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('STILL SENDS A GENUINELY SIGNED-OUT VISITOR TO SIGN IN', () => {
    renderAt({ ...BASE, status: 'unauthenticated' });

    /*
     * The fix must not go too far. A 401 means the session really has ended, and that
     * person does need the sign-in form — the point is only that an unreachable API is not
     * a 401.
     */
    expect(screen.getByText('Sign-in form')).toBeInTheDocument();
  });

  it('SENDS A TEMPORARY PASSWORD STRAIGHT TO THE CHANGE SCREEN', () => {
    renderAt({
      ...BASE,
      status: 'authenticated',
      mustChangePassword: true,
      user: null,
    });

    /* Unchanged behaviour, asserted so the rewrite above cannot have lost it: the server
       refuses account data for such a session, so the portal would otherwise render a
       shell full of failed panels. */
    expect(screen.getByText('Change your password')).toBeInTheDocument();
  });

  it('LETS A SIGNED-IN CUSTOMER THROUGH', () => {
    renderAt({ ...BASE, status: 'authenticated' });

    expect(screen.getByText('The protected page')).toBeInTheDocument();
  });
});
