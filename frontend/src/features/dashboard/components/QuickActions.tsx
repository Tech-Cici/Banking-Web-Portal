import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState, Panel } from '@/components/ui';
import { useSession } from '@/hooks/useSession';
import type { Permission } from '@/types/api';

export interface QuickAction {
  readonly label: string;
  readonly to: string;
  /** The permission that makes this action worth offering. */
  readonly permission: Permission;
}

interface QuickActionsProps {
  readonly actions: readonly QuickAction[];
  readonly title?: string;
  /** Shown when the user holds none of the permissions — an approver, typically. */
  readonly emptyMessage?: string;
}

/**
 * Shortcuts the signed-in user is actually allowed to use.
 *
 * Filtering is a courtesy, not a control. The server refuses an unauthorised request
 * whether or not a button exists, and nothing here may be mistaken for authorisation.
 * The reason to filter anyway: offering an approver a "New transfer" button that the
 * backend will reject teaches them to distrust the screen.
 *
 * Note what this does NOT do: branch on a role name. Roles are labels; permissions are
 * what the backend grants, and they are what the UI reads.
 */
export function QuickActions({
  actions,
  title = 'Quick actions',
  emptyMessage = 'Your access does not include creating transactions.',
}: QuickActionsProps): ReactElement {
  const session = useSession();
  const allowed = actions.filter((action) => session.can(action.permission));

  return (
    <Panel title={title}>
      {allowed.length === 0 ? (
        <EmptyState message={emptyMessage} />
      ) : (
        <div className="dash__actions">
          {allowed.map((action) => (
            <Link key={action.to} to={action.to} className="dash__action">
              {action.label}
            </Link>
          ))}
        </div>
      )}
    </Panel>
  );
}
