import type { MoneyDto, Permission, TransactionStatus } from './api';

/**
 * Banking domain types the dashboard and later screens read.
 *
 * Shapes the front end needs, not a database schema. When the backend publishes its
 * OpenAPI document these should be generated from it and this file deleted.
 *
 * Two conventions hold throughout:
 *  - Money is always a {@link MoneyDto} with a decimal STRING amount, never a number.
 *  - Identifiers arrive already masked. The client never holds a full account number or
 *    PAN, so it cannot leak one.
 */

/* ------------------------------------------------------------------ session */

export type UserType = 'RETAIL' | 'CORPORATE';

/** A company the signed-in user may act for. */
export interface CorporateMembership {
  readonly id: string;
  readonly name: string;
  /** Short code the administrator shares with colleagues. */
  readonly code: string;
  /** Role within this company. Permissions are the authority, not this label. */
  readonly role: 'ADMIN' | 'MAKER' | 'APPROVER' | 'VIEWER';
}

/**
 * The signed-in user.
 *
 * `permissions` is the only thing the UI may branch on when deciding what to show.
 * Hiding an action is a courtesy; the server still refuses anything unauthorised.
 */
export interface SessionUser {
  readonly id: string;
  readonly fullName: string;
  readonly preferredName: string;
  readonly email: string;
  readonly phone: string;
  /** Masked, e.g. `****3120`. */
  readonly customerNumber: string;
  readonly userType: UserType;
  readonly permissions: readonly Permission[];
  readonly corporates: readonly CorporateMembership[];
  /**
   * The sign-in BEFORE this one, shown in the dashboard's security message.
   *
   * Absent on a first sign-in, because there is no previous one — and that is the common
   * case for a bank that has just started onboarding, not an edge case. Anything
   * rendering this must handle its absence rather than print a placeholder into a
   * sentence: "Last sign-in — from undefined" is worse than saying nothing, because the
   * line exists to help the customer spot a session they did not start.
   */
  readonly lastLoginAt?: string;
  /**
   * What the previous sign-in was done on — "Chrome on Windows" — when the client said.
   *
   * REPLACES `lastLoginLocation`, which was the literal "Kigali, Rwanda" on every session
   * this service ever issued. All four sign-in call sites passed that string and there has
   * never been a geo-IP lookup behind it, so the dashboard printed it to every customer in
   * the world and the profile page printed it under a heading reading "Where". The sign-in
   * history exists so a customer notices access that was not theirs, and a constant
   * defeats that in precisely the case it is for. See the backend's SignInMethod and V19.
   *
   * Absent whenever the client sent no recognisable User-Agent, and not replaced by a
   * default — supplying one here is how the original got onto the screen.
   */
  readonly lastLoginDevice?: string;
  /** How the previous sign-in was done, in the service's own words. */
  readonly lastLoginMethod?: string;
}

/* ------------------------------------------------------------------ accounts */

/*
 * ONE LIST, defined with the admin types.
 *
 * This union used to have its own values — including FCY, which is a currency rather than
 * a type, and LOAN, which nothing produced. Two lists that disagree about what an account
 * can be is one list too many: the administrator enters an account with a type from the
 * other list, and this one decides whether the portal can render it.
 */
import type { AccountType } from './admin';

export type { AccountType };

export type AccountStatus = 'ACTIVE' | 'DORMANT' | 'FROZEN' | 'CLOSED';

export interface Account {
  readonly id: string;
  readonly nickname: string;
  readonly accountType: AccountType;
  /** Masked, e.g. `**** 4582`. */
  readonly maskedNumber: string;
  readonly currency: string;
  readonly status: AccountStatus;
  /*
   * OPTIONAL, because this service does not have them.
   *
   * The accounts are real — an administrator entered them from the bank's records — but
   * the money is in core banking, which is not connected. Absent is the truth; a zero
   * would be a statement about the customer's money that nothing here knows.
   *
   * `formatBalanceDto` renders the absence as "Not available". Do not default these to
   * zero to quiet a type error.
   */
  readonly availableBalance?: MoneyDto;
  readonly currentBalance?: MoneyDto;
  /** When the balance was read from core banking, if it ever was. */
  readonly balanceAsOf?: string;
  /** Present for company accounts; absent for personal ones. */
  readonly corporateId?: string;
  readonly debitAllowed: boolean;
}

export type TransactionDirection = 'DEBIT' | 'CREDIT';

