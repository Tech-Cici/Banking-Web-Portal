import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Account } from '@/types/banking';
import { BalanceBar } from './BalanceBar';

/**
 * THE ONE COMPONENT HERE THAT DOES ARITHMETIC, so it is the one worth testing.
 *
 * The bar's width comes from dividing one balance by another. Everything that can go
 * wrong with that is in here: dividing by zero, a proportion between two currencies, and
 * a full bar drawn beside two identical figures that tells the customer nothing.
 */

function account(over: Partial<Account> = {}): Account {
  return {
    id: 'acct-1',
    nickname: 'Current',
    accountType: 'CURRENT',
    maskedNumber: '**** 8901',
    currency: 'RWF',
    status: 'ACTIVE',
    availableBalance: { amount: '75000', currency: 'RWF' },
    currentBalance: { amount: '100000', currency: 'RWF' },
    balanceAsOf: '2026-09-30T08:00:00Z',
    debitAllowed: true,
    ...over,
  };
}

describe('BalanceBar', () => {
  it('SHOWS THE SPENDABLE SHARE AS A PROPORTION OF WHAT IS THERE', () => {
    render(<BalanceBar account={account()} />);

    const bar = screen.getByRole('progressbar');
    // 75,000 of 100,000.
    expect(bar).toHaveAttribute('aria-valuenow', '75');

    /*
     * And the uncleared remainder is stated, not left for the customer to subtract. This
     * is the figure the question "why can't I spend it" is actually about.
     */
    expect(screen.getByText(/25,000 not cleared/)).toBeInTheDocument();
  });

  it('DRAWS NOTHING WHEN EVERYTHING IS SPENDABLE', () => {
    /*
     * A full bar beside two identical figures says "100% of your money is your money",
     * which is noise on every ordinary account. Rendering nothing is the right amount of
     * interface for the case where there is nothing to explain.
     */
    const { container } = render(
      <BalanceBar account={account({ currentBalance: { amount: '75000', currency: 'RWF' } })} />,
    );
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('DOES NOT DIVIDE BY AN EMPTY ACCOUNT', () => {
    /*
     * AVAILABLE WITHOUT A CURRENT BALANCE TO DIVIDE BY, which should never arrive and is
     * defended anyway.
     *
     * This test was originally written with both balances at zero, and it passed with the
     * zero guard deleted — because two equal balances are caught by the guard above it.
     * It was therefore testing nothing. These figures are contradictory on purpose: they
     * are the only shape that actually reaches the division.
     */
    const { container } = render(
      <BalanceBar
        account={account({
          availableBalance: { amount: '5000', currency: 'RWF' },
          currentBalance: { amount: '0', currency: 'RWF' },
        })}
      />,
    );
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('DRAWS NOTHING FOR AN ACCOUNT WITH NOTHING IN IT', () => {
    /* Both at zero: no bar, and no "0% available" sentence on an empty account. */
    const { container } = render(
      <BalanceBar
        account={account({
          availableBalance: { amount: '0', currency: 'RWF' },
          currentBalance: { amount: '0', currency: 'RWF' },
        })}
      />,
    );
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('WILL NOT COMPARE TWO CURRENCIES', () => {
    /*
     * Two currencies on one account should not happen. If it ever does, a proportion
     * between them would be this component inventing an exchange rate — so it declines
     * to draw anything rather than produce a bar that means nothing.
     */
    const { container } = render(
      <BalanceBar
        account={account({ currentBalance: { amount: '100000', currency: 'USD' } })}
      />,
    );
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('DRAWS NOTHING WHEN THE BALANCE IS NOT KNOWN AT ALL', () => {
    /*
     * The balance lives in core banking, which this service does not talk to. A bar at
     * zero would be a statement about somebody's money; absence is a statement about this
     * system, which is the true one.
     */
    const withoutBalances: Record<string, unknown> = { ...account() };
    delete withoutBalances['availableBalance'];
    delete withoutBalances['currentBalance'];

    const { container } = render(
      <BalanceBar account={withoutBalances as unknown as Account} />,
    );
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('HANDLES A CURRENCY WITH MINOR UNITS WITHOUT ROUNDING THE AMOUNTS', () => {
    /*
     * RWF has no minor unit, so every other case here is whole numbers. USD has two, and
     * the ratio is computed in integer minor units — 12.34 of 24.68 is exactly half, and
     * would not be if the amounts went through a float on the way.
     */
    render(
      <BalanceBar
        account={account({
          currency: 'USD',
          availableBalance: { amount: '12.34', currency: 'USD' },
          currentBalance: { amount: '24.68', currency: 'USD' },
        })}
      />,
    );

    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '50');
    expect(screen.getByText(/12\.34 not cleared/)).toBeInTheDocument();
  });
});
