import type { Transaction } from '@/types/banking';
import { formatMoneyDto } from '@/utils/money';

/**
 * WHAT HAPPENED, IN A SENTENCE A CUSTOMER WOULD SAY.
 *
 * <p>One function for every surface that shows a movement — the dashboard, the
 * notifications log and the statement — because the alternative is three screens that
 * word the same event three ways and drift apart on the next change. The statement used
 * to read "Transfer to **** 7898", which names an account and not a person, and the
 * dashboard could only say "money moved".
 *
 * <p>IT WORDS, IT DOES NOT DECIDE. The kind of movement comes from the server, in
 * {@code movementKind}. Nothing here infers a meaning from the direction or from the
 * description text: a refused transfer is a CREDIT carrying the payee's name, exactly
 * like an arrival, and wording that from the direction would tell a customer whose
 * payment had just failed that they received money.
 *
 * <p>NO ARITHMETIC. The amount is formatted, never computed, and the sign is presentation
 * only — the API sends a positive amount plus a direction, and turning it into a negative
 * number here is how a debit later gets added to a total instead of subtracted from it.
 */

export interface MovementLine {
  /** The sentence: "You received RWF 5,000 from Teta Eliana". */
  readonly headline: string;
  /**
   * The server's own words for this entry, or undefined when the headline already says
   * everything.
   *
   * <p>Kept separate and kept verbatim. "Sent, awaiting approval" is the server saying
   * the money has not arrived yet, and a screen that swallowed it in favour of a tidier
   * sentence would be promising an outcome the server has not.
   */
  readonly detail: string | undefined;
  /** Which way the money went, for the icon and the sign. */
  readonly direction: 'IN' | 'OUT';
  /** Whether this entry is money coming back rather than money arriving. */
  readonly isReturn: boolean;
}

/** The amount with its sign, for the right-hand column. */
export function signedAmount(movement: Transaction): string {
  return `${movement.direction === 'CREDIT' ? '+' : '−'} ${formatMoneyDto(movement.amount)}`;
}

export function describeMovement(movement: Transaction): MovementLine {
  const amount = formatMoneyDto(movement.amount);

  /*
   * The other party, named. `counterpartyName` is ABSENT rather than empty on a movement
   * with no other party, so this is a presence check and not a truthiness check on a
   * string the server might have sent blank.
   */
  const other =
    movement.counterpartyName === undefined
      ? undefined
      : movement.counterpartyMask === undefined
        ? movement.counterpartyName
        : `${movement.counterpartyName} (${movement.counterpartyMask})`;

  switch (movement.movementKind) {
    case 'TRANSFER_IN':
      return {
        headline: other === undefined ? `You received ${amount}` : `You received ${amount} from ${other}`,
        detail: movement.description,
        direction: 'IN',
        isReturn: false,
      };

    case 'TRANSFER_OUT':
      return {
        headline: other === undefined ? `You sent ${amount}` : `You sent ${amount} to ${other}`,
        /*
         * The detail matters most here. A transfer out is posted the moment the customer
         * sends it and the money does not arrive until a manager releases it, so the
         * server's "Sent, awaiting approval" is the difference between money on its way
         * and money delivered.
         */
        detail: movement.description,
        direction: 'OUT',
        isReturn: false,
      };

    case 'TRANSFER_RETURNED':
      return {
        /*
         * NOT "you received". The money is the customer's own, coming back because the
         * payment was refused, and the useful fact is which payment.
         */
        headline:
          other === undefined
            ? `Your ${amount} came back — the transfer was refused`
            : `Your ${amount} to ${other} came back — the transfer was refused`,
        detail: movement.description,
        direction: 'IN',
        isReturn: true,
      };

    case 'CASH':
    default:
      /*
       * Cash over the counter, or an opening balance. There is nobody to name, so the
       * server's description IS the headline rather than being wrapped in an invented
       * sentence — "You received 5,000 from the bank" would be a claim nobody made.
       */
      return {
        headline: `${movement.direction === 'CREDIT' ? 'Paid in' : 'Taken out'} ${amount}`,
        detail: movement.description,
        direction: movement.direction === 'CREDIT' ? 'IN' : 'OUT',
        isReturn: false,
      };
  }
}
