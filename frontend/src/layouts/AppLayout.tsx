import { useEffect, useState, type ReactElement } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { BRAND } from '@/config/brand';
import { useSession } from '@/hooks/useSession';
import type { SessionState } from '@/contexts/sessionContext';
import {
  CORPORATE_PATHS,
  DIAGNOSTIC_PATHS,
  homePathFor,
  PUBLIC_PATHS,
  RETAIL_PATHS,
} from '@/routes/paths';
import type { Permission } from '@/types/api';
import './AppLayout.css';

/**
 * Authenticated application shell.
 *
 * PHASE 1 SCAFFOLD, now permission-aware. The header, side navigation and title area are
 * the shell the blueprint specifies (section 2.3 desktop, 2.4 mobile). Phase 4 still owes
 * the corporate context selector in the header, notifications, the user menu, search,
 * breadcrumbs and mobile bottom navigation.
 *
 * NO FOOTER, deliberately — see the comment at the end of the render where it used to be.
 * The signed-in shell is two grid rows, so the page content reaches the bottom of the
 * window instead of stopping above a strip that repeated the same two lines on every
 * screen.
 *
 * What is no longer scaffolding is the navigation itself: it is filtered against the
 * session's permissions, so a personal customer is not shown Salary and an approver is
 * not shown links to create payments they are not allowed to create.
 *
 * Filtering is a courtesy, never a control. The backend refuses an unauthorised request
 * whether or not the link exists, and none of this may ever be mistaken for authorisation
 * (blueprint sections 3 and 24). Its purpose is that the menu describes the job the person
 * actually has.
 */

interface NavItem {
  readonly label: string;
  readonly to: string;
  /**
   * Shown when the user holds ANY of these. Empty means always shown.
   *
   * Several links are legitimately reachable from two different permissions — a transfer
   * screen serves a retail customer with TRANSFER_CREATE and a corporate maker with
   * CORPORATE_TRANSFER_CREATE — so this is a list rather than a single value.
   */
  readonly anyOf: readonly Permission[];
}

interface NavGroup {
  readonly heading: string;
  readonly items: readonly NavItem[];
}

const VIEW_ACCOUNTS: readonly Permission[] = ['RETAIL_ACCOUNT_VIEW', 'CORPORATE_VIEW'];
const MAKE_TRANSFER: readonly Permission[] = ['TRANSFER_CREATE', 'CORPORATE_TRANSFER_CREATE'];
const MAKE_PAYMENT: readonly Permission[] = ['PAYMENT_CREATE', 'CORPORATE_PAYMENT_CREATE'];

const NAV_GROUPS: readonly NavGroup[] = [
  {
    heading: 'Banking',
    items: [
      { label: 'Accounts', to: RETAIL_PATHS.accounts, anyOf: VIEW_ACCOUNTS },
      { label: 'Statements', to: RETAIL_PATHS.statements, anyOf: VIEW_ACCOUNTS },
      { label: 'Beneficiaries', to: RETAIL_PATHS.beneficiaries, anyOf: ['BENEFICIARY_MANAGE'] },
    ],
  },
  {
    heading: 'Move money',
    items: [
      { label: 'Transfers', to: RETAIL_PATHS.transfers, anyOf: MAKE_TRANSFER },
      { label: 'Payments', to: RETAIL_PATHS.payments, anyOf: MAKE_PAYMENT },
      { label: 'Standing orders', to: RETAIL_PATHS.standingOrders, anyOf: MAKE_TRANSFER },
      { label: 'Foreign exchange', to: RETAIL_PATHS.fx, anyOf: MAKE_TRANSFER },
    ],
  },
  {
    heading: 'Products',
    items: [
      { label: 'Loans', to: RETAIL_PATHS.loans, anyOf: ['LOAN_VIEW'] },
      { label: 'Cards', to: RETAIL_PATHS.cards, anyOf: ['CARD_MANAGE'] },
      { label: 'Cheque books', to: RETAIL_PATHS.chequeBooks, anyOf: ['RETAIL_ACCOUNT_VIEW'] },
    ],
  },
  {
    heading: 'Corporate',
    items: [
      { label: 'Approvals', to: CORPORATE_PATHS.approvals, anyOf: ['APPROVAL_VIEW'] },
      { label: 'Bulk operations', to: CORPORATE_PATHS.bulk, anyOf: ['BULK_VIEW', 'BULK_CREATE'] },
      { label: 'Salary', to: CORPORATE_PATHS.salary, anyOf: ['BULK_VIEW', 'BULK_CREATE'] },
    ],
  },
  {
    heading: 'Account',
    items: [
      { label: 'Profile', to: RETAIL_PATHS.profile, anyOf: [] },
      { label: 'Security', to: RETAIL_PATHS.security, anyOf: [] },
      { label: 'Notifications', to: RETAIL_PATHS.notifications, anyOf: [] },
      { label: 'Help', to: RETAIL_PATHS.help, anyOf: [] },
      { label: 'System status', to: DIAGNOSTIC_PATHS.systemStatus, anyOf: [] },
    ],
  },
];

/**
 * The groups this user should see, with empty groups dropped.
 *
 * Dropping them matters: a "Corporate" heading with nothing under it tells a personal
 * customer there is a part of the product being kept from them, which is both untrue and
 * an invitation to go looking.
 *
 * Dashboard is prepended rather than listed, because its destination depends on who is
 * signed in — a corporate user's dashboard is not at the retail path.
 */
