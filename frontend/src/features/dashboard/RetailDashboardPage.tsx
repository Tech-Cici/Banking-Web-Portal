import type { ReactElement } from 'react';
import { Navigate } from 'react-router-dom';
import { Alert, Skeleton } from '@/components/ui';
import { useSession } from '@/hooks/useSession';
import { CORPORATE_PATHS, RETAIL_PATHS } from '@/routes/paths';
import { AccountsPanel } from './components/AccountsPanel';
import { CardsPanel } from './components/CardsPanel';
import { DashboardHeader } from './components/DashboardHeader';
import { HeldPanel } from './components/HeldPanel';
import { LoansPanel } from './components/LoansPanel';
import { NotificationsPanel } from './components/NotificationsPanel';
import { QuickActions, type QuickAction } from './components/QuickActions';
import { RecentActivityPanel } from './components/RecentActivityPanel';
import './dashboard.css';

/**
 * The personal customer's dashboard.
 *
 * Ordered by what someone actually opens their banking app to do: how much have I got,
 * what just happened, and the two or three things I came here to press. Products they
 * hold — loans, cards — sit below that, because they are checked monthly, not daily.
 *
 * Each panel loads on its own. A customer whose notifications service is down should
 * still see their balances, so there is no single "load the dashboard" call that can
 * take the whole page with it.
 */

const ACTIONS: readonly QuickAction[] = [
  {
    label: 'Move money between my accounts',
    to: RETAIL_PATHS.transferOwn,
    permission: 'TRANSFER_CREATE',
  },
  {
    label: 'Send to someone else',
    to: RETAIL_PATHS.transferInternal,
    permission: 'TRANSFER_CREATE',
  },
  { label: 'Pay a bill', to: RETAIL_PATHS.payments, permission: 'PAYMENT_CREATE' },
  { label: 'Buy airtime', to: RETAIL_PATHS.paymentAirtime, permission: 'PAYMENT_CREATE' },
  { label: 'Add a beneficiary', to: RETAIL_PATHS.beneficiaries, permission: 'BENEFICIARY_MANAGE' },
];

export function RetailDashboardPage(): ReactElement {
  const session = useSession();

  if (session.status === 'loading') {
    return <Skeleton rows={6} label="Loading your dashboard" />;
  }

  if (session.user === null) {
    return <Alert tone="error">Your session has ended. Please sign in again.</Alert>;
  }

  /*
   * A corporate user has no personal accounts here, so this page would render as a set
   * of empty panels — which reads as "the bank lost my money" rather than "wrong page".
   * Send them to their own dashboard instead.
   */
  if (session.user.userType === 'CORPORATE') {
    return <Navigate to={CORPORATE_PATHS.dashboard} replace />;
  }

  const scopeKey = `retail:${session.user.id}`;

  return (
    /*
      `dash` makes this a column that fills the height of the main area, so the last row
      of panels can take up whatever is left instead of the page ending halfway down and
      leaving a band of empty background. See dashboard.css.
    */
    <article className="dash">
      <DashboardHeader
        user={session.user}
        roleLabel="Personal banking"
        roleNote="Your own accounts, cards and loans."
      />

      <div className="dash__grid">
        <AccountsPanel scopeKey={scopeKey} title="Your accounts" />
        <QuickActions actions={ACTIONS} />
      </div>

      <div className="dash__section dash__grid">
        <RecentActivityPanel scopeKey={scopeKey} />
        {/*
          ABOVE notifications, and above products. A customer whose balance has just
          dropped by money they sent an hour ago needs that explained before anything
          else on this page — it is the one figure on the dashboard that looks like a
          mistake until something accounts for it.
        */}
        <HeldPanel scopeKey={scopeKey} />
      </div>

      {/*
        NOTIFICATIONS, LOANS AND CARDS IN ONE ROW.
        
        Notifications had a row to itself and, with nothing in it, was a full-width white
        box holding one grey sentence. These three are the panels most often empty for a
        new customer, so they share a row: the grid auto-fits, so they sit as three
        columns on a wide screen and stack on a narrow one, and an empty one costs a line
        rather than a third of the page.
      */}
      <div className="dash__section dash__grid dash__section--fills">
        <NotificationsPanel scopeKey={scopeKey} />
        {session.can('LOAN_VIEW') && <LoansPanel scopeKey={scopeKey} />}
        {session.can('CARD_MANAGE') && <CardsPanel scopeKey={scopeKey} />}
      </div>
    </article>
  );
}
