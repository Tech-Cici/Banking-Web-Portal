# Open items

Things this portal does not do yet, and the decisions still owed by the bank.

These used to be printed on the screens themselves, as banners reading **THIS IS NOT
DEFINED IN THE BLUEPRINT**. That was the wrong place for them. A customer reading "the
upload endpoint and CSV column contract still need to be specified" learns nothing they
can act on, and it tells anyone looking at the site which parts of a bank's software are
unfinished — which is not information a bank volunteers.

The screens now say only what a user needs in order to decide what to do
("Downloads are not available yet"). The reasoning, and everything that is genuinely
waiting on someone, lives here.

---

## The palette is Zigama's own green now, and one of the two cannot carry text

Both greens are sampled from the bank's logo: **#36a148** is 83% of the mark and
**#184d28** is the shadow behind the Z. They replace `#0a5a2e` / `#07421f`, which were an
accessible green invented as a stand-in and were a cooler, bluer hue than the real one —
the hue was wrong, not the darkness.

### The constraint, because it shapes every decision below

`#36a148` is **3.31:1** against white. WCAG AA asks 4.5:1 of body text, so the brand green
cannot carry white text and cannot be a green label on a white page. Using it for
`--color-primary` would have failed contrast on the header, the sidebar, every primary
button and the sign-in field — which is to say on every screen in the product.

So the mark supplies two greens doing two jobs:

| token | value | job | measured |
|---|---|---|---|
| `--color-primary` | `#1f5c29` | header, buttons, green text on white | 8.01:1 on white |
| `--color-primary-hover` | `#184d28` | hover, and the field's dark end | 9.85:1 on white |
| `--color-sidebar` | `#184d28` | the side navigation | 9.85:1 white, 8.64:1 `#eaf2ec` |
| `--color-brand` | `#36a148` | **non-text only** | 3.31:1 — clears the 3:1 bar and nothing more |

`#1f5c29` is the lightest green the gradient's light end can hold: it sits under the text
panel and takes a 16% white wash, which composites to `#43764b` where the panel text is
4.68:1. The brand green in the same position composites to `#56b065` at 2.36:1.

### Where the brand green actually appears

The decorative pattern on the sign-in and landing fields — the arc, the spot and the dot
grid. Those carry no text and convey nothing, so WCAG puts no contrast floor on them, and
it is the corner the bank's own reference design put a pattern in. That is what stops the
change being a hue correction nobody can see.

It is **not** the selected nav row's marker, which stays white. `#36a148` on the sidebar is
**2.98:1**, two hundredths under the 3:1 a non-text indicator needs.

### Two figures with little margin

- **The selected nav row**: white on the 28% white overlay is **4.52:1** computed, 4.59:1 as
  rendered. It was 5.11:1 on the old darker sidebar. Every opacity from 18% to 28% passes,
  so 28% is kept for the strongest surface cue — but if the sidebar is ever lightened
  again, this is the value that fails first, and the answer is to lower the opacity.
- **The gradient's wash ceiling** is still 16%, and is now a lower ceiling than it was: 20%
  puts the panel text at 4.21:1.

### Success can no longer be told apart by colour

`--color-success` (`#15803d`) is 1.52:1 against the brand green and 1.60:1 against
`--color-primary` — all three are within a hair of each other in lightness. There is no
version of this where a green brand leaves success a distinct green, so the tick and the
word are not a belt-and-braces nicety, they are the whole signal. The old comment claiming
success was "a brighter, lighter green than the brand's" has been corrected.

### Still owed by the bank

The values came from the logo, not from a brand sheet. A real one would specify the
text-safe dark rather than leaving it to be derived, give the tint ramps, and name the
typeface — `src/config/brand.ts` still carries only the words "Zigama CSS", and the stand-in
logo entry below still applies.

### How the figures were checked

`e2e/palette-measure.mjs` reads them back from rendered pixels. Two things it had to learn:
subpixel antialiasing paints pale-yellow fringes along glyph edges, which a naive sampler
reports as the background (every screen came out at ~1.07:1 until `--disable-lcd-text` went
in); and the lightest pixel in an element's bounding box is the page showing through a
rounded pill's corners, so a flat fill is read from computed style and only a gradient from
pixels. 39 pairings, all passing.

## Saved payees are checked by a member of staff — and still cannot be paid from any screen

A payee is saved `PENDING_VERIFICATION` and is not payable until bank staff compare the
name the customer typed with the name the bank holds for that account. Migration **V17**
records the design; `BeneficiaryApprovalTest` and `BeneficiaryNameCheckTest` walk it.

### What was there before, because the shape of the bug matters

Payees existed only in the browser's mock. Adding one created a `PENDING_VERIFICATION` row
and **nothing anywhere ever moved it on** — no screen, no endpoint, no timer. There was no
backend beneficiary at all. The customer was told the payee was "in its cooling-off
period", which names a clock that did not exist, so a payee stayed unusable permanently and
the only action available on it was Remove.

The copy also described two different controls and implemented neither: "pending
verification" says a party checks it, "cooling-off period" says time passes. Every screen
now says what actually happens, which is that a person looks at it.

### Either staff role may approve, and that does not weaken four-eyes

`ADMIN` and `MANAGER` are deliberately disjoint for ACCOUNT creation — an admin creates, a
manager releases, so issuing a credential takes two people. A payee is different: the
**maker is the customer** and bank staff are the checker, so the separation holds whoever
clears it. It is also volume work, and queueing it behind the one role that releases money
would mean customers waiting on a manager to do a name comparison.

### The review is a comparison, and only sometimes possible

The queue shows the name the customer typed beside the name the bank holds, with one of four
verdicts. `UNAVAILABLE` is **not** a mismatch and the screen never renders them alike: a
payee at another bank, abroad, or on a wallet is held by an institution this service cannot
ask, and three of the four payee types are in that position. A blank where a comparison
belongs reads as a pass.

Nothing is decided automatically. A perfect name match does not clear a payee, because
somebody adding a stranger's account usually knows that stranger's name — it is on whatever
they were sent.

### THE GAP THE BANK SHOULD DECIDE ON: no screen can pay a saved payee

**This is the honest limit of the feature as it stands.** The live send-money screen
(`SendMoneyPage`, serving `/transfers/own` and `/transfers/internal`) takes either one of
the customer's own accounts or an account number they **type**. It does not list saved
payees. `TransferPage`, which does have a payee dropdown, is exported but **routed
nowhere** — it speaks a quote-and-fee API the service never grew.

So a customer can save a payee, staff can approve it, the customer is emailed that they can
now pay it, and then there is nowhere in the portal to do so. The only path is the API:
`POST /transfers` now accepts `beneficiaryId`, and `BeneficiaryService.destinationFor` is
where ownership and approval are enforced.

Two ways out, and it is a product decision:

- **Wire `SendMoneyPage` to offer approved payees** as a third destination mode beside "one
  of my accounts" and "an account number". This is what the approval email currently
  implies, so it is the option that makes the existing wording true.
- **Keep payees as an address book** the customer copies a number out of, and stop
  promising a payee list on the transfer screen. Cheaper, and it makes the staff check
  almost pointless — the customer could type any number anyway.

Until one is chosen, `TransferPage` is dead code carrying a dropdown nobody sees. Its
status filter and `TransferPage.test.tsx` were fixed and are worth keeping for whichever
option wins, but they guard a screen that is not currently reachable.

### Smaller things owed

- **The per-row holder lookup** in `BeneficiaryService.review` is one indexed read per
  pending payee. The queue is capped at ten pending per customer, so this is a handful of
  reads; if it ever becomes long, the fix is one query joining accounts, not caching a name
  whose whole value is being current.
- **One payee per customer per destination** is enforced in the service, not by a partial
  unique index, for the reason V15 gives: H2 has no filtered indexes and a constraint that
  exists in production but not in the tests is worse than one in neither.
- **The mock's holder lookup matches on the mask**, because the mock account store keeps no
  full numbers. Masks are not unique, so a demo could show the wrong holder. The real queue
  is computed from the number.
- **Adding a payee is not rate limited by time**, only capped at ten pending and fifty saved
  per customer. A public deployment should also throttle it.

## Forgotten passwords go through a manager, not a reset link

"Forgot password?" used to be a page saying the service was not available online. It now
takes the request, puts it in a queue in the staff portal, and a **manager** re-issues a
temporary password through exactly the path used at approval — emailed, expiring, and
refused for everything except the change-password screen until it has been replaced.

