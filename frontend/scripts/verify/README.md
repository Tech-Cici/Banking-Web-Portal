# Live verification walks

Two scripts that drive a real browser against the **real** Spring Boot service, to check
the things a unit test cannot: that the portal and the backend agree, and that the guards
hold when somebody types a URL instead of following the buttons.

They are not part of `npm run verify`, because they need two servers running. Run them
when auth or onboarding changes.

## What they check

`onboarding-chain.mjs` — the whole path, one customer, start to finish:

1. Register in the browser (account number, national ID, date of birth, phone, email).
2. Read the verification code from `GET /api/v1/admin/outbox`, the only place it exists.
3. Confirm the terms and submit.
4. An **administrator** creates the account; a **manager** approves it — two different
   staff credentials, because one person cannot do both.
5. Sign in with the temporary password, get forced to the change-password screen, replace
   it, land on the dashboard.

It also asserts the name on the application is the one the **bank** holds, not a value
that arrived in the request.

`session-guards.mjs` — what the server refuses:

- Typing `/dashboard` while still on the temporary password.
- Reaching `/dashboard` after signing out, with the same cookie.
- A settled password producing a one-time code rather than a session.
- The delivery hint being masked.

## Running them

```sh
# 1. the API. Either profile seeds the two dev staff logins.
#    `h2` is in-memory and wiped on restart, which is what you want here: each run
#    starts from nothing, so the walks cannot pass by reusing an earlier run's data.
cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=h2

#    For manual testing you want the data to survive instead:
#        docker compose up -d db
#        ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 2. the portal, with the onboarding endpoints pointed at it
#    (VITE_LIVE_API=registration,auth,session in .env.development)
npm run dev

# 3. the walks
npm i -D playwright     # not a project dependency; only these scripts use it
npm run verify:live
```

Screenshots of every step land in `walk-output/`. Read them when a line says `!!` — the
picture usually shows the reason faster than the log does.

Each run registers a fresh `walk…@example.rw` / `guard…@example.rw` address, so they can
be run repeatedly without clearing the database.

## Why the codes come from the outbox

With `MAIL_ENABLED=false` the service records every message it would have sent instead of
sending it, and `GET /api/v1/admin/outbox` (staff credentials required) returns them.
Nothing logs a code and no endpoint returns one, which is deliberate — so these scripts
read it the same way a member of staff would, rather than through a test-only back door
that would have to exist in the running service.
