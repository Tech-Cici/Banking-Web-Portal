import type { ReactElement } from 'react';
import { EmptyState, humaniseStatus, StatusBadge, toneForStatus } from '@/components/ui';
import type { Transaction } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';
import { describeMovement, signedAmount } from './describeMovement';
import './activity.css';

/**
 * A list of movements, worded as sentences and grouped by how recently they happened.
 *
 * <p>ONE COMPONENT FOR EVERY SURFACE that lists activity — the dashboard panel and the
 * notifications log. They differ in how many rows they ask for, not in what a row says,
 * and two implementations of the same row is how a dashboard and a log end up
 * disagreeing about what happened.
 *
 * <p>GROUPED BY DAY BAND, because "29 Sept, 14:06" answers a question nobody asked when
 * scanning. What a customer wants from a feed is "is this new", and Today / Last 7 days /
 * Earlier answers that at a glance while the exact timestamp stays on the row.
 *
 * <p>Every row keeps its status badge, including the unglamorous ones. A feed showing
 * only settled entries lets somebody believe a payment has landed when it has not.
 */

interface ActivityFeedProps {
  readonly movements: readonly Transaction[];
  /** Shown when there is nothing. Worded for the surface, since "no activity" reads differently on a log and on a dashboard. */
  readonly emptyMessage?: string;
}

type Band = 'Today' | 'Last 7 days' | 'Earlier';

/**
 * Which band a movement falls in, in the READER'S timezone.
 *
 * <p>Compared on the local calendar day rather than on a 24-hour window: something
 * posted at 23:50 last night is "yesterday" to a person even though it is fourteen hours
 * ago, and a feed that called it Today would be arguing with them.
 *
 * <p>Not `toISOString()`, which is UTC — in Kigali (UTC+2) that puts everything before
 * 02:00 on the previous day, so a payment made at midnight would file itself under
 * yesterday for the person who had just made it.
 */
const LOCAL_DAY = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

function bandFor(bookedAt: string, now: Date): Band {
  const when = new Date(bookedAt);
  if (Number.isNaN(when.getTime())) return 'Earlier';

  if (LOCAL_DAY.format(when) === LOCAL_DAY.format(now)) return 'Today';

  const sevenDaysAgo = new Date(now);
  sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 7);
  return when >= sevenDaysAgo ? 'Last 7 days' : 'Earlier';
}

const BAND_ORDER: readonly Band[] = ['Today', 'Last 7 days', 'Earlier'];

export function ActivityFeed({
  movements,
  emptyMessage = 'Nothing has happened on these accounts yet.',
}: ActivityFeedProps): ReactElement {
  if (movements.length === 0) return <EmptyState message={emptyMessage} />;

  /*
   * Computed once per render rather than per row. Called inside the loop, a feed
   * rendering across midnight could put two rows from the same minute in two different
   * bands.
   */
  const now = new Date();

  const grouped = new Map<Band, Transaction[]>();
  for (const movement of movements) {
    const band = bandFor(movement.bookedAt, now);
    const bucket = grouped.get(band);
    if (bucket === undefined) {
      grouped.set(band, [movement]);
    } else {
      bucket.push(movement);
    }
  }

  return (
    <div className="feed">
      {BAND_ORDER.filter((band) => grouped.has(band)).map((band) => (
        <section key={band} className="feed__band">
          <h3 className="feed__band-label">{band}</h3>

          <ul className="feed__rows">
            {(grouped.get(band) ?? []).map((movement) => {
              const line = describeMovement(movement);

              return (
                <li key={movement.id} className="feed__row">
                  {/*
                    The icon carries the direction as a shape, not only as a colour.
                    Colour alone fails for a colour-blind reader and disappears in a
                    printed statement, and in and out is the one thing on this row that
                    must never be misread.
                  */}
                  <span
                    className={`feed__icon feed__icon--${line.isReturn ? 'return' : line.direction.toLowerCase()}`}
                    aria-hidden="true"
                  >
                    {line.isReturn ? '↺' : line.direction === 'IN' ? '↓' : '↑'}
                  </span>

                  <div className="feed__main">
                    <p className="feed__headline">{line.headline}</p>
                    <p className="feed__meta">
                      {formatDateTime(movement.bookedAt)}
                      {line.detail !== undefined && <> · {line.detail}</>}
                    </p>
                  </div>

                  <div className="feed__side">
                    <p
                      className={
                        movement.direction === 'CREDIT'
                          ? 'feed__amount feed__amount--in'
                          : 'feed__amount'
                      }
                    >
                      {signedAmount(movement)}
                    </p>
                    <StatusBadge
                      tone={toneForStatus(movement.status)}
                      label={humaniseStatus(movement.status)}
                    />
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