**There is deliberately no self-service reset link.** A link in an email is a second
credential-bearing path into every account at the bank, with its own token store, expiry
and single-use rules. This flow adds no new way to obtain a credential at all: it adds a
queue in front of the one that already exists and is already tested, and keeps a human in
the loop for an action whose purpose is to hand somebody else's password out.

What that costs, stated plainly so the bank can decide whether to pay it:

- **A reset is not instant.** It waits for a manager. The customer's screen says so and
  gives no timescale, because the bank has not set one — **a service level for this queue
  is owed**. If managers do not work it promptly, customers will telephone anyway and the
  feature will have moved the call rather than removed it.
- **There is no identity check in the software, and there cannot be.** Anybody can type an
  address into the public form. The manager's screen says this and tells them to confirm
  the customer the way the bank confirms a caller — **the bank's own identity-verification
  procedure for this is owed**, and it is the only control in the flow.
- **The public endpoint is rate limited per customer, not per address.** An unknown address
  writes nothing and sends nothing, so hammering it with guesses achieves only load — but a
  public deployment should still throttle `POST /api/v1/auth/password/forgot` by address and
  by IP at the gateway. That is not done in the application.
- **The staff overview does not count the queue.** A manager has to open the page to find
  out whether anybody is waiting. Adding it to `/admin/summary` is small and is not done.

What is already enforced, so that nobody re-litigates it by accident: the endpoint answers
identically for an address that banks here and one that does not; nothing is written for an
address that is not a customer's; asking creates no credential and invalidates none, so a
stranger cannot lock somebody out; issuing one revokes the customer's trusted browsers; and
only a manager can issue or refuse. `PasswordResetTest` covers all of these, and
`ForgotPasswordPage.test.tsx` covers the wording, because on that screen the wording *is*
the control.

---

## The sign-in card's submit button is 40px, not 44px

The design the bank supplied puts the submit control inside the password field, as a
round arrow button. It is 40px across. The blueprint asks for 44px touch targets, and
WCAG 2.5.8 asks for 24px, so this clears the standard and misses the house rule by 4px —
a 44px circle inside a 52px pill leaves 4px of field above and below it and reads as a
button jammed into a slot.

It is not the only way to submit: pressing Enter in either field still signs in, so
nobody is locked out by the size of the circle. If the bank would rather keep 44px
everywhere, the fix is to go back to the full-width button under the fields and give up
that part of the design.

The same screen also drops the hint under the email field, which said the address was the
one the temporary password was emailed to. That is true on a customer's first sign-in and
wrong on every one after it.

---

## What now runs against the real backend

Registration (personal **and** company), sign-in, the session, staff onboarding,
corporate account creation, the customer's accounts and their balances and statements are
answered by the Spring Boot service. Everything else —
transfers, payments, cards, loans, statement downloads, approvals, batches — is still
mocked, and is unaffected by this.

The switch is `VITE_LIVE_API` in `.env.development`, a comma-separated list of API path
prefixes to leave out of the mock worker. MSW passes through anything it has no handler
for, so omitting a handler is what sends a call to the real service. It replaced a single
`VITE_LIVE_REGISTRATION` boolean, which could only move all of registration or none of
it — useless once the backend also implemented sign-in.

The chain verified end to end in a real browser, against the real service:

register → verify the email → submit → **an administrator** creates the account → **a
manager** approves it → sign in with the temporary password → forced password change →
dashboard. Then: sign out, sign in again with the chosen password, receive a one-time
code, complete. See `scripts/verify/README.md`.

Three things that a screen-level check would have hidden, found by making the server
answer:

- **A session on a temporary password could have reached every future banking endpoint.**
  The catch-all rule was `authenticated()`, and such a session *is* authenticated. Nothing
  was exposed, because no banking endpoint exists yet — but the first one to land would
  have been open to a password bank staff had just read off a screen. It is now
  `hasRole('CUSTOMER')`, and the temporary-password session holds `MUST_CHANGE_PASSWORD`
  *instead of* that role.
- **CSRF was enabled but impossible to satisfy.** The client sends `credentials: 'include'`
  and never sent a token, and the server wrote the raw token to the cookie while expecting
  a masked one in the header. Both ends are fixed; see `SpaCsrf` and `src/services/csrf.ts`.
- **A mocked endpoint was returning 401 to a genuinely signed-in customer.** With the
  session live, the mock had no session of its own, so three dashboard widgets reported
  "signed out" and tore down a real session a second after sign-in. A mock must not answer
  an authentication question it is not being asked; it now scopes to a stand-in that
  matches no fixtures, so those screens show empty rather than fabricated data.

### Where the data goes

Real tables in a real database, via Flyway migrations — `applications`, `customers`,
`customer_accounts`, `account_transactions`, `email_verifications`, `login_challenges`,
`sign_ins`, `outbox`, `staff`, `companies`, `corporate_memberships`. Hibernate's
`ddl-auto` is `none` in every environment, so the schema is owned by the migrations and
never invented at start-up; a schema Hibernate guesses locally is a schema that differs
from production.

**Which database depends on the profile, and one of them forgets everything.**

| Profile | Database | Survives a restart |
|---|---|---|
| `dev` | PostgreSQL, `docker compose up -d db` | Yes |
| `postgres` | PostgreSQL, from `DB_URL` / `DB_PASSWORD` | Yes |
| `h2` | H2, **in memory** | **No** — wiped every time the API stops |

Verified against PostgreSQL 16: both migrations apply, a registered-and-approved customer
is still there after a full restart, the password is stored as a 60-character BCrypt hash
and the verification code as a 64-character SHA-256 digest. Neither is recoverable from
the row.

Use `dev` for anything you want to keep. `h2` is for a quick start with no Docker, and for
the verification walks, where starting from nothing every time is the point.

### Company registration: implemented, and what is still owed

`/registration/business/start` and `/registration/business/complete` are real now —
controller, service, entities, migration V10, and `BusinessRegistrationTest` walking the
flow against the database. A company application reaches the staff queue with its
signatories and its promised document names, and an administrator sees the company,
registration number, TIN, sector, address, contact and signatory list.

**What it replaced is worth keeping on the record.** The five-step wizard was answered
end to end by MSW. The applicant reached a page reading *"Your application has been
received. The bank will review it and contact your named representative"*, with a
reference number, and nothing had been stored anywhere; staff opened Registrations and saw
no such application. A false receipt is worse than an error — an error sends somebody to a
branch, a receipt sends them home to wait for a call that was never going to come. Any
company application taken before this was built was not received and cannot be recovered.

It mirrors personal registration rather than being a second implementation: same
verification table, same rate limit, same attempt counter, same `/personal/verify`
endpoint. The contact's email is the one verified and the one stored on the application,
because that is the address the bank corresponds with; the company's own inbox is a
separate field and frequently a shared one.

**Do not list `registration/business` in `VITE_LIVE_API`.** Its mock is deleted, so MSW
passes the request through on its own — it is live by virtue of having no handler. Listing
it now fails at start-up, and the error says so.

#### Four decisions the bank still owes, and what the code does meanwhile

| Question | What happens until it is answered |
|---|---|
| Which documents must a company supply? | The wizard asks for at least one file and sends only its NAME. `business_documents.received` is false on every row, and the staff screen prints "not received, ask the applicant for it" beside each one. There is no upload endpoint and no storage. |
| Who may apply on a company's behalf? | Anyone who can read the contact address they typed. Nothing checks that they are a director, a signatory, or connected to the company at all. |
| How are signatories verified, and which of them get access? | They are stored in the order listed, unverified, and **being listed grants nothing**. `BusinessSignatoryEntity` says so; the named CONTACT on the application becomes the company's first administrator, and no other signatory becomes anything. |
| Does a corporate mandate need more than one manager? | Not expressed. One administrator can prepare and approve the company's payments, because they are the only member the bank has admitted — see below. |

#### Corporate account creation: implemented, and where it stops

Migration **V11** adds `companies`, `corporate_memberships` and `customer_accounts.company_id`.
An administrator opening a company application now gets the same accounts form a personal
one gets, and pressing the button admits the company, enters the accounts **against the
company**, creates a login for the named contact, and grants that contact an `ADMIN`
membership. `CorporateAccountTest` walks it end to end against the database.

