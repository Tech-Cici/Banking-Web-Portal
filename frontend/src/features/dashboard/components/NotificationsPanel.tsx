import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { EmptyState, StatusBadge } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS } from '@/routes/paths';
import { notificationService } from '@/services';
import type { Notification } from '@/types/banking';
import { formatAge } from '@/utils/datetime';

interface NotificationsPanelProps {
  readonly scopeKey: string;
  readonly limit?: number;
}

/** Unread first, then newest. Security messages are never buried below routine ones. */
function order(items: readonly Notification[]): readonly Notification[] {
  return [...items].sort((a, b) => {
    if (a.read !== b.read) return a.read ? 1 : -1;
    if (a.severity !== b.severity) {
      const weight = (item: Notification): number =>
        item.severity === 'SECURITY' ? 0 : item.severity === 'WARNING' ? 1 : 2;
      return weight(a) - weight(b);
    }
    return Date.parse(b.createdAt) - Date.parse(a.createdAt);
  });
}

export function NotificationsPanel({
  scopeKey,
  limit = 4,
}: NotificationsPanelProps): ReactElement {
  const { state, reload } = useAsync(`${scopeKey}:notifications`, (signal) =>
    notificationService.list(signal),
  );

  return (
    <AsyncPanel
      title="Notifications"
      state={state}
      reload={reload}
      action={<Link to={RETAIL_PATHS.notifications}>All</Link>}
    >
      {(items: readonly Notification[]) =>
        items.length === 0 ? (
          <EmptyState message="Nothing new." />
        ) : (
          <ul className="dash__rows">
            {order(items)
              .slice(0, limit)
              .map((item) => (
                <li key={item.id} className="dash__row">
                  <div className="dash__row-main">
                    <p className={item.read ? 'dash__row-title' : 'dash__row-title dash__unread'}>
                      {item.title}
                    </p>
                    <p className="dash__row-meta">{item.body}</p>
                    <p className="dash__row-meta">
                      {formatAge(item.createdAt)}
                      {/*
                       * Only in-app paths are ever turned into links. A notification is
                       * attacker-influenced content in any real bank, and rendering an
                       * arbitrary href from it is a phishing vector with the bank's own
                       * domain lending it credibility.
                       */}
                      {item.link !== undefined && item.link.startsWith('/') && (
                        <>
                          {' · '}
                          <Link to={item.link}>Open</Link>
                        </>
                      )}
                    </p>
                  </div>

                  {item.severity === 'SECURITY' && (
                    <StatusBadge tone="warning" label="Security" />
                  )}
                </li>
              ))}
          </ul>
        )
      }
    </AsyncPanel>
  );
}
