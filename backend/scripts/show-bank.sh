#!/usr/bin/env bash
#
# Prints what is actually in the database: who has registered, who has an account, and
# what the bank has sent them.
#
# Read-only. Every statement here is a SELECT, so it cannot change or delete anything —
# run it as often as you like while walking the onboarding flow.
#
#   ./scripts/show-bank.sh            summary, applications, customers, money, messages
#   ./scripts/show-bank.sh codes      also show the verification codes still live
#   ./scripts/show-bank.sh numbers    also show FULL account numbers, to test a transfer
#   ./scripts/show-bank.sh login      why a customer cannot sign in
#   ./scripts/show-bank.sh transfer   why a transfer is refused: every field the
#                                     money control reads, per account
#
# It deliberately does NOT print password hashes. There is nothing useful in them and a
# hash on a terminal is a hash in a screenshot. Full account numbers are behind
# `numbers` for the same reason: you need one to send a transfer, and it should be a
# thing you asked for rather than a thing every run leaves on your scrollback.

set -euo pipefail

DB_NAME="${DB_NAME:-ibanking}"
DB_USER="${DB_USERNAME:-ibanking}"
DB_PASS="${DB_PASSWORD:-ibanking_local_only}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5433}"

# ---------------------------------------------------------------- find psql

if ! command -v psql >/dev/null 2>&1; then
  for candidate in /Applications/Postgres.app/Contents/Versions/*/bin; do
    if [ -x "$candidate/psql" ]; then
      PATH="$candidate:$PATH"
      export PATH
      break
    fi
  done
fi

run() {
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASS" psql -w -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
      -P pager=off "$@"
  else
    # No psql on the Mac? Use the one inside the container.
    docker compose exec -T db psql -U "$DB_USER" -d "$DB_NAME" -P pager=off "$@"
  fi
}

if ! run -Atc 'select 1' >/dev/null 2>&1; then
  cat >&2 <<HINT
Could not reach the database at $DB_HOST:$DB_PORT.

Is it running?  docker compose ps
Start it with:  docker compose up -d db
HINT
  exit 1
fi

# ------------------------------------------------------------------ report

run <<'SQL'
\echo
\echo '=============================== WHERE THINGS STAND ==============================='
SELECT
  (SELECT count(*) FROM applications WHERE status = 'SUBMITTED')         AS "registered, no account",
  (SELECT count(*) FROM customers    WHERE status = 'PENDING_APPROVAL')  AS "awaiting approval",
  (SELECT count(*) FROM customers    WHERE status = 'ACTIVE')            AS "active",
  (SELECT count(*) FROM customers    WHERE status = 'ACTIVE'
                                       AND must_change_password)         AS "never signed in",
  (SELECT count(*) FROM customers    WHERE status = 'REJECTED')          AS "rejected";

\echo
\echo '=============================== REGISTRATIONS ===================================='
SELECT
  reference           AS "reference",
  status              AS "status",
  display_name        AS "name (from the bank)",
  email               AS "email",
  email_verified      AS "email ok",
  to_char(submitted_at, 'DD Mon HH24:MI') AS "registered"
FROM applications
ORDER BY submitted_at DESC
LIMIT 20;

\echo
\echo '=============================== CUSTOMERS ========================================'
\echo '(must_change = the temporary password has not been replaced yet)'
SELECT
  customer_number     AS "number",
  full_name           AS "name",
  email               AS "email",
  status              AS "status",
  must_change_password AS "must_change",
  created_by          AS "created by",
  coalesce(approved_by, '—') AS "approved by"
FROM customers
ORDER BY created_at DESC
LIMIT 20;

\echo
\echo '=============================== SIGN-INS ========================================='
SELECT
  c.customer_number   AS "number",
  c.full_name         AS "name",
  to_char(s.signed_in_at, 'DD Mon HH24:MI') AS "signed in",
  s.location          AS "from"
FROM sign_ins s JOIN customers c ON c.id = s.customer_id
ORDER BY s.signed_in_at DESC
LIMIT 10;

\echo
\echo '=============================== ACCOUNTS AND BALANCES ============================'
\echo '(RWF has no minor unit, so balance_minor IS the number of francs)'
SELECT
  a.masked_number     AS "account",
  a.account_type      AS "type",
  a.currency          AS "ccy",
  a.balance_minor     AS "balance",
  coalesce(co.name, c.full_name) AS "held by",
  CASE WHEN a.company_id IS NULL THEN 'personal' ELSE 'company' END AS "kind",
  CASE WHEN a.removed_at IS NULL THEN '' ELSE 'REMOVED' END AS "state"
FROM customer_accounts a
JOIN customers c ON c.id = a.customer_id
LEFT JOIN companies co ON co.id = a.company_id
ORDER BY a.assigned_at DESC
LIMIT 30;

\echo
\echo '=============================== TRANSFERS ========================================'
\echo '(PENDING_APPROVAL means the sender is ALREADY debited and a manager has not decided)'
SELECT
  to_char(t.submitted_at, 'DD Mon HH24:MI') AS "sent",
  src.masked_number   AS "from",
  dst.masked_number   AS "to",
  t.amount_minor      AS "amount",
  t.currency          AS "ccy",
  t.status            AS "status",
  coalesce(t.decided_by, '—') AS "decided by",
  coalesce(t.rejection_reason, '') AS "reason"
FROM transfers t
JOIN customer_accounts src ON src.id = t.source_account_id
JOIN customer_accounts dst ON dst.id = t.destination_account_id
ORDER BY t.submitted_at DESC
LIMIT 20;

\echo
\echo '=============================== DOES THE MONEY ADD UP? ==========================='
\echo 'in_accounts + in_flight must not change when a transfer is approved or refused.'
\echo 'While one is pending the money has left the sender and arrived nowhere, so the'
\echo 'accounts alone are SHORT by in_flight. There is no suspense account yet.'
SELECT
  (SELECT coalesce(sum(balance_minor), 0) FROM customer_accounts)   AS "in_accounts",
  (SELECT coalesce(sum(amount_minor), 0)  FROM transfers
     WHERE status = 'PENDING_APPROVAL')                            AS "in_flight",
  (SELECT coalesce(sum(balance_minor), 0) FROM customer_accounts)
    + (SELECT coalesce(sum(amount_minor), 0) FROM transfers
         WHERE status = 'PENDING_APPROVAL')                        AS "total";

\echo
\echo '=============================== LAST LEDGER ENTRIES ============================='
\echo '(the balance is the SUM of these and nothing else)'
SELECT
  to_char(e.created_at, 'DD Mon HH24:MI') AS "at",
  a.masked_number     AS "account",
  e.direction         AS "dir",
  e.amount_minor      AS "amount",
  e.balance_after_minor AS "balance after",
  e.description        AS "description"
FROM account_transactions e
JOIN customer_accounts a ON a.id = e.account_id
ORDER BY e.created_at DESC
LIMIT 15;

\echo
\echo '=============================== RECENT MESSAGES =================================='
\echo '(delivered = false means mail is off, or the send failed)'
SELECT
  kind                AS "kind",
  recipient           AS "to",
  subject             AS "subject",
  delivered           AS "sent",
  to_char(sent_at, 'DD Mon HH24:MI') AS "at"
FROM outbox
ORDER BY sent_at DESC
LIMIT 10;
SQL

# ----------------------------------------------------- codes, only if asked

if [ "${1:-}" = "login" ]; then
  run <<'SQL'
\echo
\echo '=============================== CAN THEY SIGN IN? ==============================='
\echo 'Sign-in accepts the EMAIL ADDRESS and nothing else - not the customer number,'
\echo 'not a username. If why_not says ok, that email plus the temporary password from'
\echo 'the approval message is the right pair.'
SELECT
  email               AS "sign in with this",
  customer_number     AS "number (for phoning, NOT for signing in)",
  status              AS "status",
  must_change_password AS "on a temp password",
  CASE
    WHEN status <> 'ACTIVE'
      THEN 'refused: status is ' || status
    WHEN must_change_password
         AND temporary_password_expires_at IS NOT NULL
         AND temporary_password_expires_at < now()
      THEN 'refused: temp password expired ' ||
           to_char(temporary_password_expires_at, 'DD Mon HH24:MI')
    WHEN must_change_password
      THEN 'ok - use the temp password, then you must change it'
    ELSE 'ok - use their own password'
  END                 AS "why_not"
FROM customers
ORDER BY created_at DESC
LIMIT 20;
SQL

  cat <<'HINT'

The temporary password is only in the approval email. Read it with:

  SELECT body FROM outbox
   WHERE kind = 'ACCOUNT_APPROVED' AND recipient = 'them@example.rw'
   ORDER BY sent_at DESC LIMIT 1;

Only the HASH is stored, so no password can be looked up here - and a temp password
issued before an account was approved again is no longer the live one.
HINT
fi

if [ "${1:-}" = "numbers" ]; then
  run <<'SQL'
\echo
\echo '=============================== FULL ACCOUNT NUMBERS ============================='
\echo 'You need one of these to send a transfer: the sender types the full number of'
\echo 'the RECEIVING account, and the service looks it up. Masks are not accepted and'
\echo 'could not be: they are not unique, so a mask would name several accounts.'
SELECT
  a.account_number    AS "account number",
  a.currency          AS "ccy",
  a.balance_minor     AS "balance",
  coalesce(co.name, c.full_name) AS "held by"
FROM customer_accounts a
JOIN customers c ON c.id = a.customer_id
LEFT JOIN companies co ON co.id = a.company_id
WHERE a.removed_at IS NULL
ORDER BY a.assigned_at DESC
LIMIT 30;
SQL
fi

if [ "${1:-}" = "codes" ]; then
  run <<'SQL'
\echo
\echo '=============================== LIVE CODES ======================================='
\echo 'Only the HASH is stored, never the code — so this shows which codes are still'
\echo 'usable, not what they are. Read the code itself from the message body:'
\echo '  SELECT body FROM outbox WHERE recipient = ''you@example.rw'' ORDER BY sent_at DESC LIMIT 1;'
SELECT
  email               AS "for",
  attempts            AS "wrong tries",
  to_char(expires_at, 'HH24:MI:SS') AS "expires",
  (expires_at > now() AND consumed_at IS NULL) AS "still usable"
FROM email_verifications
ORDER BY created_at DESC
LIMIT 10;
SQL
fi

if [ "${1:-}" = "transfer" ]; then
  run <<'SQL'
\echo
\echo '========================= WHY A TRANSFER IS REFUSED ============================='
\echo
\echo 'Sending money checks three things about the FROM account, and refuses with'
\echo '"We could not find the account you are sending from." if any of them fails.'
\echo 'This prints all three, per account, so a refusal names a field instead of a guess.'
\echo
\echo '  owner        the row must belong to the signed-in customer'
\echo '  company_id   must be EMPTY for a personal account. A company account is reached'
\echo '               through a LIVE membership and never through this column'
\echo '  removed_at   must be empty. A closed account cannot be debited'
\echo
\echo 'HOW TO READ IT. The "From" menu offers exactly the accounts that say CAN SEND, so'
\echo 'if the account you chose says CAN SEND here and the portal still refuses it, the'
\echo 'account is fine and the id the form sent is not one of these — look at the'
\echo 'sourceAccountId in the browser Network tab and compare it to the column below.'
SELECT
  cu.full_name                                   AS "customer",
  ca.id                                          AS "account id the form must send",
  ca.account_type                                 AS "type",
  ca.masked_number                                AS "mask",
  CASE
    WHEN ca.removed_at IS NOT NULL
      THEN 'NO — closed ' || to_char(ca.removed_at, 'YYYY-MM-DD')
    WHEN ca.company_id IS NOT NULL AND NOT EXISTS (
           SELECT 1 FROM corporate_memberships m
           WHERE m.company_id = ca.company_id
             AND m.customer_id = cu.id
             AND m.revoked_at IS NULL)
      THEN 'NO — company account, no live membership'
    WHEN ca.company_id IS NOT NULL
      THEN 'CAN SEND (via company membership)'
    ELSE 'CAN SEND'
  END                                             AS "may money leave it?"
FROM customer_accounts ca
JOIN customers cu ON cu.id = ca.customer_id
ORDER BY cu.full_name, ca.assigned_at;

\echo
\echo '--- Company memberships (the only way a company account is reachable) -----------'
SELECT
  cu.full_name              AS "customer",
  co.name                   AS "company",
  m.role                    AS "role",
  (m.revoked_at IS NULL)    AS "still live"
FROM corporate_memberships m
JOIN customers cu ON cu.id = m.customer_id
JOIN companies  co ON co.id = m.company_id
ORDER BY cu.full_name;

\echo
\echo '--- Transfers recorded so far ---------------------------------------------------'
\echo 'Empty means no submission has ever reached the database.'
SELECT
  to_char(t.submitted_at, 'MM-DD HH24:MI')  AS "when",
  t.status                                   AS "status",
  t.amount_minor                             AS "amount",
  t.currency                                 AS "ccy",
  src.masked_number                          AS "from",
  dst.masked_number                          AS "to",
  t.reference                                AS "reference",
  coalesce(t.rejection_reason, '-')          AS "refused because"
FROM transfers t
JOIN customer_accounts src ON src.id = t.source_account_id
JOIN customer_accounts dst ON dst.id = t.destination_account_id
ORDER BY t.submitted_at DESC
LIMIT 20;
SQL
fi

cat <<DONE

To poke around yourself:

  PGPASSWORD=$DB_PASS psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME

Inside psql:  \\dt  lists the tables,  \\d customers  describes one,  \\q  quits.
DONE
