/**
 * Bank-staff and onboarding DTOs.
 *
 * PROPOSED CONTRACT — NOT DEFINED IN THE BLUEPRINT. The brief covers the customer
 * portal; it says nothing about how a customer gets an account in the first place, who
 * inside the bank creates one, or who signs it off. These shapes are the front end's
 * proposal for that, and the backend should publish the real ones.
 *
 * The pipeline they describe:
 *
 *   someone registers        -> Application (SUBMITTED)
 *   an admin creates a login -> Application (ACCOUNT_CREATED) + Customer (PENDING_APPROVAL)
 *   a manager signs it off   -> Customer (ACTIVE) and an email goes out
 *   the customer signs in    -> forced to replace the temporary password
 *
 * Two people, two steps. The admin who creates an account cannot approve it, for the
 * same reason a maker cannot approve their own payment: issuing credentials to a
 * fictitious customer is the single most valuable thing an insider can do.
 */

import type { BeneficiaryStatus, BeneficiaryType } from './banking';

/* ------------------------------------------------------------------ staff */

/**
 * A member of bank staff.
 *
 * Deliberately NOT a `SessionUser`. Staff are not customers: they hold no accounts, they
 * move no money of their own, and conflating the two is how a support tool ends up able
 * to spend a customer's balance. They sign in at their own URL and their session says
 * which kind of user they are.
 */
export type StaffRole = 'ADMIN' | 'MANAGER';

export interface StaffUser {
  readonly id: string;
  readonly fullName: string;
  readonly email: string;
  readonly role: StaffRole;
  readonly branch: string;
  readonly lastLoginAt: string | undefined;
}

/* ------------------------------------------------------------------ applications */

export type ApplicationKind = 'PERSONAL' | 'BUSINESS' | 'JOIN_BUSINESS';

export type ApplicationStatus =
  /** Registered and waiting for an admin to look at it. */
  | 'SUBMITTED'
  /** An admin has created the login; a manager has to approve it. */
  | 'ACCOUNT_CREATED'
  /** A manager approved it. The customer can sign in. */
  | 'APPROVED'
  /** Turned down, with a reason the applicant is told. */
  | 'REJECTED';

/** A detail captured at registration, shown to the admin as a label/value pair. */
export interface ApplicationField {
  readonly label: string;
  readonly value: string;
}

export interface Application {
  readonly id: string;
  readonly reference: string;
  readonly kind: ApplicationKind;
  readonly status: ApplicationStatus;
  readonly submittedAt: string;

  /** Person's name, or the company's. What the admin scans the list for. */
  readonly displayName: string;
  readonly email: string;
  readonly phone: string;

  /**
   * The identity details a PERSONAL applicant gave, for the admin to check against the
   * bank's own record before creating a login. Absent on other kinds.
   *
   * Typed fields rather than pre-formatted label/value pairs, because the label is a
   * presentation decision and belongs on this side: an API that ships "National ID
   * number" as a string cannot be read in Kinyarwanda or French without changing the
   * API.
   */
  readonly accountNumber?: string;
  readonly nationalId?: string;
  readonly dateOfBirth?: string;

  /**
   * Extra label/value fields for the kinds whose shape is not settled yet — business
   * registrations and company joins, which carry a TIN, a sector, signatories and so on.
   *
   * OPTIONAL, and that is not cosmetic: the real backend does not send this at all, and
   * assuming it did crashed the application detail page with "Cannot read properties of
   * undefined". When those kinds are implemented for real they should get typed fields
   * like the three above, and this should stop existing.
   */
  readonly details?: readonly ApplicationField[];

  /**
   * Whether the applicant proved they can read the email address they gave.
   *
   * It matters to the person approving this, not just to the form. Everything after
   * this point is delivered by email — the approval notice, and the conversation in
   * which a temporary password is arranged — so an unverified address means the
   * applicant may be uncontactable, or the address may belong to someone else
   * entirely. An admin should see which of those they are looking at before creating
   * a login.
   */
  readonly emailVerified: boolean;

  /** Set once an admin has created the login. */
  readonly customerId?: string;
  /** Set when a manager turns it down. */
  readonly rejectionReason?: string;
}

/* ------------------------------------------------------------------ customers */

export type CustomerStatus = 'PENDING_APPROVAL' | 'ACTIVE' | 'REJECTED' | 'SUSPENDED';

