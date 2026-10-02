# Deploying the demonstration instance

Frontend on **Vercel**, database on **Neon**, API on **Render**. Vercel cannot host the API:
it is a Spring Boot service with a connection pool, Flyway migrations and sessions in
Postgres, and Vercel runs static sites and short-lived functions.

This describes a **demonstration** deployment. It is not production — see
`frontend/docs/OPEN-ITEMS.md` for what the bank has not supplied and which features have no
backend at all.

---

## 1. Neon — the database

Create a project and a database. Take the **connection string**, and prefer the one Neon
labels *direct* rather than *pooled*: the API runs its own HikariCP pool, and stacking it on
top of Neon's pgBouncer gives two poolers fighting over the same connections. If you only
have the pooled string, append `?prepareThreshold=0` so prepared statements are not cached
across a transaction-mode pooler.

Neon requires TLS and will refuse a plaintext connection, so keep `sslmode=require`.

Split the string into the three variables the API expects:

```
DB_URL       jdbc:postgresql://<host>/<database>?sslmode=require
DB_USERNAME  <role>
DB_PASSWORD  <password>
```

Note the `jdbc:` prefix — Neon gives you a `postgresql://user:pass@host/db` URL, which is
not a JDBC URL. Move the credentials out of it into the two variables.

**Neon scales to zero when idle**, and a compute that has slept drops its connections. The
`demo` profile already retires pooled connections before that happens
(`max-lifetime: 300000`, `keepalive-time: 60000`), so the first request after an idle period
reconnects rather than handing out a dead socket. Expect that request to be slow.

Flyway runs all 21 migrations on first start, including `V21__spring_session.sql` — so
**sessions live in this database too**. Nothing else to configure.

---

## 2. Render — the API

Deploys from `backend/Dockerfile`. `render.yaml` in the repository root declares the service;
point Render at the repository and it will read it.

Set these in Render's environment, as **secret** values where marked:

| Variable | Value | Secret |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo` | no |
| `DB_URL` | from Neon, above | no |
| `DB_USERNAME` | from Neon | no |
| `DB_PASSWORD` | from Neon | **yes** |
| `CORS_ALLOWED_ORIGINS` | the exact Vercel origin, e.g. `https://zigama-portal.vercel.app` — scheme and host, no trailing slash | no |
| `SESSION_COOKIE_SAME_SITE` | `none` — see below | no |
| `BOOTSTRAP_ADMIN_EMAIL` | the administrator's address | no |
| `BOOTSTRAP_ADMIN_PASSWORD` | 12+ chars, mixed case, a digit | **yes** |
| `BOOTSTRAP_MANAGER_EMAIL` | the manager's address, **different** from the admin | no |
| `BOOTSTRAP_MANAGER_PASSWORD` | 12+ chars | **yes** |
| `MAIL_ENABLED` | `true` if you have a Google App Password, otherwise `false` | no |
| `MAIL_HOST` / `MAIL_PORT` | `smtp.gmail.com` / `587` | no |
| `MAIL_USERNAME` | the sending address | no |
| `MAIL_PASSWORD` | a Google **App Password**, not the account password | **yes** |

### Why `SESSION_COOKIE_SAME_SITE=none` here, and what it costs

A frontend on `*.vercel.app` calling an API on `*.onrender.com` is **cross-site**. A
`SameSite=Strict` cookie is simply not sent on those requests, which presents as signing in
and being immediately signed out again.

`none` fixes that and gives up the CSRF protection SameSite buys. The service still has its
own CSRF token, which then becomes the only layer rather than the second one.

**For anything beyond a demo, do not do this.** Put both behind one registrable domain —
`portal.zigama.rw` on Vercel and `api.zigama.rw` on Render — and leave the value at
`strict`. Subdomains of one domain are the same site, so nothing is weakened and no
configuration changes.

### The first staff logins