**The accounts belong to the company, and that is the decision the whole schema exists to
express.** A company's money is not the finance director's money. Hung off a person,
removing that person's access would orphan the accounts, and a second signatory would need
their own copy of every account — two records of one balance. So visibility runs through
membership: somebody sees a company's accounts because a live membership says they may, and
stops the moment it is revoked, with nothing about the accounts changing. `customer_id`
on a company account records who entered it and grants **nothing**.

That rule lives in exactly one method, `AccountAccess.mayAct`, which both the account
reads and the ledger call before anything is shown or moved. It was two inline checks in
two files, which was fine while "is it yours" was one comparison; two copies of a
two-part rule is how the screen and the payment end up disagreeing about whose money it
is, and the dangerous direction of that disagreement is the one where the payment is more
permissive.

**What is still owed, and is not faked meanwhile:**

- **Extra signatories.** There is no way to add a second person to a company. The schema
  holds many memberships per company and the code creates exactly one. The other
  signatories on the application get nothing, and the staff screen says so in as many
  words, because the alternative is a company believing three people can approve payments
  when one can.
- **Per-signatory limits.** Nothing anywhere holds an amount a given person may move.
- **Maker/checker separation.** `MAKER` and `APPROVER` roles exist and are never issued.
  An `ADMIN` prepares and approves, which is the only coherent behaviour for a company
  whose sole admitted member is its administrator — refusing them their own approval would
  leave them unable to move any money at all. "Not the same person for one payment" is a
  rule about a payment, not something a permission list can express.
- **Switching between companies.** A person can hold memberships of several companies, and
  the session reports the **oldest grant** as the active one with that membership's
  permissions. Choosing needs an endpoint that records the choice; there is none, so the
  frontend's `/session/corporate` call is still answered by MSW. Nothing creates a second
  membership today, so nobody can reach this yet.
- **Revoking access.** `CorporateMembershipEntity.revoke` exists and no endpoint calls it.
  The read side is tested — a revoked member's accounts vanish and their deposits and
  withdrawals 404 in the same session — but taking access away is currently a database
  operation.
- **Joining an existing company.** Still the one kind of application the create button is
  withheld for; see below.

**Permissions are derived, in one place.** `SessionPermissions` turns a role into a
permission list and `CustomerEntitlements` decides which company that list is for. The
session used to answer `"RETAIL"` and a fixed list to every signed-in person, which was
true while a personal login was the only kind. Note what a corporate permission list does
**not** mean: `CORPORATE_TRANSFER_CREATE`, `BULK_CREATE`, `APPROVAL_APPROVE` and
`SALARY_VIEW_DETAILS` name parts of the product this service has not built, exactly as the
retail list already named transfers and cards it had not built. Those screens are still
answered by MSW. A permission is what the portal reads to decide what to offer; it is never
checked in place of a server-side control.

**Scoped to one company, never unioned.** An accountant who administers one client and
only views another must not carry approval rights into the second, so the session carries
the active membership's permissions rather than the union of all of them.

#### Two smaller decisions already taken, and why

**A duplicate TIN is accepted.** Refusing one on a public, unauthenticated form is an
existence oracle: Rwandan TINs are enumerable, so anyone could walk a list and learn which
companies bank at Zigama — a ready-made target list for phishing their signatories, and
commercially sensitive besides. Two applications for one TIN sit in the queue instead,
where a person can see both and decide. Same rule as unknown company join codes.

**Company identifiers are not pattern-validated.** This service has no company register to
check a registration number against and no tax authority to confirm a TIN with, so a
format rule here would be a guess — and a wrong guess turns a real company away at a
public form with no way to find out why. Length caps match the columns; the judgement is a
person's, against the bank's records. Signatory ID numbers are deliberately not held to
the personal form's 16-digit rule either, because a director may be foreign.

#### Joining an existing company is still not implemented

`/registration/join` has no controller, no entity and no migration, and is still answered
by a fixture. It is also the one application kind for which the staff screen withholds the
accounts form, and `OnboardingService.createAccount` refuses: giving somebody access to a
company that already banks here means establishing that the company agrees they may act
for it, and there is no way to ask it for that yet. `companies.code` exists for that flow
and nothing consumes it. It is the last entry in `NOT_ON_THE_BACKEND`, which refuses at start-up if
anyone routes it live — for the reason below.

How that guard came to exist, because the symptom pointed the wrong way entirely:
`VITE_LIVE_API` once listed the whole `registration` prefix, which sent the company
endpoints to the real service when it had none. Spring Security authorises before the
dispatcher resolves a handler, so an unimplemented path is not a 404 — it falls to the
catch-all `hasRole("CUSTOMER")` and is denied. With a staff session in the same browser
that renders as *"Your account does not have permission to do this"*: a permission error,
for a feature that did not exist, shown to someone whose permissions were never the
problem.

That the catch-all denied it is correct and worth keeping — an unwritten endpoint should be
closed, not open. The cost is the message, and it is the reason a 403 from this service
should never be read as evidence about a user's role until the path is known to exist.

### Still mocked, so still not real

Accounts, their balances and their statements are REAL now — `accounts` and
`transactions/recent` are both in `VITE_LIVE_API`, and the figures come from the backend's
own ledger.

Company registration is real too, and needs no entry in `VITE_LIVE_API` — its mock is
gone, so it passes through by default.

Saved payees are real now too (migration **V17**), and `beneficiaries` is in
`VITE_LIVE_API`. `transfers` has been REMOVED from that list and must stay out: its mock
handlers are gone, so there is nothing for the entry to remove and the start-up check in
`src/mocks/handlers.ts` throws on it. MSW passes through whatever it has no handler for, so
`/transfers` reaches the service either way.

Cards and cheque books a customer ASKS FOR are real now too (migration **V18**), and
`service-requests` belongs in `VITE_LIVE_API`. Note what that does and does not cover: the
request, the staff queue that fulfils it, the collection point and the two emails are real;
the `/cards` list itself is still mocked, because the portal has no card-issuing connection
and so has no cards to list.

The customer's own security page is real too (migration **V19**), and `security` belongs in
`VITE_LIVE_API`: trusted browsers, the revoke, the sign-in history and the change-password
form all reach the service. The password change revokes every trusted browser and emails
the customer, so it is not a form to try idly against a database you care about.

In-app notifications are real too (migration **V20**), and `notifications` belongs in
`VITE_LIVE_API`.

Loans, payments, standing orders and statements are still mocked, so they are **empty or
fabricated**, not populated from anything. A card limit or a loan payoff figure in this
configuration is a fixture.

`accounts` and `transactions/recent` belong together in that list and neither should be
removed alone: they are two views of one ledger, and with only the first live the account
page shows a real balance beside mocked activity that does not explain it.

### Transfers: what settles, and four things a bank would add

`POST /transfers` is real (migration **V12**). A customer sends to another account **inside
Zigama** — either one of their own, picked from a list, or anybody's by full account number
— the sender is debited **immediately**, and a MANAGER approves before the beneficiary is
credited. Every transfer waits, whatever the amount. `TransferTest` walks it against the
database.

**The hold is a ledger entry, not a column.** V9 states that a balance is the sum of its
entries; a `held_minor` column would have broken that by design and left the customer's
statement unable to explain why their money was unavailable. So submitting posts a real
DEBIT, approval posts the CREDIT, and a refusal posts a visible reversal rather than
unwriting anything. That gives an invariant every test asserts:

```
sum(every balance) + sum(every pending transfer) = constant
```

While a transfer waits, the money has left the sender and arrived nowhere, so the accounts
alone are **short** by the pending total. A bank holds that difference in a **suspense
account** and this service has none — the invariant is asserted in the tests instead.
`./scripts/show-bank.sh` prints it as `in_accounts + in_flight = total`.

#### Knowing an account number is enough to pay INTO it

That is intended: an account number is a payment address, not a secret. It is **not** enough
to take money out, read a balance or read a history — all three go through
`AccountAccess.mayAct` on the *source* account.

**The name lookup is back, bounded this time.** `POST /transfers/resolve` answers a full
account number with the holder's name. It was removed once as an unbounded oracle and
reinstated on the bank's instruction with the limit that was missing: `BeneficiaryLookups`
caps one **signed-in customer** at twenty lookups an hour, a miss costs the same allowance
as a hit, and every refusal is logged. Over the limit the customer gets 429 with a
`Retry-After`.

It is no longer optional. The name is fetched when the customer presses Continue and shown
on a **review step** beside the amount, because an optional check on the one mistake nothing
else catches is a check most people will not make. Sending the right amount to the wrong
account is unrecoverable and invisible to the approving manager, who has no idea who the
sender meant to pay.

