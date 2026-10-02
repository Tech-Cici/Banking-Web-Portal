import type { MoneyDto, TransactionStatus } from './api';

/**
 * Money-movement DTOs.
 *
 * PROVISIONAL, like the rest of `types/`: generated from the OpenAPI document once the
 * backend publishes one. Two rules hold throughout and are not provisional:
 *
 *  - Amounts are decimal STRINGS, never numbers.
 *  - Fees, rates, limits and final debits come from the server. The client displays
 *    them; it never computes them.
 */

/* ------------------------------------------------------------------ transfers */

export type TransferKind = 'OWN' | 'INTERNAL' | 'EXTERNAL' | 'INTERNATIONAL';

export interface TransferRequest {
  readonly kind: TransferKind;
  readonly sourceAccountId: string;
  /** Present for a move between the customer's own accounts. */
  readonly destinationAccountId?: string;
  /** Present for everything else. */
  readonly beneficiaryId?: string;
  readonly amount: MoneyDto;
  readonly reference: string;
  /** Scheduled transfers carry a date; immediate ones do not. */
  readonly executeOn?: string;
}

/* ------------------------------------------- internal transfers (the real ones) */

/**
 * What the customer sends to move money inside Zigama.
 *
 * <p>SEPARATE FROM {@link TransferRequest}, which is the four-rail mocked shape with a
 * quote id, a fee and a saved payee. This one matches the service that exists: one rail,
 * no fee, no quote, and a destination that is either an account of their own or an
 * account number they typed.
 */
export interface InternalTransferRequest {
  readonly sourceAccountId: string;
  /**
   * EXACTLY ONE OF THESE, and the server refuses both or neither rather than guessing.
   *
   * `destinationAccountId` is how a picker sends one of the customer's own accounts: the
   * client is never given a full account number, so it has nothing else to send.
   * `destinationAccountNumber` is for paying somebody else, typed in full — masks are not
   * unique and are not accepted.
   */
  readonly destinationAccountId?: string;
  readonly destinationAccountNumber?: string;
  /** A decimal STRING, exactly as typed. Never a number — see CashRequest. */
  readonly amount: string;
  readonly reference: string;
}

/**
 * A transfer as the server reports it.
 *
 * <p>THERE IS NO COMPLETED AND NO SENT. `PENDING_APPROVAL` means the money has already
 * left the sender and a manager has not decided; `APPROVED` means it has arrived. The
 * client must not improve on these — a screen inventing "on its way" would be describing
 * a state the service does not have.
 */
export interface InternalTransfer {
  readonly id: string;
  readonly status: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED';
  /** Decimal string with its currency beside it, like every amount in this API. */
  readonly amount: string;
  readonly currency: string;
  readonly reference: string;
  readonly submittedAt: string;
  readonly decidedAt?: string | null;
  readonly rejectionReason?: string | null;
}

/**
 * Who holds an account number, so a mistyped digit is caught before the money moves.
 *
 * <p>A name, a mask and a currency — never the account id and never the number that was
 * typed. The endpoint behind this is rate limited per customer, because answering "who
 * holds this number" without limit turns a list of numbers into a list of people.
 */
export interface ResolvedBeneficiary {
  readonly holderName: string;
  readonly maskedNumber: string;
  readonly currency: string;
}

/* ------------------------------------------------------------------ payments */

export type BillerCategory = 'WALLET' | 'AIRTIME' | 'ELECTRICITY' | 'WATER' | 'TV' | 'TAX' | 'AFOS';

export interface Biller {
  readonly id: string;
  readonly name: string;
  readonly category: BillerCategory;
  /** What the customer types to identify their account with the biller. */
  readonly accountLabel: string;
  readonly accountHint: string;
  readonly currency: string;
  /** Some billers quote the amount themselves; the customer cannot change it. */
  readonly amountFixed: boolean;
}

export interface PaymentRequest {
  readonly billerId: string;
  readonly sourceAccountId: string;
  readonly customerReference: string;
  readonly amount: MoneyDto;
}

/**
 * What the bank quotes back before anything is sent.
 *
 * The fee and the total are the SERVER'S figures. The review screen shows exactly these
 * and adds nothing of its own — a total the browser worked out is a total the bank never
 * agreed to.
 */
export interface MovementQuote {
  readonly quoteId: string;
  readonly amount: MoneyDto;
  readonly fee: MoneyDto;
  readonly totalDebit: MoneyDto;
  readonly destinationSummary: string;
  readonly expiresAt: string;
  /** Warnings the bank attaches, e.g. a payee staff have not finished checking. */
  readonly warnings: readonly string[];
}

/** The outcome of a submitted movement. */
export interface MovementReceipt {
  readonly id: string;
  readonly reference: string;
  readonly status: TransactionStatus;
  readonly amount: MoneyDto;
  readonly fee: MoneyDto;
  readonly totalDebit: MoneyDto;
  readonly sourceAccountMask: string;
  readonly destinationSummary: string;
  readonly submittedAt: string;
  /** Set when the movement needs approval rather than posting immediately. */
  readonly awaitingApproval: boolean;
}

/* ------------------------------------------------------- cash over the counter */