/**
 * How to render {@link Customer.accountMasks}, in one place.
 *
 * Three screens joined that array with a comma, so an empty one produced a blank
 * "Account" row that reads as a page that failed to load rather than as the truth: no
 * account is linked because this service does not open them.
 */
export function describeAccountMasks(masks: readonly string[] | undefined): string {
  /*
   * Tolerates undefined as well as empty. See `asArray` below for why the server can
   * legitimately send neither.
   *
   * "None linked yet" was the permanent state of every customer for as long as
   * accountMasks was a hardcoded empty list on the server. It can now change, because a
   * manager can link a real account — so the wording is a state rather than a fact about
   * the product.
   */
  return masks === undefined || masks.length === 0 ? 'None linked yet' : masks.join(', ');
}

/* --------------------------------------------- defending the contract */

/**
 * An array that is definitely an array.
 *
 * WHY THIS EXISTS, and it is the same fault three times in a row.
 *
 * The backend runs `default-property-inclusion: non_null`, so a null field is OMITTED
 * from the JSON rather than sent as null. An older or partially deployed server omits a
 * field it does not know about at all. Either way the client receives an object whose
 * type says `readonly T[]` and whose value is `undefined`, and TypeScript cannot catch it
 * because the type is a promise about the server, not a fact about the response.
 *
 * What that has cost so far, all of it a white screen in a banking portal:
 *
 *   `application.details.map(...)`        — crashed the registration detail page
 *   `customer.accountMasks.join(...)`     — crashed the staff portal after "create account"
 *   `created.requestedAccounts.length`    — crashed it again after this field was added
 *
 * Each was patched where it crashed, which is why there was a next one. The fix is to
 * normalise at the boundary instead, so a missing array becomes an empty array once, for
 * every consumer, before any component sees it.
 */
function asArray<T>(value: readonly T[] | undefined | null): readonly T[] {
  /*
   * The cast is doing real work, not silencing a complaint. `Array.isArray` narrows a
   * `readonly T[]` to `any[]` — TypeScript's type guard for it predates readonly arrays
   * — so returning the narrowed value directly loses T and lets anything through
   * downstream. The runtime check is genuine; only the narrowing is wrong.
   */
  return Array.isArray(value) ? (value as readonly T[]) : [];
}

/**
 * Makes a {@link Customer} safe to render whatever the server left out.
 *
 * Applied in `adminService`, not in each screen — a screen that has to remember is a
 * screen that will forget.
 */
export function normaliseCustomer(customer: Customer): Customer {
  return {
    ...customer,
    accountMasks: asArray(customer.accountMasks),
    accounts: asArray(customer.accounts),
  };
}

/**
 * How a customer's accounts read on screen.
 *
 * One place, like {@link describeAccountMasks}, because three screens will otherwise each
 * invent their own wording.
 */
export function describeCustomerAccounts(accounts: readonly CustomerAccount[] | undefined): string {
  if (accounts === undefined || accounts.length === 0) return 'None yet';

  return accounts
    .map(
      (entry) =>
        `${ACCOUNT_TYPES.find((type) => type.value === entry.accountType)?.label ?? entry.accountType} · ${entry.maskedNumber} · ${entry.currency}`,
    )
    .join(', ');
}

/** The same, for the create-account response. */
export function normaliseCreatedCustomer(created: CreatedCustomer): CreatedCustomer {
  return { ...created, customer: normaliseCustomer(created.customer) };
}

/** And for an application, whose `details` is optional by design. */
export function normaliseApplication(application: Application): Application {
  return { ...application, details: asArray(application.details) };
}

export interface Customer {
  readonly id: string;
  readonly applicationId: string;
  readonly fullName: string;
  readonly email: string;
  readonly phone: string;
  readonly customerNumber: string;
  readonly userType: 'RETAIL' | 'CORPORATE';
  readonly status: CustomerStatus;

  readonly createdAt: string;
  readonly createdByName: string;
  readonly approvedAt?: string;
  readonly approvedByName?: string;
  readonly rejectionReason?: string;

  /** True until the customer replaces the password the bank emailed them. */
  readonly mustChangePassword: boolean;

  /**
   * Masked identifiers of the accounts this login can reach.
   *
   * OFTEN EMPTY, and that is not a fault: this service issues a login, it does not open
   * bank accounts. The real ones live in the core banking system, which is not connected.
   * Always an array — never absent — because the screens join it, and reading
   * `.join()` on undefined is exactly how the staff portal crashed after an
   * administrator pressed "create account".
   */
  readonly accountMasks: readonly string[];

