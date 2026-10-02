import { useState, type ReactElement } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AccountSelector, Alert, Button, EmptyState, ErrorNotice, PageHeader, Panel, ReviewPanel, Select, Skeleton, StatusBadge, humaniseStatus, toneForStatus } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { accountService, cardService, newIdempotencyKey, serviceRequestService } from '@/services';
import './products.css';

/*
 * THERE IS NO BRANCH LIST HERE ANY MORE, and that is the fix rather than an omission.
 *
 * This file used to hold `['Nyarugenge', 'Kimironko', 'Remera', 'Musanze', 'Rubavu',
 * 'Huye']` and offer it as a "Collect from" dropdown. The bank never supplied a branch
 * list — docs/OPEN-ITEMS.md records that under "Waiting on the bank" — so those were six
 * names the front end made up, and the confirmation screen then told the customer to take
 * photo identification to the one they had picked.
 *
 * The bank now names the collection point when it has actually produced the card, and the
 * customer is emailed it. Nothing in the portal states a place until a person has typed
 * one.
 */

/**
 * The customer's cards.
 *
 * Only the last four digits ever reach the browser, so nothing here can leak a PAN into
 * a screenshot or a support ticket. There is no CVV, no expiry entry and no "show full
 * number" — none of those belong in a portal, and the API does not send them.
 */
