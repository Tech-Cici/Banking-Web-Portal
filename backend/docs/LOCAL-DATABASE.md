# Running the API against a real database

The API stores every applicant, customer, verification code, sign-in and outbound message
in PostgreSQL. Flyway owns the schema and applies the migrations at start-up; Hibernate's
`ddl-auto` is `none` in every environment, so nothing invents tables behind Flyway's back.

## Which profile, and why it matters

| Profile | Database | Survives a restart |
| --- | --- | --- |
| `dev` | PostgreSQL on `localhost:5433` | **Yes** |
| `postgres` | PostgreSQL from `DB_URL` / `DB_PASSWORD` | **Yes** |
| `h2` | H2, in memory | **No** — wiped when the API stops |

Use `dev` for anything you want to keep. `h2` starts with nothing installed, which is
useful for a quick look and for the browser verification walks — but every customer you
register on it disappears the next time the API restarts, so it is the wrong choice for
walking the onboarding flow over a few days.

## A note on the port

This project's PostgreSQL lives on **5433**, not the standard 5432.

A developer Mac very often already has a PostgreSQL on 5432 from another project. Putting
this one there means the container refuses to start, and the obvious workaround — stop the
other server — makes setting up this project interfere with someone else's work. Neither
is acceptable, and a different port costs nothing.

5433 is the default in three places, and they must agree: `docker-compose.yml`, the `dev`
profile's `DB_URL`, and `scripts/setup-local-db.sh`. Override all three together with
`DB_HOST_PORT`, `DB_URL` and `DB_PORT` if 5433 is taken too.

## Setting it up with Docker

```sh
docker compose up -d db          # from the repository root; publishes 5433
cd backend
./mvnw spring-boot:run           # profiles.default is dev, so no flag needed
```

Install Docker Desktop from <https://www.docker.com/products/docker-desktop/> if you do
not have it. On a Mac that is tight on memory, **OrbStack** (<https://orbstack.dev>) is a
drop-in replacement that is considerably lighter — `docker compose` commands work
unchanged.

Two things about `docker-compose.yml` worth knowing, because both are silent failures if
they are ever "tidied up":

- **The volume is mounted at `/var/lib/postgresql`, not `/var/lib/postgresql/data`.**
  PostgreSQL 18 moved `PGDATA` and moved the image's declared `VOLUME` up a level. Mount
  the old `/data` path and Docker quietly creates an anonymous volume over the parent,
  the database writes there, and the named volume stays empty. Nothing errors — it looks
  like it is working until the container is recreated and the data is gone.
- **The Compose project name is pinned to `ibanking`.** By default Compose names it after
  the containing folder, so the volume would be `bankingwebportal_ibanking-db-data` and
  the data would belong to the folder's *name*. Rename or move the checkout and Compose
  looks for a volume that no longer exists, starts empty, and every registered customer
  appears to have vanished.

Useful commands:

```sh
docker compose ps                # is it healthy?
docker compose logs -f db        # what PostgreSQL is saying
docker compose stop db           # stop it, keep the data
docker compose down              # remove the container, keep the data
docker compose down -v           # remove the data too — this is the destructive one
```

## Without Docker

Postgres.app is the lightest way to get a real PostgreSQL on a Mac:

1. Download it from <https://postgresapp.com>.
2. Drag it to Applications and open it.
3. Click **Initialize**. The menu-bar elephant should say _Running_.
4. From the `backend` directory: `./scripts/setup-local-db.sh`

That creates the `ibanking` role and database the `dev` profile expects. It is idempotent
— run it again any time; it leaves an existing role or database alone, so it cannot
destroy data you have already registered. It deliberately does **not** create tables:
Flyway does that, and a script that also created them would be a second source of truth
for the schema.

Homebrew works just as well if you have it:

```sh
brew install postgresql@17 && brew services start postgresql@17
./scripts/setup-local-db.sh
```

### If your PostgreSQL asks for a password

Fine — the script asks once and reuses it for the rest of the run. To skip the prompt:

```sh
 ADMIN_PASSWORD='your-password' ./scripts/setup-local-db.sh
```

The leading space keeps it out of your shell history in zsh and bash.

`ADMIN_USER` defaults to your macOS username, which is the superuser Postgres.app and
Homebrew both create. Override it if your superuser is named something else:
`ADMIN_USER=postgres ./scripts/setup-local-db.sh`

### If the script cannot connect

It prints what PostgreSQL actually said, then a suggestion based on it. The two common
cases:

- **`role "<you>" does not exist`** — the server is running but has no superuser under
  your macOS username. Re-run naming one that exists:
  `ADMIN_USER=postgres ./scripts/setup-local-db.sh`
- **`Connection refused`** — nothing is listening. Open Postgres.app, or pass the port
  you configured: `DB_PORT=5433 ./scripts/setup-local-db.sh`

## Starting up

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The first start applies both migrations. You should see:

```
Migrating schema "public" to version "1 - onboarding"
Migrating schema "public" to version "2 - login challenges"
```

and on every start after that, `Schema "public" is up to date. No migration necessary.`

## Looking at what is stored

```sh
cd backend
./scripts/show-bank.sh          # who registered, who has an account, what was sent
./scripts/show-bank.sh codes    # also: which verification codes are still live
```

Read-only — every statement in it is a SELECT, so run it as often as you like while
walking the flow. It never prints password hashes: there is nothing useful in one, and a
hash on a terminal is a hash in a screenshot.

### By hand

```sh
PGPASSWORD=ibanking_local_only psql -h localhost -U ibanking -d ibanking
```

```sql
\dt                                   -- the seven tables, plus flyway_schema_history
select customer_number, full_name, status, must_change_password,
       created_by, approved_by from customers;
select reference, status, email, email_verified from applications;
select kind, "to", subject, sent_at from outbox order by sent_at desc limit 5;
```

Two things you will not find, by design: passwords are 60-character BCrypt hashes, and
verification codes are 64-character SHA-256 digests. Neither can be read back out of the
row, and no endpoint returns either. When `MAIL_ENABLED=false`, the code is readable in
`outbox` — which is the development stand-in for an inbox, and the reason that table
exists.

## Starting over

With Docker:

```sh
docker compose down -v && docker compose up -d db
```

Without:

```sh
psql -h localhost -d postgres -c 'DROP DATABASE ibanking;'
./scripts/setup-local-db.sh
```

Flyway rebuilds the schema on the next start. There is no "clear the data" endpoint on
the real service, deliberately — the mock has one, the bank's API must not.
