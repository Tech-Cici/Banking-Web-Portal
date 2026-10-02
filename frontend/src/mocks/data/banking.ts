import type {
  ApprovalItem,
  Beneficiary,
  BulkBatch,
  Card,
  Loan,
  Notification,
  ServiceRequest,
} from '@/types/banking';

/**
 * Beneficiaries, cards, loans, approvals, batches, notifications and service requests.
 *
 * ALL EMPTY. They used to hold a worked-out set of fixtures for three seeded customers;
 * those customers no longer exist, and neither should their data. Everything here is now
 * created by using the portal — add a payee and it appears in BENEFICIARIES, request a
 * card and it appears in SERVICE_REQUESTS.
 *
 * The arrays are mutable and the scoping helpers are unchanged, so every screen keeps
 * working; they simply start by showing the empty state, which for a brand-new customer
 * is the correct screen.
 */

/**
 * A saved payee, plus the thing the real service keeps and never sends: the full number.
 *
 * WHY THE MOCK HAS TO HOLD IT. The staff review queue's whole job is comparing the name
 * the customer typed with the name the bank holds for that ACCOUNT, and there is no way to
 * look a holder up from a mask — masks are not unique. The first version of this mock
 * discarded the number at the door and kept only the mask, which made the review queue
 * impossible to build against mocks and would have hidden every bug in it until the
 * feature met the real backend.
 *
 * IT IS STILL NEVER IN A RESPONSE. `publicBeneficiary` below strips it, and that is the
 * only shape the handlers return — the same rule the server keeps with a deliberately
 * named accessor.
 */
export interface MockBeneficiary extends Beneficiary {
  /** Mock-side only. Stripped from every response by `publicBeneficiary`. */
  readonly accountNumber: string;
}

export const BENEFICIARIES: MockBeneficiary[] = [];

/** A payee as the API returns it: everything except the full number. */
export function publicBeneficiary(payee: MockBeneficiary): Beneficiary {
  /* Destructured out rather than deleted, so adding a secret field later is a type error
     here instead of a leak. */
  const { accountNumber: _number, ...rest } = payee;
  return rest;
}
export const CARDS: Card[] = [];
export const LOANS: Loan[] = [];
export const APPROVALS: ApprovalItem[] = [];
export const BULK_BATCHES: BulkBatch[] = [];
/**
 * A notification, plus the owner the API never sends back.
 *
 * Same pattern as `MockBeneficiary` and `MockServiceRequest`: the real response carries no
 * customer id — a customer reading their own list already knows whose it is — but the mock
 * needs one to scope the list and to refuse marking somebody else's row read, which is the
 * rule most worth having a mock for here. The whole value of a security notification is
 * that it is still unread and at the top when its owner next signs in.
 */
export interface MockNotification extends Notification {
  /** Mock-side only. Stripped by `publicNotification`. */
  readonly ownerId: string;
}

export const NOTIFICATIONS: MockNotification[] = [];

/** A notification as the API returns it: no owner id. */
export function publicNotification(item: MockNotification): Notification {
  const { ownerId: _owner, ...rest } = item;
  return rest;
}

/**
 * A card or cheque-book request, plus the two columns the API never sends back.
 *
 * WHY THESE ARE MOCK-SIDE. `service_requests` has a `customer_id` and an `account_id`;
 * neither is in the response, because a customer reading their own list already knows
 * whose it is, and the account is already in the `details` string as a mask. The mock
 * needs both anyway — `customerId` to scope the list, `accountId` to apply the real
 * one-open-request-per-account rule rather than a looser one it could get away with.
 *
 * Same reasoning as {@link MockBeneficiary}: holding them here and stripping them on the
 * way out is what lets the handlers enforce what the server enforces, instead of being a
 * thinner gate that hides bugs until the feature meets the real backend.
 */
export interface MockServiceRequest extends ServiceRequest {
  /** Mock-side only. Stripped by `publicServiceRequest`. */
  readonly ownerId: string;
  /** Mock-side only. Stripped by `publicServiceRequest`. */
  readonly accountId: string;
}