  /**
   * The customer's accounts in full, for STAFF.
   *
   * `accountMasks` above is the same set flattened to masks, kept because the portal
   * already reads entitlement that way. Always sent by the server, so an empty list means
   * "no accounts" rather than "this endpoint did not look" — an ambiguity that hid a
   * discarded request body for longer than it should have.
   */
  readonly accounts: readonly CustomerAccount[];

  /*
   * The freeze record. STAFF-ONLY, all three.
   *
   * Optional rather than nullable because the backend omits null fields
   * (`default-property-inclusion: non_null`), so an account that has never been frozen
   * has no such keys at all — and under `exactOptionalPropertyTypes` absent and null are
   * not interchangeable.
   *
   * KEPT AFTER UNFREEZING, deliberately. These describe the last freeze or unfreeze,
   * not the current state — `status` is what says whether the account is frozen now.
   * Restoring access should not erase the fact that access was once removed, so a screen
   * must read `status === 'SUSPENDED'` to decide what to show, never the presence of
   * `freezeReason`.
   */
  readonly frozenBy?: string;
  readonly frozenAt?: string;
  /**
   * Why. Never rendered anywhere a customer can reach — the frozen email carries none of
   * it, because a freeze may concern an investigation the customer must not be tipped off
   * about.
   */
  readonly freezeReason?: string;
}

/* ------------------------------------------------ opening accounts */

/**
 * The account types a branch can ask for. MIRRORS {@code AccountType} on the backend.
 *
 * **THIS LIST IS A PLACEHOLDER AND ZIGAMA HAS NOT CONFIRMED IT.** It is a plausible set
 * for a Rwandan savings bank, which is what makes it risky: it will read as authoritative
 * to anyone who opens the dropdown. The real catalogue — what each product is called to a
 * customer, who is eligible, which currencies it supports — has to replace this before
 * the screen goes near a branch. Tracked in docs/OPEN-ITEMS.md.
 *
 * CURRENCY IS NOT A TYPE. A foreign-currency account is one of these with a currency that
 * is not RWF, not a value of its own. `FCY_SAVINGS` would mean the same product exists
 * twice under different names, "savings in EUR" would need a third value, and a report
 * counting savings accounts would have to know every spelling.
 *
 * AND NEITHER ARE COMBINATIONS. A customer opening a current account and a savings account
 * is opening TWO accounts — two rows, each closeable and reportable on its own. That is
 * why the form lets you add accounts rather than offering `CURRENT_AND_SAVINGS`, which
 * could never describe closing one of the pair.
 */
/**
 * A customer as the bank's core banking system holds them.
 *
 * READ, NEVER STORED. This is what the account number on an application resolves to, and
 * it is the bank's record rather than this service's: nothing here is saved, and the only
 * part that survives the screen is what a member of staff confirms and creates.
 *
 * `telephoneMask` is the last three digits and the server sends nothing more. Staff need
 * to compare it against the number on the application, and comparing does not require the
 * whole number.
 */
export interface CbsCustomer {
  readonly accountNumber: string;
  readonly name: string;
  readonly customerType: 'INDIVIDUAL' | 'COMPANY';
  readonly status: string;
  readonly dormant: boolean;
  readonly openedOn: string | null;
  readonly telephoneMask: string;
  readonly holdings: readonly CbsHolding[];
}

/**
 * One product held under that number.
 *
 * `balance` is a DECIMAL STRING, never a number. A balance that goes through JavaScript's
 * floating point is a balance that can come back wrong, and this one is about to be
 * submitted as an opening figure.
 */
export interface CbsHolding {
  readonly product: AccountType;
  readonly currency: string;
  readonly balance: string;
}

export type AccountType =
  /* The bank's own five, read from the core banking system. */
  | 'CURRENT'
  | 'COMPULSORY_SAVINGS'
  | 'VOLUNTARY_SAVINGS'
  | 'FIXED_DEPOSIT'
  | 'EDUCATION_SAVINGS'
  /* Legacy. Not offered anywhere; kept until no stored row holds one. */
  | 'SAVINGS'
  | 'SALARY'
  | 'JUNIOR_SAVINGS'
  | 'TARGET_SAVINGS'
  | 'LOAN_SERVICING';