**The limiter is in memory, which is a real limit rather than a detail.** The counters live
in one process: they reset on restart and are not shared between instances, so two instances
behind a load balancer allow twice the traffic. That is enough to turn a scripted harvest
into a slow, noisy one and not enough to be the only thing in its way. The durable version
is a counter in the database or in Redis, keyed the same way.

**Still owed:** the lookup returns the **full** name. The narrower option, if the bank wants
it, is an initial and a surname (`J. NKURUNZIZA`) — enough to catch a typo for somebody you
already know, less useful to somebody harvesting.

#### Four gaps still owed

| Gap | What happens meanwhile |
|---|---|
| **No daily or per-transfer limit.** | A customer can move their whole balance in one instruction. Only the manager's judgement bounds it, and nothing tells them what is unusual for this customer. |
| **The reference is free text on a stranger's statement.** | 140 characters chosen by the sender are written onto the beneficiary's ledger entry, and the beneficiary cannot block a sender. This is a documented harassment route in retail banking, not a hypothetical. |
| **The beneficiary is never notified.** | Money arrives silently. Nobody is told they were paid, or that a refused transfer came back. |
| **No suspense account.** | The in-flight total is reconciled only by a test assertion. A real ledger balances at every instant; this one balances across two tables. |

Also absent by design, for now: no fee (the bank has not set one, and a zero would read as a
decision), no threshold below which a transfer settles without approval, and no scheduled or
recurring transfers.

#### Sending outside Zigama now refuses instead of pretending

The EXTERNAL and INTERNATIONAL rails were answered end to end by MSW and finished on
*"Done — the money is on its way"*, with a reference number, for money that went nowhere.
Both now show a screen saying it is not available and to use a branch. There is no rail to
send on, so a form that accepted the instruction was a false receipt — the same failure as
the business-registration wizard.

#### Cards and cheque books: three invented facts removed

Both request forms posted to endpoints only MSW answered, onto an array that starts empty
on every page load. The request reached nobody, no staff screen existed to show it, and the
reference stopped existing when the customer refreshed. On top of that the screens stated
three things the bank never said:

| What the screen said | Where it came from |
|---|---|
| "Cards take about five working days to print" / "Printing takes about a week" | String constants in `CardsPage.tsx` and `ChequeBooksPage.tsx`. |
| A reference number | `Date.now()`. |
| "Collect from the *Nyarugenge* branch" and five others | `const BRANCHES = [...]`, held in **three** places — both pages and `src/mocks/data/movement.ts`. The branch list has never been supplied; it is still a row in the table below. |

Migration **V18** records the request, and `ServiceRequestsPage` is the staff queue that
fulfils it. All three invented facts are gone and nothing replaced the first two: the
product no longer states how long anything takes. The collection point is **typed by the
member of staff who produced the thing**, which is the only version of that sentence that
can be true, and until they type it the customer's screen shows an em dash.

The `/cheque-books` inventory endpoint, its `ChequeBook` type, its mock store and
`chequeBookService` were deleted rather than left unreferenced — a second, parallel,
fictional cheque-book API in the services barrel is how the next screen gets wired back to
it. `ServiceRequestsPage.test.tsx` asserts the six branch names appear nowhere in the
rendered markup, so re-adding one as a placeholder or a default fails a test.

Still missing, and not inventable here: **card issuing**. The portal can record that
somebody asked for a card and that staff handed one over; it cannot produce a PAN, so
`/cards` lists nothing.

#### The sign-in location was a string constant

`sign_ins` carried a `location` column from V2, and all four call sites in
`CustomerAuthService` passed the same literal:

```java
signIns.save(new SignInEntity(customer.id(), "Kigali, Rwanda"));
```

There has never been a geo-IP lookup in this service. The dashboard header printed
*"Last sign-in … from Kigali, Rwanda"* and the profile page printed the same under a
heading reading **Where**, to every customer, for every sign-in, from anywhere. This
reached real UI through the live `/session` endpoint; it was not mock data. An earlier test
in `CustomerSignInTest` asserted the value, which is how a fabrication survives — a test
starts defending it.

**Why it was a security fault, not a cosmetic one.** The sign-in history has one job: a
customer notices access that was not theirs. A constant defeats it in exactly the case it
exists for, because an intruder in another country produced a row indistinguishable from
the customer's own. A blank column would have been better, since a blank prompts a
question.

**V19** drops it and records what the service genuinely knows at the moment it writes the
row: which sign-in path was taken (`SignInMethod` — emailed code, remembered browser,
password only, temporary password), and a coarse browser description from the User-Agent
(`DeviceDescription`, two words, never a version string). Both are absent rather than
defaulted when unknown, and `SecurityPageTest` asserts the raw response body cannot contain
"Kigali" or "Rwanda".

| Still missing | Why it is not built |
|---|---|
| Real geo-location of a sign-in | Needs a geo-IP provider the bank has not chosen, and a decision about retaining IP addresses it has not made. An IP against every sign-in is a movement history of the customer — V2's own comment gave that as the reason to keep the location coarse. |
| Failed sign-in attempts | Worth recording and deliberately not faked. `sign_ins` holds sign-ins that happened; the old screen had an `outcome` of SUCCESS or FAILURE and nothing ever wrote FAILURE. It needs its own table, a retention rule for attempts against an address that may not belong to a customer, and a rate-limit story. |
| Device-token rotation on each use | Recorded against `TrustedDevices` already: two tabs signing in at once would race. |

#### The security page had no server behind it

The page called `/security/devices`, `/security/events` and `/security/password`. **None of
the three existed.** MSW answered all three from arrays that start empty on every page
load, so the history showed nothing whatever had happened to the account and *Remove*
withdrew trust from a browser that had never been trusted. `SecurityController` and
`SecurityService` are real now, scoped to the session with no customer id anywhere in the
API.

Four further claims on that screen are gone, each of which read as a working feature:

| What it said | What was true |
|---|---|
| A `location` on every row | No table has ever held one. |
| "Sign-ins, failed attempts and changes to your payees" | It showed sign-ins. |
| An **ok** / **failed** badge per row | Nothing could set it to failed. |
| "Other devices stay signed in until their sessions end" | Changing a password now revokes **every** trusted browser — that is the point of doing it — and the response carries the count so the customer is told. |

It also called trusted browsers "devices signed in". A trusted browser is permission to
skip the emailed code and it outlives every session on that machine; removing one signs
nobody out of anything, and the wrong name made *Remove* read as a panic button it is not.

#### "Nothing new." was a claim the portal could not check

The dashboard had a notifications panel, there was a full notifications page, and both had a
mark-as-read and a mark-all-read action. **No notifications table existed on the server**, and
`/notifications` was answered by MSW from an array that resets on every page load.

How it surfaced: a customer asked for a card, staff marked it ready and typed the counter,
the email arrived quoting reference and collection point — and the dashboard still read
*Nothing new.*

**An empty notification list is not a neutral state.** A customer reads it as a record and
concludes nothing happened; the panel had no means of checking that. Same shape of fault as
the fabricated sign-in location: a surface that looks like a control and answers from
nowhere.

**V20** adds the table. Each notification is written **in the same transaction as the event**,
alongside the email, so one cannot survive an event that rolled back and an event cannot
half-tell the customer. Eight kinds, each carrying its own severity:

| Event | Severity | Was the customer told before? |
|---|---|---|
| Card or cheque book ready | INFO | Email only |
| Card or cheque book declined | WARNING | Email only |
| Payee approved / refused | INFO / WARNING | Email only |
| **Transfer released** | INFO | **Nothing at all** |
| **Transfer refused** | WARNING | **Nothing at all** |
| Password changed | SECURITY | Email only |
| Freeze lifted | INFO | Email only |

The two transfer rows are the ones worth noting: `TransferService.approve` and `reject` send
no email, so until now the only way to learn whether your own money had actually left was to
keep reloading the dashboard's "Waiting for the bank" panel until the row disappeared.

**Deliberately absent**: account created, approved, rejected, frozen, and a re-issued or
refused password. Every one happens at a moment the customer *cannot sign in* — usually that
is the point of it — so an in-app row would be unreadable by construction. Email is the only
channel that reaches somebody locked out, which is why those stay emails. Rows nobody can
read would make this table look more finished than the product is.

Links are **in-app paths only**, enforced in the entity and again by a `CHECK`. A notification
carries text the bank did not author — a staff member's reason, a payee name the customer
typed — so a rendered absolute URL out of one is phishing with the bank's own domain behind
it.

