import type { Permission } from '@/types/api';
import type {
  Application,
  ApplicationStatus,
  Customer,
  OutboxMessage,
  StaffUser,
} from '@/types/admin';
import type { Account, SessionUser } from '@/types/banking';
import { persistedArray } from './persist';

/**
 * The onboarding store: applications, customers, staff and the outbox.
 *
 * NOTHING IS SEEDED HERE except two members of bank staff. There are deliberately no
 * ready-made customers any more: every customer in the system has to arrive the way a
 * real one does — register, be created by an admin, be approved by a manager, then sign
 * in with a temporary password and replace it. A fixture customer would let that chain
 * be skipped, which is exactly what makes a broken link in it invisible.
 *
 * The two staff logins exist because somebody has to be able to start the chain. They
 * are the bootstrap, and in a real deployment they would be provisioned by the bank's
 * own identity system rather than shipped in code.
 */

/* ------------------------------------------------------------------ staff */

export const STAFF_PASSWORD = 'ZigamaStaff1';

export interface StaffRecord extends StaffUser {
  /** Development only. A real system stores a hash and never the password. */
  readonly password: string;
}

export const STAFF: StaffRecord[] = [
  {
    id: 'staff-admin',
    fullName: 'Jean Claude Nkurunziza',
    email: 'admin@zigama.local',
    role: 'ADMIN',
    branch: 'Head office',
    lastLoginAt: undefined,
    password: STAFF_PASSWORD,
  },
  {
    id: 'staff-manager',
    fullName: 'Immaculée Mukandayisenga',
    email: 'manager@zigama.local',
    role: 'MANAGER',
    branch: 'Head office',
    lastLoginAt: undefined,
    password: STAFF_PASSWORD,
  },
];

export function staffByEmail(identifier: string): StaffRecord | undefined {
  const needle = identifier.trim().toLowerCase();
  return STAFF.find((member) => member.email.toLowerCase() === needle || member.id === needle);
}

export function staffById(id: string): StaffRecord | undefined {
  return STAFF.find((member) => member.id === id);
}

/* ------------------------------------------------------------------ applications */

/**
 * Filled by the registration endpoints. Starts empty, on purpose.
 *
 * Persisted, so a registration survives the hot reload that happens while an admin is
 * halfway through looking at it. Call `saveApplications()` after any change.
 */
const applicationStore = persistedArray<Application>('applications');
export const APPLICATIONS: Application[] = applicationStore.items;
export const saveApplications = applicationStore.commit;

export function applicationById(id: string): Application | undefined {
  return APPLICATIONS.find((item) => item.id === id);
}

export function setApplicationStatus(
  id: string,
  status: ApplicationStatus,
  extra: Partial<Application> = {},
): Application | undefined {
  const index = APPLICATIONS.findIndex((item) => item.id === id);
  if (index < 0) return undefined;

  const current = APPLICATIONS[index];
  if (current === undefined) return undefined;

  const next: Application = { ...current, ...extra, status };
  APPLICATIONS[index] = next;
  saveApplications();
  return next;
}

/* ------------------------------------------------------------------ customers */

export interface CustomerRecord extends Customer {
  /** Development only; a real system stores a hash. Cleared once replaced. */
  password: string;
  readonly permissions: readonly Permission[];
  readonly corporateId: string | undefined;
}

const customerStore = persistedArray<CustomerRecord>('customers');
export const CUSTOMERS: CustomerRecord[] = customerStore.items;
export const saveCustomers = customerStore.commit;

export function customerById(id: string): CustomerRecord | undefined {
  return CUSTOMERS.find((item) => item.id === id);
}

/** Sign-in accepts the email or the customer number. */
export function customerByIdentifier(identifier: string): CustomerRecord | undefined {
  const needle = identifier.trim().toLowerCase();
  return CUSTOMERS.find(
    (item) => item.email.toLowerCase() === needle || item.customerNumber.toLowerCase() === needle,
  );
}

