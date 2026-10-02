import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, EmptyState, ErrorNotice, PageHeader, Skeleton, StatusBadge } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { accountService, notificationService } from '@/services';
import type { Notification, Transaction } from '@/types/banking';
import { ActivityFeed } from '@/features/activity';
import { AsyncPanel } from '@/components/AsyncPanel';
import { formatDateTime } from '@/utils/datetime';
import './account.css';

const SEVERITY_TONE = { SECURITY: 'warning', WARNING: 'warning', INFO: 'neutral' } as const;

/** Unread first, then security, then newest. */
function order(items: readonly Notification[]): readonly Notification[] {
  const weight = (item: Notification): number =>
    item.severity === 'SECURITY' ? 0 : item.severity === 'WARNING' ? 1 : 2;

  return [...items].sort((a, b) => {
    if (a.read !== b.read) return a.read ? 1 : -1;
    if (weight(a) !== weight(b)) return weight(a) - weight(b);
    return Date.parse(b.createdAt) - Date.parse(a.createdAt);
  });
}

/**
 * Everything that has happened to this customer: money, and messages.
 *
 * TWO SECTIONS, AND THEY ARE NOT THE SAME THING. The money feed is what the accounts
 * did — "you received 5,000 from Teta Eliana". The messages below are what the bank
 * chose to say, which is a smaller and more deliberate list. Merging them would put a
 * routine deposit and a security alert in one stream ordered by time, and the alert is
 * the one that must not scroll away under a week of ordinary payments.
 *
 * The money feed is the same component the dashboard uses, asking for more rows. The
 * dashboard answers "what just happened"; this answers "what has happened".
 *
 * Marking as read is an explicit action, not something that happens because the page
 * rendered. A security alert that silently marks itself read the moment it scrolls past
 * is an alert that can be missed and then cannot be found again.
 *
 * Links are followed only when they are in-app paths. A notification is
 * attacker-influenced content at any real bank, and rendering an arbitrary href from
 * one is a phishing link with the bank's own domain lending it credibility.
 */
export function NotificationsPage(): ReactElement {
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);

  const { state, reload } = useAsync(`notifications:${String(version)}`, (signal) =>
    notificationService.list(signal),
  );

  /*
   * FIFTY, which is the most the endpoint will return. A log wants everything and this
   * is not yet everything — the honest limit rather than a paginated list this screen
   * cannot yet drive. Recorded in docs/OPEN-ITEMS.md.
   */
  const movements = useAsync('notifications:movements', (signal) =>
    accountService.recentTransactions(50, signal),
  );

  const act = (id: string | null): void => {
    setBusy(id ?? 'all');
    setFailure(null);

    const run = id === null ? notificationService.markAllRead() : notificationService.markRead(id);

    void run
      .then(() => {
        setVersion((count) => count + 1);
      })
      .catch((cause: unknown) => {
        setFailure(cause);
      })
      .finally(() => {
        setBusy(null);
      });
  };

  const unread = state.status === 'ready' ? state.data.filter((item) => !item.read).length : 0;

  return (
    <article className="account__page">
      <PageHeader
        title="Notifications"
        lead={
          unread === 0
            ? 'Your money movements, and messages from the bank.'
            : `Your money movements, and ${String(unread)} unread message${unread === 1 ? '' : 's'}.`
        }
        action={
          unread > 0 ? (
            <Button
              variant="secondary"
              loading={busy === 'all'}
              onClick={() => {
                act(null);
              }}
            >
              Mark all as read
            </Button>
          ) : undefined
        }
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="update that message" />
      )}

      <AsyncPanel
        title="Money in and out"
        state={movements.state}
        reload={movements.reload}
        skeletonRows={6}
      >
        {(rows: readonly Transaction[]) => (
          <ActivityFeed
            movements={rows}
            emptyMessage="No money has moved on your accounts yet."
          />
        )}
      </AsyncPanel>

      <h2 className="account__section">Messages from the bank</h2>

      {state.status === 'loading' && <Skeleton rows={6} label="Loading notifications" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your notifications" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (state.data.length === 0 ? (
          <EmptyState message="The bank has not sent you anything yet." />
        ) : (
          <ul className="account__notifications">
            {order(state.data).map((item) => (
              <li
                key={item.id}
                className={item.read ? 'account__notification' : 'account__notification account__notification--unread'}
              >
                <div className="dash__row-main">
                  <p className={item.read ? 'dash__row-title' : 'dash__row-title dash__unread'}>
                    {item.title}
                  </p>
                  <p className="dash__row-meta">{item.body}</p>
                  <p className="dash__row-meta">
                    {formatDateTime(item.createdAt)}
                    {item.link !== undefined && item.link.startsWith('/') && (
                      <>
                        {' · '}
                        <Link to={item.link}>Open</Link>
                      </>
                    )}
                  </p>
                </div>

                <div className="dash__row-side">
                  <StatusBadge
                    tone={SEVERITY_TONE[item.severity]}
                    label={item.severity.toLowerCase()}
                  />
                  {!item.read && (
                    <p className="dash__row-meta">
                      <Button
                        variant="tertiary"
                        loading={busy === item.id}
                        onClick={() => {
                          act(item.id);
                        }}
                      >
                        Mark as read
                      </Button>
                    </p>
                  )}
                </div>
              </li>
            ))}
          </ul>
        ))}
    </article>
  );
}