`NotificationKindsArePersistableTest` guards the enum against the constraint, and that guard
earns its place: removing one value from the `CHECK` and re-running the suite makes a
manager's **transfer approval return 500** with the money unmoved, because the notification
write sits inside that transaction. Exactly the V5 failure, reproduced on demand.

#### The dashboard said "You have no cards yet" with a card on the counter

Reported alongside the above, and a different fault. The Cards panel called `/cards`, got an
empty list, and rendered the empty state — **a true sentence**, since the portal has no
card-issuing connection and so has no card to list. It was the wrong answer to the question
being asked, which was "what happened to my request?"

The panel now makes a second call for open service requests and lists them above the cards,
with reference, status, the staff-typed collection point and any decline reason. Two calls
rather than one on purpose: a card is a piece of plastic with a PAN, a request is a row
saying somebody asked for one, and folding the second into `/cards` would mean inventing the
first.

Still true, and still waiting on the bank: **card issuing**. The portal can record that
somebody asked and that staff handed one over. It cannot produce a PAN, so `/cards` lists
nothing.

#### Dev reset: the cascade was asserted, not tested

`DevResetController` deletes some children explicitly and relies on `ON DELETE CASCADE` for
the rest, and its own comment says the cascade "does not" happen on H2 — where the whole
suite runs. Two tests called `/dev/reset`, but neither had a customer holding a beneficiary,
a service request, a trusted device or a notification, so the ordering was never exercised
against anything added since V14. `DevResetCoversEveryTableTest` now does, with a customer
who has rows in all of them. It passes, so H2 does honour the cascade here — the point is
that this is now checked by the suite rather than believed.

#### Reloading a signed-in page sent you to sign in — two causes

Reported as "reloading signs me out". It had two independent causes, and only one of them
was a bug in the usual sense. Both were reproduced before either was touched: sign in
through a browser, restart the API, reload.

**1. Sessions lived in Tomcat's memory.** So they really did end — every restart of the
service signed out every customer at once, which during a week of shipping migrations meant
several times an hour. Not only a development annoyance: it also meant every deploy signed
out everyone using the portal, and that the service could never run on more than one node,
because two instances cannot share an in-memory session.

Migration **V21** moves sessions into the database (Spring Session JDBC — the service
already owns PostgreSQL and a Flyway pipeline, so Redis would have been a second thing to
run, secure and back up). Verified end to end: sign in, kill the API, start a fresh JVM
against the same database, reload → the page stayed put and `/session` answered 200.

> **The trap, recorded because it cost an hour.** Declaring `spring-session-jdbc` alone —
> the obvious dependency — is a **silent no-op**. Spring Boot 4 split
> `spring-boot-autoconfigure` into one module per technology, so session support no longer
> ships in the core jar: the library sits on the classpath with nothing configuring it, the
> application starts cleanly, logs nothing about sessions, and keeps using Tomcat's memory.
> The only visible symptom is that the cookie is still `JSESSIONID` instead of `SESSION`.
> The correct artifact is **`spring-boot-starter-session-jdbc`**.
> `SessionsAreInTheDatabaseTest` asserts a row lands in `SPRING_SESSION`, which is the only
> assertion that can tell a working store from the no-op.

**2. `ProtectedRoute` treated "could not check" as "signed out".** Its last condition was
`status !== 'authenticated'`, which lumped the `error` status in with `unauthenticated`. So
any failure to *check* the session — the API restarting, a dropped connection, a 500 — sent
the customer to a bare sign-in form. Wrong twice over: it asserts they are signed out when
nobody knows, and it sends them where signing in cannot work either, because the service
that would authenticate them is the one that is down. It also discarded the explanation
`SessionProvider` had already composed.

It now keeps the route, says the session has *not* ended, and offers **Try again** instead
of a password prompt. `ProtectedRoute.test.tsx` covers each status.

**3. And the sign-in page now explains itself.** On a genuine expiry the customer used to
land on an unexplained form — the commonest reading of which is "my password has stopped
working". It now says the session ended, that nothing has happened to their account, and
that they will be returned to the page they were on, which they are. Deliberately *not*
worded as a flat "your session expired": after a reload the page is fresh and has no memory
of having been signed in, so the app cannot know whether it was a timeout or a restart. The
wording covers both without claiming either.

**Sessions in tests**: `application-test.yml` excludes the JDBC session auto-configuration.
Spring Session carries identity in a cookie and owns `getSession()`, so a MockMvc request
handed a `MockHttpSession` stops being the same caller — switching it on turned 165 call
sites across 17 files into 401s at once. The exclusion keeps those tests doing exactly what
they did before V21 (this suite has never exercised session persistence, because a
`MockHttpSession` never persisted anywhere), and `SessionsAreInTheDatabaseTest` clears the
exclusion so one test runs the production wiring. The reasoning is written out in both
files.

| Still the bank's decision | Why it is not set here |
|---|---|
| The session idle timeout | Now **explicit** at 30 minutes in `application.yml`, where it was an inherited framework default nobody had chosen. The number itself is unchanged, so nothing behaves differently today. Shorter is the usual advice for a service holding money — that is a bank decision, not a developer one. |
| A warning before the session drops | Better than any timeout on its own: a customer halfway through a transfer should be told their session is about to end, not discover it on submit. Needs the bank to settle the timeout first. |

#### The staff overview was a developer's page wearing a bank's clothes

Below five onboarding figures sat a panel headed **"Start over — Development only"** with a
red **"Delete every customer and application"** button, under four sentences naming
`VITE_LIVE_API`, the `dev`, `h2` and `test` profiles, and the fact that the button used to
say "mock bank". Under that came the development mailbox. This was the *first screen* an
administrator or a manager saw on signing in.

**The message log** moved to a separate **Developer tools** page, linked last and only
outside a production build. It earns a page: with email switched off it is the only way to
read a verification code or a temporary password, so losing it would stop the onboarding
flow being walkable at all. It is read-only and changes nothing.

**The reset stayed**, on that page rather than on the overview — which is the distinction
that mattered. Whoever runs the service needs to empty it between test runs; a manager
signing in to release payments should not be one click from doing the same.

It is now **behind a typed confirmation**: the button is disabled until `DELETE` is typed,
case-sensitively, because "delete" is a word people type by habit. A single click is the
wrong amount of effort for an action that removes every customer with no undo, and a
`confirm()` dialog would be worse — people dismiss those without reading.
`StaffDeveloperToolsPage.test.tsx` asserts the button is disabled on arrival and on a
near miss.

There is also a terminal equivalent, **`npm run reset-bank`**
(`frontend/scripts/reset-bank.mjs`), for use without a browser. It names the host it is about
to empty before doing anything, explains a refusal outside the `dev`/`h2`/`test` profiles as
the protection working rather than an error, and exits non-zero on failure so it can be used
from a pipeline. Verified against a live API on all three paths: success, wrong credentials,
unreachable.

`src/services/devReset.ts` stays as the client, reached only through a dynamic `import()`.
That shape is not stylistic: the reset was once an ordinary method on `adminService`, where
it survived tree-shaking and shipped the `/admin/dev/reset` path string into the production
bundle. A module nothing statically imports is the only version that cannot leak. The server
refusing the endpoint outside those profiles remains the actual control.

**And the numbers answered the wrong question.** `/admin/summary` returned five onboarding
figures while the service had grown six queues, so a manager signing in saw almost entirely
an administrator's work — registrations, accounts created, applications rejected — and no
sign of their own. The single link out of the panel went to the registrations queue whoever
was looking.

`OnboardingSummary` is now `StaffSummary` (the old name had stopped being true, and a type
whose name is a lie is one nobody adds the right field to) and carries all six queues. The
overview answers **"what do I have to do?"** first:

| Rule | Why |
|---|---|
| Your own queues sort first | One order for everybody made a manager read past four administrator figures to reach the one queue where money is already in flight. |
| **Money to release** never sorts below anything | It is the only queue where a slow day *costs* the customer rather than inconveniencing them — the money is debited and not yet delivered. |
| Other roles' queues are shown and **labelled**, not hidden | A manager who can see nine registrations stuck can go and find an administrator. Hiding it makes the backlog somebody else's secret. |
| Zero rows are omitted entirely | And the empty state says "nothing is waiting for the bank right now" — a sentence every figure behind it can stand behind, because they are all database counts. |

