import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { humaniseStatus, StatusBadge, toneForStatus } from '@/components/ui';
import { routeTo } from '@/routes/paths';
import { ACCOUNT_TYPES } from '@/types/admin';
import type { Account } from '@/types/banking';
import { formatAge } from '@/utils/datetime';
import { formatBalanceDto } from '@/utils/money';
import './tiles.css';

/**
 * The customer's accounts as a row of cards.
 *
 * <p>Built to the shape of the reference design — a strip of tiles, the first one filled
 * with the brand colour, the account's name and masked number along the bottom — in the
 * portal's own green rather than the reference's lime.
 *
 * <p>WHY TILES AND NOT THE LIST IT REPLACES. The list gave every account the same weight
 * and the same vertical space, so a customer with a current account and a savings account
 * had to read two identical blocks to find the one they were looking for. A tile is
 * scanned by colour and shape; the row is read at a glance and the first tile is
 * obviously the main account.
 *
 * <p>NO TOTAL ACROSS THEM, and this is deliberate rather than an omission. Summing
 * balances in the browser produces a figure the bank never stated — and it would be wrong
 * the moment two accounts are in different currencies, which is exactly when somebody
 * would most want to trust it. If the product wants a headline total, the server should
 * send one it is willing to stand behind.
 */

/**
 * The product name for the small line at the top of a tile.
 *
 * READ FROM THE SHARED LIST, not from a map of its own, and that was a real bug rather
 * than tidying. The map that stood here named six types — including an `FCY` the service
 * has never had — and was missing three the bank actually offers, so a customer with a
 * target savings account read "TARGET_SAVINGS" on their own dashboard: the raw enum, in
 * capitals, with the underscore showing.
 *
 * A second list of the same names is a list that falls behind the first. `ACCOUNT_TYPES`
 * is what staff pick from when the account is opened, so it cannot fall behind without
 * somebody noticing at the point of sale.
 *
 * The trailing " account" is dropped because this line sits above the balance in small
 * capitals, where "CURRENT ACCOUNT" crowds out the figure that the tile is for.
 */
function productName(accountType: string): string {
  const known = ACCOUNT_TYPES.find((type) => type.value === accountType);
  if (known === undefined) {
    /*
     * A type this build has never heard of — the bank added a product and the portal has
     * not caught up. Shown as ordinary words rather than as the raw token, because
     * "LOAN_SERVICING" tells the customer only that something is broken.
     */
    const words = accountType.toLowerCase().replace(/_/g, ' ');
    return words.charAt(0).toUpperCase() + words.slice(1);
  }
  return known.label.replace(/ account$/i, '');
}

interface AccountTilesProps {
  readonly accounts: readonly Account[];
}

export function AccountTiles({ accounts }: AccountTilesProps): ReactElement {
  return (
    <ul className="tiles">
      {accounts.map((account, index) => (
        <li key={account.id}>
          {/*
            THE WHOLE TILE IS THE LINK, not a "view" button in the corner. A tile that
            looks like a card invites a tap anywhere on it, and a customer who taps the
            middle of one and gets nothing concludes the page is broken.
          */}
          <Link
            to={routeTo.accountDetail(account.id)}
            className={index === 0 ? 'tile tile--primary' : 'tile'}
          >
            <span className="tile__top">
              <span className="tile__type">{productName(account.accountType)}</span>
              {/*
                The status badge only when there is something to say. ACTIVE on every
                tile is noise that trains the eye to skip the one place it matters.
              */}
              {account.status !== 'ACTIVE' && (
                <StatusBadge
                  tone={toneForStatus(account.status)}
                  label={humaniseStatus(account.status)}
                />
              )}
            </span>

            <span className="tile__balance">
              {account.availableBalance === undefined ? (
                /*
                 * NOT A ZERO. The account is real — staff entered it from the bank's
                 * records — but the money lives in core banking, which this service does
                 * not talk to. A zero here is indistinguishable from a real balance, and
                 * somebody would reasonably conclude their account had been emptied.
                 */
                <span className="tile__unknown">Balance not shown</span>
              ) : (
                formatBalanceDto(account.availableBalance)
              )}
            </span>

            <span className="tile__foot">
              <span className="tile__name">{account.nickname}</span>
              <span className="tile__number numeric">{account.maskedNumber}</span>
            </span>

            {/*
              Uncleared funds, and only when the two figures differ. "Why can't I spend my
              own money" is the commonest call a contact centre takes, and repeating an
              identical figure twice adds nothing.
            */}
            {account.currentBalance !== undefined &&
              account.availableBalance !== undefined &&
              account.currentBalance.amount !== account.availableBalance.amount && (
                <span className="tile__note">
                  {formatBalanceDto(account.currentBalance)} in total — the difference has
                  not cleared
                </span>
              )}

            {account.availableBalance !== undefined && account.balanceAsOf !== undefined && (
              <span className="tile__note">Updated {formatAge(account.balanceAsOf)}</span>
            )}
          </Link>
        </li>
      ))}
    </ul>
  );
}
