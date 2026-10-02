import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { AsyncPanel } from '@/components/AsyncPanel';
import { EmptyState, humaniseStatus, StatusBadge, toneForStatus } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { cardService, serviceRequestService } from '@/services';
import { formatDateTime } from '@/utils/datetime';
import type { Card } from '@/types/banking';

/**
 * Cards, with the one action a dashboard should carry: blocking a lost card fast.
 *
 * Everything shown is already masked by the API — last four digits, linked account mask.
 * The client never holds a full PAN, so it cannot leak one into a screenshot, a log or a
 * bug report.
 *
 * <p>IT ALSO SHOWS CARDS THAT DO NOT EXIST YET, which is what this panel was missing. A
 * customer asked for a card, staff made it and named a counter, the customer was emailed —
 * and this panel still said "You have no cards yet." That sentence was TRUE: the portal has
 * no card-issuing connection, so there was no card to list. It was also the wrong answer to
 * the question the customer was asking, which was "what happened to my request?"
 *
 * <p>TWO CALLS, NOT ONE, because they are two different things. A card is a piece of
 * plastic with a PAN; a request is a row saying somebody asked for one. Folding the request
 * into `/cards` would mean inventing a card that does not exist — which is the mistake this
 * whole area of the portal was built on.
 */
export function CardsPanel({ scopeKey }: { readonly scopeKey: string }): ReactElement {
  const { state, reload } = useAsync(`${scopeKey}:cards`, (signal) => cardService.list(signal));

  const requests = useAsync(`${scopeKey}:card-requests`, (signal) =>
    serviceRequestService.list(signal),
  );

  /*
   * Open card requests only. A collected one is in the list above as a card; a request for
   * a cheque book belongs on that page, not this one.
   */
  const open =
    requests.state.status === 'ready'
      ? requests.state.data.filter(
          (row) => row.requestType === 'CARD' && row.status !== 'COLLECTED',
        )
      : [];

  return (
    <AsyncPanel
      title="Cards"
      state={state}
      reload={reload}
      action={<Link to={RETAIL_PATHS.cards}>Manage</Link>}
    >
      {(cards: readonly Card[]) =>
        cards.length === 0 && open.length === 0 ? (
          <EmptyState message="You have no cards yet.">
            <p className="dash__note">
              <Link to={RETAIL_PATHS.cardRequest}>Request a card</Link>
            </p>
          </EmptyState>
        ) : (
          <ul className="dash__rows">
            {/*
              THE REQUESTS FIRST. A card waiting on a counter is the thing the customer came
              to check; a card they already hold is not news to them.
            */}
            {open.map((row) => (
              <li key={row.id} className="dash__row">
                <div className="dash__row-main">
                  <p className="dash__row-title">{row.details}</p>
                  <p className="dash__row-meta">
                    Reference <span className="numeric">{row.reference}</span> · asked{' '}
                    {formatDateTime(row.submittedAt)}
                  </p>
                  {row.status === 'SUBMITTED' && (
                    <p className="dash__row-meta">
                      The bank is making it. We will email you when it is ready.
                    </p>
                  )}
                  {/*
                    THE COLLECTION POINT ONLY ONCE A PERSON HAS TYPED ONE. No fallback
                    branch name — the six this portal used to offer were invented by the
                    front end, and the bank has never supplied a branch list.
                  */}
                  {row.status === 'READY' && (
                    <p className="dash__row-meta">
                      Ready to collect
                      {row.collectionPoint === undefined ? '' : ` at ${row.collectionPoint}`}.
                      Bring photo identification.
                    </p>
                  )}
                  {row.status === 'DECLINED' && (
                    <p className="dash__row-meta">
                      {row.declineReason ?? 'We were not able to action this.'}
                    </p>
                  )}
                </div>

                <div className="dash__row-side">
                  <StatusBadge
                    tone={toneForStatus(row.status)}
                    label={humaniseStatus(row.status)}
                  />
                </div>
              </li>
            ))}

            {cards.map((card) => (
              <li key={card.id} className="dash__row">
                <div className="dash__row-main">
                  <p className="dash__row-title numeric">{card.maskedPan}</p>
                  <p className="dash__row-meta">
                    {card.brand} {card.cardType.toLowerCase()} · linked to{' '}
                    <span className="numeric">{card.linkedAccountMask}</span> · expires{' '}
                    {String(card.expiryMonth).padStart(2, '0')}/{String(card.expiryYear).slice(-2)}
                  </p>
                </div>

                <div className="dash__row-side">
                  <StatusBadge
                    tone={toneForStatus(card.status)}
                    label={humaniseStatus(card.status)}
                  />
                  {card.status === 'ACTIVE' && (
                    <p className="dash__row-meta">
                      <Link to={routeTo.cardBlock(card.id)}>Block this card</Link>
                    </p>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )
      }
    </AsyncPanel>
  );
}
