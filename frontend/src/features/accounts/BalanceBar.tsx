import type { ReactElement } from 'react';
import type { Account } from '@/types/banking';
import { formatMoney, formatMoneyDto, moneyFromDto } from '@/utils/money';

/**
 * How much of this account's money is actually spendable, drawn as a bar.
 *
 * <p>The reference design has a usage meter — a figure, a bar, and a number at each end.
 * The obvious thing to put in it here would be a spending limit, and this service has no
 * limits to report: a bar labelled "ATM withdrawals, 6,900 of 12,000" would be two
 * figures nobody set. So the meter shows the one proportion the server does state, and
 * the one customers ring up about: what is available against what is in there.
 *
 * <p>BOTH FIGURES COME FROM THE SERVER and belong to ONE account, so nothing is summed
 * and nothing is converted. The only arithmetic is the bar's width, computed from
 * {@code minorUnits}, which is a {@code bigint} — integer units, never floating point, so
 * there is no rounding to get wrong. The width is presentation; neither figure on screen
 * is calculated here.
 *
 * <p>Renders nothing at all when the two are equal, when either is missing, or when the
 * account is empty. A full bar beside two identical figures tells a customer nothing, and
 * a bar over a zero balance would be dividing by it.
 */

interface BalanceBarProps {
  readonly account: Account;
}

export function BalanceBar({ account }: BalanceBarProps): ReactElement | null {
  const { availableBalance, currentBalance } = account;

  if (availableBalance === undefined || currentBalance === undefined) return null;

  /*
   * Same currency, or there is nothing to compare. Two currencies on one account should
   * not happen; if it ever does, drawing a proportion between them would invent an
   * exchange rate.
   */
  if (availableBalance.currency !== currentBalance.currency) return null;

  const available = moneyFromDto(availableBalance);
  const current = moneyFromDto(currentBalance);

  if (current.minorUnits <= 0n) return null;
  if (available.minorUnits === current.minorUnits) return null;

  /*
   * Integer maths, then a single conversion for the CSS width. Multiplying by 10,000
   * before dividing keeps two decimal places of precision in integer space, so the bar
   * is accurate to a hundredth of a percent without a float ever touching the amounts.
   */
  const basisPoints = Number((available.minorUnits * 10_000n) / current.minorUnits);
  const percent = Math.min(100, Math.max(0, basisPoints / 100));

  const uncleared = current.minorUnits - available.minorUnits;

  return (
    <div>
      <p className="held__figure">{formatMoneyDto(availableBalance)}</p>
      <p className="held__label">
        Available to spend now, out of {formatMoneyDto(currentBalance)} in the account.
      </p>

      {/*
        A real progress element, not a styled div. It exposes the value to assistive
        technology, so a screen-reader user hears the proportion rather than being told
        there is a decorative bar.
      */}
      <div
        className="held__track"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(percent)}
        aria-label="Share of this balance available to spend"
      >
        <div
          className="held__fill held__fill--available"
          style={{ width: `${String(percent)}%` }}
        />
      </div>

      <p className="held__ends">
        <span>{formatMoneyDto(availableBalance)} available</span>
        <span>
          {/*
            The uncleared remainder: the difference between two figures the server stated,
            in one currency, computed in integer minor units. Rendered through the shared
            money formatter rather than by subtracting two formatted strings.
          */}
          {formatMoney({
            minorUnits: uncleared,
            currency: current.currency,
            scale: current.scale,
          })}{' '}
          not cleared
        </span>
      </p>
    </div>
  );
}