Every figure is a `COUNT(*)`, never the length of a page a queue screen fetched. The queue
screens ask for bounded pages, so an overview built from those lists would tell a manager
"12 waiting" with 200 waiting — a worse number than none, because it is actionable and
wrong.

Wording changed where a status was standing in for a meaning: *"approved, not signed in
yet"* now reads **"handed a password, not used yet"**, because what the figure means is that
a temporary password was handed over and nobody has taken ownership of the account — so a
number that stops falling is a set of live credentials sitting on desks.

`StaffDashboardPage.test.tsx` asserts the rendered markup contains none of `VITE_LIVE_API`,
"mock bank", "Development only", "Delete every customer", "Start over" or "profile", so any
of it coming back fails a test.

> **Sent messages is still a developer tool, not a banking feature.** It exists because no
> mail server is wired up. Once real email is sending, a record of what was sent to a
> customer becomes a legitimate support screen and should move back into the staff area with
> its own wording — at that point it stops being "what the bank *would have* sent".

## Waiting on the bank

Nothing below is invented in code. A plausible-looking placeholder is worse than a blank:
a wrong support number on a banking site is a fraud vector, and a stand-in logo teaches
customers to trust the wrong mark.

| What | Where it shows | Why it is blank |
|---|---|---|
| Contact centre number, opening hours, branch list, secure-message channel | Help page; marketing footer | Not supplied. A wrong number on a help page is worse than none. **No longer blocks card and cheque-book collection:** three copies of an invented six-branch list have been deleted and a member of staff now types the collection point, so the portal states a place only once the bank has named one. The help page still has nothing to show. |
| Registered legal name, logo, wordmark | `src/config/brand.ts`; sign-in screens | Only the text "Zigama CSS" is used. A stand-in logo on a sign-in screen trains customers to trust the wrong mark. |
| Account products: which account types exist, their customer-facing names, eligibility, and which currencies each supports | Admin → create account | A placeholder list is in use. See "THE ACCOUNT TYPE LIST IS A GUESS" above. |
| Terms, privacy notice, fees and charges | Marketing footer | Links exist, targets do not. |
| Regulatory disclosures and licensing details | Marketing footer | Pending. |

## Accounts: entered by an administrator, and now holding balances

**Staff → Applications → create the login.** The administrator types one row per account
the person already holds at Zigama — number, type, currency and opening balance — and
presses create. A manager approves. The customer signs in and sees exactly those accounts,
with those balances, and can pay in and take money out.

**A row is a human assertion, not a synchronised fact.** This service cannot see core
banking. What is stored is that a named administrator read the bank's own records and
said so — the same shape as the approval that precedes it, where identity is verified
outside the system and the staff member's name is the record that it was done. Nothing
here checks that the account exists, belongs to this person, or holds the figure typed.

### THE DECISION THAT NEEDS THE BANK'S SIGN-OFF: this service now holds the money

Until recently this service held no balances. It recorded which accounts a customer holds
and showed "Not available" where a balance would go, on the grounds that the money is in
core banking and a figure kept here would be a second, unreconciled copy.

**The bank decided the portal holds the balances.** The administrator enters an opening
balance and the customer deposits and withdraws in this portal. That is built and works.

**The risk has not gone away, and nothing in code can close it.** If core banking also
holds balances for these accounts, the two **will** diverge — the first time a teller
takes a deposit at a counter, or this portal takes one, the other system does not know.
Nothing reconciles them, and there is no way for either system to tell which is right.

This is a decision, not a gap:

- If this portal is the **only** ledger for these accounts, the design is sound as built
  and the branch must stop recording movements for them anywhere else.
- If core banking is the ledger, this portal must stop holding balances and integrate
  with it instead — which is the earlier design, and a bigger piece of work than removing
  the fields.
- **A period where both hold balances is the one outcome with no safe ending.** Two
  ledgers for one account produce two true-looking figures, and a customer acts on
  whichever they were shown.

Zigama has to pick one. Until then, treat this as live in development and not in a branch.

### What the ledger guarantees, once the decision above is settled

The balance is the **sum of the entries** and nothing else. `AccountLedgerService` is the
only code that moves one, and every movement it makes is paired with a row in
`account_transactions` in the same database transaction — including the opening balance,
which is posted as a CREDIT rather than assigned. There is no setter: a balance that can
be set without an entry is a number nobody can account for afterwards.

Four properties, each of which is a way real money goes missing. All four are tested in
`AccountBalancesTest`, and each test was verified by breaking the code and watching it
fail:

| Property | What it prevents |
|---|---|
| Atomic | Half a deposit — an entry with no balance change, or the reverse, that nobody can reconstruct |
| Serialised per account (`PESSIMISTIC_WRITE`) | Two simultaneous withdrawals both reading the old balance and both deciding there is enough |
| Idempotent (unique `idempotency_key`) | A retry after a lost response posting the money a second time |
| Never negative (checked in code *and* as a column CHECK) | Lending money nobody approved — there is no overdraft product |

Money is a whole number of minor units, never a floating-point type, and **the scale comes
from the currency**: RWF has no minor unit, so 1000 RWF is 1000 minor units and a decimal
amount in RWF is *refused* rather than rounded. `Money.scaleOf` and the portal's
`currencyScale` both resolve it from ISO 4217, so the two ends of the wire agree.

The `Idempotency-Key` header is **required** on deposit and withdrawal. The server refuses
a request without one rather than generating a fallback, because a generated key defeats
the entire mechanism: the retry arrives with a fresh key and posts the money again.

### What this replaced — twice — because the failure modes are worth remembering

**First, the form that stored nothing.** It collected an account type, a currency, an
opening balance and a branch, and posted them. The endpoint was
`createAccount(@PathVariable UUID id, Authentication caller)` — **no `@RequestBody`** — so
Spring bound the path variable and discarded the JSON. The administrator chose "Current
account", entered a figure, and was shown a success message. Nothing was stored; there was
no table it could have gone in. The whole suite passed throughout, because every test
asserted on the response, and a body that is not read comes back 200 exactly like a body
that is.

**Then two models that were built, complete and tested, and were the wrong shape.**
`account_opening_requests` recorded requests to open accounts, marked
`AWAITING_CORE_BANKING`, that the customer never saw and nothing could ever advance. After
that, a separate manager-only "link an account" screen. Both asked the administrator to do
their real job — reading the bank's records — somewhere else, and then do a made-up one
here. Migration V8 dropped both tables.

The lesson is not about code. Each was a faithful implementation of a flow nobody at the
bank had described, and the cost was two complete features. **Ask what the branch actually
does before building the screen for it.**

### How the account number is protected

- Stored, because identifying the account at the bank needs the whole number.
- **Never returned.** `CustomerAccountEntity.accountNumber()` is package-private; the API,
  the logs and the screens all read `maskedNumber`. `CustomerAccountsTest.theNumberNeverLeaves`
  and `AccountBalancesTest.theNumberNeverLeaks` assert on the raw response body rather than
  on a named field, so a future addition that carried it would fail.
- Never in a path — it goes in a POST body, because a number in a URL is a number in the
  browser history, the proxy log and the referer header.
- Logged as `**** 7891`, never in full. The idempotency key is never returned either: it is
  the client's, and anybody who saw a receipt carrying it could replay that submission.

### Questions for the bank

**Joint accounts.** An account already on another customer is REFUSED, and the whole
request fails rather than creating a login with half its accounts. That is a guess, and the
safe one: two logins reaching one account is either a joint account or somebody being handed
a stranger's money, and only one of those is safe to assume. If Zigama offers joint
accounts, they have to say what evidence permits the second assignment.

**Whether assigning an account needs a second approver.** The administrator assigns and a
*different* manager approves, so account assignment does get four eyes — but only as part
of approving the whole login. Removing a wrongly assigned account is manager-only and
single-handed; the control there is the audit trail (`removed_by`, `removed_at`,
`removal_reason`, and the row surviving removal) rather than prevention. **This is the same
question as unfreezing**, and the two should be answered together.

**Who may enter an opening balance, and against what evidence.** An administrator can
currently type any figure. The audit trail records who (`performed_by` on the opening
entry) but nothing limits it. If this portal is the ledger, that is a cash-handling
control the bank has to specify — a maximum, a second signature, a reference to a deposit
slip, or all three.

**Whether a customer should be able to withdraw at all.** "Withdraw" in this portal moves
the balance down with no cash leaving anywhere. That makes sense if the portal is the
ledger and the branch reconciles against it; it makes no sense alongside a real teller.
Same decision as above, and it needs the same answer.