export interface Transaction {
  readonly id: string;
  readonly accountId: string;
  readonly bookedAt: string;
  readonly description: string;
  readonly reference: string;
  readonly direction: TransactionDirection;
  readonly amount: MoneyDto;
  /** Balance after this entry, when the core system supplies it. */
  readonly runningBalance?: MoneyDto;
  readonly status: TransactionStatus;
  readonly category: string;
  /**
   * The other party's name, ABSENT when the movement had no other party.
   *
   * <p>Optional rather than nullable because that is what arrives: the API omits null
   * fields, so a deposit's payload simply has no such key. Treat absence as "nobody",
   * never as an empty name.
   */
  readonly counterpartyName?: string;
  /** The other party's masked account number. Present exactly when the name is. */
  readonly counterpartyMask?: string;
  /**
   * What the movement was, as the SERVER understands it.
   *
   * <p>Screens word their lines from this and never from the direction. A transfer
   * arriving and a transfer being refused are both a credit carrying somebody else's
   * name, so direction alone cannot tell an arrival from a refund.
   *
   * <p>Optional for the moment a client is talking to an older API: `describeMovement`
   * falls back to the cash wording, which states no outcome it cannot know.
   */
  readonly movementKind?: MovementKind;
}

/** What a movement was. Mirrors the backend enum of the same name. */
export type MovementKind = 'CASH' | 'TRANSFER_OUT' | 'TRANSFER_IN' | 'TRANSFER_RETURNED';

/* ------------------------------------------------------------------ beneficiaries */

export type BeneficiaryType = 'INTERNAL' | 'DOMESTIC' | 'INTERNATIONAL' | 'WALLET';

/**
 * WHERE A PAYEE IS IN ITS REVIEW, and these are the only three states there are.
 *
 * A payee is saved PENDING_VERIFICATION and a member of bank staff compares the name the
 * customer typed with the name the bank holds for that account before anything can be sent
 * to it. Nothing but a person moves it on — there is no timer, whatever the old wording
 * about a "cooling-off period" implied.
 *
 * `DISABLED` used to be listed here and was never produced by anything. It has been
 * replaced by `REFUSED`, which the service really does return, and which carries a reason.
 */
export type BeneficiaryStatus = 'ACTIVE' | 'PENDING_VERIFICATION' | 'REFUSED';

export interface Beneficiary {
  readonly id: string;
  readonly name: string;
  readonly beneficiaryType: BeneficiaryType;
  /** Bank or mobile network. */
  readonly provider: string;
  readonly maskedDestination: string;
  readonly currency: string;
  readonly status: BeneficiaryStatus;
  /**
   * WHY STAFF DID NOT APPROVE IT, in their own words, and only ever on a REFUSED payee.
   *
   * OPTIONAL RATHER THAN NULLABLE, which is not a style choice. The service sets
   * `default-property-inclusion: non_null`, so a field with no value is left out of the
   * JSON altogether rather than sent as `null` — a type saying `string | null` would make
   * the compiler expect a key that is not there.
   */
  readonly refusedReason?: string;
  readonly addedAt?: string;
  /**
   * Only the mocks set this; the real service scopes every payee to the signed-in customer
   * server-side and has no reason to tell the browser whose list it is reading.
   */
  readonly ownerId?: string;
}

/* ------------------------------------------------------------------ cards */

export interface Card {
  readonly id: string;
  /** Last four only. The BIN is deliberately never sent to the client. */
  readonly maskedPan: string;
  readonly brand: string;
  readonly cardType: 'DEBIT' | 'PREPAID';
  readonly status: 'ACTIVE' | 'BLOCKED' | 'EXPIRED' | 'PENDING_COLLECTION';
  readonly linkedAccountMask: string;
  readonly expiryMonth: number;
  readonly expiryYear: number;
  readonly cardholderName: string;
  readonly ownerId: string;
}

/* ------------------------------------------------------------------ loans */

export interface Loan {
  readonly id: string;
  readonly product: string;
  readonly reference: string;
  readonly currency: string;
  readonly principal: MoneyDto;
  readonly outstandingBalance: MoneyDto;
  readonly installment: MoneyDto;
  readonly nextDueDate: string;
  readonly interestRatePercent: string;
  readonly status: 'ACTIVE' | 'IN_ARREARS' | 'SETTLED';
  readonly ownerId: string;
}

/* ------------------------------------------------------------------ approvals */

export type ApprovalItemType = 'TRANSFER' | 'PAYMENT' | 'BULK' | 'SALARY';

export interface ApprovalItem {
  readonly id: string;
  readonly corporateId: string;
  readonly itemType: ApprovalItemType;
  readonly submittedAt: string;
  readonly makerName: string;
  /** Maker's user id — used to enforce that nobody approves their own work. */
  readonly makerId: string;
  readonly sourceAccountMask: string;
  readonly beneficiarySummary: string;
  /**
   * Absent when the caller is not entitled to the figure — a salary run seen by someone
   * without SALARY_VIEW_DETAILS.
   *
   * Absent, not zero. A payroll rendered as `RWF 0` is a wrong number on a screen a
   * person is about to approve, and the UI cannot tell it apart from an empty batch.
   */
  readonly amount?: MoneyDto;
  /** True when {@link amount} was withheld, so the UI can say so instead of showing a gap. */
  readonly amountWithheld?: boolean;
  /** e.g. 1 of 2 approvals collected. */
  readonly approvalsCollected: number;
  readonly approvalsRequired: number;
  readonly status: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED';
  /** Warnings the backend attaches, such as an unusually large amount. */
  readonly warnings: readonly string[];
}

