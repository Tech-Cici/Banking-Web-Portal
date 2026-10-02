import { describe, expect, it } from 'vitest';
import type { Transaction } from '@/types/banking';
import { describeMovement, signedAmount } from './describeMovement';

/**
 * RWF 30,000 with a NON-BREAKING space, which is what Intl produces.
 *
 * Written as an escape rather than typed, because the two are indistinguishable in an
 * editor and in a failure message: asserting against a plain space fails with
 * "expected 'RWF 30,000' to be 'RWF 30,000'", which is the least helpful diff a test
 * can produce. The non-breaking space is also correct — a currency code should not wrap
 * away from its figure.
 */
const AMOUNT = 'RWF\u00a030,000';

/**
 * HOW A MOVEMENT IS WORDED, and the one case that makes the movement kind necessary.
 *
 * The interesting test here is the refund. A refused transfer returns the money as a
 * CREDIT carrying the payee's name — identical in shape to an arrival. Worded from the
 * direction, it reads "you received 30,000 from Ciara": a receipt for money that never
 * moved, shown to the customer whose payment had just failed. That is why the server
 * sends a kind and this function switches on it.
 */

function movement(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 'txn-1',
    accountId: 'acct-1',
    bookedAt: '2026-09-30T08:20:00Z',
    description: 'Sent, awaiting approval',
    reference: 'AB12CD34',
    direction: 'DEBIT',
    amount: { amount: '30000', currency: 'RWF' },
    status: 'COMPLETED',
    category: 'TRANSFER',
    movementKind: 'TRANSFER_OUT',
    counterpartyName: 'Ciara Teta MUZORA',
    counterpartyMask: '**** 7890',
    ...over,
  };
}

/**
 * The same movement with optional fields ABSENT rather than set to undefined.
 *
 * The project runs with `exactOptionalPropertyTypes`, which treats those two as
 * different — and they are different here in a way that matters: Jackson omits null
 * fields, so a deposit's payload has no `counterpartyName` key at all. A test that set
 * the key to undefined would be asserting against a shape the server never sends, and
 * `'counterpartyName' in movement` would answer differently in the test than in life.
 */
function without(
  base: Transaction,
  ...keys: readonly ('counterpartyName' | 'counterpartyMask' | 'movementKind')[]
): Transaction {
  /*
   * Rebuilt by filtering rather than by `delete`, which the lint rules forbid on a
   * computed key — and rightly, since a mistyped key would silently delete nothing and
   * the test would then pass for the wrong reason.
   */
  const kept = Object.entries(base).filter(
    ([key]) => !keys.includes(key as (typeof keys)[number]),
  );
  return Object.fromEntries(kept) as unknown as Transaction;
}

describe('describeMovement', () => {
  it('NAMES THE PAYEE ON MONEY GOING OUT', () => {
    const line = describeMovement(movement());

    expect(line.headline).toBe(`You sent ${AMOUNT} to Ciara Teta MUZORA (**** 7890)`);
    expect(line.direction).toBe('OUT');
    expect(line.isReturn).toBe(false);

    /*
     * The server's own words are kept beside the sentence rather than replaced by it.
     * "Sent, awaiting approval" is the server saying the money has not arrived, and a
     * screen that dropped it would promise an outcome the server has not.
     */
    expect(line.detail).toBe('Sent, awaiting approval');
  });

  it('NAMES THE SENDER ON MONEY COMING IN', () => {
    const line = describeMovement(
      movement({
        direction: 'CREDIT',
        movementKind: 'TRANSFER_IN',
        description: 'Received — school fees',
        counterpartyName: 'Teta Eliana',
        counterpartyMask: '**** 8901',
      }),
    );

    expect(line.headline).toBe(`You received ${AMOUNT} from Teta Eliana (**** 8901)`);
    expect(line.direction).toBe('IN');
    expect(line.isReturn).toBe(false);
  });

  it('DOES NOT CALL A REFUND AN ARRIVAL', () => {
    const line = describeMovement(
      movement({
        direction: 'CREDIT',
        movementKind: 'TRANSFER_RETURNED',
        description: 'Refused — money returned',
      }),
    );

    /*
     * The assertion that matters: it must NOT read as having received money from the
     * payee. Asserted negatively as well as positively, because a future rewording that
     * happens to contain the right nouns could otherwise still say the wrong thing.
     */
    expect(line.headline).not.toContain('You received');
    expect(line.headline).toBe(
      `Your ${AMOUNT} to Ciara Teta MUZORA (**** 7890) came back — the transfer was refused`,
    );
    expect(line.isReturn).toBe(true);
  });

  it('INVENTS NOBODY FOR CASH OVER THE COUNTER', () => {
    const paidIn = describeMovement(
      without(
        movement({
          direction: 'CREDIT',
          movementKind: 'CASH',
          category: 'CASH',
          description: 'Salary',
        }),
        'counterpartyName',
        'counterpartyMask',
      ),
    );

    /*
     * A deposit is the customer and the bank. "You received 30,000 from the bank" would
     * be a claim nobody made, so the wording states the movement and leaves the
     * description to say what it was.
     */
    expect(paidIn.headline).toBe(`Paid in ${AMOUNT}`);
    expect(paidIn.detail).toBe('Salary');
    expect(paidIn.direction).toBe('IN');

    const takenOut = describeMovement(
      without(
        movement({ movementKind: 'CASH', description: 'ATM withdrawal' }),
        'counterpartyName',
        'counterpartyMask',
      ),
    );
    expect(takenOut.headline).toBe(`Taken out ${AMOUNT}`);
    expect(takenOut.direction).toBe('OUT');
  });

  it('FALLS BACK TO THE CASH WORDING WHEN THE SERVER SENDS NO KIND', () => {
    /*
     * An older API, or a fixture written before the field existed. The fallback must
     * state no outcome it cannot know — so it describes the movement and names nobody,
     * rather than guessing "received from" off the direction.
     */
    const line = describeMovement(
      without(
        movement({ direction: 'CREDIT', description: 'Opening balance' }),
        'movementKind',
        'counterpartyName',
        'counterpartyMask',
      ),
    );

    expect(line.headline).toBe(`Paid in ${AMOUNT}`);
    expect(line.isReturn).toBe(false);
  });

  it('DROPS THE MASK RATHER THAN THE NAME IF ONLY ONE ARRIVES', () => {
    /*
     * The server's constraint makes this impossible, and the client still must not print
     * "Teta Eliana (undefined)" if it ever happens. The name alone is useful; the word
     * undefined on a statement line is not.
     */
    const line = describeMovement(without(movement(), 'counterpartyMask'));
    expect(line.headline).toBe(`You sent ${AMOUNT} to Ciara Teta MUZORA`);
  });
});

describe('signedAmount', () => {
  it('SIGNS FOR DISPLAY WITHOUT NEGATING THE VALUE', () => {
    expect(signedAmount(movement())).toBe(`− ${AMOUNT}`);
    expect(signedAmount(movement({ direction: 'CREDIT' }))).toBe(`+ ${AMOUNT}`);
  });
});
