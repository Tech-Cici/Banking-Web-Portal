/*
 * THE BANK'S CORE BANKING SYSTEM, SIMULATED.
 *
 * WHAT THIS IS NOT: the Internet Banking Portal's own database. The portal runs on
 * PostgreSQL. This is the system of record the portal READS to find out whether an
 * account number exists and what it holds. The portal must never write here.
 *
 * THE SHAPE IS THE BANK'S, NOT A TIDY ONE, and that is deliberate. One row is a
 * CUSTOMER, not an account: the seventeen balance columns are product x currency, so a
 * single row carries up to seventeen separate balances. Normalising it here would mean
 * the portal is developed against a database that does not exist. The awkwardness has to
 * live somewhere, and it is cheaper in a mapping layer we write than in a surprise on
 * the day it meets the real thing.
 *
 * The source spellings are kept exactly -- 'Domant', 'CCompany', the inconsistent casing
 * across BalanceCurrentAc and BalanceCurrentacUSD. If the real CBS says 'Domant' then the
 * portal has to read 'Domant'. Correcting it here would hide the problem rather than
 * solve it.
 *
 * Run it:
 *     docker start zigama-cbs
 *     docker exec -i zigama-cbs /opt/mssql-tools18/bin/sqlcmd \
 *         -S localhost -U sa -C -i /dev/stdin < cbs-sim/01-schema.sql
 */

IF DB_ID('ZigamaCbs') IS NOT NULL
BEGIN
    ALTER DATABASE ZigamaCbs SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE ZigamaCbs;
END
GO

CREATE DATABASE ZigamaCbs;
GO

USE ZigamaCbs;
GO

CREATE TABLE customer_accounts (
    Account                     VARCHAR(20)   NOT NULL PRIMARY KEY,
    Names                       NVARCHAR(150) NOT NULL,
    Categ                       CHAR(1)       NOT NULL,
    AStatus                     VARCHAR(20)   NOT NULL,

    /*
     * DECIMAL, never FLOAT and never MONEY. FLOAT is the same binary floating point the
     * portal forbids in the browser and cannot hold a balance exactly; MONEY rounds in
     * ways that surprise people the moment anything is divided. Two decimal places,
     * because that is what the source carries -- including for RWF, which does not
     * really have minor units.
     */
    BalanceCurrentAc            DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalanceCompAc               DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalanceVolAc                DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalanceFixedac              DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalanceEducationSavings     DECIMAL(19,2) NOT NULL DEFAULT 0,

    BalanceCurrentacUSD         DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalancecompSavingsUSD       DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalancevoluSavingsUSD       DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalanceFixedacUSD           DECIMAL(19,2) NOT NULL DEFAULT 0,

    BalanceCurrentacEuro        DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalancecompSavingsEuro      DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalancevoluSavingsEuro      DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalanceFixedacEuro          DECIMAL(19,2) NOT NULL DEFAULT 0,

    BalanceCurrentacPound       DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalancecompSavingsPound     DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalancevoluSavingsPound     DECIMAL(19,2) NOT NULL DEFAULT 0,
    BalanceFixedacPound         DECIMAL(19,2) NOT NULL DEFAULT 0,

    /*
     * REDUNDANT WITH Categ, and both are in the source. M pairs with Individual and C
     * with 'COompany' -- spelled exactly that way in the data, and kept, because a value
     * the CBS actually emits is the value the portal has to match. Two columns stating
     * one fact will drift eventually; the check below makes the drift loud instead of
     * silent. Verified against all 200 rows: 172 M/Individual, 28 C/COompany, none
     * disagreeing.
     */
    Customercategory            NVARCHAR(30)  NOT NULL,
    Dateofcreation              DATE          NOT NULL,
    TelephoneNumber             VARCHAR(20)   NOT NULL,

    /*
     * RWF HAS NO MINOR UNIT, so an RWF balance has no fractional part.
     *
     * The source spreadsheet carried two decimals on every column -- 648 of its 660
     * non-zero RWF balances had centimes -- which is the generator's habit rather than
     * anything the bank does. It surfaced as a refusal at the counter: the portal parses
     * money strictly against the currency's own scale, so copying "3056912.21" in as an
     * RWF opening balance was rejected, correctly.
     *
     * The rule is enforced here rather than remembered, because the alternative on offer
     * was to let RWF carry decimals in the portal -- weakening the one rule that stops a
     * rounding decision nobody made from reaching somebody's balance.
     */
    CONSTRAINT customer_accounts_rwf_whole
        CHECK (BalanceCurrentAc = FLOOR(BalanceCurrentAc)
           AND BalanceCompAc = FLOOR(BalanceCompAc)
           AND BalanceVolAc = FLOOR(BalanceVolAc)
           AND BalanceFixedac = FLOOR(BalanceFixedac)
           AND BalanceEducationSavings = FLOOR(BalanceEducationSavings)),

    CONSTRAINT customer_accounts_categ_known
        CHECK (Categ IN ('M', 'C')),
    CONSTRAINT customer_accounts_category_agrees
        CHECK ((Categ = 'M' AND Customercategory = 'Individual')
            OR (Categ = 'C' AND Customercategory = 'COompany'))
);
GO

/* The portal's lookup is by account number, which is the primary key, so that one is
   already indexed. This is for the admin searching by name or phone. */
CREATE INDEX customer_accounts_name_idx  ON customer_accounts (Names);
CREATE INDEX customer_accounts_phone_idx ON customer_accounts (TelephoneNumber);
GO