/* ------------------------------------------------------------------ bulk */

export type BulkBatchType = 'INTERNAL' | 'EXTERNAL' | 'SALARY' | 'RRA' | 'WALLET';

export type BulkBatchStatus =
  'UPLOADED' | 'VALIDATING' | 'PENDING_APPROVAL' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface BulkBatch {
  readonly id: string;
  readonly corporateId: string;
  readonly reference: string;
  readonly batchType: BulkBatchType;
  readonly createdBy: string;
  readonly createdAt: string;
  readonly totalRecords: number;
  /** Absent when withheld — see {@link ApprovalItem.amount}. */
  readonly totalAmount?: MoneyDto;
  readonly amountWithheld?: boolean;
  readonly status: BulkBatchStatus;
  readonly successCount: number;
  readonly failedCount: number;
  readonly pendingCount: number;
  /**
   * True when the batch contains employee-level pay.
   *
   * The API omits per-row amounts for a user without SALARY_VIEW_DETAILS; this flag lets
   * the UI explain the omission rather than render a confusing empty column.
   */
  readonly containsSalaryDetail: boolean;
}

/* ------------------------------------------------------------------ notifications */

/**
 * Something the bank did that the customer should know about.
 *
 * REAL NOW (migration **V20**), and it is worth recording what it was. The dashboard panel
 * and the notifications page were both answered by the browser's own mock from an array
 * that starts empty on every page load, and no notifications table existed on the server at
 * all — so the panel read "Nothing new." permanently, including immediately after a member
 * of staff produced a customer's card, typed the counter to collect it from, and emailed
 * them about it.
 *
 * AN EMPTY LIST IS A CLAIM, not a neutral state: a customer reads it as a record and
 * concludes nothing happened. That is why this is written in the same transaction as the
 * event that caused it.
 *
 * `severity` IS DERIVED FROM THE KIND on the server and is not stored, so it cannot come to
 * disagree with what the notification is about. The kind itself is deliberately not sent —
 * nothing here branches on it, and the three severities are what the screens sort and style
 * by.
 */
export interface Notification {
  readonly id: string;
  readonly title: string;
  readonly body: string;
  readonly createdAt: string;
  readonly read: boolean;
  readonly severity: 'INFO' | 'WARNING' | 'SECURITY';
  /**
   * An in-app path, or absent.
   *
   * NEVER AN EXTERNAL URL, and that is enforced twice on the server — in the entity and by
   * V20's own constraint — not merely promised here. A notification carries text the bank
   * did not author (a staff member's reason, a payee name the customer typed), so a
   * rendered absolute href out of one would be phishing with the bank's domain behind it.
   * Both screens also refuse to link anything that does not start with "/".
   *
   * Optional rather than nullable: the service omits null fields.
   */
  readonly link?: string;
}

/* ------------------------------------------------------------------ service requests */

/**
 * WHAT THE CUSTOMER ASKED THE BANK FOR, and where it has got to.
 *
 * TWO KINDS, not four. The old union listed LOAN and STATEMENT as well, which nothing
 * produced: a loan is an application with its own lifecycle and a statement is a document
 * generated on demand, and neither is a thing somebody collects from a counter. The server
 * returns CARD or CHEQUE_BOOK.
 */
export type ServiceRequestType = 'CARD' | 'CHEQUE_BOOK';

/**
 * FOUR STATES, matching the server, where the old `ServiceRequestStatus` in api.ts listed
 * eight that nothing ever produced — DRAFT, UNDER_REVIEW, ADDITIONAL_INFO_REQUIRED,
 * APPROVED, REJECTED, PROCESSING, COMPLETED. That union was written before anything
 * implemented it. These are the states that exist.
 */
export type ServiceRequestState = 'SUBMITTED' | 'READY' | 'COLLECTED' | 'DECLINED';

export interface ServiceRequest {
  readonly id: string;
  /** What the customer quotes on the telephone. Avoids O/0, I/1 and S/5 deliberately. */
  readonly reference: string;
  readonly requestType: ServiceRequestType;
  /** What was asked for, composed by the server: "Debit card for **** 7890". */
  readonly details: string;
  readonly status: ServiceRequestState;
  readonly submittedAt: string;
  /**
   * WHERE TO COLLECT IT, typed by the member of staff who produced it.
   *
   * ABSENT UNTIL THEN, and that absence is the whole design. The screens used to name a
   * branch from a six-item list the front end had invented — Nyarugenge, Kimironko,
   * Remera, Musanze, Rubavu, Huye — and told the customer to take their ID there. The bank
   * never supplied a branch list. With no value here there is nothing for a screen to
   * render, which is a stronger guarantee than everybody remembering not to guess.
   *
   * Optional rather than nullable: the service omits null fields from the JSON.
   */
  readonly collectionPoint?: string;
  /** Why staff declined it, in their words. Only ever on a DECLINED request. */
  readonly declineReason?: string;
}