export const SERVICE_REQUESTS: MockServiceRequest[] = [];

/** A request as the API returns it: no owner, no account id. */
export function publicServiceRequest(row: MockServiceRequest): ServiceRequest {
  /* Destructured out rather than deleted, so a new internal field is a type error here
     instead of something that quietly reaches the browser. */
  const { ownerId: _owner, accountId: _account, ...rest } = row;
  return rest;
}

/**
 * A reference in the same shape the server produces: CRD-XXXXXX or CHQ-XXXXXX.
 *
 * THE ALPHABET IS THE SERVER'S, deliberately missing O/0, I/1 and S/5 —
 * `ServiceRequestEntity.referenceFrom` explains why. Keeping the shape identical is what
 * lets a test or a screenshot taken against mocks be compared with one taken against the
 * real service without the difference being the first thing anybody notices.
 *
 * DERIVED FROM A UUID, as the server derives it from the row's own id, and for the reason
 * recorded there: a reference built from a count collides between two concurrent requests.
 * `crypto.randomUUID` rather than `Math.random`, which this repo's lint rule forbids for
 * identifiers — and rightly, even here: a predictable reference in a fixture is the kind
 * of thing that gets copied into something that matters.
 *
 * The suffix is NOT byte-identical to what the server would make of the same id — Java
 * reads those 64-bit halves as signed and this does not — and nothing depends on it being
 * so. Only the alphabet, the prefix and the length are a contract.
 */
export function mockRequestReference(kind: 'CARD' | 'CHEQUE_BOOK'): string {
  const alphabet = 'ABCDEFGHJKLMNPQRTUVWXYZ2346789';
  const hex = crypto.randomUUID().replace(/-/g, '');

  let bits = BigInt(`0x${hex.slice(0, 16)}`) ^ BigInt(`0x${hex.slice(16)}`);
  const size = BigInt(alphabet.length);

  let suffix = '';
  for (let position = 0; position < 6; position++) {
    suffix += alphabet.charAt(Number(bits % size));
    bits /= size;
  }
  return `${kind === 'CARD' ? 'CRD' : 'CHQ'}-${suffix}`;
}

/* ------------------------------------------------------------------ scoping helpers */

/**
 * One customer's payees, WITH the mock-side number still attached.
 *
 * The handlers map through `publicBeneficiary` before returning anything. The internal
 * shape is what the transfer handler needs in order to apply the same gate the server
 * applies — a mock that returned only public shapes could not check a payee's status
 * server-side, which is exactly the hole this whole change closes.
 */
export function beneficiariesFor(userId: string): readonly MockBeneficiary[] {
  return BENEFICIARIES.filter((item) => item.ownerId === userId);
}

export function cardsFor(userId: string): readonly Card[] {
  return CARDS.filter((item) => item.ownerId === userId);
}

export function loansFor(userId: string): readonly Loan[] {
  return LOANS.filter((item) => item.ownerId === userId);
}

export function notificationsFor(userId: string): readonly MockNotification[] {
  return NOTIFICATIONS.filter((item) => item.ownerId === userId);
}

/**
 * One customer's requests, WITH the mock-side columns still attached.
 *
 * The handlers map through `publicServiceRequest` before returning anything, for the
 * reason recorded on `beneficiariesFor`.
 */
export function serviceRequestsFor(userId: string): readonly MockServiceRequest[] {
  return SERVICE_REQUESTS.filter((item) => item.ownerId === userId);
}

export function approvalsFor(corporateId: string | undefined): readonly ApprovalItem[] {
  if (corporateId === undefined) return [];
  return APPROVALS.filter((item) => item.corporateId === corporateId);
}

export function batchesFor(corporateId: string | undefined): readonly BulkBatch[] {
  if (corporateId === undefined) return [];
  return BULK_BATCHES.filter((item) => item.corporateId === corporateId);
}