/**
 * What each type is called on screen.
 *
 * NO LONGER A DROPDOWN'S OPTIONS, and the wording is no longer a placeholder. The five
 * products are the bank's own, taken from the core system's balance columns, and a member
 * of staff does not choose between them any more -- the type arrives with the account when
 * the customer's record is fetched. This map exists so that a stored type has one label,
 * in one place, wherever it is shown.
 *
 * The legacy five are here only so an old row renders as words rather than as a constant.
 */
export const ACCOUNT_TYPE_LABELS: Readonly<Record<AccountType, { label: string; hint: string }>> = {
  CURRENT: { label: 'Current account', hint: 'Day-to-day transacting.' },
  COMPULSORY_SAVINGS: {
    label: 'Compulsory savings',
    hint: 'Required contributions, the standing commitment.',
  },
  VOLUNTARY_SAVINGS: {
    label: 'Voluntary savings',
    hint: 'Saved over and above the required amount.',
  },
  FIXED_DEPOSIT: { label: 'Fixed deposit', hint: 'Locked for a fixed term at an agreed rate.' },
  EDUCATION_SAVINGS: { label: 'Education savings', hint: 'Put aside for school fees.' },

  SAVINGS: { label: 'Savings account', hint: 'No longer offered.' },
  SALARY: { label: 'Salary account', hint: 'No longer offered.' },
  JUNIOR_SAVINGS: { label: 'Junior savings', hint: 'No longer offered.' },
  TARGET_SAVINGS: { label: 'Target savings', hint: 'No longer offered.' },
  LOAN_SERVICING: { label: 'Loan servicing', hint: 'No longer offered.' },
};

/**
 * The same thing as a list, for the places that render every type.
 *
 * DERIVED rather than written out a second time: two hand-maintained copies of a product
 * catalogue drift, and the one that drifts is always the one somebody reads.
 */
export const ACCOUNT_TYPES: readonly { value: AccountType; label: string; hint: string }[] =
  Object.entries(ACCOUNT_TYPE_LABELS).map(([value, meta]) => ({
    value: value as AccountType,
    ...meta,
  }));

/**
 * Only the five the bank actually holds.
 *
 * For anywhere that offers a choice. Nothing should offer the legacy values, and the
 * separation means a screen cannot accidentally present one by reaching for the full list.
 */
export const OFFERED_ACCOUNT_TYPES: readonly { value: AccountType; label: string; hint: string }[] =
  ACCOUNT_TYPES.filter((type) =>
    (
      [
        'CURRENT',
        'COMPULSORY_SAVINGS',
        'VOLUNTARY_SAVINGS',
        'FIXED_DEPOSIT',
        'EDUCATION_SAVINGS',
      ] as const
    ).includes(type.value as 'CURRENT'),
  );

/**
 * Currencies offered in the form. ALSO A PLACEHOLDER.
 *
 * The server validates the shape (three letters) rather than membership of a list,
 * deliberately: a hardcoded guess at which currencies Zigama deals in would turn a real
 * customer away at the counter. This list is only what the dropdown offers.
 */
export const ACCOUNT_CURRENCIES: readonly string[] = ['RWF', 'USD', 'EUR', 'GBP'];

/**
 * One account the administrator is entering for this customer.
 *
 * A number, a type and a currency. The administrator reads all three off Zigama's own
 * records — which this service has no view of — while creating the login.
 */
export interface NewAccount {
  readonly accountNumber: string;
  readonly accountType: AccountType;
  readonly currency: string;
  /**
   * The balance this account starts with.
   *
   * A decimal STRING, never a number. A JSON number goes through a binary floating-point
   * type on the way to the server, so 1234.30 can arrive as 1234.2999999999999 — a
   * rounding decision nobody made, on somebody's opening balance.
   *
   * Empty means an account opened at zero, which is ordinary. The server reads an empty
   * box as "0" rather than refusing it.
   */
  readonly openingBalance: string;
}

/**
 * What the administrator submits.
 *
 * THE OPENING BALANCE IS BACK, and this comment used to argue at length that it should
 * never be. The argument was that money the branch has taken is a movement in the core
 * banking ledger, and a figure stored here would be a second, unreconciled record of it.
 *
 * The bank decided the portal holds the balances. The risk the old comment named has not
 * gone away — if core banking also holds them the two WILL diverge, and nothing
 * reconciles them — but it is the bank's decision to carry, and it is written down in
 * docs/OPEN-ITEMS.md rather than re-argued here.
 *
 * What the front end still must not do is compute one. The figure the administrator types
 * is submitted as text and every balance shown afterwards is the server's.
 */
