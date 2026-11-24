/*
 * ONE CUSTOMER NUMBER, SEVERAL ACCOUNTS INSIDE IT.
 *
 * Zigama issues a customer one number -- AC100000001 -- and the products held under it
 * are distinguished by name and currency, not by numbers of their own. The core banking
 * record is one row per customer with seventeen balance columns, product crossed with
 * currency, and there is no per-product account number anywhere in it.
 *
 * The old unique constraint assumed otherwise. UNIQUE (customer_id, account_number) let a
 * customer hold exactly one account, because every account they hold carries the same
 * number. A customer with a current account and a compulsory savings account could not be
 * created at all: the second insert failed on the first.
 *
 * WHAT IS NOT DONE HERE, and was considered. A suffixed number -- AC100000001-01, -02 --
 * would have kept the old constraint untouched and cost nothing to write. It would also
 * print on a customer's screen, and later their statements, an account number that does
 * not exist at their bank. A portal inventing identifiers for somebody's money is the
 * thing this schema has refused everywhere else, and it is refused here.
 *
 * STILL NO BALANCE COLUMN. The money is in core banking, which this service now reads
 * directly. Copying those figures in at account creation would be the second unreconciled
 * copy the original comment on this table warned about -- and it would be worse than when
 * that was written, because there is now a live source to disagree with.
 */

ALTER TABLE customer_accounts
    DROP CONSTRAINT customer_accounts_unique;

ALTER TABLE customer_accounts
    ADD CONSTRAINT customer_accounts_unique
        UNIQUE (customer_id, account_number, account_type, currency);

/*
 * The products the core system actually holds.
 *
 * Its five RWF columns are a current account, compulsory savings, voluntary savings, a
 * fixed deposit and education savings. Three of those had no name here, so a customer's
 * real holdings could not have been recorded even once the constraint allowed it. The
 * existing values are kept: they are in use, and a type nobody opens is cheaper than a
 * migration that breaks a row somebody already has.
 */
ALTER TABLE customer_accounts
    DROP CONSTRAINT customer_accounts_type_check;

ALTER TABLE customer_accounts
    ADD CONSTRAINT customer_accounts_type_check
        CHECK (account_type IN ('CURRENT', 'SAVINGS', 'FIXED_DEPOSIT', 'SALARY',
                                'JUNIOR_SAVINGS', 'TARGET_SAVINGS', 'LOAN_SERVICING',
                                'COMPULSORY_SAVINGS', 'VOLUNTARY_SAVINGS',
                                'EDUCATION_SAVINGS'));

/*
 * NO `COMMENT ON CONSTRAINT` HERE, and the omission is deliberate rather than an
 * oversight. Postgres accepts it; H2 answers "Syntax error ... expected '., IS'", and
 * this project's tests run the real migration chain against H2 on purpose. A Postgres-only
 * statement in a migration fails Flyway, which fails the Spring context, which errors
 * every test in the suite -- which is exactly what V23's partial index did. Measured
 * against H2 2.3.232 before this file was saved; the four ALTERs above pass, two products
 * under one number are accepted, and an exact duplicate is still refused.
 */