export function CardsPage(): ReactElement {
  const session = useSession();
  const canManage = session.can('CARD_MANAGE');
  const { state, reload } = useAsync('cards', (signal) => cardService.list(signal));

  /*
   * THE REQUESTS BELONG ON THIS PAGE because the confirmation screen promises them here:
   * "You can check on it any time under Cards". Until this was added that sentence was
   * false — a customer who had just asked for a card came back to "You have no cards" and
   * no trace of the reference they had been given. The cheque books page had always listed
   * its own requests; this one listed only cards that already exist.
   *
   * A SEPARATE CALL, not folded into /cards, because they are different things: a card is
   * a piece of plastic with a PAN, and a request is a row saying somebody asked for one.
   * Merging them would mean inventing a card that does not exist yet.
   */
  const requests = useAsync('cards:requests', (signal) => serviceRequestService.list(signal));

  /* One endpoint serves both kinds, so this screen shows its own. Collected ones drop off:
     once the card is in the customer's hand it is in the list above, as a card. */
  const open =
    requests.state.status === 'ready'
      ? requests.state.data.filter(
          (row) => row.requestType === 'CARD' && row.status !== 'COLLECTED',
        )
      : [];

  return (
    <article>
      <PageHeader
        title="Cards"
        lead="Block a lost card immediately. Unblocking has to be done by the bank."
        action={canManage ? <Link to={RETAIL_PATHS.cardRequest}>Request a card</Link> : undefined}
      />

      {state.status === 'loading' && <Skeleton rows={5} label="Loading cards" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load your cards" reference={state.reference}>
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
          <EmptyState message="You have no cards.">
            {canManage && (
              <p className="dash__note">
                <Link to={RETAIL_PATHS.cardRequest}>Request one</Link>
              </p>
            )}
          </EmptyState>
        ) : (
          <div className="products__grid">
            {state.data.map((card) => (
              <Panel
                key={card.id}
                title={card.maskedPan}
                subtitle={`${card.brand} ${card.cardType.toLowerCase()}`}
                action={
                  <StatusBadge
                    tone={toneForStatus(card.status)}
                    label={humaniseStatus(card.status)}
                  />
                }
              >
                <ReviewPanel
                  rows={[
                    { label: 'Cardholder', value: card.cardholderName },
                    { label: 'Linked account', value: card.linkedAccountMask },
                    {
                      label: 'Expires',
                      value: `${String(card.expiryMonth).padStart(2, '0')}/${String(card.expiryYear)}`,
                    },
                  ]}
                />

                {card.status === 'BLOCKED' && (
                  <Alert tone="warning" title="This card is blocked">
                    It cannot be used. To unblock it, call the bank or visit a branch — that
                    cannot be done from here, on purpose.
                  </Alert>
                )}

                {canManage && card.status === 'ACTIVE' && (
                  <div className="dash__actions">
                    <Link to={routeTo.cardBlock(card.id)} className="dash__action">
                      Block this card
                    </Link>
                  </div>
                )}
              </Panel>
            ))}
          </div>
        ))}

      {open.length > 0 && (
        <div className="dash__section">
          <Panel
            title="Cards you have asked for"
            subtitle="Requests the bank has not finished yet."
          >
            <ul className="dash__rows">
              {open.map((row) => (
                <li key={row.id} className="dash__row">
                  <div className="dash__row-main">
                    <p className="dash__row-title">{row.details}</p>
                    <p className="dash__row-meta">
                      Reference <span className="numeric">{row.reference}</span>
                    </p>
                    {row.status === 'SUBMITTED' && (
                      <p className="dash__row-meta">
                        The bank is making it. We will email you when it is ready.
                      </p>
                    )}
                    {/*
                      THE COLLECTION POINT IS WHATEVER STAFF TYPED, and it is only ever
                      shown once they have. No fallback branch name: see the note at the
                      top of this file on the six that used to be here.
                    */}
                    {row.status === 'READY' && (
                      <p className="dash__row-meta">
                        Ready to collect{row.collectionPoint === undefined ? '' : ` at ${row.collectionPoint}`}.
                        Bring photo identification.
                      </p>
                    )}
                    {row.status === 'DECLINED' && (
                      <p className="dash__row-meta">
                        {row.declineReason ?? 'We were not able to action this.'}
                      </p>
                    )}
                  </div>
                  <StatusBadge
                    tone={toneForStatus(row.status)}
                    label={humaniseStatus(row.status)}
                  />
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      )}
    </article>
  );
}

/**
 * Blocks a card.
 *
 * Asymmetric on purpose: blocking is one confirmation away because speed is the whole
 * point when a wallet has just gone missing, and a wrongly blocked card costs an
 * inconvenience. Unblocking is not offered at all, because a wrongly unblocked card
 * costs money.
 */
export function CardBlockPage(): ReactElement {
  const { id = '' } = useParams();
  const navigate = useNavigate();

  const { state } = useAsync('cards', (signal) => cardService.list(signal));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState(false);

  const card = state.status === 'ready' ? state.data.find((item) => item.id === id) : undefined;

  const block = (): void => {
    setBusy(true);
    setFailure(null);

    void cardService
      .block(id, newIdempotencyKey())
      .then(() => {
        setDone(true);
      })
      .catch((cause: unknown) => {
        setFailure(cause);
      })
      .finally(() => {
        setBusy(false);
      });
  };

  if (state.status === 'loading') return <Skeleton rows={4} label="Loading card" />;

  if (card === undefined) {
    return (
      <article>
        <PageHeader title="Block a card" crumbs={[{ label: 'Cards', to: RETAIL_PATHS.cards }]} />
        <Alert tone="error" title="We could not find that card">
          <Link to={RETAIL_PATHS.cards}>Back to cards</Link>
        </Alert>
      </article>
    );
  }

  if (done) {
    return (
      <article className="products__narrow">
        <PageHeader title="Card blocked" crumbs={[{ label: 'Cards', to: RETAIL_PATHS.cards }]} />
        <Alert tone="success" title={`${card.maskedPan} is blocked`}>
          <p>It cannot be used from now on, including for payments already set up on it.</p>
          <p className="money__note">
            If the card was stolen, check your recent transactions and tell the bank about
            anything you do not recognise.
          </p>
        </Alert>
        <div className="money__actions">
          <Button
            onClick={() => {
              void navigate(RETAIL_PATHS.cards);
            }}
          >
            Back to cards
          </Button>
          <Link to={RETAIL_PATHS.accounts} className="money__link">
            Check my transactions
          </Link>
        </div>
      </article>
    );
  }

  return (
    <article className="products__narrow">
      <PageHeader
        title="Block this card"
        crumbs={[{ label: 'Cards', to: RETAIL_PATHS.cards }]}
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="block that card" />
      )}

      <ReviewPanel
        rows={[
          { label: 'Card', value: card.maskedPan },
          { label: 'Type', value: `${card.brand} ${card.cardType.toLowerCase()}` },
          { label: 'Linked account', value: card.linkedAccountMask },
        ]}
      />

      <Alert tone="warning" title="This takes effect immediately">
        The card stops working straight away, and it cannot be unblocked from the app — you
        would have to call the bank or visit a branch.
      </Alert>

      <div className="money__actions">
        <Button variant="danger" loading={busy} onClick={block}>
          Block this card
        </Button>
        <Link to={RETAIL_PATHS.cards} className="money__link">
          Cancel
        </Link>
      </div>
    </article>
  );
}

