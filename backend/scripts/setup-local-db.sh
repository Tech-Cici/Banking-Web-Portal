#!/usr/bin/env bash
#
# Creates the local development database for the Internet Banking API.
#
# Idempotent: safe to run again. It creates the role and database only if they are
# missing, and never touches either if they are already there — so it cannot destroy
# data you have registered.
#
# It does NOT create tables. Flyway owns the schema and applies the migrations when the
# API starts; a script that also created tables would be a second source of truth for
# the schema, which is how local and production drift apart.

set -euo pipefail

DB_NAME="${DB_NAME:-ibanking}"
DB_USER="${DB_USERNAME:-ibanking}"
DB_PASS="${DB_PASSWORD:-ibanking_local_only}"
DB_HOST="${DB_HOST:-localhost}"
# 5433 by default, matching docker-compose.yml and the dev profile. A PostgreSQL you
# installed yourself is usually on 5432, and this project deliberately stays off it.
DB_PORT="${DB_PORT:-5433}"

# ---------------------------------------------------------------- find psql

if ! command -v psql >/dev/null 2>&1; then
  # Postgres.app does not put its tools on PATH. Look where it installs them.
  for candidate in /Applications/Postgres.app/Contents/Versions/*/bin; do
    if [ -x "$candidate/psql" ]; then
      PATH="$candidate:$PATH"
      export PATH
      echo "Using psql from $candidate"
      break
    fi
  done
fi

if ! command -v psql >/dev/null 2>&1; then
  cat >&2 <<'MISSING'
psql was not found.

If you installed Postgres.app, open it once and click Initialize, then run this again.
If you installed PostgreSQL another way, make sure psql is on your PATH.
MISSING
  exit 1
fi

# ------------------------------------------------------- check it is running

# Connects as the current OS user, which is the superuser Postgres.app and Homebrew
# both create at install time. ADMIN_USER overrides it.
ADMIN_USER="${ADMIN_USER:-$(id -un)}"

# How the ADMINISTRATIVE connection is made. Three ways, tried in this order.
#
# -w everywhere, so psql never prompts on its own: the script makes several admin calls
# and each one would ask for the same password again — five prompts to create one role
# and one database. We settle the connection once, here, and reuse it.
#
# The UNIX SOCKET is tried before TCP, and that is the important part. A self-installed
# PostgreSQL very commonly requires a password over TCP while trusting connections over
# the local socket, because the socket already proves which OS user you are. So the
# owner of the machine can usually do admin work with no password at all — and being
# asked for one they never set, for a server that is plainly theirs, is a dead end.
ADMIN_ARGS=()

probe() {
  psql -w "${ADMIN_ARGS[@]}" -U "$ADMIN_USER" -d postgres -Atc 'select 1' 2>&1 >/dev/null
}

# 1. The local socket, no password.
ADMIN_ARGS=(-p "$DB_PORT")
connect_error=$(probe) || true

if [ -z "$connect_error" ]; then
  ADMIN_VIA="the local socket, with no password"
else
  # 2. TCP, no password.
  socket_error="$connect_error"
  ADMIN_ARGS=(-h "$DB_HOST" -p "$DB_PORT")
  ADMIN_VIA="$DB_HOST:$DB_PORT"
  connect_error=$(probe) || true

  # 3. TCP, asking once for a password.
  case "$connect_error" in
    *"no password supplied"*|*"password authentication failed"*)
      echo "PostgreSQL wants a password for '$ADMIN_USER' over TCP."
      case "$socket_error" in
        *"no password supplied"*|*"password authentication failed"*)
          echo "(The local socket asked for one too.)"
          ;;
      esac
      if [ -n "${ADMIN_PASSWORD:-}" ]; then
        PGPASSWORD="$ADMIN_PASSWORD"
      else
        # -s so it is not echoed, and read from the terminal rather than stdin so this
        # still works when the script itself is piped.
        printf 'Password for %s (blank to skip): ' "$ADMIN_USER" >&2
        IFS= read -rs PGPASSWORD < /dev/tty
        printf '\n' >&2
      fi
      if [ -n "$PGPASSWORD" ]; then
        export PGPASSWORD
        connect_error=$(probe) || true
      fi
      ;;
  esac
fi


if [ -n "$connect_error" ]; then
  # Two very different faults, and guessing between them wastes the reader's time: a
  # server that is not running needs Postgres.app opened, a missing role needs a role.
  # So report what psql actually said, then suggest by what it said.
  echo "Could not connect to PostgreSQL on $DB_HOST:$DB_PORT as '$ADMIN_USER'." >&2
  echo >&2
  echo "PostgreSQL said:" >&2
  echo "  $connect_error" >&2
  echo >&2

  case "$connect_error" in
    *"role"*"does not exist"*)
      cat >&2 <<HINT
The server is running, but it has no role named '$ADMIN_USER'.

Connect as whichever superuser does exist and re-run with that name, e.g.

  ADMIN_USER=postgres ./scripts/setup-local-db.sh
HINT
      ;;
    *"no password supplied"*|*"password authentication failed"*)
      cat >&2 <<HINT
'$ADMIN_USER' could not be authenticated, over the socket or over TCP.

A role's PostgreSQL password is not your macOS login password, and on a server you
installed yourself you may never have set one. You do not need to guess it. Two ways
forward, both using the fact that the server is yours:

  1. If another superuser exists, use it:

       ADMIN_USER=postgres ./scripts/setup-local-db.sh

  2. Set a password for '$ADMIN_USER' from the server itself. Postgres.app can open a
     psql session with no password at all — click its elephant icon, then the database
     named after you — and in that session:

       ALTER ROLE $ADMIN_USER PASSWORD 'pick-something';

     Then re-run this script and give it that password.

If you would rather not type it interactively:

   ADMIN_PASSWORD='your-password' ./scripts/setup-local-db.sh

(The leading space keeps it out of your shell history.)
HINT
      ;;
    *"could not connect"*|*"Connection refused"*|*"No such file"*)
      cat >&2 <<HINT
Nothing is listening on that port.

Open Postgres.app and check the server shows "Running". If you set it to a port other
than 5432, pass it: DB_PORT=<port> ./scripts/setup-local-db.sh
HINT
      ;;
  esac
  exit 1
fi

echo "Connected as '$ADMIN_USER' via ${ADMIN_VIA:-$DB_HOST:$DB_PORT}."

# --------------------------------------------------------------- role + db

role_exists=$(psql "${ADMIN_ARGS[@]}" -U "$ADMIN_USER" -d postgres -Atc \
  "select 1 from pg_roles where rolname = '$DB_USER'")

if [ "$role_exists" = "1" ]; then
  echo "Role '$DB_USER' already exists, leaving it alone."
else
  psql "${ADMIN_ARGS[@]}" -U "$ADMIN_USER" -d postgres -c \
    "CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASS';" >/dev/null
  echo "Created role '$DB_USER'."
fi

db_exists=$(psql "${ADMIN_ARGS[@]}" -U "$ADMIN_USER" -d postgres -Atc \
  "select 1 from pg_database where datname = '$DB_NAME'")

if [ "$db_exists" = "1" ]; then
  echo "Database '$DB_NAME' already exists, leaving it alone."
else
  # CREATE DATABASE cannot run inside a transaction block, hence its own -c.
  psql "${ADMIN_ARGS[@]}" -U "$ADMIN_USER" -d postgres -c \
    "CREATE DATABASE $DB_NAME OWNER $DB_USER ENCODING 'UTF8';" >/dev/null
  echo "Created database '$DB_NAME' owned by '$DB_USER'."
fi

# ------------------------------------------------ prove the app's credentials work

if PGPASSWORD="$DB_PASS" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
     -Atc 'select 1' >/dev/null 2>&1; then
  echo "Connected as '$DB_USER' — the API's credentials reach the database."

  # Does the password actually matter here? A deliberately wrong one that still gets in
  # means the server is on `trust` auth, and the check above proved connectivity rather
  # than the password. Worth saying out loud rather than leaving as a false reassurance:
  # on trust, ANY process on this machine can connect as any role without a password.
  if PGPASSWORD="definitely-not-the-password-$$" \
       psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
       -Atc 'select 1' >/dev/null 2>&1; then
    cat <<'TRUST'

  Note: this server accepts any password for that role, so it is configured with
  `trust` authentication. Fine for a local database on your own machine, and it is
  why the check above could not verify the password itself. It must not be how the
  bank's real database is configured — there, `scram-sha-256` in pg_hba.conf.
TRUST
  fi
else
  cat >&2 <<FAILED

The role and database exist, but signing in as '$DB_USER' failed.

The usual cause is a role created earlier with a different password. To reset it:

  psql -d postgres -c "ALTER ROLE $DB_USER PASSWORD '$DB_PASS';"
FAILED
  exit 1
fi

cat <<DONE

Done. Start the API with:

  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

Flyway creates the tables on that first start. To look at what is stored:

  PGPASSWORD=$DB_PASS psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c '\\dt'
DONE