**The account-number format is the same 10–16 digit guess** as the registration form. If
Zigama's real numbers are a different shape, this turns real accounts away at the counter.

### THE ACCOUNT TYPE LIST IS A GUESS

`ACCOUNT_TYPES` in `src/types/admin.ts` and `AccountType` on the backend list current,
savings, fixed deposit, salary, junior savings, target savings and loan servicing. **The
bank has not confirmed any of it.** It is a plausible set for a Rwandan savings bank, which
is exactly the danger: it reads as authoritative in a dropdown.

Zigama has to supply the real catalogue before this screen goes near a branch — what each
product is called to a customer, who is eligible for it, and which currencies it supports.
The offered currencies (RWF, USD, EUR, GBP) are a guess for the same reason; the server
validates the *shape* of a currency code rather than membership of a list, so a hardcoded
guess there cannot turn a real customer away at the counter.

Changing the list means editing both enums and the CHECK constraint in
`V8__customer_accounts.sql` — three copies of one list.

## Waiting on a backend contract

| Feature | What is missing |
|---|---|
| Bulk payment upload | The upload endpoint, the CSV column contract, and the shape of the validation report the bank returns. The screen collects the file so the flow is real; there is nowhere to send it. |
| Per-payment rows in a batch | A paged endpoint, plus the salary-detail rule applied per row rather than per batch. |
| Statement download | An authenticated endpoint that does **not** hand out a public URL. A statement link that works without a session is a statement anyone with the link can read. |
| `/forgot-password`, `/reset-password` | Routes exist as placeholders. No reset flow is specified. |
| Email delivery (SMTP) | Wired up now (`spring.mail.*`, driven from `backend/.env`). With `MAIL_ENABLED=false` nothing is sent and every message is recorded at `GET /api/v1/admin/outbox` instead — which is where the verification codes are read from in development. See below. |
| Banking endpoints | Accounts, balances, transactions, cash in/out, transfers, saved payees (**V17**) and card/cheque-book requests (**V18**) are implemented. The rest are mocked: payments, card issuing, loans, standing orders, FX, statement downloads, notifications, approvals, batches. Money movement between parties is the part not to rush. |
| Whether this service should hold balances at all | Built, working, and the bank's call to confirm or reverse. See "Accounts: entered by an administrator, and now holding balances". |

## Deliberately vague messages

Three messages are less specific than they could be, on purpose. If one gets "improved"
into something more helpful, the leak comes back.

- **Sign-in failure** is identical for a wrong password and an unknown customer. A form
  that distinguishes them is a way to find out who banks here.
- **Business registration accepts a duplicate TIN** rather than refusing it. Refusing
  confirmed to an anonymous visitor that a given TIN banks at Zigama, and Rwandan TINs
  are enumerable — that is a phishing target list. Two applications for one TIN now sit
  in the staff queue for a person to resolve.
- **Switching to a company that is not yours** returns the same "could not find that"
  as a company that does not exist. It previously returned 403 for the former and 404
  for the latter, which let any signed-in customer map the bank's corporate client list
  by the difference.

## Email: what is and is not wired up

**There is no SMTP.** No host, no credentials, no sending library, no queue. Nothing in
this project has ever put a message on the internet. Three things now depend on email,
and all three write to the development mailbox at `/staff/outbox` instead:

1. The **registration code** that confirms an applicant's address.
2. The **approval notice** when a manager releases an account.
3. The **rejection notice**.

The mock's registration code is fixed at `123456`. A random one would be unreadable
without opening the mailbox and would make the tests flaky for no benefit. The real
generator belongs on the server, where the code can be hashed, expired and rate limited
rather than sitting in browser memory.

### The sender address

Currently `ciaramuzora@gmail.com`, set in `src/mocks/data/onboarding.ts` as
`SENDER_ADDRESS`. That is the right value for development — it makes the mailbox at
`/staff/outbox` show a real address, and it is one place to change.

**It cannot be the production sender, and the reason is not preference.**

- **SPF, DKIM and DMARC cannot be configured for `gmail.com`.** Those records live in
  the sending domain's DNS, and Google owns that DNS, not Zigama. So the bank's mail
  can never be cryptographically attributable to the bank. Every receiving server has
  to treat it as unauthenticated mail claiming to be from a bank — which is the exact
  signature of a phishing campaign.
- **It teaches customers the wrong thing.** This portal tells people, on the sign-in
  page and in the footer, that the bank will never ask for their password or one-time
  code. That warning works only if customers can recognise real bank mail. A bank whose
  verification codes arrive from a personal Gmail has trained its customers to accept
  mail from *any* Gmail — and the next one will be the attacker's.
- **Gmail will throttle it.** Consumer accounts cap at roughly 500 recipients a day and
  are rate limited well below transactional volumes. Registration codes are time
  sensitive; a throttled code is a customer who cannot open an account.

What production needs instead: an address on a domain the bank controls
(`noreply@zigama.rw` or similar), with SPF, DKIM and DMARC published in that domain's
DNS, sent through a transactional provider or the bank's own relay. The credentials for
it stay server-side — never in a `VITE_` variable, which is inlined into the JavaScript
and public.

### What the backend needs before this is real

- An SMTP host and credentials, held **server-side only**. Credentials must never reach
  a `VITE_` variable — everything prefixed that way is inlined into the JavaScript and
  is public.
- A sender domain with SPF, DKIM and DMARC records. A bank's mail that fails these lands
  in spam, and the whole onboarding chain stalls silently at the step where the customer
  is waiting.
- Rate limiting per address and per IP on the code endpoint, or it is an open relay for
  sending mail to strangers.
- Codes stored hashed with a short expiry and an attempt counter.
- A decision on **SMS as well as email**. Registration used to send the code to the
  phone the bank already holds, which proved possession of a number on file. It now goes
  to email, because email is what the rest of the pipeline runs on. Identity is still
  checked — account number, national ID and date of birth must match the bank's records
  before any code is sent — but if the bank wants possession of the registered phone
  proven too, that is a second factor to add, and it needs an SMS gateway.

### Not verified yet

Business registration and "join a business" have **no code step**. Their applications
are recorded with `emailVerified: false` and the staff screen says so, so an admin can
see which applications arrived with a confirmed contact address. Adding a code step to
those flows is outstanding.

## The temporary password is emailed — a decision, and its cost

The bank chose to send the temporary password in the approval email rather than have the
customer collect it from a branch. The reason is sound: Zigama's customers are often
posted a long way from one, and a password that requires a branch visit makes online
registration pointless.

**The cost is real and should not be forgotten.** That message carries both where to sign
in and the credential to do it with, so a compromised mailbox is a compromised account.
Three things bound it, and none should be removed without replacing them:

- **It expires after 72 hours** (`TEMPORARY_PASSWORD_VALIDITY`, enforced at sign-in, with
  its own column added in `V3__temporary_password_expiry.sql`). A mailbox breached months
  later yields nothing.
- **It is useless for anything but replacing itself** — the session it opens holds
  `MUST_CHANGE_PASSWORD` instead of `ROLE_CUSTOMER`, so every other endpoint refuses it.
- **The sign-in is recorded**, so a customer who opens the email and finds the password
  already used has a trace.

Two consequences worth knowing:

- **It is now generated at APPROVAL, not at creation**, because only the hash is stored
  and the plaintext no longer exists by the time the email is composed. A side effect is
  a genuine improvement: no member of staff ever sees a working credential for a
  customer's account, including the administrator who created it.
- **The old promise is gone, not softened.** The email used to say "we did not include
  your password and we never will". Leaving that in a message containing the password
  would teach customers to ignore the assurance that protects them from real phishing.

**What would remove the cost rather than bound it:** send the password by SMS to the
number on the bank's records, which is a separate channel from the email telling them
where to sign in. That needs an SMS gateway, which does not exist yet. Until it does,
this is the trade the bank has accepted.

## Security decisions still owed

**Staff sign-in has no second factor**, no device binding and no IP restriction. Staff
credentials reach other people's accounts, so they need *more* verification than a
customer's, not less. The bank has to say what that is before this goes live. This is the
largest single gap in the portal.

**Whether a sole company user may approve their own payment** is undecided. The mock
currently refuses it. See `src/mocks/data/onboarding.ts`.