/** Requests a new or replacement card. A service request, so it ends with a reference. */
export function CardRequestPage(): ReactElement {
  const accounts = useAsync('card-request:accounts', (signal) => accountService.list(signal));

  const [cardType, setCardType] = useState('DEBIT');
  const [accountId, setAccountId] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [reference, setReference] = useState<string | null>(null);

  const list = accounts.state.status === 'ready' ? accounts.state.data : [];

  const submit = (): void => {
    setBusy(true);
    setFailure(null);

    void serviceRequestService
      .raise({ requestType: 'CARD', accountId, cardType })
      .then((created) => {
        setReference(created.reference);
      })
      .catch((cause: unknown) => {
        setFailure(cause);
      })
      .finally(() => {
        setBusy(false);
      });
  };

  if (accounts.state.status === 'loading') return <Skeleton rows={5} label="Loading accounts" />;

  if (reference !== null) {
    return (
      <article className="products__narrow">
        <PageHeader title="Card requested" crumbs={[{ label: 'Cards', to: RETAIL_PATHS.cards }]} />
        <Alert tone="success" title="We have your request">
          <p>
            Your reference is <strong className="numeric">{reference}</strong>.
          </p>
          {/*
            NO PRINTING TIME AND NO BRANCH. This said "Cards take about five working days to
            print" — a constant in this file, not a figure the bank gave us — and named a
            branch from the invented list. What is true is that a person has to make the
            card and will say where to collect it, and that the customer is emailed when
            that happens.
          */}
          <p className="money__note">
            We will email you when it is ready, and tell you where to collect it. Bring photo
            identification when you do.
          </p>
          <p className="money__note">
            You can check on it any time under <strong>Cards</strong>.
          </p>
        </Alert>
        <div className="money__actions">
          <Link to={RETAIL_PATHS.cards} className="dash__action">
            Back to cards
          </Link>
        </div>
      </article>
    );
  }

  return (
    <article className="products__narrow">
      <PageHeader
        title="Request a card"
        lead="New cards are collected in person. The bank will tell you where once it is ready."
        crumbs={[{ label: 'Cards', to: RETAIL_PATHS.cards }]}
      />

      {failure !== null && (
        <ErrorNotice error={failure} action="send your card request" />
      )}

      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (accountId !== '') submit();
        }}
      >
        <Select
          label="Card type"
          value={cardType}
          options={[
            { value: 'DEBIT', label: 'Debit card — spends from your account' },
            { value: 'PREPAID', label: 'Prepaid card — load it first' },
          ]}
          onChange={(event) => {
            setCardType(event.target.value);
          }}
        />

        <AccountSelector
          label="Link it to"
          accounts={list}
          value={accountId}
          onChange={setAccountId}
        />

        <div className="money__actions">
          <Button type="submit" loading={busy} disabled={accountId === ''}>
            Request card
          </Button>
          <Link to={RETAIL_PATHS.cards} className="money__link">
            Cancel
          </Link>
        </div>
      </form>
    </article>
  );
}
