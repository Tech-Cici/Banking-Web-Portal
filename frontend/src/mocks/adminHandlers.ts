import { http, HttpResponse } from 'msw';
import { maskAccountNumber } from '@/utils/mask';
import { moneyToDto, parseMoney } from '@/utils/money';
import {
  ACCOUNT_TYPES,
  type BeneficiaryReview,
  type NameCheck,
  type CreatedCustomer,
  type Customer,
  type PasswordRequest,
  type CustomerAccount,
  type NewAccount,
  type ServiceRequestQueueItem,
} from '@/types/admin';
import { activeStaff } from './data/activeSession';
import {
  BENEFICIARIES,
  SERVICE_REQUESTS,
  type MockBeneficiary,
  type MockServiceRequest,
} from './data/banking';
import {
  APPLICATIONS,
  applicationById,
  CORPORATE_ADMIN_PERMISSIONS,
  CUSTOMERS,
  customerById,
  nextCustomerNumber,
  OUTBOX,
  PROVISIONED_ACCOUNTS,
  PASSWORD_REQUESTS,
  publicCustomer,
  saveCustomers,
  savePasswordRequests,
  RETAIL_PERMISSIONS,
  send,
  setApplicationStatus,
  saveAccounts,
  temporaryPassword,
  updateCustomer,
  type CustomerRecord,
} from './data/onboarding';
import { clearAll } from './data/persist';
import { CORPORATES, registerCorporate } from './data/personas';
import { mockApiError } from './handlers';

/**
 * The bank's own endpoints: onboarding applications, account creation, approval.
 *
 * THE ONE RULE THIS FILE EXISTS TO ENFORCE: the person who creates an account is not
 * the person who approves it. Issuing working credentials for a customer who does not
 * exist is the most valuable thing an insider can do in a banking system, so it takes
 * two people — an admin creates, a manager signs off — and the manager endpoint refuses
 * anything the caller created themselves.
 *
 * Role checks are here, on the server side of the boundary, not in the screens. Hiding
 * a button is a courtesy; this is the control.
 */

const API = '*/api/v1/admin';

/** Matches TEMPORARY_PASSWORD_VALIDITY on the backend. */
const TEMPORARY_PASSWORD_HOURS = 72;

function unauthenticated() {
  return mockApiError(
    401,
    'UNAUTHENTICATED',
    'You have been signed out. Please sign in again — your money is unaffected.',
  );
}

function forbidden(message: string) {
  return mockApiError(403, 'FORBIDDEN', message);
}

interface MockLink {
  readonly id: string;
  readonly customerId: string;
  readonly accountNumber: string;
  readonly maskedNumber: string;
  unlinked: boolean;
}

/*
 * Linked accounts, mock side. Not persisted across reloads on purpose: these stand in for
 * rows the real service owns, and a mock that quietly accumulates them across sessions
 * makes the development database and the screen disagree.
 */
const LINKS: MockLink[] = [];

/** A customer with its masks derived from the links, exactly as the server derives them. */
function withLinkedAccounts(record: CustomerRecord): Customer {
  return {
    ...publicCustomer(record),
    accountMasks: LINKS.filter((link) => link.customerId === record.id && !link.unlinked).map(
      (link) => link.maskedNumber,
    ),
  };
}

async function body(request: Request): Promise<Record<string, unknown>> {
  return (await request.json()) as Record<string, unknown>;
}

function str(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  return typeof value === 'string' ? value : '';
}

/**
 * The accounts array from a create-account body.
 *
 * Validates rather than coerces. The old code took whatever arrived and defaulted it to
 * CURRENT/RWF, which meant a malformed request produced a confident-looking account —
 * the mock equivalent of the server discarding the body.
 */
function newAccounts(source: Record<string, unknown>): readonly NewAccount[] {
  const raw = source['accounts'];
  if (!Array.isArray(raw)) return [];

  const types = new Set<string>(ACCOUNT_TYPES.map((entry) => entry.value));

  return raw.flatMap((item): NewAccount[] => {
    if (item === null || typeof item !== 'object') return [];
    const record = item as Record<string, unknown>;
    const accountType = str(record, 'accountType');
    const currency = str(record, 'currency');
    const accountNumber = str(record, 'accountNumber').trim();
    if (!types.has(accountType)) return [];
    if (!/^[A-Za-z]{3}$/.test(currency)) return [];
    if (!/^\d{10,16}$/.test(accountNumber)) return [];

    /*
     * The opening balance is validated by SHAPE here and not by scale.
     *
     * The server decides whether this currency has decimal places at all — RWF has none
     * — and the mock deliberately does not duplicate that rule. Two implementations of
     * a money rule is one too many, and the one that disagrees would teach whoever is
     * developing against the mock the wrong thing.
     */
    const openingBalance = str(record, 'openingBalance').trim();
    if (openingBalance !== '' && !/^\d{1,15}(\.\d{1,2})?$/.test(openingBalance)) return [];

    return [
      {
        accountNumber,
        accountType: accountType as NewAccount['accountType'],
        currency: currency.toUpperCase(),
        openingBalance,
      },
    ];
  });
}

/**
 * "TARGET_SAVINGS" -> "Target savings", exactly as AccountsController.nicknameFor does.
 *
 * Derived rather than looked up in ACCOUNT_TYPES: that list holds the labels the STAFF
 * dropdown shows, and a customer reading a different name for their own account from
 * the one the bank's own API sends is the sort of disagreement nobody notices until a
 * phone call.
 */
