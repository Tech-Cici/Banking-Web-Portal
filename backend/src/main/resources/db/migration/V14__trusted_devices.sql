/*
 * BROWSERS THAT HAVE ALREADY PROVED THEMSELVES.
 *
 * Signing in takes a password and then a code emailed to the customer. The code is the
 * control that makes a stolen password insufficient, and it was asked for on EVERY
 * sign-in - so a customer checking their balance twice in a morning read two emails and
 * typed two codes, and the honest complaint was that the portal was unusable.
 *
 * The usual answer is to delete the second factor. This keeps it and remembers the
 * browser instead: the code is asked for once per browser, and for thirty days after
 * that the same browser signs in on the password alone. A stolen password still gets an
 * attacker nothing, because their browser has never been trusted.
 *
 * WHY A TABLE AND NOT A SIGNED COOKIE. A signed cookie needs no storage and cannot be
 * taken back - once issued it is valid until it expires, whatever happens to the
 * account. Trust that cannot be revoked is the wrong shape for this: freezing an account
 * has to stop the browsers that were already trusted, and that means a row somewhere to
 * mark revoked. It also means no signing key to rotate, lose or leak.
 */

CREATE TABLE trusted_devices (
    id            UUID         NOT NULL PRIMARY KEY,

    customer_id   UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /*
     * THE HASH, NEVER THE TOKEN. The token itself exists in exactly two places: the
     * customer's cookie and the response that set it. A database dump therefore hands
     * out no working device tokens, which is the same reason the verification codes are
     * stored hashed.
     *
     * SHA-256 and not BCrypt, for the opposite reason to the codes: the token is 256
     * bits of SecureRandom output, so there is nothing to brute-force and no need to pay
     * BCrypt's cost on every sign-in. What protects this secret is its entropy.
     *
     * UNIQUE, so presenting a token is a single indexed lookup rather than a scan, and so
     * the same token can never be recorded against two customers.
     */
    token_hash    VARCHAR(64)  NOT NULL UNIQUE,

    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * WHEN THE TRUST LAPSES, stored rather than computed from created_at. The window is
     * a policy that will change; a row that carries its own expiry keeps meaning what it
     * meant when it was written, and shortening the policy later does not retroactively
     * expire devices the bank had already promised thirty days to.
     */
    expires_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * Updated on each sign-in that uses this device. The point is not analytics: it is
     * the column that makes "which of these is the laptop I lost" answerable when the
     * customer is looking at their own list of trusted browsers.
     */
    last_used_at  TIMESTAMP(6) WITH TIME ZONE,

    /*
     * WHY TRUST WAS WITHDRAWN, and when. Kept rather than deleted, because "this browser
     * was trusted and then it was not" is exactly the history somebody investigating an
     * unauthorised sign-in needs, and a deleted row answers nothing.
     */
    revoked_at    TIMESTAMP(6) WITH TIME ZONE,
    revoked_reason VARCHAR(200),

    CONSTRAINT trusted_devices_expiry_after_creation
        CHECK (expires_at > created_at),

    /*
     * A revocation needs its reason. Without this, a future code path can withdraw a
     * customer's trusted browsers and leave nothing saying why - and the first person to
     * ask will be the customer wondering why they were asked for a code again.
     */
    CONSTRAINT trusted_devices_revocation_reasoned
        CHECK ((revoked_at IS NULL AND revoked_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL))
);

/*
 * The query that runs on every sign-in is "has this customer got a live device with this
 * hash", and the one behind a freeze is "every device of this customer". The unique index
 * on token_hash serves the first; this serves the second.
 */
CREATE INDEX trusted_devices_customer_idx ON trusted_devices (customer_id);

COMMENT ON TABLE trusted_devices IS
    'Browsers that have completed the emailed-code step and may sign in on the password alone until expires_at. Revocable, which is why this is a table and not a signed cookie.';

COMMENT ON COLUMN trusted_devices.token_hash IS
    'SHA-256 of the opaque token held in the customer''s cookie. The token itself is never stored.';
