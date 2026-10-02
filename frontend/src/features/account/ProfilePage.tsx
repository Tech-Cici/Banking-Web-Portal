import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { Alert, PageHeader, Panel, ReviewPanel, Skeleton, StatusBadge } from '@/components/ui';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { formatDateTime } from '@/utils/datetime';
import './account.css';

const PERMISSION_LABELS: Readonly<Record<string, string>> = {
  RETAIL_ACCOUNT_VIEW: 'See your accounts',
  TRANSFER_CREATE: 'Make transfers',
  PAYMENT_CREATE: 'Pay bills',
  BENEFICIARY_MANAGE: 'Manage payees',
  LOAN_VIEW: 'See loans',
  CARD_MANAGE: 'Manage cards',
  CORPORATE_VIEW: 'See company accounts',
  CORPORATE_TRANSFER_CREATE: 'Prepare company transfers',
  CORPORATE_PAYMENT_CREATE: 'Prepare company payments',
  APPROVAL_VIEW: 'See the approval queue',
  APPROVAL_APPROVE: 'Approve payments',
  APPROVAL_REJECT: 'Reject payments',
  BULK_CREATE: 'Upload batches',
  BULK_VIEW: 'See batches',
  BULK_APPROVE: 'Approve batches',
  SALARY_VIEW_DETAILS: 'See salary amounts',
};

/**
 * Who the bank thinks you are, and what your access lets you do.
 *
 * Nothing on this screen is editable. Changing a registered name, phone number or
 * address is an identity event at a bank — it is the step a fraudster needs before
 * taking over an account, so it goes through a channel that can verify the person, not
 * through a form behind a session that may already be compromised.
 *
 * The access list is here because "why can my colleague do this and I cannot?" is a
 * daily support call at every corporate bank, and the answer should be readable.
 */
export function ProfilePage(): ReactElement {
  const session = useSession();

  if (session.status === 'loading') return <Skeleton rows={6} label="Loading your profile" />;
  if (session.user === null) {
    return <Alert tone="error">Your session has ended. Please sign in again.</Alert>;
  }

  const user = session.user;

  return (
    <article className="account__page">
      <PageHeader
        title="Profile"
        lead="Your details as the bank holds them."
        action={<Link to={RETAIL_PATHS.security}>Security settings</Link>}
      />

      <div className="account__grid">
        <Panel title="Your details">
          <ReviewPanel
            rows={[
              { label: 'Name', value: user.fullName },
              { label: 'Customer number', value: user.customerNumber },
              { label: 'Email', value: user.email },
              { label: 'Phone', value: user.phone },
              {
                label: 'Profile type',
                value: user.userType === 'CORPORATE' ? 'Business' : 'Personal',
              },
            ]}
          />

          <Alert tone="info" title="Changing these">
            Your name, phone number and email cannot be changed here. Call the bank or visit a
            branch — changing them is the first step in an account takeover, so it needs a channel
            that can verify it is really you.
          </Alert>
        </Panel>

        <Panel title="What your access lets you do">
          <ul className="account__permissions">
            {user.permissions.map((permission) => (
              <li key={permission}>{PERMISSION_LABELS[permission] ?? permission}</li>
            ))}
          </ul>

          <p className="dash__note">
            Granted by the bank, or by your company administrator. The app hides what you cannot do;
            the bank refuses it regardless.
          </p>
        </Panel>

        {user.corporates.length > 0 && (
          <Panel title="Companies you act for">
            <ul className="dash__rows">
              {user.corporates.map((company) => (
                <li key={company.id} className="dash__row">
                  <div className="dash__row-main">
                    <p className="dash__row-title">{company.name}</p>
                    <p className="dash__row-meta numeric">{company.code}</p>
                  </div>
                  <StatusBadge tone="info" label={company.role.toLowerCase()} />
                </li>
              ))}
            </ul>
          </Panel>
        )}

        <Panel title="Last sign-in">
          {user.lastLoginAt === undefined ? (
            // No previous sign-in to report. A panel of placeholder dashes reads like a
            // fault in the page rather than the plain fact that this is the first visit.
            <p className="dash__note">
              This is your first sign-in, so there is no earlier one to show here yet.
            </p>
          ) : (
            <>
              {/*
                NO "WHERE" ROW. It read `lastLoginLocation ?? 'Not recorded'`, and the
                value behind it was the literal "Kigali, Rwanda" — so the fallback never
                fired and the panel told every customer the same city. What is shown now is
                what the service knows: how they signed in, and on what, each row dropped
                entirely rather than filled with a placeholder.
              */}
              <ReviewPanel
                rows={[
                  { label: 'When', value: formatDateTime(user.lastLoginAt) },
                  ...(user.lastLoginMethod === undefined
                    ? []
                    : [{ label: 'How', value: user.lastLoginMethod }]),
                  ...(user.lastLoginDevice === undefined
                    ? []
                    : [{ label: 'On', value: user.lastLoginDevice }]),
                ]}
              />
              <p className="dash__note">
                Do not recognise this? <Link to={RETAIL_PATHS.security}>Secure your account</Link>{' '}
                and call the bank.
              </p>
            </>
          )}
        </Panel>
      </div>
    </article>
  );
}