export interface CreateCustomerRequest {
  readonly accounts: readonly NewAccount[];
}

/**
 * An account on a customer's profile, as STAFF see it.
 *
 * NO FULL NUMBER — the server keeps it for identifying the account at the bank and
 * returns only the mask.
 *
 * NO BALANCE ON THE STAFF VIEW either, and that is a separate decision from the customer's
 * screens, which do show one. Staff need to know which accounts are on a profile and who
 * put them there; how much is in them is the customer's business, and a balance on an
 * approvals list is a balance on a screen that is read over somebody's shoulder all day.
 *
 * `assignedBy` is the verification record. Nothing in this service checks that the account
 * exists or belongs to this person; a named administrator read it off the bank's records,
 * exactly as the approving manager's name records that the identity was checked.
 */
export interface CustomerAccount {
  readonly id: string;
  readonly maskedNumber: string;
  readonly accountType: AccountType;
  readonly currency: string;
  readonly assignedBy: string;
  readonly assignedAt: string;
}

/*
 * The OLD CreateCustomerRequest carried accountType, currency, openingBalance and branch
 * as flat fields.
 *
 * The endpoint took no body, so none of it was ever read and there was no table it could
 * have been written to — the administrator chose "Current account", saw a success
 * message, and nothing happened. It is now a list, the endpoint reads it, and
 * account_opening_requests stores one row per account.
 */

/**
 * What the create-account call returns.
 *
 * NO PASSWORD. It used to carry one, returned exactly once for the administrator to hand
 * over in person. The bank chose to email the temporary password instead, so it is
 * generated when a manager approves and lives only in that message — there is nothing
 * for this response to carry, and no endpoint anywhere returns a credential.
 */
export interface CreatedCustomer {
  readonly customer: Customer;
}

/* -------------------------------------------------- forgotten passwords */

/**
 * A customer waiting for a new password, as the queue screen reads it.
 *
 * CARRIES NO CREDENTIAL. The password is created when a manager presses the button and
 * exists only in the email composed from it — no endpoint in this API returns one, and if
 * one ever appears in this interface something has gone badly wrong upstream.
 */
export interface PasswordRequest {
  readonly id: string;
  readonly customerId: string;
  readonly customerNumber: string;
  readonly fullName: string;
  readonly email: string;
  /**
   * The CUSTOMER's status, not the request's.
   *
   * Here because it is what the manager has to look at first: a frozen customer cannot
   * sign in whatever password they are handed, and the server refuses to issue one. Shown
   * in the queue so the manager learns that before pressing the button rather than from
   * the error it returns.
   */
  readonly status: CustomerStatus;
  readonly requestedAt: string;
}

/* -------------------------------------------------------- payees to review */

/**
 * How the name the customer typed compares with the name the bank holds.
 *
 * `UNAVAILABLE` IS NOT A MISMATCH and the screen must never render them the same way. A
 * payee at another bank, abroad, or on a mobile wallet is held by an institution this
 * service cannot ask, so no comparison was made — and a reviewer shown a blank where the
 * comparison belongs reads it as a pass.
 *
 * `PARTIAL` is a short form: "Teta Eliana" against "Teta Eliana Muzora". It is both the
 * commonest innocent case and a usable disguise, so it is its own verdict rather than
 * being rounded to one of the other two.
 */
export type NameCheck = 'MATCH' | 'PARTIAL' | 'MISMATCH' | 'UNAVAILABLE';

/**
 * A saved payee waiting for a member of staff, with what they need in order to decide.
 *
 * THE TWO NAMES ARE THE WHOLE POINT. `name` is what the CUSTOMER typed and is evidence of
 * nothing — it is free text. `heldName` is who the bank holds that account for. Together
 * with `nameCheck` the reviewer's job is a comparison; with `name` alone there is nothing
 * to compare and approving is a reflex.
 *
 * `heldName` IS OPTIONAL RATHER THAN NULLABLE because the service sets
 * `default-property-inclusion: non_null` — a field with no value is absent from the JSON,
 * not sent as `null`.
 *
 * NO FULL ACCOUNT NUMBER, here as everywhere: a staff screen is still a screen in an
 * open-plan office with somebody standing behind it.
 */