**Freezing an account is deliberately NOT four-eyes, and unfreezing is a question the bank
has to answer.** A manager freezes on their own signature, with a required written reason.
That is a considered choice: a freeze is protective, and making a protective action wait
for a second person costs exactly the minutes that matter when a login is believed
compromised. Account creation is four-eyes because it *grants* access; a freeze removes it.

Unfreezing does not have that justification. It restores access to an account somebody
judged compromised, it is not urgent in the same way, and one manager can currently freeze
and unfreeze alone — so the control against a manager acting improperly is the audit trail
(`frozen_by`, `frozen_at`, `freeze_reason`, retained after unfreezing) and not prevention.
**The bank should decide whether restoring access needs a second approver.** If it does, it
is the same mechanism already used for approval, applied to `unfreeze`.

Two properties of the freeze that must not be undone:

- **It ends sessions already open**, not just the next sign-in, via
  `CustomerStillAllowedFilter` re-reading the customer's status on every authenticated
  request. Without that, freezing a compromised login stops the attacker's next sign-in and
  leaves the one already inside untouched — the opposite of the only case where the timing
  matters. `AccountFreezeTest.freezingEndsTheLiveSession` fails if the filter leaves the
  chain; this was verified by removing it.
- **The customer is told that it happened and never why.** A freeze may concern an
  investigation the customer must not be tipped off about, so the reason is staff-only: it
  is absent from the frozen email and from every customer-facing response.

**Identity is verified outside this system, and that is the design.** Registration
accepts any account number of the right shape (10–16 digits) and does not ask whether it
exists. Matching the account number, national ID and date of birth against the bank's own
records is a separate process; the manager's approval is the record that it was done.

Three consequences that must not be quietly undone:

- **An application holds claims, not facts** — including the applicant's own name, which
  the form asks for because nothing here can supply it. No screen may present any of it
  as confirmed before approval, and the approval warning says explicitly that nothing in
  the system has checked it.
- **The manager needs to see what they are attesting to.** The approval panel links to the
  registration, because for a while it instructed the manager to check identity details it
  did not display.
- **THE ACCOUNT-NUMBER FORMAT IS A GUESS.** 10–16 digits, matched on both sides. If
  Zigama's real numbers are shorter or longer, real clients are turned away at the form.
  The bank has to confirm it.

There is no longer any "we could not match those details" refusal on the real API. The
mock still has a test trigger that produces one — harmless for demonstrating the error
screen, but it no longer corresponds to anything the backend does.

**Sessions live in Tomcat's memory.** A second replica would not recognise a session
created by the first. Spring Session with Redis or JDBC is the answer, and it is
configuration rather than a rewrite — but it has to happen before this runs on more than
one instance.

**Whether the correlation id is logged server-side.** The frontend generates a reference
and sends it as `X-Correlation-Id`; the whole point is that support can find the failure
by it. If the Spring Boot side does not record the incoming header, the reference on the
error screen points at nothing. Worth confirming before support starts quoting them.

## Development-only, and what actually keeps it out of a build

These are the development affordances. They now live together on one page,
`/staff/developer-tools`, which says on it what it is for:

- The **development mailbox** — every message the service would have emailed. This was
  `/staff/outbox`, named like an ordinary banking screen, which is how a developer tool
  ended up in a manager's navigation.
- **Start over** — `POST /admin/dev/reset`, in `src/services/devReset.ts`. It deletes every
  application, customer, sign-in record and message. It is behind a typed confirmation
  (`DELETE`), not a single click.
- The mock API, and the local-storage persistence in `src/mocks/data/persist.ts`.

**What keeps them out of a build, measured rather than assumed.** The route is registered
only when a *literal* `import.meta.env.VITE_ENABLE_DEV_TOOLS === 'true'` is true, and the
page is imported **by path** inside that conditional. Three weaker arrangements were tried
first and each was caught by grepping `dist`, not by reasoning:

1. Route registered unconditionally, navigation link hidden — build emitted
   `devReset-*.js` containing the literal `/admin/dev/reset`.
2. Gated on `appConfig.enableDevTools` — a property of a runtime object cannot be constant-
   folded, so the branch and its dynamic `import()` both survived.
3. Still reached through the `@/features/staff` barrel — `router.tsx` loads that barrel as a
   namespace, and a namespace import retains **every** export, so the page stayed alive
   whether or not a route referenced it. `StaffDeveloperToolsPage` is deliberately not
   exported from `src/features/staff/index.ts`, with a comment saying why.

With the flag off: `'/admin/dev/reset'` and `'Type DELETE'` both appear **0** times in
`dist`. With it on, both appear once. The comment in `devReset.ts` used to claim the
dynamic import alone was sufficient; it was wrong, and it has been corrected.

**The server is the control, not any of this.** `POST /admin/dev/reset` requires staff
credentials and refuses outside the `dev`, `h2` and `test` profiles. No arrangement of the
client can point it at a real deployment. The flag decides whether a *demonstration* build
shows the page — which is why it is a flag and not `!isProduction`.

## Test inputs

These used to be listed on the registration screens. They force specific server responses
from the mock and are the way to demonstrate each error state without waiting for one.
Defined in `src/mocks/testTriggers.ts`.

| Input | Where | Result |
|---|---|---|
| `0000000000` | Account number | Details not matched |
| `1111111111` | Account number | Rate limited |
| `9999999999` | Account number | Server error |
| `000000` | Verification code | Incorrect |
| `111111` | Verification code | Expired |
| `222222` | Verification code | Locked out |
| `NOSUCHCO` | Company code | Unknown company |
| `REJECTED` | Company code | Not accepting requests |

## Staff logins (development)

Seeded in `src/mocks/data/onboarding.ts`. There are no seeded **customers** by design —
the only way into the customer portal is to register, have an administrator create the
account and a manager approve it, which is the chain most worth testing.

| Role | Identifier | Password |
|---|---|---|
| Administrator | `admin@zigama.local` | `ZigamaStaff1` |
| Manager | `manager@zigama.local` | `ZigamaStaff1` |

Staff portal: `/staff/login`.

## The emailed sign-in code is OFF, so a password is all that stands in the way

`ibanking.sign-in.code-required` defaults to **false**, so a customer signs in with a
password and nothing else. A leaked password therefore reaches somebody's money from
anywhere in the world, with nothing in the way. This is the largest single gap in the
portal's security and it is a deliberate choice, not an oversight.

Why: the code was asked for on every sign-in, which made the portal tiring to use. Two
attempts were made to keep it and make it bearable — remember one browser for thirty
days, then remember several — and both failed against how the portal is actually used
(multiple accounts in one browser, multiple tabs, one shared cookie). The bank's original
instruction was to remove it, so it is removed.

`LOGIN_CODE_REQUIRED=true` turns it back on, and everything behind it still works: the
code, the challenge, the attempt counter, and the remembered-browser machinery below. All
of it is still covered by tests, which run with the property on.

**Before real customers, this has to come back** — with something better than a shared
cookie behind it. The obvious candidates are a per-account trusted-device list the
customer can see and revoke, or a proper authenticator app rather than email.

## Remembered browsers: the token is not rotated on use

`TrustedDevices` issues a device token when the emailed-code step is completed and then
accepts that same token for thirty days. A copied cookie is therefore usable for the rest
of that window.

Rotating the token on each successful sign-in would shorten the window to a single use,
which is the stronger design. It is not done yet because two tabs signing in at once
would race: both would present the same token, one would rotate it, and the other would
be refused and asked for a code it did not need. Doing it properly means accepting the
old token for a short grace period after rotation, and that is a change worth making
deliberately rather than as a footnote.

Mitigated meanwhile by the cookie being HttpOnly, Secure outside development,
SameSite=Lax and scoped to `/api/v1/auth`, and by a freeze revoking every device.

## Remembered browsers: the customer cannot see or revoke them

The bank can withdraw trust — freezing does it automatically — and the customer cannot.
There is no screen listing "browsers that can sign in without a code" and no button to
clear them, which is the control somebody wants the moment a laptop is lost. The data is
already there: `trusted_devices` records `created_at`, `last_used_at` and `expires_at`
per device, so this is a screen and an endpoint, not a schema change.

## Notifications: the money feed shows at most 50 movements

The log on the notifications page asks `/transactions/recent` for 50, which is that
endpoint's maximum. A customer with more than fifty movements cannot reach the older ones
from that screen — the per-account statement pages the rest, this one does not. It needs
either a paged endpoint across every account or a "show older" control driving the
existing per-account paging.
