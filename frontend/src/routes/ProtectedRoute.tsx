import type { ReactElement } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { Alert, Button, PageHeader } from '@/components/ui';
import { LoadingState } from '@/components/feedback/LoadingState';
import { useSession } from '@/hooks/useSession';
import { PUBLIC_PATHS } from './paths';

/**
 * Route guard for authenticated areas.
 *
 * A UX affordance, never a security control. The backend decides entitlement on every
 * request; this only spares a signed-out visitor a screen full of failed panels
 * (blueprint sections 3 and 24).
 *
 * `loading` renders a placeholder rather than redirecting. Redirecting while the session
 * is still being fetched would bounce every signed-in user to the login page on each
 * refresh.
 *
 * <p>AND 'error' IS NOT 'SIGNED OUT', which this guard used to treat as the same thing. The
 * single condition {@code status !== 'authenticated'} sent the customer to a bare sign-in
 * form whenever the session COULD NOT BE CHECKED — the API restarting, a dropped
 * connection, a 500 — which is the wrong answer twice over: it tells them they are signed
 * out when nobody knows, and it sends them to a page where signing in cannot work either,
 * because the thing that is down is the thing that would authenticate them. It also threw
 * away the explanation: `SessionProvider` had already composed a usable `errorMessage` and
 * this guard discarded it.
 *
 * <p>REPRODUCED BEFORE IT WAS FIXED, by signing in and restarting the API: the page went to
 * /login, no `/session` request was recorded at all (the fetch failed at the network layer),
 * and the sign-in screen said nothing about why.
 */
export function ProtectedRoute(): ReactElement {
  const location = useLocation();
  const session = useSession();

  if (session.status === 'loading') {
    return <LoadingState label="Checking your session…" />;
  }

  /*
   * A session still on the temporary password may go exactly one place.
   *
   * The server already refuses to return any account data for it, so without this the
   * portal would render a shell full of failed panels. Checked in the guard rather than
   * on the login page alone, because a redirect after sign-in is trivially skipped by
   * typing a URL — which is how this was found.
   */
  if (session.status === 'authenticated' && session.mustChangePassword) {
    return <Navigate to={PUBLIC_PATHS.changeTemporaryPassword} replace />;
  }

  /*
   * THE SESSION COULD NOT BE CHECKED. Stay where we are and say so.
   *
   * No redirect, deliberately: the customer keeps their URL, so retrying lands them back
   * on the page they asked for rather than on a dashboard they then have to navigate from.
   * And no claim about whether they are signed in, because this branch is reached precisely
   * when that is unknown.
   */
  if (session.status === 'error') {
    return (
      <article>
        <PageHeader
          title="We cannot reach the bank right now"
          lead="Your session has not ended — we simply could not check it."
        />
        <Alert tone="warning" title="Nothing has happened to your account">
          <p>
            {session.errorMessage ??
              'The connection to the bank failed. This is usually brief.'}
          </p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            {/*
              RETRY, NOT "SIGN IN AGAIN". Signing in cannot help when the service that
              would authenticate them is the one that is unreachable, and sending somebody
              to type their password at a dead API is how a customer concludes their
              credentials have stopped working.
            */}
            <Button
              onClick={() => {
                void session.refresh();
              }}
            >
              Try again
            </Button>
          </p>
        </Alert>
      </article>
    );
  }

  if (session.status !== 'authenticated') {
    /*
     * Remember where they were headed so sign-in can return them there — LoginPage reads
     * this and navigates to it rather than to the dashboard. `returning` is what lets the
     * sign-in page explain itself instead of presenting a bare form to somebody who was
     * reading their statement a second ago.
     */
    return (
      <Navigate
        to={PUBLIC_PATHS.login}
        replace
        state={{ from: location.pathname + location.search, returning: true }}
      />
    );
  }

  return <Outlet />;
}