/** Applies a change to a stored customer and returns the updated record. */
export function updateCustomer(
  id: string,
  patch: Partial<CustomerRecord>,
): CustomerRecord | undefined {
  const index = CUSTOMERS.findIndex((item) => item.id === id);
  if (index < 0) return undefined;

  const current = CUSTOMERS[index];
  if (current === undefined) return undefined;

  const next: CustomerRecord = { ...current, ...patch };
  CUSTOMERS[index] = next;
  saveCustomers();
  return next;
}

/** Strips the fields that must never leave the server. */
export function publicCustomer(record: CustomerRecord): Customer {
  const { password: _secret, permissions: _perms, corporateId: _corp, ...rest } = record;
  return rest;
}

/**
 * What the customer portal's `/session` returns for a signed-in customer.
 *
 * Permissions come from the record rather than from a role name, so the whole portal
 * keeps reading entitlement the one way it already does.
 */
export function sessionUserFor(
  record: CustomerRecord,
  corporates: SessionUser['corporates'],
): SessionUser {
  return {
    id: record.id,
    fullName: record.fullName,
    preferredName: record.fullName.split(' ')[0] ?? record.fullName,
    email: record.email,
    phone: record.phone,
    customerNumber: record.customerNumber,
    userType: record.userType,
    permissions: record.permissions,
    corporates,
    lastLoginAt: record.approvedAt ?? record.createdAt,
    /*
     * NEITHER `lastLoginDevice` NOR `lastLoginMethod` IS SET HERE, and the omission is
     * deliberate rather than unfinished.
     *
     * This line used to read `lastLoginLocation: 'Kigali, Rwanda'` — the same string the
     * real service passed at all four of its sign-in call sites, with no geo-IP lookup
     * behind either. The mock agreeing with the fabrication is how it went unnoticed: the
     * screen looked right against mocks and against production, and was wrong in both.
     *
     * The mock has no User-Agent to describe and did not record how this sign-in happened,
     * so the honest answer is to send nothing. Absent rather than null, because the
     * service omits null fields, and absent is what the screens are written to survive.
     */
  };
}

/* ------------------------------------------------------------------ accounts */

/**
 * Accounts opened by the admin, keyed by the customer they belong to.
 *
 * Empty until an account is created. A newly opened account has no transaction history
 * and, unless the branch took a deposit, no money — and the portal should look like
 * that rather than like a demo.
 */
const accountStore = persistedArray<Account>('accounts');
export const PROVISIONED_ACCOUNTS: Account[] = accountStore.items;
export const saveAccounts = accountStore.commit;

export function accountsForCustomer(customerId: string): readonly Account[] {
  const record = customerById(customerId);
  if (record === undefined) return [];

  return PROVISIONED_ACCOUNTS.filter((account) =>
    record.corporateId === undefined
      ? account.corporateId === undefined && account.id.startsWith(`acc-${customerId}`)
      : account.corporateId === record.corporateId,
  );
}

/* --------------------------------------------- forgotten passwords */

/**
 * Customers who have asked for a new password.
 *
 * <p>Mirrors the server's table, including the part that matters: a row exists only for a
 * REAL customer. The handler answers identically for an address that is not one and writes
 * nothing, because a store of everything typed into the public form is a list of addresses
 * that do not bank here.
 */
export interface PasswordRequestRecord {
  readonly id: string;
  readonly customerId: string;
  readonly requestedAt: string;
  status: 'PENDING' | 'FULFILLED' | 'REFUSED';
}

const passwordRequestStore = persistedArray<PasswordRequestRecord>('password-requests');
export const PASSWORD_REQUESTS: PasswordRequestRecord[] = passwordRequestStore.items;
export const savePasswordRequests = passwordRequestStore.commit;

/* ------------------------------------------------------------------ outbox */

const outboxStore = persistedArray<OutboxMessage>('outbox');
export const OUTBOX: OutboxMessage[] = outboxStore.items;
export const saveOutbox = outboxStore.commit;