`StaffSeeder`'s development logins (`admin@zigama.local` / `ZigamaStaff1`) **do not exist
here** — that class is `@Profile({"dev","h2","test"})` and its password is published in this
repository. `StaffBootstrap` creates the first two from the four `BOOTSTRAP_*` variables
instead, and only while the `staff` table is empty.

It refuses, and logs why, if a password is missing, too weak, or if both addresses are the
same. If you see `NO STAFF LOGINS EXIST AND NONE CAN BE CREATED` in the logs, the variables
are not set and nobody can sign into the staff portal.

**One person setting both passwords holds both roles**, which defeats the four-eyes rule the
portal is built around. Hand them to two different people and have each change their own.

---

## 3. Vercel — the frontend

Root directory `frontend`. `vercel.json` sets the build, the SPA rewrite and the security
headers.

| Variable | Value |
|---|---|
| `VITE_API_BASE_URL` | the Render URL plus `/api/v1`, e.g. `https://zigama-api.onrender.com/api/v1` |
| `VITE_API_TIMEOUT_MS` | `30000` |
| `VITE_ENABLE_MOCK_API` | `false` — the build refuses `true` and the app will not boot |
| `VITE_APP_VERSION` | anything; shown in the footer for support |
| `VITE_ENABLE_DEV_TOOLS` | `true` for this demo — see below |

Leave `VITE_LIVE_API` and `VITE_LIVE_REGISTRATION` **unset**. They only matter when mocks
are on, and the app refuses to boot if either is present in a production build.

**None of these are secret.** Every `VITE_` variable is inlined into the JavaScript and is
readable by anyone who opens the bundle. Never put a credential in one.

### `VITE_ENABLE_DEV_TOOLS=true`

Includes the staff **Developer tools** page: the message log and the start-over button.

The demo needs the log, because it is the only way to read a verification code or a
temporary password if mail is ever off. Setting the flag to anything else removes the page,
its route and the reset client from the bundle entirely — verified by building both ways and
grepping `dist` for the endpoint path, which is how we found that gating it on the build mode
alone did **not** work.

---

## 4. In this order

1. **Neon** first — the API will not start without `DB_URL`.
2. **Render** next, with ALL of the variables above set, `CORS_ALLOWED_ORIGINS` included.
   You do not know the Vercel origin yet, so put in the one you intend to end up with —
   `https://<the project name you will use>.vercel.app` — and correct it at step 4.

   It has to be set now because the API **refuses to start** without it, by design: the
   alternative is a service that reports itself healthy while rejecting every request a
   browser makes, with nothing in its log saying why. The refusal names the variable.

   Watch the logs for `BOOTSTRAPPED THE FIRST TWO STAFF LOGINS` and for Flyway applying 21
   migrations.
3. **Vercel**, with `VITE_API_BASE_URL` pointing at the Render hostname from step 2.
4. **Back to Render**: correct `CORS_ALLOWED_ORIGINS` to the origin Vercel actually gave
   you, if it differs from your guess. Changing it redeploys.

If the API will not start, read the first error in the log rather than the last: a missing
variable is reported by name, in a list, before anything else is attempted.

## 5. First run

1. Register on the public site.
2. Read the verification code — from your inbox if `MAIL_ENABLED=true`, otherwise from
   `/staff/developer-tools` → **Sent messages**, signed in as either bootstrap login.
3. Sign into `/staff` as the **administrator** to create the account, which issues a
   temporary password (same two places to read it).
4. Sign in as the **manager** to approve it.
5. Sign into the customer portal with the customer number and the temporary password, and
   change it when asked.

That chain is the thing worth demonstrating, and there is no shortcut through it: there are
no seeded customers, deliberately.

## What will not work, by design

Loans, bill payments, foreign exchange, standing orders, statement downloads and the card
list have **no backend**. With mocks off they will error rather than show fabricated data.
Standing orders, loans and statements currently still mint a reference and report success
through the mock layer — with mocks off that path is gone, but those screens are not honest
about being unavailable the way external transfers are. Worth fixing before anyone outside
your team uses this.