/**
 * A deposit into, or a withdrawal from, an account the customer holds.
 *
 * <p>Its own shape rather than a {@link MovementReceipt}, because it is a different event.
 * A transfer has a fee, a destination and an approval path; cash has none of those, and
 * reusing the transfer receipt would mean inventing a zero fee and an empty destination
 * for every one of these — invented fields that later read as real.
 */
export interface CashRequest {
  /**
   * A decimal STRING, exactly as the customer typed it.
   *
   * Never a number. `0.1 + 0.2` is not `0.3` in binary floating point and a JS number
   * loses integer precision past 2^53, which RWF amounts reach sooner than most
   * currencies. Nothing on the client parses, totals or reformats this on its way out.
   */
  readonly amount: string;
  readonly description?: string;
}

export interface CashReceipt {
  readonly id: string;
  readonly accountId: string;
  readonly direction: 'CREDIT' | 'DEBIT';
  readonly amount: MoneyDto;
  /** The balance once this entry was applied. The server's figure, never a subtraction. */
  readonly balanceAfter: MoneyDto;
  readonly description: string;
  readonly bookedAt: string;
  readonly status: TransactionStatus;
}

/* ------------------------------------------------------------------ FX */

export interface FxQuote {
  readonly quoteId: string;
  readonly sellAmount: MoneyDto;
  readonly buyAmount: MoneyDto;
  /** Decimal string, e.g. `1285.40`. Quoted by the bank, never derived here. */
  readonly rate: string;
  readonly rateExpiresAt: string;
  readonly fee: MoneyDto;
}

/* ------------------------------------------------------------------ standing orders */

export type StandingOrderFrequency = 'WEEKLY' | 'MONTHLY' | 'QUARTERLY';

export interface StandingOrder {
  readonly id: string;
  readonly reference: string;
  readonly sourceAccountMask: string;
  readonly destinationSummary: string;
  readonly amount: MoneyDto;
  readonly frequency: StandingOrderFrequency;
  readonly nextRunOn: string;
  readonly endsOn?: string;
  readonly status: 'ACTIVE' | 'PAUSED' | 'ENDED';
  readonly ownerId: string;
}

export interface StandingOrderRequest {
  readonly sourceAccountId: string;
  readonly beneficiaryId: string;
  readonly amount: MoneyDto;
  readonly frequency: StandingOrderFrequency;
  readonly startOn: string;
  readonly endsOn?: string;
  readonly reference: string;
}

/* ------------------------------------------------------------------ statements */

export interface Statement {
  readonly id: string;
  readonly accountId: string;
  readonly accountMask: string;
  readonly periodFrom: string;
  readonly periodTo: string;
  readonly format: 'PDF' | 'CSV';
  readonly requestedAt: string;
  readonly status: 'REQUESTED' | 'GENERATING' | 'READY' | 'FAILED';
  readonly sizeBytes?: number;
}

export interface StatementRequest {
  readonly accountId: string;
  readonly from: string;
  readonly to: string;
  readonly format: 'PDF' | 'CSV';
}

/*
 * NO `ChequeBook` INTERFACE. It described an inventory of printed books, with a five-state
 * lifecycle and a `branch` the customer had picked — none of which the bank has or ever
 * described. What the portal actually knows is in `ServiceRequest` in types/banking.ts:
 * what somebody asked for, and what staff did about it.
 */

/* ------------------------------------------------------------------ security */

/**
 * A browser that has completed the emailed-code step and may sign in on the password alone.
 *
 * NOT "A DEVICE SIGNED IN", which is what this screen used to call it. A trusted browser
 * is not a session — it is permission to skip the code, and it outlives every session on
 * that machine. Conflating the two made "Remove" look like "sign that device out", which
 * it is not: the other session continues, and what ends is the code exemption.
 *
 * THERE IS NO `location`, and the old type had one. The table has never held a location,
 * this service has no geo-IP lookup, and the only way to fill the field was to invent a
 * value — which is exactly what `sign_ins` did for a year with the literal "Kigali,
 * Rwanda". See the backend's SignInMethod and migration V19.
 *
 * `device` IS OPTIONAL, not nullable: the service omits null fields from its JSON, and a
 * client that sent no User-Agent has no description. The screen shows the dates instead.
 */
export interface TrustedDevice {
  readonly id: string;
  /** "Chrome on Windows", as at the moment trust was granted. Absent if unknown. */
  readonly device?: string;
  readonly trustedAt: string;
  /** Absent until this browser has actually been used to skip the code. */
  readonly lastUsedAt?: string;
  readonly expiresAt: string;
  /** The browser making this request. The server computes it from the cookie. */
  readonly current: boolean;
  readonly revoked: boolean;
  readonly revokedReason?: string;
}

/**
 * One recorded sign-in.
 *
 * NO `outcome`. The old type had SUCCESS or FAILURE and nothing ever produced FAILURE —
 * `sign_ins` records sign-ins that happened. A field that is always SUCCESS tells a
 * customer their history is complete when it is not, so recording failed attempts is left
 * as the open item it actually is rather than implied by this shape.
 *
 * NO `description` EITHER. That was free text the mock composed; what the service knows is
 * which sign-in path was taken, and it sends the label.
 */
export interface SignIn {
  readonly at: string;
  /** "Password and emailed code", "Password only, from a remembered browser", and so on. */
  readonly method: string;
  /** What it was done on, when the client said. Absent otherwise; never defaulted. */
  readonly device?: string;
}
