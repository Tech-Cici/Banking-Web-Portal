/*
 * WHAT A CONVERSION LOOKS LIKE ON A STATEMENT.
 *
 * NOT A TRANSFER, though it is two ledger entries between two accounts the same customer
 * holds. A transfer moves one amount: what leaves one account arrives in the other, and a
 * statement line saying "sent RWF 100,000 to **** 0001" is complete. A conversion moves two
 * different amounts in two different currencies, and describing either leg as a transfer
 * produces a line whose halves do not tie -- RWF 100,000 out, USD 68.96 in, to and from an
 * account with the same masked number.
 *
 * SO BOTH LEGS SAY WHAT THEY ARE. FX_SOLD is the currency the customer gave up, FX_BOUGHT is
 * what they received, and the description carries the rate they got. A customer checking a
 * statement six months later can see the trade rather than two transfers that do not add up.
 *
 * NO COUNTERPARTY ON EITHER, which MovementKind.hasCounterparty must agree with. The other
 * party to a conversion is the bank's treasury, not a payee, and printing the customer's own
 * name opposite their own account would be inventing a party where there is a product.
 *
 * Same change as V5, V16, V17, V18, V22, V24 and V30: the enum and this CHECK are two copies
 * of one list.
 */

ALTER TABLE account_transactions DROP CONSTRAINT IF EXISTS account_transactions_movement_kind_check;

ALTER TABLE account_transactions
    ADD CONSTRAINT account_transactions_movement_kind_check
        CHECK (movement_kind IN ('CASH',
                                 'TRANSFER_OUT',
                                 'TRANSFER_IN',
                                 'TRANSFER_RETURNED',
                                 'LOAN_DISBURSEMENT',
                                 'FX_SOLD',
                                 'FX_BOUGHT'));
