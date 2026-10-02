import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import {
  Alert,
  EmptyState,
  PageHeader,
  Panel,
  Skeleton,
  StatusBadge,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { STAFF_PATHS } from '@/routes/paths';
import { adminService } from '@/services';
import type { StaffSummary } from '@/services/adminService';
import { formatDateTime } from '@/utils/datetime';
import { useStaffSession } from './useStaffSession';
import './staff.css';

/**
 * ONE QUEUE, AS A MEMBER OF STAFF WOULD DESCRIBE IT.
 *
 * `count` is the figure from the server. `mine` is whether this person can actually do
 * anything about it — the queues split between an administrator and a manager, and a
 * figure somebody cannot act on belongs further down the page, not at the top of it.
 */
interface Queue {
  readonly label: string;
  readonly detail: string;
  readonly to: string;
  readonly count: number;
  readonly mine: boolean;
  /** Money in flight. Rendered louder, and never sorted below anything else. */
  readonly urgent?: boolean;
}

/**
 * The staff overview.
 *
 * <p>WHAT THIS REPLACED, because almost all of it had to go.
 *
 * <p>IT WAS A DEVELOPER'S PAGE WEARING A BANK'S CLOTHES. The first thing on it after the
 * counts was a panel headed "Start over — Development only", explaining in four sentences
 * which database the button would empty, naming `VITE_LIVE_API`, the `dev`, `h2` and `test`
 * profiles, and the fact that the label used to read "mock bank". Under that sat a red
 * button reading "Delete every customer and application". A bank manager has no business
 * being shown either, and the one thing worse than a developer tool on a staff screen is a
 * destructive developer tool with a paragraph of build configuration next to it.
 *
 * <p>Below that came "Recent messages" — the development mailbox, which exists because no
 * mail server is wired up. Also not a thing a manager should meet on their landing page.
 *
 * <p>AND THE NUMBERS ANSWERED THE WRONG QUESTION. Five onboarding figures and nothing else,
 * while the service has six queues. A manager signing in saw counts that are mostly an
 * administrator's job, and no sign of their own work: money waiting to be released,
 * passwords to re-issue, payees to check, cards to hand over. The one link out of the panel
 * went to the registrations queue whoever was looking.
 *
 * <p>SO THIS PAGE NOW ANSWERS "WHAT DO I HAVE TO DO?" FIRST, for whichever role is looking,
 * and the figures that are somebody else's job sit underneath as context. Every number
 * comes from a database count — see {@code OnboardingService.StaffSummary} — and nothing on
 * this screen mentions a profile, an environment variable or a database.
 */
export function StaffDashboardPage(): ReactElement {
  const session = useStaffSession();
  const summary = useAsync('staff:summary', (signal) => adminService.summary(signal));

  if (session.status === 'loading') return <Skeleton rows={5} label="Loading" />;
  if (session.staff === null) {
    return <Alert tone="error">Your staff session has ended. Please sign in again.</Alert>;
  }

  const staff = session.staff;
  const roleName = staff.role === 'ADMIN' ? 'Administrator' : 'Manager';

  /**
   * The six queues, in the order a day is worked.
   *
   * <p>`mine` MIRRORS THE SERVER, and is a courtesy rather than a control: every endpoint
   * behind these links checks the caller itself, so a figure shown to the wrong role is
   * untidy and not unsafe. It is set from the same rules the navigation uses, for the
   * reasons written out there — the admin/manager split exists for issuing credentials, and
   * payees and cards deliberately sit either side of it.
   */
  const queuesFor = (counts: StaffSummary): readonly Queue[] => [
    {
      label: 'Money to release',
      detail: 'Transfers that have left an account and are waiting for a manager.',
      to: STAFF_PATHS.transferQueue,
      count: counts.transfersToRelease,
      mine: session.isManager,
      urgent: true,
    },
    {
      label: 'Accounts to approve',
      detail: 'Logins an administrator has created. They do not work until approved.',
      to: STAFF_PATHS.approvals,
      count: counts.awaitingApproval,
      mine: session.isManager,
    },
    {
      label: 'Registrations to action',
      detail: 'People who have registered and have no account yet.',
      to: STAFF_PATHS.applications,
      count: counts.submitted,
      mine: session.isAdmin,
    },
    {
      label: 'Password requests',
      detail: 'Customers locked out who have asked the bank for a new password.',
      to: STAFF_PATHS.passwordRequests,
      count: counts.passwordRequests,
      mine: session.isManager,
    },
    {
      label: 'Payees to check',
      detail: 'Saved payees nobody has compared against the name we hold yet.',
      to: STAFF_PATHS.beneficiaryReviews,
      count: counts.payeesToCheck,
      mine: true,
    },
    {
      label: 'Cards and cheque books',
      detail: 'To make, or made and waiting on a counter to be collected.',
      to: STAFF_PATHS.serviceRequests,
      count: counts.cardsAndChequeBooks,
      mine: true,
    },
  ];

  return (
    <article>
      <PageHeader
        title={`Hello, ${staff.fullName.split(' ')[0] ?? staff.fullName}`}
        lead={
          session.isAdmin
            ? 'You create accounts for people who have registered, and check payees and card requests. A manager approves each account.'
            : 'You approve the accounts administrators create and release money customers have sent. You cannot approve your own work.'
        }
      />

      <p className="dash__signin">
        {roleName} · {staff.branch}
        {staff.lastLoginAt !== undefined && ` · last sign-in ${formatDateTime(staff.lastLoginAt)}`}
      </p>

      <AsyncPanel
        title="What needs doing"
        subtitle="Yours first. Everything here is somebody waiting."
        state={summary.state}
        reload={summary.reload}
        skeletonRows={6}
      >
        {(counts: StaffSummary) => {
          const queues = queuesFor(counts);

          /*
           * MINE FIRST, THEN BY SIZE — and money never moves off the top of its group.
           * A manager should not have to read past four administrator figures to find the
           * transfers waiting on them, which is what the old single ordering did.
           */
          const ordered = [...queues].sort((a, b) => {
            if (a.mine !== b.mine) return a.mine ? -1 : 1;
            if ((a.urgent ?? false) !== (b.urgent ?? false)) return (a.urgent ?? false) ? -1 : 1;
            return b.count - a.count;
          });

          const waiting = ordered.filter((queue) => queue.count > 0);

          return waiting.length === 0 ? (
            /*
             * SAYS WHAT IT MEANS. "Nothing new" on a staff queue reads as a fault once
             * somebody has been told a customer is waiting; "there is nothing waiting" is
             * a statement the screen can actually stand behind, because every figure
             * behind it is a database count.
             */
            <EmptyState message="Nothing is waiting for the bank right now." />
          ) : (
            <ul className="dash__rows">
              {waiting.map((queue) => (
                <li key={queue.to} className="dash__row">
                  <div className="dash__row-main">
                    <p className="dash__row-title">
                      <Link to={queue.to}>{queue.label}</Link>
                    </p>
                    <p className="dash__row-meta">{queue.detail}</p>
                    {!queue.mine && (
                      /*
                       * SHOWN, NOT HIDDEN, and labelled. A manager who can see that nine
                       * registrations are stuck can go and find an administrator; hiding
                       * the figure would make the backlog somebody else's secret.
                       */
                      <p className="dash__row-meta">
                        {session.isAdmin ? 'A manager' : 'An administrator'} does this one.
                      </p>
                    )}
                  </div>

                  <div className="dash__row-side">
                    <span className="staff__count">{queue.count}</span>
                    {queue.urgent === true && queue.count > 0 && (
                      <StatusBadge tone="warning" label="Money in flight" />
                    )}
                  </div>
                </li>
              ))}
            </ul>
          );
        }}
      </AsyncPanel>

      <div className="staff__stats">
        <AsyncPanel
          title="Customers"
          subtitle="How the bank's books stand today."
          state={summary.state}
          reload={summary.reload}
          skeletonRows={3}
        >
          {(counts: StaffSummary) => (
            <ul className="staff__counts">
              <li>
                <span className="staff__count">{counts.active}</span>
                <span className="staff__count-label">active customers</span>
              </li>
              <li>
                <span className="staff__count">{counts.awaitingFirstSignIn}</span>
                {/*
                  WORDED AS THE RISK IT IS. "Approved, not signed in yet" is a status; what
                  it means is that a temporary password was handed over and nobody has taken
                  ownership of the account, so a figure that stops falling is a set of live
                  credentials sitting on desks.
                */}
                <span className="staff__count-label">handed a password, not used yet</span>
              </li>
              <li>
                <span className="staff__count">{counts.rejected}</span>
                <span className="staff__count-label">applications turned down</span>
              </li>
            </ul>
          )}
        </AsyncPanel>

        <Panel title="How a new account is opened" subtitle="Four steps, two people.">
          <ol className="staff__steps">
            <li>
              <strong>Someone registers</strong> on the public site. Nothing is created yet —
              it is an application.
            </li>
            <li>
              <strong>An administrator creates the account</strong> and is shown a temporary
              password once, to hand over in person.
            </li>
            <li>
              <strong>A manager approves it.</strong> Only then does the login work, and only
              then is the customer emailed.
            </li>
            <li>
              <strong>The customer signs in</strong> with the temporary password and has to
              replace it before the portal opens.
            </li>
          </ol>

          <p className="dash__note">
            Steps two and three are deliberately different people. Issuing working
            credentials to a customer who does not exist is the most valuable thing an
            insider can do, so it takes two.
          </p>
        </Panel>
      </div>
    </article>
  );
}