function readableType(accountType: string): string {
  const words = accountType.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * The queue row, in the shape the API returns.
 *
 * <p>Built in one place because three handlers return it, and the one field it would be
 * easy to add by accident is a password. There is nowhere to put one here.
 */
function passwordRequestView(
  entry: { id: string; customerId: string; requestedAt: string },
  customer: CustomerRecord,
): PasswordRequest {
  return {
    id: entry.id,
    customerId: customer.id,
    customerNumber: customer.customerNumber,
    fullName: customer.fullName,
    email: customer.email,
    status: customer.status,
    requestedAt: entry.requestedAt,
  };
}

/* ----------------------------------------------- payees waiting to be checked */

/**
 * Compares the name the customer typed with the name the bank holds for that account.
 *
 * A SMALLER COPY OF THE SERVER'S RULE, copied deliberately rather than approximated: case,
 * punctuation and the ORDER of the words are all ignored, because "MUZORA Teta Eliana" and
 * "Teta Eliana Muzora" are one person written two ways. A comparison stricter than that
 * fires on legitimate payees all day, and a reviewer who sees the warning every time learns
 * to click through it — which is worse than having no warning.
 *
 * The authoritative version is `BeneficiaryService.compare`, with its own unit test. This
 * one exists so the queue can be built and demonstrated against mocks; if the two ever
 * disagree the server wins, and the screen is only ever shown what the server computed.
 */
function nameCheckOf(typed: string, held: string | undefined): NameCheck {
  if (held === undefined) return 'UNAVAILABLE';

  const words = (value: string): readonly string[] =>
    [
      ...new Set(
        value
          .toLowerCase()
          .split(/[^\p{L}\p{N}]+/u)
          .filter((word) => word !== ''),
      ),
    ].sort();

  const left = words(typed);
  const right = words(held);
  if (left.length === 0 || right.length === 0) return 'UNAVAILABLE';

  if (left.join(' ') === right.join(' ')) return 'MATCH';
  if (left.every((word) => right.includes(word)) || right.every((word) => left.includes(word))) {
    return 'PARTIAL';
  }
  return 'MISMATCH';
}

/**
 * Who holds an account at this bank, or undefined.
 *
 * Undefined for a payee at another institution — this service cannot ask another bank who
 * holds an account — and also for a number this bank simply does not hold, which is a
 * refusal reason in itself rather than a mismatch.
 *
 * MATCHED ON THE MASK, AND THAT IS A REAL WEAKNESS OF THE MOCK rather than of the feature.
 * The server matches the full account number, which is unique. The mock's own account
 * store keeps only `maskedNumber` — the full number never enters it — so the strongest
 * lookup available here is "which customer lists this mask", and masks are not unique: two
 * accounts ending 0192 would both match and the first wins.
 *
 * That is tolerable only because this is the mock. The queue the screen actually shows in
 * any real deployment is computed by `BeneficiaryService.review`, from the number. Nothing
 * is decided here — if this returns the wrong holder in a demo, a reviewer sees a mismatch
 * and refuses, which is the safe direction to be wrong in.
 */
function heldNameFor(payee: MockBeneficiary): string | undefined {
  if (payee.beneficiaryType !== 'INTERNAL') return undefined;

  const holder = CUSTOMERS.find((customer) =>
    customer.accountMasks.includes(payee.maskedDestination),
  );
  return holder?.fullName;
}

function beneficiaryReviewView(payee: MockBeneficiary): BeneficiaryReview | null {
  const customer = payee.ownerId === undefined ? undefined : customerById(payee.ownerId);
  if (customer === undefined) return null;

  const held = heldNameFor(payee);
  return {
    id: payee.id,
    customerId: customer.id,
    customerNumber: customer.customerNumber,
    customerName: customer.fullName,
    name: payee.name,
    /*
     * SPREAD CONDITIONALLY, never set to undefined. `exactOptionalPropertyTypes` is on, so
     * `heldName: undefined` is not assignable to `heldName?: string` — and it would also
     * misrepresent the server, which omits the key entirely rather than sending null.
     */
    ...(held === undefined ? {} : { heldName: held }),
    nameCheck: nameCheckOf(payee.name, held),
    beneficiaryType: payee.beneficiaryType,
    provider: payee.provider,
    maskedDestination: payee.maskedDestination,
    currency: payee.currency,
    addedAt: payee.addedAt ?? new Date().toISOString(),
    status: payee.status,
  };
}

/**
 * The approval email, worded as the server words it.
 *
 * It carries the mask and not the number, and it says what to do if the customer did NOT
 * add this payee — a payee appearing on somebody's account that they did not save is the
 * clearest single sign that another person has their password, and this message is the
 * only moment the bank gets to tell them.
 */
function beneficiaryApprovedEmail(fullName: string, payee: MockBeneficiary): string {
  return [
    `Dear ${fullName},`,
    '',
    'A member of our staff has checked the payee you saved, and you can now send',
    'money to them.',
    '',
    `  Payee:   ${payee.name}`,
    `  Account: ${payee.maskedDestination}`,
    '',
    'You will find them in the payee list when you make a transfer or set up a',
    'standing order.',
    '',
    'IF YOU DID NOT ADD THIS PAYEE, somebody else may have access to your internet',
    'banking. Call the number printed on the back of your card straight away.',
    '',
    'Zigama CSS',
  ].join('\n');
}

/** The refusal, with the reviewer's own words, and what the customer can do about it. */
function beneficiaryRefusedEmail(fullName: string, payee: MockBeneficiary, reason: string): string {
  return [
    `Dear ${fullName},`,
    '',
    'We were not able to approve a payee you saved, so money cannot be sent to it.',
    '',
    `  Payee:   ${payee.name}`,
    `  Account: ${payee.maskedDestination}`,
    '',
    `Reason: ${reason}`,
    '',
    'Nothing has been sent and no money has left your account. If the account',
    'number was wrong, add the payee again with the corrected number.',
    '',
    'Zigama CSS',
  ].join('\n');
}

/* ------------------------------------- cards and cheque books to make */

/**
 * A request as the STAFF queue sees it: with the customer attached, and the account masked.
 *
 * <p>Returns null where the customer has gone — the same guard `beneficiaryReviewView`
 * uses, and for the same reason: a row whose customer cannot be found is a row the screen
 * cannot act on, so it is dropped from the queue rather than rendered with holes in it.
 */
function serviceRequestQueueView(row: MockServiceRequest): ServiceRequestQueueItem | null {
  const customer = customerById(row.ownerId);
  if (customer === undefined) return null;

  const account = PROVISIONED_ACCOUNTS.find((candidate) => candidate.id === row.accountId);

  return {
    id: row.id,
    reference: row.reference,
    customerId: customer.id,
    customerNumber: customer.customerNumber,
    customerName: customer.fullName,
    email: customer.email,
    requestType: row.requestType,
    details: row.details,
    /* Matches the server's fallback rather than showing an empty cell. */
    accountMask: account?.maskedNumber ?? '****',
    status: row.status,
    submittedAt: row.submittedAt,
    /* Spread conditionally: `exactOptionalPropertyTypes` is on, and the server omits
       the key rather than sending null. */
    ...(row.collectionPoint === undefined ? {} : { collectionPoint: row.collectionPoint }),
  };
}

/** Where to collect it, worded as the server words it. */
function serviceRequestReadyEmail(
  fullName: string,
  what: string,
  reference: string,
  collectionPoint: string,
): string {
  return [
    `Dear ${fullName},`,
    '',
    `The ${what} you asked for is ready to collect.`,
    '',
    `  Reference:  ${reference}`,
    `  Collect at: ${collectionPoint}`,
    '',
    'Please bring photo identification. We cannot hand it to anybody else.',
    '',
    'IF YOU DID NOT ASK FOR THIS, somebody else may have access to your internet',
    'banking. Call the number printed on the back of your card straight away.',
    '',
    'Zigama CSS will never ask you for your password, PIN or one-time code by',
    'phone, SMS or email.',
    '',
    'Zigama CSS',
  ].join('\n');
}

/** The refusal, in the staff member's own words. */
function serviceRequestDeclinedEmail(
  fullName: string,
  what: string,
  reference: string,
  reason: string,
): string {
  return [
    `Dear ${fullName},`,
    '',
    `We were not able to action the ${what} you asked for.`,
    '',
    `  Reference: ${reference}`,
    '',
    `Reason: ${reason}`,
    '',
    'Nothing has been charged to your account. If you think this is a mistake,',
    'visit any branch with your ID or call the number printed on the back of your',
    'card.',
    '',
    'Zigama CSS',
  ].join('\n');
}

/** "card" or "cheque book", as both the emails and the refusals word it. */
function serviceRequestLabel(kind: 'CARD' | 'CHEQUE_BOOK'): string {
  return kind === 'CARD' ? 'card' : 'cheque book';
}

/**
 * The re-issued password, worded as the server words it.
 *
 * <p>DELIBERATELY THE SAME SHAPE AS THE APPROVAL EMAIL — the same two labelled lines, the
 * same expiry, the same warning at the foot. A customer has seen that layout once already,
 * and a password email that looks different from the one the bank sent before is the one a
 * phishing message gets to imitate.
 */
function reissuedPasswordEmail(fullName: string, signInEmail: string, password: string): string {
  return [
    `Dear ${fullName},`,
    '',
    'You asked us for a new internet banking password, and a member of our staff has',
    'issued one.',
    '',
    'Sign in with your email address:',
    `  Email address:      ${signInEmail}`,
    `  Temporary password: ${password}`,
    '',
    'You will be asked to replace this password straight away, before you can use the',
    'service.',
    '',
    'IF YOU DID NOT ASK FOR THIS, do not use the password above. Call the number on the',
    'back of your card straight away and tell us.',
    '',
    'Any browsers that were set to skip the sign-in code have been reset, so you will be',
    'asked for a code again next time. That is expected.',
    '',
    'Zigama CSS',
  ].join('\n');
}

/* ------------------------------------------------------------------ email bodies */

/**
 * The approval email.
 *
 * It does NOT contain the temporary password. The password reaches the customer through
 * a channel the bank controls — handed over at the branch, or in the letter it prints —
 * because a message carrying both the address to sign in at and the password to use
 * means one compromised inbox is a compromised bank account.
 */
/**
 * Mirrors EmailTemplates.approved on the backend, INCLUDING the temporary password.
 *
 * Kept in step deliberately. The mock is what runs when the backend is not, so a mock
 * that still told the customer to collect a password at the branch would demonstrate a
 * flow the real service no longer has.
 */
function approvalEmail(
  customer: Customer,
  temporaryPassword: string,
  validityHours: number,
): { subject: string; body: string } {
  return {
    subject: 'Your Zigama CSS internet banking account is ready',
    body: [
      `Dear ${customer.fullName},`,
      '',
      'Your internet banking account has been approved and is now active.',
      '',
      `Customer number:    ${customer.customerNumber}`,
      `Temporary password: ${temporaryPassword}`,
      '',
      'Sign in with the temporary password above. You will be asked to replace it',
      'straight away, before you can use the service — please do that as soon as',
      'you are in. Once you have, nobody at the bank knows your password, and this',
      'email is worth nothing to anybody who reads it.',
      '',
      `This temporary password stops working in ${String(validityHours)} hours.`,
      'If it expires before you use it, call us and we will issue another.',
      '',
      'Delete this email once you have changed your password.',
      '',
      'Zigama CSS will never ask you for your password, PIN or one-time code by',
      'phone, SMS or email. Always type the banking address into your browser',
      'yourself rather than following a link — including a link in this message.',
      '',
      'Zigama CSS',
    ].join('\n'),
  };
}

function rejectionEmail(customer: Customer, reason: string): { subject: string; body: string } {
  return {
    subject: 'About your Zigama CSS internet banking application',
    body: [
      `Dear ${customer.fullName},`,
      '',
      'We were not able to approve your internet banking application.',
      '',
      `Reason: ${reason}`,
      '',
      'If you think this is a mistake, or you would like to apply again with different',
      'details, please visit any branch with your identification.',
      '',
      'Zigama CSS',
    ].join('\n'),
  };
}

/* ------------------------------------------------------------------ handlers */

export const adminHandlers = [
  /* ---------------------------------------------- applications */

  /**
   * Every registration, newest first.
   *
   * Both roles can read this. An approver who cannot see what they are approving is not
   * approving anything — they are clicking a button.
   */
  http.get(`${API}/applications`, ({ request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const status = new URL(request.url).searchParams.get('status');
    const rows = status === null ? APPLICATIONS : APPLICATIONS.filter((a) => a.status === status);

    return HttpResponse.json(
      [...rows].sort((a, b) => Date.parse(b.submittedAt) - Date.parse(a.submittedAt)),
    );
  }),

  http.get(`${API}/applications/:id`, ({ params }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const application = applicationById(String(params['id']));
    return application === undefined
      ? mockApiError(
          404,
          'NOT_FOUND',
          'We could not find that registration. It may already have been dealt with.',
        )
      : HttpResponse.json(application);
  }),

  /* ---------------------------------------------- creating an account */

  /**
   * Creates the customer's login and opens their first account.
   *
   * ADMIN ONLY, and the response is the only time the temporary password is ever
   * returned. It is not stored anywhere an admin can read it back, and no later endpoint
   * exposes it: a password staff can look up afterwards is a password staff can use, and
   * an audit log cannot tell that apart from the customer signing in.
   *
   * The account is created but NOT usable. The customer's status is PENDING_APPROVAL
   * until a manager signs it off, and sign-in refuses it with that reason.
   */
  http.post(`${API}/applications/:id/create-account`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'ADMIN') {
      return forbidden('Only an administrator can create an account.');
    }

    const application = applicationById(String(params['id']));
    if (application === undefined) {
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that registration. It may already have been dealt with.',
      );
    }

    if (application.status !== 'SUBMITTED') {
      return mockApiError(
        409,
        'CONFLICT',
        'This application has already been processed. Refresh the list.',
      );
    }

    /*
     * The requested accounts, which the mock now READS rather than assumes.
     *
     * It used to take an accountType and an openingBalance and fabricate a funded
     * Account from them. That made the mock more capable than the real service, which is
     * how the discarded request stayed invisible: demoing with mocks showed an account
     * appearing, so nobody asked why it never appeared against the real backend.
     *
     * A mock that is kinder than the thing it stands in for teaches a false model of the
     * product. This one now does what the server does — records the request, opens
     * nothing, invents no balance.
     */
    const payload = await body(request);
    const requested = newAccounts(payload);

    if (requested.length === 0) {
      return mockApiError(
        400,
        'VALIDATION_FAILED',
        'Say which account or accounts the branch is opening.',
      );
    }

    /*
     * A business application also creates the company. A company exists at a bank
     * because somebody applied and somebody signed it off, and the applicant becomes its
     * first administrator because there is nobody else yet.
     */
    const company =
      application.kind === 'BUSINESS' ? registerCorporate(application.displayName) : undefined;

    const customerId = `cust-${crypto.randomUUID().slice(0, 8)}`;

    /*
     * The accounts the administrator entered, masked exactly as the server masks them.
     *
     * THE ID CARRIES THE CUSTOMER, and that is load-bearing rather than cosmetic.
     * `accountsForCustomer` finds a personal customer's accounts by the `acc-<customerId>`
     * prefix, so a random id here produced an account that existed in the staff portal
     * and was invisible to the customer who owned it: their dashboard said "No accounts
     * are linked to this profile yet" immediately after an administrator had linked
     * three.
     */
    const assigned: CustomerAccount[] = requested.map((entry, index) => ({
      id: `acc-${customerId}-${String(index + 1)}`,
      maskedNumber: maskAccountNumber(entry.accountNumber),
      accountType: entry.accountType,
      currency: entry.currency,
      assignedBy: member.fullName,
      assignedAt: new Date().toISOString(),
    }));

    /*
     * AND THE SAME ACCOUNTS AS BANKABLE ONES, which this used to leave out.
     *
     * The note that stood here said no balance was invented because the real service
     * stored nothing. That has stopped being true: the backend now opens the accounts,
     * posts the opening balance to its own ledger and serves them from /accounts. So the
     * mock had drifted the other way — LESS capable than the thing it stands in for —
     * and a mock that cannot show an account cannot be used to walk the flow it exists
     * to demonstrate.
     *
     * The balance is the figure the administrator typed, and nothing else. When they
     * leave it blank the balance is ABSENT rather than zero, which is the distinction
     * the tiles are built around: "Balance not shown" is the truth, and a zero would be
     * a statement about the customer's money that nobody made.
     */
    requested.forEach((entry, index) => {
      const opened = assigned[index];
      if (opened === undefined) return;

      const balance =
        entry.openingBalance === ''
          ? undefined
          : moneyToDto(parseMoney(entry.openingBalance, entry.currency));

      PROVISIONED_ACCOUNTS.push({
        id: opened.id,
        /*
         * The name is DERIVED from the type, the way AccountsController.nicknameFor does
         * it — "TARGET_SAVINGS" becomes "Target savings". The staff dropdown's own label
         * would be a second spelling of the same thing, and the customer would see one
         * name in the portal and the bank another.
         */
        nickname: readableType(entry.accountType),
        accountType: entry.accountType,
        maskedNumber: opened.maskedNumber,
        currency: entry.currency,
        /*
         * ACTIVE, matching the server: dormant, blocked and closed live in core banking,
         * which neither the service nor this mock is connected to. A frozen LOGIN is the
         * customer's status and a separate field.
         */
        status: 'ACTIVE',
        /*
         * Spread rather than assigned, because `exactOptionalPropertyTypes` treats an
         * explicit `undefined` as a different thing from an absent key — and absent is
         * what the API sends, since Jackson omits nulls.
         */
        ...(balance === undefined
          ? {}
          : {
              availableBalance: balance,
              currentBalance: balance,
              balanceAsOf: new Date().toISOString(),
            }),
        /*
         * Whether the account may be debited is, on the server, simply whether it is
         * active — there is no product rule behind it yet, and inventing one here would
         * be the mock teaching a rule the service does not have.
         */
        debitAllowed: true,
      });
    });
    saveAccounts();

    const record: CustomerRecord = {
      id: customerId,
      applicationId: application.id,
      fullName: application.displayName,
      email: application.email,
      phone: application.phone,
      customerNumber: nextCustomerNumber(),
      userType: company === undefined ? 'RETAIL' : 'CORPORATE',
      status: 'PENDING_APPROVAL',
      createdAt: new Date().toISOString(),
      createdByName: member.fullName,
      mustChangePassword: true,
      /*
       * The masks the administrator's entries produce — real, because a real account
       * number went in. Derived from `accounts` rather than kept in step by hand.
       */
      accountMasks: assigned.map((entry) => entry.maskedNumber),
      accounts: assigned,
      /*
       * No usable credential until a manager approves. The real backend stores a hash of
       * a value nobody holds at this point; the mock's equivalent is an empty string,
       * which matches no password anyone can type.
       */
      password: '',
      permissions: company === undefined ? RETAIL_PERMISSIONS : CORPORATE_ADMIN_PERMISSIONS,
      corporateId: company?.id,
    };

    CUSTOMERS.push(record);
    saveCustomers();
    setApplicationStatus(application.id, 'ACCOUNT_CREATED', { customerId });

    /*
     * An internal note, not a customer email. Nothing goes to the customer until a
     * manager has approved — telling them their account exists while it is still
     * unusable generates a support call and teaches them to distrust the next message.
     */
    send({
      kind: 'ACCOUNT_CREATED',
      to: `${member.email} (internal)`,
      subject: `Account created for ${record.fullName} — awaiting approval`,
      body: [
        `${member.fullName} created an account for ${record.fullName}.`,
        `Customer number ${record.customerNumber}${company === undefined ? '' : `, company ${company.name} (${company.code})`}.`,
        `Accounts: ${assigned.map((a) => `${a.accountType} ${a.maskedNumber}`).join(', ')}.`,
        '',
        'A manager must approve it before the customer can sign in. Give the customer',
        'their temporary password in person or by printed letter — never in the same',
        'message as the sign-in address.',
      ].join('\n'),
    });

    const result: CreatedCustomer = { customer: publicCustomer(record) };
    return HttpResponse.json(result, { status: 201 });
  }),

  /* ------------------------------------------- forgotten passwords */

  /**
   * The queue of customers waiting for a new password. MANAGER ONLY.
   *
   * <p>The role check is here and not only on the screen, because that is the point the
   * real server enforces it: an administrator can open the registrations queue and not
   * this one, since issuing a password hands out working access to an existing account.
   */
  http.get(`${API}/password-requests`, () => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'MANAGER') {
      return forbidden('Only a manager can see password requests.');
    }

    return HttpResponse.json(
      PASSWORD_REQUESTS.filter((entry) => entry.status === 'PENDING')
        .map((entry) => {
          const customer = customerById(entry.customerId);
          return customer === undefined ? null : passwordRequestView(entry, customer);
        })
        .filter((view) => view !== null)
        /* Oldest first: this is a queue, and newest-first buries whoever has waited longest. */
        .sort((a, b) => a.requestedAt.localeCompare(b.requestedAt)),
    );
  }),

  /**
   * Issues a new temporary password and "emails" it.
   *
   * <p>THE RESPONSE CARRIES NO CREDENTIAL, matching the server. The password exists in the
   * outbox message and nowhere else, so a staff screen has nothing to display and no
   * screenshot of one can contain it.
   */
  http.post(`${API}/password-requests/:id/issue`, ({ params }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'MANAGER') {
      return forbidden('Only a manager can issue a password.');
    }

    const entry = PASSWORD_REQUESTS.find((row) => row.id === String(params['id']));
    if (entry === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that password request.');
    }
    if (entry.status !== 'PENDING') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Another manager has already dealt with this request. Refresh the page to see what happened.',
      );
    }

    const customer = customerById(entry.customerId);
    if (customer === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find the customer this belongs to.');
    }

    /*
     * A frozen customer gets nothing, matching the server. Sign-in is refused on status
     * whatever password they hold, so issuing one would send a credential that cannot work
     * and move the customer's confusion to the sign-in screen.
     */
    if (customer.status !== 'ACTIVE') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'This customer cannot sign in at the moment, so a new password would not help them.' +
          ' Deal with the account\u2019s status first, then issue one.',
      );
    }

    const issued = temporaryPassword();
    updateCustomer(customer.id, { password: issued, mustChangePassword: true });

    entry.status = 'FULFILLED';
    savePasswordRequests();

    send({
      kind: 'PASSWORD_REISSUED',
      to: customer.email,
      subject: 'Your new Zigama CSS internet banking password',
      body: reissuedPasswordEmail(customer.fullName, customer.email, issued),
    });

    return HttpResponse.json(passwordRequestView(entry, customer));
  }),

  /** Refuses the request. The reason is emailed, so it is required. */
  http.post(`${API}/password-requests/:id/refuse`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'MANAGER') {
      return forbidden('Only a manager can refuse a password request.');
    }

    const entry = PASSWORD_REQUESTS.find((row) => row.id === String(params['id']));
    if (entry === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that password request.');
    }
    if (entry.status !== 'PENDING') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Another manager has already dealt with this request. Refresh the page to see what happened.',
      );
    }

    const payload = await body(request);
    const reason = str(payload, 'reason').trim();
    if (reason === '') {
      return mockApiError(
        400,
        'VALIDATION_FAILED',
        'Give a reason, so whoever speaks to the customer next can explain it.',
      );
    }

    const customer = customerById(entry.customerId);
    if (customer === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find the customer this belongs to.');
    }

    entry.status = 'REFUSED';
    savePasswordRequests();

    send({
      kind: 'PASSWORD_REQUEST_REFUSED',
      to: customer.email,
      subject: 'About your Zigama CSS password request',
      body: [
        `Dear ${customer.fullName},`,
        '',
        'We were not able to issue a new internet banking password from the request we',
        'received.',
        '',
        `Reason: ${reason}`,
        '',
        'Your existing password has not been changed. To get this sorted out, please visit',
        'any branch with your ID, or call the number printed on the back of your card.',
        '',
        'Zigama CSS',
      ].join('\n'),
    });

    return HttpResponse.json(passwordRequestView(entry, customer));
  }),

  /* ------------------------------------------- linking real accounts */

  /*
   * The mock mirrors the server's rules rather than being kinder than them.
   *
   * That lesson is recent: this file used to fabricate a funded Account at onboarding,
   * which made demos show an account appearing and hid the fact that the real service
   * stored nothing. A mock that is more capable than the thing it stands in for teaches
   * a false model of the product.
   */
  http.post(`${API}/customers/:id/accounts`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'MANAGER') {
      return forbidden('Only a manager can link an account.');
    }

    const record = customerById(String(params['id']));
    if (record === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that customer.');
    }

    const payload = await body(request);
    const accountNumber = str(payload, 'accountNumber').trim();
    const accountType = str(payload, 'accountType');
    const currency = str(payload, 'currency').toUpperCase();

    if (!/^\d{10,16}$/.test(accountNumber)) {
      return mockApiError(400, 'VALIDATION_FAILED', 'An account number is 10 to 16 digits.');
    }
    if (!ACCOUNT_TYPES.some((type) => type.value === accountType)) {
      return mockApiError(400, 'VALIDATION_FAILED', 'Choose the account type.');
    }
    if (!/^[A-Za-z]{3}$/.test(currency)) {
      return mockApiError(400, 'VALIDATION_FAILED', 'A currency is a three-letter code.');
    }

    const alreadyLinked = LINKS.find(
      (link) => link.accountNumber === accountNumber && !link.unlinked,
    );
    if (alreadyLinked !== undefined && alreadyLinked.customerId !== record.id) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'That account is already linked to a different customer. If this is a joint ' +
          'account, refer it rather than linking it here.',
      );
    }
    if (alreadyLinked !== undefined) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'That account is already linked to this customer.',
      );
    }

    LINKS.push({
      id: `lnk-${crypto.randomUUID().slice(0, 8)}`,
      customerId: record.id,
      accountNumber,
      maskedNumber: maskAccountNumber(accountNumber),
      unlinked: false,
    });

    return HttpResponse.json(withLinkedAccounts(record));
  }),

  http.post(`${API}/customers/:id/accounts/:linkId/unlink`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'MANAGER') {
      return forbidden('Only a manager can unlink an account.');
    }

    const record = customerById(String(params['id']));
    if (record === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that customer.');
    }

    const payload = await body(request);
    if (str(payload, 'reason').trim() === '') {
      return mockApiError(400, 'VALIDATION_FAILED', 'Give a reason.');
    }

    const link = LINKS.find(
      (entry) => entry.id === String(params['linkId']) && entry.customerId === record.id,
    );
    if (link === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that linked account.');
    }

    // Marked, not removed — the history is the point. See V7__linked_accounts.sql.
    link.unlinked = true;

    return HttpResponse.json(withLinkedAccounts(record));
  }),

  /* ---------------------------------------------- customers */

  http.get(`${API}/customers`, ({ request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const status = new URL(request.url).searchParams.get('status');
    const rows = CUSTOMERS.filter((c) => status === null || c.status === status);

    // Never the password, for either role, at any point.
    return HttpResponse.json(
      rows.map(publicCustomer).sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)),
    );
  }),

  /* ---------------------------------------------- approval */

  /**
   * Approves a created account, activates it, and emails the customer.
   *
   * MANAGER ONLY, and refused when the caller is the admin who created it. That check
   * is what makes this a control rather than a formality; the UI disables the button
   * too, but this is the part that cannot be bypassed.
   */
  http.post(`${API}/customers/:id/approve`, ({ params }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'MANAGER') {
      return forbidden('Only a manager can approve an account.');
    }

    const record = customerById(String(params['id']));
    if (record === undefined)
      return mockApiError(404, 'NOT_FOUND', 'We could not find that customer.');

    if (record.status !== 'PENDING_APPROVAL') {
      return mockApiError(
        409,
        'CONFLICT',
        'Another manager has already approved or rejected this account. Refresh the page to see the result.',
      );
    }

    if (record.createdByName === member.fullName) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'You created this account, so you cannot approve it. It needs a different manager.',
      );
    }

    /*
     * The temporary password is made HERE, not when the account was created, matching
     * the backend. It goes to the customer by email and nowhere else.
     */
    const password = temporaryPassword();

    const updated = updateCustomer(record.id, {
      status: 'ACTIVE',
      approvedAt: new Date().toISOString(),
      approvedByName: member.fullName,
      password,
      mustChangePassword: true,
    });
    if (updated === undefined)
      return mockApiError(404, 'NOT_FOUND', 'We could not find that customer.');

    setApplicationStatus(updated.applicationId, 'APPROVED');

    const mail = approvalEmail(publicCustomer(updated), password, TEMPORARY_PASSWORD_HOURS);
    send({ kind: 'ACCOUNT_APPROVED', to: updated.email, ...mail });

    return HttpResponse.json(publicCustomer(updated));
  }),

  http.post(`${API}/customers/:id/reject`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();
    if (member.role !== 'MANAGER') {
      return forbidden('Only a manager can reject an account.');
    }

    const payload = await body(request);
    const reason = str(payload, 'reason').trim();

    if (reason === '') {
      return mockApiError(
        422,
        'VALIDATION_FAILED',
        'Give a reason. The applicant is told what it says.',
      );
    }

    const record = customerById(String(params['id']));
    if (record === undefined)
      return mockApiError(404, 'NOT_FOUND', 'We could not find that customer.');

    if (record.status !== 'PENDING_APPROVAL') {
      return mockApiError(
        409,
        'CONFLICT',
        'Another manager has already approved or rejected this account. Refresh the page to see the result.',
      );
    }

    const updated = updateCustomer(record.id, {
      status: 'REJECTED',
      rejectionReason: reason,
      approvedAt: new Date().toISOString(),
      approvedByName: member.fullName,
      /*
       * The temporary password is destroyed on rejection. A refused account with a live
       * password is an account waiting to be used by whoever was given it.
       */
      password: crypto.randomUUID(),
    });
    if (updated === undefined)
      return mockApiError(404, 'NOT_FOUND', 'We could not find that customer.');

    setApplicationStatus(updated.applicationId, 'REJECTED', { rejectionReason: reason });

    const mail = rejectionEmail(publicCustomer(updated), reason);
    send({ kind: 'ACCOUNT_REJECTED', to: updated.email, ...mail });

    return HttpResponse.json(publicCustomer(updated));
  }),

  /* ---------------------------------------------- payees to check */

  /**
   * Payees waiting for a member of staff, oldest first.
   *
   * <p>EITHER ROLE, unlike the password queue above, and there is no role check for the
   * same reason the server has none beyond `hasAnyRole('ADMIN','MANAGER')`: the four-eyes
   * split between the two is about creating an account and releasing it, and a payee's
   * maker is the CUSTOMER, so the separation holds whoever checks it.
   */
  http.get(`${API}/beneficiaries`, () => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    return HttpResponse.json(
      BENEFICIARIES.filter((payee) => payee.status === 'PENDING_VERIFICATION')
        .map(beneficiaryReviewView)
        .filter((view) => view !== null)
        /* Oldest first: a queue, not a feed. */
        .sort((a, b) => a.addedAt.localeCompare(b.addedAt)),
    );
  }),

  /** Clears the payee for payment and "emails" the customer. */
  http.post(`${API}/beneficiaries/:id/approve`, ({ params }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const payee = BENEFICIARIES.find((row) => row.id === String(params['id']));
    if (payee === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that payee.');
    }
    /*
     * A SECOND DECISION IS A CONFLICT, matching the server. Without this the second
     * reviewer silently replaces the first one's decision, and a refusal landing on an
     * approved payee would make one the customer has already been emailed about unpayable
     * again with no record that it was ever cleared.
     */
    if (payee.status !== 'PENDING_VERIFICATION') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Another member of staff has already dealt with this payee. Refresh the page to see what happened.',
      );
    }

    const approved: MockBeneficiary = { ...payee, status: 'ACTIVE' };
    BENEFICIARIES[BENEFICIARIES.indexOf(payee)] = approved;

    const customer = approved.ownerId === undefined ? undefined : customerById(approved.ownerId);
    if (customer !== undefined) {
      send({
        kind: 'BENEFICIARY_APPROVED',
        to: customer.email,
        subject: 'You can now pay a payee you saved',
        body: beneficiaryApprovedEmail(customer.fullName, approved),
      });
    }

    const view = beneficiaryReviewView(approved);
    return view === null
      ? mockApiError(404, 'NOT_FOUND', 'We could not find the customer this payee belongs to.')
      : HttpResponse.json(view);
  }),

  /** Declines the payee. The reason reaches the customer's own list and their inbox. */
  http.post(`${API}/beneficiaries/:id/refuse`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const payload = (await request.json()) as Record<string, unknown>;
    const reason = str(payload, 'reason').trim();
    /*
     * 400, matching the server: the real refusal is caught by @NotBlank at the controller
     * boundary before the service is reached, so a mock answering 422 would send the
     * screen down a different branch than production does.
     */
    if (reason === '') {
      return mockApiError(
        400,
        'VALIDATION_FAILED',
        'Give a reason, so the customer knows what to put right.',
      );
    }

    const payee = BENEFICIARIES.find((row) => row.id === String(params['id']));
    if (payee === undefined) {
      return mockApiError(404, 'NOT_FOUND', 'We could not find that payee.');
    }
    if (payee.status !== 'PENDING_VERIFICATION') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Another member of staff has already dealt with this payee. Refresh the page to see what happened.',
      );
    }

    const refused: MockBeneficiary = { ...payee, status: 'REFUSED', refusedReason: reason };
    BENEFICIARIES[BENEFICIARIES.indexOf(payee)] = refused;

    const customer = refused.ownerId === undefined ? undefined : customerById(refused.ownerId);
    if (customer !== undefined) {
      send({
        kind: 'BENEFICIARY_REFUSED',
        to: customer.email,
        subject: 'About a payee you saved',
        body: beneficiaryRefusedEmail(customer.fullName, refused, reason),
      });
    }

    const view = beneficiaryReviewView(refused);
    return view === null
      ? mockApiError(404, 'NOT_FOUND', 'We could not find the customer this payee belongs to.')
      : HttpResponse.json(view);
  }),

  /* ------------------------------------ cards and cheque books to make */

  /**
   * Everything still open, oldest first.
   *
   * <p>OPEN ONLY, matching the server: a collected or declined request is finished work,
   * and a queue that keeps showing finished work stops being a list of what to do. The
   * customer keeps seeing theirs on their own pages, where the history is the point.
   */
  http.get(`${API}/service-requests`, () => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    return HttpResponse.json(
      SERVICE_REQUESTS.filter((row) => row.status === 'SUBMITTED' || row.status === 'READY')
        .map(serviceRequestQueueView)
        .filter((view) => view !== null)
        /* Oldest first: a queue, not a feed. */
        .sort((a, b) => a.submittedAt.localeCompare(b.submittedAt)),
    );
  }),

  /** Marks it produced, records where it is, and "emails" the customer. */
  http.post(`${API}/service-requests/:id/ready`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const payload = (await request.json()) as Record<string, unknown>;
    const collectionPoint = str(payload, 'collectionPoint').trim();
    /*
     * 400, matching the server: @NotBlank on CollectionPointRequest rejects this at the
     * controller boundary before the service is reached, so a mock answering 422 would
     * send the screen down a different branch than production does.
     */
    if (collectionPoint === '') {
      return mockApiError(
        400,
        'VALIDATION_FAILED',
        'Say where the customer should collect it. The portal has no branch list of its' +
          ' own and must not guess one.',
      );
    }

    const found = SERVICE_REQUESTS.find((row) => row.id === String(params['id']));
    if (found === undefined) {
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that request. It may already have been dealt with.',
      );
    }
    /*
     * A SECOND DECISION IS A CONFLICT, matching the entity. Without this the second staff
     * member silently replaces the first one's name and collection point, so the record
     * credits whoever was slower and the customer may have been emailed two places.
     */
    if (found.status !== 'SUBMITTED') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        `This request is already ${found.status.toLowerCase()}, so it cannot be marked` +
          ' ready. Refresh the page to see what happened.',
      );
    }

    const ready: MockServiceRequest = { ...found, status: 'READY', collectionPoint };
    SERVICE_REQUESTS[SERVICE_REQUESTS.indexOf(found)] = ready;

    const customer = customerById(ready.ownerId);
    if (customer !== undefined) {
      send({
        kind: 'SERVICE_REQUEST_READY',
        to: customer.email,
        subject: 'Your request is ready to collect',
        body: serviceRequestReadyEmail(
          customer.fullName,
          serviceRequestLabel(ready.requestType),
          ready.reference,
          collectionPoint,
        ),
      });
    }

    const view = serviceRequestQueueView(ready);
    return view === null
      ? mockApiError(404, 'NOT_FOUND', 'We could not find the customer this request belongs to.')
      : HttpResponse.json(view);
  }),

  /** Records that it was handed over. NO EMAIL: the customer is at the counter. */
  http.post(`${API}/service-requests/:id/collected`, ({ params }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const found = SERVICE_REQUESTS.find((row) => row.id === String(params['id']));
    if (found === undefined) {
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that request. It may already have been dealt with.',
      );
    }
    /*
     * FROM READY ONLY, matching the entity. Straight from SUBMITTED would record that
     * something was handed over before it was made, with no collection point and no ready
     * timestamp against a database constraint that requires both.
     */
    if (found.status !== 'READY') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        `This request is already ${found.status.toLowerCase()}, so it cannot be marked` +
          ' collected. Refresh the page to see what happened.',
      );
    }

    const collected: MockServiceRequest = { ...found, status: 'COLLECTED' };
    SERVICE_REQUESTS[SERVICE_REQUESTS.indexOf(found)] = collected;

    const view = serviceRequestQueueView(collected);
    return view === null
      ? mockApiError(404, 'NOT_FOUND', 'We could not find the customer this request belongs to.')
      : HttpResponse.json(view);
  }),

  /** Declines it. The reason reaches the customer's own list and their inbox. */
  http.post(`${API}/service-requests/:id/decline`, async ({ params, request }) => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    const payload = (await request.json()) as Record<string, unknown>;
    const reason = str(payload, 'reason').trim();
    if (reason === '') {
      return mockApiError(
        400,
        'VALIDATION_FAILED',
        'Give a reason for declining this, so the customer knows what to do next.',
      );
    }

    const found = SERVICE_REQUESTS.find((row) => row.id === String(params['id']));
    if (found === undefined) {
      return mockApiError(
        404,
        'NOT_FOUND',
        'We could not find that request. It may already have been dealt with.',
      );
    }
    if (found.status !== 'SUBMITTED') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        `This request is already ${found.status.toLowerCase()}, so it cannot be declined.` +
          ' Refresh the page to see what happened.',
      );
    }

    const declined: MockServiceRequest = {
      ...found,
      status: 'DECLINED',
      declineReason: reason,
    };
    SERVICE_REQUESTS[SERVICE_REQUESTS.indexOf(found)] = declined;

    const customer = customerById(declined.ownerId);
    if (customer !== undefined) {
      send({
        kind: 'SERVICE_REQUEST_DECLINED',
        to: customer.email,
        subject: 'About your request',
        body: serviceRequestDeclinedEmail(
          customer.fullName,
          serviceRequestLabel(declined.requestType),
          declined.reference,
          reason,
        ),
      });
    }

    const view = serviceRequestQueueView(declined);
    return view === null
      ? mockApiError(404, 'NOT_FOUND', 'We could not find the customer this request belongs to.')
      : HttpResponse.json(view);
  }),

  /* ---------------------------------------------- outbox */

  /**
   * What the bank would have sent.
   *
   * DEVELOPMENT ONLY. There is no mail server, and rather than pretend a message went
   * out, every one is recorded and readable here — so the flow can be walked end to end
   * and so it is obvious exactly what a customer receives.
   *
   * THIS IS NOT DEFINED IN THE BLUEPRINT. The real transport, the templates, the sender
   * domain and its SPF/DKIM setup all still need specifying.
   */
  http.get(`${API}/outbox`, () => {
    const member = activeStaff();
    return member === null ? unauthenticated() : HttpResponse.json(OUTBOX);
  }),

  /* ---------------------------------------------- reset */

  /**
   * Empties the whole mock bank.
   *
   * DEVELOPMENT ONLY. The store is persisted now, which is what makes the onboarding
   * chain testable — and also what makes a clean slate something you have to ask for.
   * Walking the flow a second time with last run's customers still in the list is how
   * you end up debugging the fixture instead of the code.
   *
   * It exists solely so the flow can be re-run from nothing, and it must never have a
   * counterpart on the real backend.
   */
  http.post(`${API}/dev/reset`, () => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    APPLICATIONS.length = 0;
    CUSTOMERS.length = 0;
    PROVISIONED_ACCOUNTS.length = 0;
    OUTBOX.length = 0;
    CORPORATES.length = 0;
    /*
     * Password requests too. A row left behind points at a customer id that no longer
     * exists, and the queue screen would show a request it cannot name anybody for.
     */
    PASSWORD_REQUESTS.length = 0;
    clearAll();

    return new HttpResponse(null, { status: 204 });
  }),

  /* ---------------------------------------------- summary */

  http.get(`${API}/summary`, () => {
    const member = activeStaff();
    if (member === null) return unauthenticated();

    return HttpResponse.json({
      submitted: APPLICATIONS.filter((a) => a.status === 'SUBMITTED').length,
      awaitingApproval: CUSTOMERS.filter((c) => c.status === 'PENDING_APPROVAL').length,
      active: CUSTOMERS.filter((c) => c.status === 'ACTIVE').length,
      rejected: CUSTOMERS.filter((c) => c.status === 'REJECTED').length,
      awaitingFirstSignIn: CUSTOMERS.filter((c) => c.status === 'ACTIVE' && c.mustChangePassword)
        .length,

      /*
       * THE QUEUE FIGURES, COUNTED FROM THE SAME ARRAYS THE QUEUE SCREENS READ.
       *
       * Not constants, and that distinction is the whole point of adding them here: the
       * overview says "3 payees to check" and the payees screen then has to show three. A
       * mock that returned a fixed number would let the dashboard be built and demoed with
       * figures that disagree with every screen they link to, which is a bug nobody sees
       * until the live API is switched on.
       *
       * Transfers are not in this file's stores — the mock transfer rail lives in
       * movementHandlers — so that one is zero here rather than guessed at. The overview
       * renders a zero by omitting the row, which is the correct behaviour for "no mock
       * data" and for "nothing waiting" alike.
       */
      transfersToRelease: 0,
      passwordRequests: PASSWORD_REQUESTS.filter((r) => r.status === 'PENDING').length,
      payeesToCheck: BENEFICIARIES.filter((b) => b.status === 'PENDING_VERIFICATION').length,
      cardsAndChequeBooks: SERVICE_REQUESTS.filter(
        (r) => r.status === 'SUBMITTED' || r.status === 'READY',
      ).length,
    });
  }),
];