export interface BeneficiaryReview {
  readonly id: string;
  readonly customerId: string;
  readonly customerNumber: string;
  readonly customerName: string;
  /** What the customer called the payee. Free text, and the thing being checked. */
  readonly name: string;
  /** Who the bank holds the account for, absent where it holds nobody. */
  readonly heldName?: string;
  readonly nameCheck: NameCheck;
  readonly beneficiaryType: BeneficiaryType;
  readonly provider: string;
  readonly maskedDestination: string;
  readonly currency: string;
  readonly addedAt: string;
  readonly status: BeneficiaryStatus;
}

/* ------------------------------------------- cards and cheque books to make */

/**
 * A card or cheque-book request as the STAFF queue sees it.
 *
 * SEPARATE FROM `ServiceRequest` in types/banking.ts, which is the same row as the
 * CUSTOMER sees it. They are deliberately two interfaces rather than one with optional
 * staff fields: this one carries the customer's name, number and email, which must never
 * appear in a response to a customer, and the customer's one carries the decline reason,
 * which the queue has no use for because the queue only ever holds open requests. A single
 * shared shape would make every one of those fields optional and the compiler would stop
 * objecting to either leak.
 *
 * THE ACCOUNT IS A MASK, not an id and not a number — same rule as every other staff
 * screen, for the same reason: this is a dense screen in an open-plan office.
 *
 * `collectionPoint` IS ABSENT UNTIL SOMEBODY TYPES ONE. Optional rather than nullable
 * because the service omits null fields from its JSON.
 */
export interface ServiceRequestQueueItem {
  readonly id: string;
  /** What the customer quotes on the telephone: CRD-XXXXXX or CHQ-XXXXXX. */
  readonly reference: string;
  readonly customerId: string;
  readonly customerNumber: string;
  readonly customerName: string;
  readonly email: string;
  /* Three now: a loan application reaches the same queue. It is NOT collected, so the
     ready-with-a-collection-point step does not apply to it. */
  readonly requestType: 'CARD' | 'CHEQUE_BOOK' | 'LOAN_APPLICATION';
  /** What to make, composed by the service: "Debit card for **** 7890". */
  readonly details: string;
  readonly accountMask: string;
  readonly status: 'SUBMITTED' | 'READY' | 'COLLECTED' | 'GRANTED' | 'DECLINED';
  readonly submittedAt: string;
  /** Where the customer was told to collect it, in the staff member's own words. */
  readonly collectionPoint?: string;

  /*
   * WHAT THE CUSTOMER ASKED TO BORROW. Present only on a loan application, and absent even
   * then if it was raised before the portal recorded terms as figures. The grant form opens
   * on these; where they are missing it opens empty rather than on an invented default.
   *
   * THE ASK, NOT THE DECISION. The bank may lend something else.
   */
  readonly loanProduct?: string;
  readonly loanAmount?: string;
  readonly loanCurrency?: string;
  readonly loanMonths?: number;
}

/** What a manager decided to lend, which need not be what was asked for. */
export interface GrantLoanRequest {
  readonly product: string;
  /** A decimal string, never a number: see the note on the opening balance. */
  readonly amount: string;
  readonly currency: string;
  readonly months: number;
  /** Also a string on the wire, for the same reason as the amount. */
  readonly annualRatePercent: string;
}

/* ------------------------------------------------------------------ outbox */

export type OutboxKind =
  | 'ACCOUNT_APPROVED'
  | 'ACCOUNT_REJECTED'
  | 'ACCOUNT_CREATED'
  | 'EMAIL_VERIFICATION'
  | 'ACCOUNT_FROZEN'
  | 'ACCOUNT_UNFROZEN'
  /**
   * A manager re-issued a temporary password for a customer who had forgotten theirs.
   *
   * Its own kind rather than reusing ACCOUNT_APPROVED, because the sent-messages screen
   * is the record of what the bank put in somebody's inbox — and two events that both
   * deliver a working credential have to be told apart when a sign-in is disputed.
   */
  | 'PASSWORD_REISSUED'
  | 'PASSWORD_REQUEST_REFUSED'
  /**
   * Staff checked a payee the customer saved, and it can now be paid.
   *
   * Its own kind because it is the message that ends a wait the customer was put in, and
   * because it is the bank's one chance to tell somebody a payee appeared on their account
   * that they did not add — the clearest single sign another person has their password.
   */
  | 'BENEFICIARY_APPROVED'
  /** Staff declined a payee, with the reason the customer is shown. */
  | 'BENEFICIARY_REFUSED'
  /**
   * A card or cheque book is ready, and where to collect it.
   *
   * ITS OWN KIND, because it is the only message the bank sends that names a physical
   * place and tells somebody to bring identification there. Folding it into a general
   * notification kind would make that sentence unsearchable in the sent-messages screen,
   * which is where a complaint about being sent to the wrong branch gets checked.
   */
  | 'SERVICE_REQUEST_READY'
  /** Staff declined a card or cheque book, with the reason the customer is shown. */
  | 'SERVICE_REQUEST_DECLINED';