function visibleGroups(session: SessionState): readonly NavGroup[] {
  const dashboard: NavGroup = {
    heading: 'Banking',
    items: [
      {
        label: 'Dashboard',
        to: homePathFor(session.user?.userType ?? 'RETAIL'),
        anyOf: [],
      },
    ],
  };

  return NAV_GROUPS.map((group) => {
    const items = group.items.filter(
      (item) => item.anyOf.length === 0 || item.anyOf.some((permission) => session.can(permission)),
    );

    return group.heading === 'Banking'
      ? { ...group, items: [...dashboard.items, ...items] }
      : { ...group, items };
  }).filter((group) => group.items.length > 0);
}

function navLinkClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'app-layout__nav-link app-layout__nav-link--active' : 'app-layout__nav-link';
}

export function AppLayout(): ReactElement {
  const session = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const groups = visibleGroups(session);

  /*
   * WHETHER THE DRAWER IS OPEN IS DERIVED, not stored.
   *
   * State holds the path the customer opened the menu on, and the drawer counts as open
   * only while that still matches where they are. So any navigation closes it — a link
   * in the menu, a redirect, the browser's back button, a screen that routes on its own
   * — without an effect watching the location and calling setState, which React warns
   * about because it renders the page twice on every single navigation.
   *
   * The link's own onClick would cover the ordinary case and miss the rest, leaving the
   * overlay sitting on top of the page the customer just arrived at.
   */
  const [openedOnPath, setOpenedOnPath] = useState<string | null>(null);
  const isNavOpen = openedOnPath === location.pathname;

  const closeNav = (): void => {
    setOpenedOnPath(null);
  };

  /*
   * ESCAPE CLOSES THE DRAWER.
   *
   * On a narrow screen the navigation is an overlay above the page, and anything that
   * covers the page has to be dismissible from the keyboard — otherwise a keyboard or
   * screen-reader user who opens it has no way out except tabbing to the toggle they
   * cannot see.
   */
  useEffect(() => {
    if (!isNavOpen) return undefined;

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') closeNav();
    };

    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [isNavOpen]);

  const onSignOut = async (): Promise<void> => {
    await session.signOut();
    void navigate(PUBLIC_PATHS.login, { replace: true });
  };

  return (
    <div className="app-layout">
      {/* First focusable element, so a keyboard user can jump past the navigation. */}
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>

      <header className="app-layout__header">
        <span className="app-layout__brand">{BRAND.SHORT}</span>

        <div className="app-layout__header-right">
          {session.user !== null && (
            <span className="app-layout__whoami">
              {session.user.preferredName}
              {session.activeCorporate !== null && (
                <span className="app-layout__company"> · {session.activeCorporate.name}</span>
              )}
            </span>
          )}

          <button type="button" className="app-layout__signout" onClick={() => void onSignOut()}>
            Sign out
          </button>

          <button
            type="button"
            className="app-layout__nav-toggle"
            aria-expanded={isNavOpen}
            aria-controls="primary-navigation"
            onClick={() => {
              setOpenedOnPath(isNavOpen ? null : location.pathname);
            }}
          >
            {isNavOpen ? 'Close menu' : 'Menu'}
          </button>
        </div>
      </header>

      {/*
       * The scrim behind the drawer.
       *
       * A button rather than a div, so it is a real dismiss control: tapping outside a
       * menu to close it is what people expect, and a div with an onClick is invisible
       * to assistive technology.
       *
       * Always rendered and hidden with CSS rather than removed from the tree, so the
       * fade works in both directions. `visibility: hidden` while closed keeps it out
       * of the tab order and out of hit-testing, which a transparent overlay left in
       * the page would not be.
       */}
      <button
        type="button"
        tabIndex={isNavOpen ? 0 : -1}
        aria-hidden={!isNavOpen}
        aria-label="Close the menu"
        className={isNavOpen ? 'app-layout__scrim app-layout__scrim--open' : 'app-layout__scrim'}
        onClick={closeNav}
      />

      <nav
        id="primary-navigation"
        aria-label="Primary"
        className={
          isNavOpen ? 'app-layout__sidebar app-layout__sidebar--open' : 'app-layout__sidebar'
        }
      >
        {groups.map((group) => (
          <div key={group.heading} className="app-layout__nav-group">
            <h2 className="app-layout__nav-heading">{group.heading}</h2>
            <ul className="app-layout__nav-list">
              {group.items.map((item) => (
                <li key={item.to}>
                  <NavLink to={item.to} className={navLinkClass} onClick={closeNav}>
                    {item.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>

      {/*
        NO FOOTER ROW. There was one, carrying "Never share your password or one-time code"
        and the build version, and it cost a strip across the bottom of every authenticated
        page — a border, a band of surface colour and two lines of grey text under content
        that had already finished. On a dashboard whose panels now grow to fill the window
        it was the one thing still stopping short of the bottom edge.
        Neither line is lost. The anti-phishing reminder belongs on the screens a phishing
        message actually targets, and it is on all of them: the sign-in card's own layout
        footer and the public marketing footer both carry it, where somebody is about to
        type a password rather than having already typed one. The version is on the System
        status page, which is where support asks a customer to look anyway.
      */}
      <main id="main-content" className="app-layout__main">
        <Outlet />
      </main>
    </div>
  );
}
