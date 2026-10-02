#!/usr/bin/env bash
#
# Deletes every customer, application, sign-in record and message from the local database.
#
#   ./scripts/clear-bank.sh              asks before doing anything
#   ./scripts/clear-bank.sh --yes        no prompt, for when you are looping
#   ./scripts/clear-bank.sh --staff      ALSO delete the staff logins (see below)
#
# STAFF LOGINS ARE KEPT by default, and that is not politeness — deleting them locks you
# out of the staff portal, and they are recreated only by the migration that seeded them,
# which will not run again on a database that already has it applied. If you do pass
# --staff, recreate them with:  ./mvnw flyway:clean flyway:migrate   (or drop the database
# and let the app migrate from scratch).
#
# WHAT THIS IS NOT FOR. It talks to whatever DB_* says, so pointing it at anything other
# than a local development database would empty that instead. There is no safeguard in a
# shell script that can prevent that; the safeguard is that production credentials should
# never be in your shell. The application's own /admin/dev/reset endpoint is the safer
# route where it is available, because the backend refuses to create it outside the dev,
# h2 and test profiles.
#
# The order matters: children before parents, or foreign keys refuse the delete. It is the
# same order as DevResetController, deliberately — two ways of doing this that disagree
# about the order is how one of them starts failing silently.
#
# THIS SCRIPT WAS BROKEN and is worth noting rather than quietly fixing. It still named
# linked_accounts and account_opening_requests, two tables migration V8 dropped when the
# account model was corrected, so the count query above failed and the script exited
# before deleting anything — while reporting an error that looked like a connection
# problem. A script that names tables by hand goes stale the moment a migration runs; the
# only defence is that this list is checked whenever one does.
#
# account_transactions goes first: it has a foreign key to customer_accounts, and the
# cascade on it means the wrong order would take a bank's whole ledger out as a side
# effect of deleting an account rather than as a decision.

set -euo pipefail

DB_NAME="${DB_NAME:-ibanking}"
DB_USER="${DB_USERNAME:-ibanking}"
DB_PASS="${DB_PASSWORD:-ibanking_local_only}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5433}"

CONFIRMED=no
WITH_STAFF=no

for arg in "$@"; do
  case "$arg" in
    --yes|-y)  CONFIRMED=yes ;;
    --staff)   WITH_STAFF=yes ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Usage: $0 [--yes] [--staff]" >&2
      exit 2
      ;;
  esac
done

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

# ------------------------------------------------------- say what will go

echo "Database: $DB_NAME on $DB_HOST:$DB_PORT (as $DB_USER)"
echo
echo "About to delete:"
run -Atc "
  SELECT '  ' || n || ' ' || label
  FROM (
    SELECT (SELECT count(*) FROM customers)        AS n, 'customers'           AS label
    UNION ALL SELECT (SELECT count(*) FROM applications),        'applications'
    UNION ALL SELECT (SELECT count(*) FROM outbox),             'recorded messages'
    UNION ALL SELECT (SELECT count(*) FROM email_verifications),'verification codes'
    UNION ALL SELECT (SELECT count(*) FROM login_challenges),   'login challenges'
    UNION ALL SELECT (SELECT count(*) FROM sign_ins),           'sign-in records'
    UNION ALL SELECT (SELECT count(*) FROM customer_accounts),  'customer accounts'
    UNION ALL SELECT (SELECT count(*) FROM account_transactions),'ledger entries'
  ) t
  ORDER BY label;
"

if [ "$WITH_STAFF" = yes ]; then
  echo
  echo "  ...AND the staff logins, which will lock you out of the staff portal:"
  run -Atc "SELECT '    ' || email || ' (' || role || ')' FROM staff ORDER BY email;"
fi

if [ "$CONFIRMED" != yes ]; then
  echo
  printf 'Type the database name (%s) to confirm: ' "$DB_NAME"
  read -r answer
  if [ "$answer" != "$DB_NAME" ]; then
    echo "Not confirmed. Nothing was deleted."
    exit 1
  fi
fi

# ------------------------------------------------------------------ delete

# One transaction. A half-finished wipe leaves rows whose parents are gone, which is a
# worse state to debug than either a full bank or an empty one.
STAFF_DELETE=""
if [ "$WITH_STAFF" = yes ]; then
  STAFF_DELETE="DELETE FROM staff;"
fi

run -v ON_ERROR_STOP=1 <<SQL
BEGIN;
DELETE FROM login_challenges;
DELETE FROM sign_ins;
DELETE FROM account_transactions;
DELETE FROM customer_accounts;
DELETE FROM customers;
DELETE FROM applications;
DELETE FROM email_verifications;
DELETE FROM outbox;
$STAFF_DELETE
COMMIT;
SQL

echo
echo "Done. Check with: ./scripts/show-bank.sh"
