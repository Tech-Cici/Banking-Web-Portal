import type { ReactElement } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Alert, StatusBadge } from '@/components/ui';
import { LoadingState } from '@/components/feedback/LoadingState';
import { BRAND } from '@/config/brand';
import { appConfig } from '@/config/env';
import { STAFF_PATHS } from '@/routes/paths';
import { useStaffSession } from './useStaffSession';
import './staff.css';

/**
 * The shell for the bank's own portal.
 *
 * Visually distinct from the customer portal, deliberately. A dark header and the word
 * "Staff" next to the name mean an employee always knows which system they are in, and
 * a customer who somehow lands here knows immediately that they should not be.
 *
 * It is also the guard: no staff session, no staff screens. Like the customer guard,
 * that is a courtesy — every endpoint behind it checks the role itself, because a
 * hidden route is not a permission.
 */
export function StaffLayout(): ReactElement {
  const session = useStaffSession();
  const navigate = useNavigate();

  if (session.status === 'loading') {
    return <LoadingState label="Checking your staff session…" />;
  }

  if (session.status !== 'authenticated' || session.staff === null) {
    return (
      <div className="staff-login">
        <div className="staff-login__card">
          <h1 className="staff-login__title">Staff sign-in required</h1>
          <Alert tone="info">
            {session.errorMessage ?? 'Please sign in with your staff account to continue.'}
          </Alert>
          <p style={{ marginTop: 'var(--space-4)' }}>
            <NavLink to={STAFF_PATHS.login}>Go to staff sign-in</NavLink>
          </p>
        </div>
      </div>
    );
  }

  const staff = session.staff;

  /*
   * Navigation reflects the role, because the two jobs barely overlap. An admin creates
   * accounts and never approves; a manager approves and never creates. Showing each the
   * other's screen invites them to try, and the server would refuse them.
   */
  const items = [
    { label: 'Overview', to: STAFF_PATHS.dashboard, show: true },
    { label: 'Registrations', to: STAFF_PATHS.applications, show: session.isAdmin },
    { label: 'Awaiting approval', to: STAFF_PATHS.approvals, show: session.isManager },
    /*
     * Named for the money rather than for the word "approval", so it does not read as a
     * second copy of the item above it. These are two different queues: one holds logins
     * that do not work yet, this one holds money that has already left an account.
     */
    { label: 'Money to release', to: STAFF_PATHS.transferQueue, show: session.isManager },
    /*
     * Manager only, matching the server. An administrator can see the registrations queue
     * and not this one, because issuing a password hands out working access to an existing
     * account — the same authority as approving a new one.
     */
    { label: 'Password requests', to: STAFF_PATHS.passwordRequests, show: session.isManager },
    /*
     * BOTH ROLES, unlike every queue above it, and that is not an oversight. The split
     * between admin and manager exists because issuing a credential should take two
     * people — one creates the account, another releases it. A payee is different: the
     * MAKER IS THE CUSTOMER and staff are the checker, so the separation holds whoever
     * clears it. It is also volume work, and putting it behind the one role that releases
     * money would mean customers waiting on a manager to do a name comparison.
     */
    { label: 'Payees to check', to: STAFF_PATHS.beneficiaryReviews, show: true },
    /*
     * BOTH ROLES, on the payee queue's reasoning and one of its own: making a card is
     * volume work, and putting it behind the single role that releases money would leave
     * customers waiting on a manager to print something.
     */
    { label: 'Cards and cheque books', to: STAFF_PATHS.serviceRequests, show: true },
    { label: 'Customers', to: STAFF_PATHS.customers, show: true },
    /*
     * DEVELOPER TOOLS, LAST, AND ONLY OUTSIDE PRODUCTION. This was "Sent messages", which
     * read like an ordinary banking screen; it is the development mailbox, and it now sits
     * with the start-over button on one page that says what it is. Hiding the link is a
     * courtesy — the backend refuses the reset outside the dev, h2 and test profiles, which
     * is the actual control.
     */
    {
      label: 'Developer tools',
      to: STAFF_PATHS.developerTools,
      /*
       * THE SAME FLAG THE ROUTE USES, not `!isProduction`. With the flag off the page does
       * not exist in the bundle at all, so a link to it would be a link to nothing; with it
       * on — a demonstration build — the link has to appear even though this IS a
       * production build.
       */
      show: appConfig.enableDevTools,
    },
  ].filter((item) => item.show);

  const onSignOut = async (): Promise<void> => {
    await session.signOut();
    void navigate(STAFF_PATHS.login, { replace: true });
  };

  return (
    <div className="staff">
      <a className="skip-link" href="#staff-main">
        Skip to main content
      </a>

      {/*
       * ONLY THE NAV STICKS, and the header scrolls away above it.
       *
       * Pinning both was the obvious reading of "make the nav sticky" and measured
       * badly: this header wraps to two lines on a phone when a long name and role no
       * longer fit beside the brand, so the pinned chrome came to 192px of a 780px
       * screen — a quarter of the display given over to the header, permanently, on
       * every list an administrator scrolls.
       *
       * A sticky nav alone needs no offset at all: it sits under the header in normal
       * flow, and pins to the top of the window once the header has scrolled past. The
       * section tabs are what staff need while working down a long list; the brand and
       * the sign-out button are not.
       */}
      <header className="staff__header">
        <div className="staff__brand">
          <span className="staff__brand-name">{BRAND.SHORT}</span>
          <StatusBadge tone="neutral" label="Staff" />
        </div>

        <div className="staff__who">
          <span>
            {staff.fullName} · {staff.role === 'ADMIN' ? 'Administrator' : 'Manager'}
          </span>
          <button type="button" className="staff__signout" onClick={() => void onSignOut()}>
            Sign out
          </button>
        </div>
      </header>

      <nav className="staff__nav" aria-label="Staff sections">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === STAFF_PATHS.dashboard}
            className={({ isActive }) =>
              isActive ? 'staff__nav-link staff__nav-link--active' : 'staff__nav-link'
            }
          >
            {item.label}
          </NavLink>
        ))}
      </nav>

      <main id="staff-main" className="staff__main">
        <Outlet context={session} />
      </main>

      <footer className="staff__footer">
        <span>
          Actions here are recorded against your name. Never share a customer&rsquo;s temporary
          password by email.
        </span>
        <span>Version {appConfig.appVersion}</span>
      </footer>
    </div>
  );
}
