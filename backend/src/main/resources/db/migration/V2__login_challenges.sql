-- One-time codes for customer sign-in.
--
-- Separate from `email_verifications` on purpose, even though the two look alike. That
-- table guards a REGISTRATION and carries the submitted details as its payload; this one
-- guards a SIGN-IN and points at a customer who already exists. Sharing a table would
-- mean a row whose meaning depends on which column is null, and a query that has to know
-- the difference — and the two have different lifetimes, different rate limits and
-- different consequences when abused.
--
-- Same two properties as the registration codes, for the same reasons: the code is stored
-- hashed so a database dump does not hand out live codes, and attempts are counted so a
-- six-digit code cannot be walked through its million combinations.

CREATE TABLE login_challenges (
    id           UUID         PRIMARY KEY,
    customer_id  UUID         NOT NULL REFERENCES customers (id),

    -- SHA-256 of the code. Never the code.
    code_hash    CHAR(64)     NOT NULL,

    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    attempts     SMALLINT     NOT NULL DEFAULT 0,
    consumed_at  TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX login_challenges_customer_idx ON login_challenges (customer_id, created_at);
CREATE INDEX login_challenges_expiry_idx   ON login_challenges (expires_at);


-- Sign-in history, for the line the dashboard shows the customer.
--
-- "Last signed in on ... from ..." is a security control disguised as a pleasantry: it is
-- how a customer notices a sign-in that was not them. It needs somewhere to live, and the
-- customers table is the wrong place because one row would only ever hold the latest.
CREATE TABLE sign_ins (
    id           UUID         PRIMARY KEY,
    customer_id  UUID         NOT NULL REFERENCES customers (id),
    signed_in_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- Coarse on purpose. A precise location stored against every sign-in is a movement
    -- history of the customer, which the bank does not need and should not hold.
    location     VARCHAR(120) NOT NULL
);

CREATE INDEX sign_ins_customer_idx ON sign_ins (customer_id, signed_in_at DESC);
