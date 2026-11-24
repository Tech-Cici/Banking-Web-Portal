/*
 * REPAYING A LOAN.
 *
 *
 * 1. WHY A TABLE AND NOT JUST LEDGER ENTRIES.
 *
 * The debit on the customer's account is already a ledger row, and it says money left. It
 * cannot say which loan it paid, or how much of it was interest. That split is the thing a
 * customer asks about -- "I paid 110,000, why did my balance only drop by 94,000?" -- and it
 * is not recoverable afterwards from the amount alone, because it depends on what was
 * outstanding at that moment. So it is recorded when it is known.
 *
 *
 * 2. THE SPLIT, AND THE ONE INVARIANT WORTH ENFORCING.
 *
 * Every repayment is interest first, capital with the remainder. interest + capital = amount
 * is checked by the database, because it is the sentence the whole row exists to make and an
 * arithmetic slip would otherwise produce a statement that quietly does not add up.
 *
 * outstanding_after IS RECORDED, NOT RECOMPUTED, for the same reason account_transactions
 * records balance_after: a statement line should be checkable on its own, and a disagreement
 * between this column and the running total is a loud, findable fault rather than a silent
 * one.
 *
 *
 * 3. WHAT THIS DELIBERATELY DOES NOT MODEL.
 *
 * No penalty interest, no fees, no early-settlement rebate, no rescheduling. Interest accrues
 * only when a payment is made, on the capital then outstanding -- not daily. A real loan book
 * does all of those; this one is a simulation and it is better for the gaps to be written
 * down here than inferred later from surprising numbers.
 */

CREATE TABLE loan_repayments (
    id                      UUID         NOT NULL PRIMARY KEY,

    loan_id                 UUID         NOT NULL REFERENCES loans (id) ON DELETE CASCADE,
    customer_id             UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /* The account the money came from. Recorded because a customer may hold several. */
    from_account_id         UUID         NOT NULL REFERENCES customer_accounts (id),

    currency                CHAR(3)      NOT NULL,

    amount_minor            BIGINT       NOT NULL,
    interest_minor          BIGINT       NOT NULL,
    capital_minor           BIGINT       NOT NULL,

    /* What was still owed once this payment was applied. */
    outstanding_after_minor BIGINT       NOT NULL,

    paid_at                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /* A payment of nothing is not a payment. */
    CONSTRAINT loan_repayment_amount_positive
        CHECK (amount_minor > 0),

    CONSTRAINT loan_repayment_parts_not_negative
        CHECK (interest_minor >= 0 AND capital_minor >= 0),

    /*
     * THE SENTENCE THIS ROW EXISTS TO MAKE. If the two parts do not add to the whole, the
     * statement built from them does not add up either, and a customer checking it is right
     * and the bank is wrong.
     */
    CONSTRAINT loan_repayment_parts_sum
        CHECK (interest_minor + capital_minor = amount_minor),

    CONSTRAINT loan_repayment_outstanding_not_negative
        CHECK (outstanding_after_minor >= 0)
);

/* One loan's history, oldest first: the order a statement reads. */
CREATE INDEX loan_repayments_loan_idx ON loan_repayments (loan_id, paid_at ASC);

COMMENT ON COLUMN loan_repayments.interest_minor IS
    'The part of this payment that was interest on the capital outstanding at the time. Recorded because it cannot be recovered from the amount afterwards.';

/* ------------------------------------------------- what it looks like on a statement */

/*
 * ITS OWN MOVEMENT KIND. A repayment is not a transfer: the other side is a loan account that
 * this portal does not hold as a customer account, so there is no second ledger entry and no
 * counterparty to name. Calling it a transfer would promise a matching credit somewhere that
 * a customer could go and look for.
 *
 * Same change as V5, V16, V17, V18, V22, V24, V30 and V33.
 */
ALTER TABLE account_transactions DROP CONSTRAINT IF EXISTS account_transactions_movement_kind_check;

ALTER TABLE account_transactions
    ADD CONSTRAINT account_transactions_movement_kind_check
        CHECK (movement_kind IN ('CASH',
                                 'TRANSFER_OUT',
                                 'TRANSFER_IN',
                                 'TRANSFER_RETURNED',
                                 'LOAN_DISBURSEMENT',
                                 'LOAN_REPAYMENT',
                                 'FX_SOLD',
                                 'FX_BOUGHT'));
