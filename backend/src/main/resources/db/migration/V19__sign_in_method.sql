/*
 * THE FABRICATED SIGN-IN LOCATION, REMOVED.
 *
 * V2 gave sign_ins a location column and commented it "coarse on purpose. A precise
 * location stored against every sign-in is a movement history of the customer, which the
 * bank does not need and should not hold." The reasoning was sound. The column was not
 * coarse: every one of the four call sites in CustomerAuthService passed the same string
 * literal, "Kigali, Rwanda", and no geo-IP lookup has ever existed in this service.
 *
 * So the dashboard header told every customer "last sign-in from Kigali, Rwanda", and the
 * profile page printed it under a heading reading "Where", whoever they were and wherever
 * they had signed in from.
 *
 * WHY THAT IS A SECURITY FAULT AND NOT A COSMETIC ONE. The sign-in history has exactly one
 * job: a customer notices access that was not theirs. A constant defeats it precisely in
 * the case it exists for — an intruder in another country produced a row indistinguishable
 * from the customer's own — so the one control the customer operates themselves returned a
 * false negative by construction. A blank column would have been better, because a blank
 * prompts a question.
 *
 * WHAT REPLACES IT: the two things this service actually knows when it writes the row.
 *
 *   method  - which branch of the sign-in it just took. Free, certain, and more use to the
 *             customer than a city: "a remembered browser signed in without a code" is a
 *             recognisable event, and it names the thing to revoke.
 *   device  - browser and platform, from the User-Agent, and nothing finer. See
 *             DeviceDescription for why two words rather than a parsed version string:
 *             enough to recognise, not enough to fingerprint.
 *
 * GEO-LOCATION IS NOT REIMPLEMENTED. Doing it truthfully needs a geo-IP provider the bank
 * has not chosen and a decision about retaining IP addresses the bank has not made. It is
 * recorded in docs/OPEN-ITEMS.md under "Waiting on the bank" rather than guessed at again.
 */

/*
 * ADDED NULLABLE, BACKFILLED, THEN MADE NOT NULL. A plain NOT NULL ADD COLUMN fails on any
 * table that already holds rows, and this one does on every environment that has been
 * signed into. Three statements that work on both H2 and PostgreSQL beat one that works on
 * neither; V4 is the standing reminder that the same file is not the same dialect.
 */
ALTER TABLE sign_ins ADD COLUMN method VARCHAR(24);

/*
 * UNKNOWN, not a guess. Rows written before this migration recorded no method, and the
 * honest value for them is that nobody knows which it was. Backfilling PASSWORD_AND_CODE
 * because it is the commonest would be the same mistake as the column being deleted: a
 * plausible value nothing stands behind.
 */
UPDATE sign_ins SET method = 'UNKNOWN' WHERE method IS NULL;

ALTER TABLE sign_ins ALTER COLUMN method SET NOT NULL;

/*
 * The enum, the constraint and the TypeScript union are three copies of one list, and
 * SignInMethodsArePersistableTest is what stops them drifting — the general fix V5 bought
 * after ACCOUNT_FROZEN was added to OutboxKind and not to its constraint, which made
 * freezing an account return 500 and do nothing.
 */
ALTER TABLE sign_ins ADD CONSTRAINT sign_ins_method_known
    CHECK (method IN ('PASSWORD_AND_CODE', 'TRUSTED_BROWSER', 'PASSWORD_ONLY',
                      'TEMPORARY_PASSWORD', 'UNKNOWN'));

/*
 * NULLABLE, and that is the honest shape. A client may send no User-Agent at all, and an
 * unrecognised one must read as "we do not know" rather than as a default — the screens
 * render nothing when this is absent. A NOT NULL column here would force a fallback
 * string, which is how "Kigali, Rwanda" happened.
 */
ALTER TABLE sign_ins ADD COLUMN device VARCHAR(120);

ALTER TABLE sign_ins DROP COLUMN location;

/*
 * THE SAME LABEL ON THE TRUSTED-BROWSER LIST.
 *
 * trusted_devices has carried created_at, expires_at and last_used_at since V14, and V14's
 * own comment says last_used_at is "the column that makes 'which of these is the laptop I
 * lost' answerable when the customer is looking at their own list of trusted browsers".
 * A date alone does not answer it: two browsers used the same afternoon are two identical
 * rows. The screen that was written against this expected a label and a location, and the
 * table had neither, so both would have had to be invented at render time.
 */
ALTER TABLE trusted_devices ADD COLUMN device VARCHAR(120);

COMMENT ON COLUMN sign_ins.method IS
    'Which sign-in path was taken. Replaces a location column whose four call sites all passed the literal ''Kigali, Rwanda''.';

COMMENT ON COLUMN sign_ins.device IS
    'Browser and platform from the User-Agent, coarse on purpose. Null when the client sent no recognisable header; never defaulted.';

COMMENT ON COLUMN trusted_devices.device IS
    'Browser and platform as at the moment trust was granted, so the customer can tell their own browsers apart well enough to revoke one.';

/*
 * THE NEW OUTBOX KIND.
 *
 * Changing a password sends PASSWORD_CHANGED, which is the only way a customer whose
 * password was changed by somebody else finds out. The enum and this constraint are two
 * copies of one list — V5 is what happened last time they drifted: ACCOUNT_FROZEN went
 * into the enum and not the constraint, so freezing an account failed writing its
 * notification inside the same transaction, rolled the suspension back, and returned 500
 * for a feature that appeared merely broken.
 *
 * A SUPERSET, re-added whole. PostgreSQL validates a new CHECK against every existing row,
 * so a narrower list than the one in force would be rejected by the rows already written
 * under it; dropping and re-adding the full list is the only form of this that is safe on
 * a database with history. Guarded by OutboxKindsArePersistableTest.
 */
ALTER TABLE outbox DROP CONSTRAINT IF EXISTS outbox_kind_check;

ALTER TABLE outbox
    ADD CONSTRAINT outbox_kind_check
        CHECK (kind IN ('EMAIL_VERIFICATION',
                        'ACCOUNT_CREATED',
                        'ACCOUNT_APPROVED',
                        'ACCOUNT_REJECTED',
                        'ACCOUNT_FROZEN',
                        'ACCOUNT_UNFROZEN',
                        'PASSWORD_REISSUED',
                        'PASSWORD_REQUEST_REFUSED',
                        'PASSWORD_CHANGED',
                        'BENEFICIARY_APPROVED',
                        'BENEFICIARY_REFUSED',
                        'SERVICE_REQUEST_READY',
                        'SERVICE_REQUEST_DECLINED'));
