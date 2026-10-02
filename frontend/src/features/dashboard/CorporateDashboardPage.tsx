import type { ReactElement } from 'react';
import { Navigate } from 'react-router-dom';
import { Alert, Skeleton } from '@/components/ui';
import { useSession } from '@/hooks/useSession';
import { CORPORATE_PATHS, RETAIL_PATHS } from '@/routes/paths';
import { AccountsPanel } from './components/AccountsPanel';
import { ApprovalsPanel } from './components/ApprovalsPanel';
import { BatchesPanel } from './components/BatchesPanel';
import { CompanySwitcher } from './components/CompanySwitcher';
import { DashboardHeader } from './components/DashboardHeader';
import { NotificationsPanel } from './components/NotificationsPanel';
import { QuickActions, type QuickAction } from './components/QuickActions';
import { RecentActivityPanel } from './components/RecentActivityPanel';
import './dashboard.css';

/**
 * The company dashboard, arranged around the user's half of maker–checker.
 *
 * One page, not two. A maker and an approver are looking at the same company, and giving
 * each their own URL would mean two route trees, two navigations, and a bug the day
 * somebody holds both sets of permissions in different companies. What changes is the
 * ORDER and the AFFORDANCES:
 *
 *  - An approver's queue comes first. It is the only thing on the page that is blocking
 *    someone else's work, and they get no shortcuts for creating payments, because they
 *    are not allowed to create them.
 *  - A maker leads with the accounts and the actions for preparing work, then sees what
 *    they have submitted and where it has got to. They get no Approve button, because
 *    the point of four-eyes is that preparing and releasing are different people.
 *
 * Both are driven by permissions from the session, never by the role label. The label is
 * for the human reading the screen; the permissions are what the backend granted.
 */

const ACTIONS: readonly QuickAction[] = [
  {
    label: 'Prepare a transfer',
    to: RETAIL_PATHS.transferInternal,
    permission: 'CORPORATE_TRANSFER_CREATE',
  },
  { label: 'Prepare a payment', to: RETAIL_PATHS.payments, permission: 'CORPORATE_PAYMENT_CREATE' },
  { label: 'Upload a bulk file', to: CORPORATE_PATHS.bulkNew, permission: 'BULK_CREATE' },
  {
    label: 'Manage beneficiaries',
    to: RETAIL_PATHS.beneficiaries,
    permission: 'BENEFICIARY_MANAGE',
  },
];

export function CorporateDashboardPage(): ReactElement {
  const session = useSession();

  if (session.status === 'loading') {
    return <Skeleton rows={6} label="Loading the company dashboard" />;
  }

  if (session.user === null) {
    return <Alert tone="error">Your session has ended. Please sign in again.</Alert>;
  }

  if (session.user.userType === 'RETAIL') {
    return <Navigate to={RETAIL_PATHS.dashboard} replace />;
  }

  const company = session.activeCorporate;
  const canApprove = session.can('APPROVAL_APPROVE');
  const canSeeApprovals = session.can('APPROVAL_VIEW');

  /*
   * The company id is part of every panel's cache key, so switching company refetches
   * rather than redrawing the previous company's figures under a new heading.
   */
  const scopeKey = `corp:${company?.id ?? 'none'}`;

  return (
    <article className="dash">
      <DashboardHeader
        user={session.user}
        roleLabel={canApprove ? 'Approver' : 'Maker'}
        roleNote={
          canApprove
            ? 'You review and release work that colleagues prepare. You cannot release your own.'
            : 'You prepare payments. A second person has to release them.'
        }
      />

      <CompanySwitcher />

      {company === null ? (
        <Alert tone="warning" title="No company selected">
          Your profile is not linked to a company yet. Ask your company administrator to add you,
          then sign in again.
        </Alert>
      ) : (
        <>
          {/* An approver's queue is what they came for, so it leads. */}
          {canApprove && canSeeApprovals && (
            <div className="dash__section dash__grid dash__grid--wide">
              <ApprovalsPanel scopeKey={scopeKey} />
            </div>
          )}

          <div className="dash__grid">
            <AccountsPanel
              scopeKey={scopeKey}
              title={`${company.name} accounts`}
              subtitle="Balances for the company you are acting for."
            />
            <QuickActions
              actions={ACTIONS}
              emptyMessage="Your role is to review, not to create. Payments are prepared by a maker."
            />
          </div>

          {/* A maker's submissions come after their accounts and actions. */}
          {!canApprove && canSeeApprovals && (
            <div className="dash__section dash__grid dash__grid--wide">
              <ApprovalsPanel scopeKey={scopeKey} />
            </div>
          )}

          <div className="dash__section dash__grid dash__section--fills">
            {session.can('BULK_VIEW') && <BatchesPanel scopeKey={scopeKey} />}
            <NotificationsPanel scopeKey={scopeKey} />
          </div>

          <div className="dash__section dash__grid dash__grid--wide">
            <RecentActivityPanel scopeKey={scopeKey} title="Recent company activity" limit={8} />
          </div>
        </>
      )}
    </article>
  );
}