/**
 * The address the bank's mail comes from.
 *
 * Set here because the mock stands in for the server, and the sender is a SERVER
 * decision — in production it is set alongside the SMTP credentials, server-side, and
 * the browser has no say in it. Nothing here is a secret: a sender address is on every
 * message the bank sends.
 *
 * See docs/OPEN-ITEMS.md before this becomes the production sender. A gmail.com address
 * cannot carry a bank's SPF, DKIM or DMARC records, because Google controls that domain
 * and not Zigama.
 */
export const SENDER_ADDRESS = 'ciaramuzora@gmail.com';

export function send(message: Omit<OutboxMessage, 'id' | 'sentAt' | 'from'>): OutboxMessage {
  const sent: OutboxMessage = {
    ...message,
    from: SENDER_ADDRESS,
    id: crypto.randomUUID(),
    sentAt: new Date().toISOString(),
  };
  // Newest first: the screen that reads this is someone checking what just went out.
  OUTBOX.unshift(sent);
  saveOutbox();
  return sent;
}

/* ------------------------------------------------------------------ permissions */

/** What a personal customer can do. */
export const RETAIL_PERMISSIONS: readonly Permission[] = [
  'RETAIL_ACCOUNT_VIEW',
  'TRANSFER_CREATE',
  'PAYMENT_CREATE',
  'BENEFICIARY_MANAGE',
  'LOAN_VIEW',
  'CARD_MANAGE',
];

/**
 * What the first user of a company gets.
 *
 * Both maker and approver rights, because the person who registers the business is its
 * administrator and there is nobody else yet. The moment a second person joins, the
 * company administrator should split these — and the portal should stop them approving
 * their own work, which it already does by comparing the maker's id.
 *
 * THIS IS NOT DEFINED IN THE BLUEPRINT. Whether a sole company user may approve their
 * own payments at all is a policy decision for the bank, not a default to inherit from
 * a mock.
 */
export const CORPORATE_ADMIN_PERMISSIONS: readonly Permission[] = [
  'CORPORATE_VIEW',
  'CORPORATE_TRANSFER_CREATE',
  'CORPORATE_PAYMENT_CREATE',
  'BENEFICIARY_MANAGE',
  'BULK_CREATE',
  'BULK_VIEW',
  'BULK_APPROVE',
  'APPROVAL_VIEW',
  'APPROVAL_APPROVE',
  'APPROVAL_REJECT',
  'SALARY_VIEW_DETAILS',
];

/* ------------------------------------------------------------------ generators */

/**
 * A temporary password.
 *
 * Readable out loud over a counter, because that is how it reaches the customer — no
 * ambiguous characters (no O/0, l/1/I), and grouped so it can be dictated. Long enough
 * that guessing is not the weak point; it is replaced on first sign-in regardless.
 */
const SAFE = 'ABCDEFGHJKMNPQRSTUVWXYZ';
const DIGITS = '23456789';

export function temporaryPassword(): string {
  const pick = (from: string, count: number): string => {
    const bytes = crypto.getRandomValues(new Uint8Array(count));
    return Array.from(bytes, (b) => from[b % from.length] ?? '').join('');
  };
  // e.g. "ZGKM-4827-QTdx" — letters, digits, and a lowercase pair for character classes.
  return `${pick(SAFE, 4)}-${pick(DIGITS, 4)}-${pick(SAFE, 2)}${pick(SAFE, 2).toLowerCase()}`;
}

/*
 * Counters derived from what is already stored, not from a module-level variable.
 *
 * A variable would restart at the same number after a reload and hand the next customer
 * a number somebody already has — which in a bank is not a cosmetic bug.
 */
export function nextCustomerNumber(): string {
  return `****${String(3100 + CUSTOMERS.length * 7 + 7)}`;
}

export function nextAccountMask(): string {
  return `**** ${String(4500 + PROVISIONED_ACCOUNTS.length * 13 + 13)}`;
}