/**
 * A message the bank would have sent.
 *
 * There is no mail server wired up (see docs/OPEN-ITEMS.md). Rather
 * than pretend an email went out, every message is recorded here and shown on a
 * development-only screen, so the flow can be tested end to end and so it is obvious
 * exactly what a customer would receive.
 */
export interface OutboxMessage {
  readonly id: string;
  readonly kind: OutboxKind;
  /**
   * The address the message appears to come from.
   *
   * Recorded rather than assumed, because the whole purpose of the outbox is to show
   * exactly what a customer receives — and who a bank's mail claims to be from is the
   * first thing a careful customer looks at.
   */
  readonly from: string;
  readonly to: string;
  readonly subject: string;
  readonly body: string;
  readonly sentAt: string;
}

/**
 * A transfer a customer has submitted and a manager has not yet decided.
 *
 * MASKS, NOT NUMBERS. The queue is the densest screen in the portal and the one most
 * likely to be screenshotted or shoulder-read, so both accounts arrive already masked
 * and the full numbers never leave the server.
 *
 * `amount` is a decimal STRING with `currency` beside it, like every other amount in
 * this API. The manager must see the figure the ledger holds — a JSON number would be a
 * double by the time it reached this screen, and a double cannot hold 1234.30.
 */
export interface PendingTransfer {
  readonly id: string;
  readonly sourceMask: string;
  readonly destinationMask: string;
  readonly amount: string;
  readonly currency: string;
  readonly reference: string;
  readonly submittedAt: string;
}

/**
 * One field of a pledged document, as the person deciding the loan needs to see it.
 *
 * THREE VALUES, NOT ONE. `declared` is what the customer typed and is what the bank holds
 * them to; `read` is what a machine thought the page said; `agreement` is the verdict
 * recorded when the application was submitted. Collapsing them into a single "value" is
 * exactly the collapse this screen exists to prevent -- a reviewer who sees only the typed
 * figure is reviewing a form, not a document.
 *
 * OPTIONAL, NOT NULLABLE, like every other absent field in this API: the service is
 * configured with `default-property-inclusion: non_null`, so a null never arrives — the key
 * is simply missing. A screen written against `=== null` would treat every absence as a
 * value and render the word "undefined" where a dash belongs.
 *
 * `declared` is absent on a row from `alsoRead`: detail the reader found that the customer
 * was never asked for and therefore never declared.
 *
 * `read` is absent when the reader found nothing for that field, which is `NOT_READ` and is
 * not a disagreement.
 */
export interface PledgedField {
  readonly key: string;
  readonly label: string;
  readonly declared?: string;
  readonly read?: string;
  readonly agreement: 'SAME' | 'DIFFERENT' | 'NOT_READ' | 'NOT_DECLARED';
}

/**
 * One thing pledged against a loan application, with the document that evidences it.
 *
 * NOTHING HERE IS VERIFIED. The portal cannot ask the land authority whether a parcel
 * number exists or who owns it, and a field marked SAME only means the customer typed what
 * the page says. This is evidence put in front of a person, not a check the system passed.
 *
 * `text` is everything the reader produced, kept so a disagreement can be judged in context
 * rather than from a one-line verdict. It is absent when nothing could be read at all.
 */
export interface PledgedItem {
  readonly id: string;
  readonly kind: string;
  readonly kindLabel: string;
  /** What document this kind of collateral is evidenced by, in the bank's words. */
  readonly expectedDocument: string;
  /** The customer's own words for the thing: "Plot at Kabeza", "Toyota Hiace". */
  readonly description: string;
  readonly documentId: string;
  readonly filename: string;
  readonly sizeBytes: number;
  readonly ocrStatus: 'READ' | 'UNREADABLE' | 'FAILED';
  /** The document's own heading, lifted off the page. Absent when none was legible. */
  readonly heading?: string;
  readonly fields: readonly PledgedField[];
  readonly alsoRead: readonly PledgedField[];
  readonly text?: string;
}
