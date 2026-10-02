import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { ACCOUNT_TYPES } from '@/types/admin';
import type { Account } from '@/types/banking';
import { AccountTiles } from './AccountTiles';

/**
 * WHETHER A CUSTOMER EVER READS A DATABASE TOKEN ON THEIR OWN DASHBOARD.
 *
 * The tile's top line used to come from a map held inside the component, which named six
 * account types and was missing three of the seven the bank opens. A target savings
 * account therefore displayed "TARGET_SAVINGS" — the enum, in capitals, underscore and
 * all — to the person who owns it.
 *
 * The fix is to read the names the bank's own staff pick from. The test worth having is
 * therefore not "does CURRENT say Current"; it is that EVERY type the bank can open has
 * a human name here, so adding the eighth product cannot quietly reintroduce this.
 */

function account(over: Partial<Account> = {}): Account {
  return {
    id: 'acct-1',
    nickname: 'Current',
    accountType: 'CURRENT',
    maskedNumber: '**** 8901',
    currency: 'RWF',
    status: 'ACTIVE',
    availableBalance: { amount: '11988000', currency: 'RWF' },
    currentBalance: { amount: '11988000', currency: 'RWF' },
    balanceAsOf: '2026-09-30T08:00:00Z',
    debitAllowed: true,
    ...over,
  };
}

function tiles(accounts: readonly Account[]) {
  return render(
    <MemoryRouter>
      <AccountTiles accounts={accounts} />
    </MemoryRouter>,
  );
}

describe('AccountTiles', () => {
  it('NAMES EVERY ACCOUNT TYPE THE BANK CAN OPEN, with no raw token anywhere', () => {
    const all = ACCOUNT_TYPES.map((type, index) =>
      account({ id: `acct-${String(index)}`, accountType: type.value }),
    );

    const { container } = tiles(all);

    const shown = [...container.querySelectorAll('.tile__type')].map((el) => el.textContent);
    expect(shown).toHaveLength(ACCOUNT_TYPES.length);

    for (const label of shown) {
      /*
       * The two marks of an unformatted enum. Asserted on the rendered text rather than
       * on a lookup table, because a lookup table is the thing that was wrong.
       */
      expect(label).not.toMatch(/_/);
      expect(label).not.toMatch(/^[A-Z]{2,}$/);
      expect(label.trim()).not.toBe('');
    }

    // And the one that was actually missing reads as words.
    expect(shown).toContain('Target savings');
  });

  it('STILL NAMES A TYPE THIS BUILD HAS NEVER HEARD OF, rather than showing the token', () => {
    /*
     * The bank adds a product and the portal has not caught up. It must not fall back to
     * printing the identifier: the customer learns nothing from "MOBILE_WALLET" except
     * that something is broken.
     */
    tiles([account({ accountType: 'MOBILE_WALLET' as Account['accountType'] })]);

    expect(screen.getByText('Mobile wallet')).toBeInTheDocument();
    expect(screen.queryByText('MOBILE_WALLET')).not.toBeInTheDocument();
  });

  it('SHOWS AN ABSENT BALANCE AS ABSENT, never as a zero', () => {
    /*
     * The account is real and the money is in a system this service cannot reach. A zero
     * is indistinguishable from an emptied account, and that is the one reading a bank
     * page may not invite.
     */
    const { availableBalance: _gone, ...withoutBalance } = account();

    tiles([withoutBalance]);

    expect(screen.getByText('Balance not shown')).toBeInTheDocument();
    /*
     * The non-breaking space `formatMoneyDto` puts between the code and the figure,
     * written as an escape: a literal one in the source is invisible in a diff and is
     * the reason an identical-looking assertion failed once already.
     */
    expect(screen.queryByText(new RegExp('RWF\\u00a00\\b'))).not.toBeInTheDocument();
  });
});
