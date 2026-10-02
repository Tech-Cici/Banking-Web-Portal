import { http, HttpResponse, type HttpResponseResolver } from 'msw';
import type { Application, ApplicationField, ApplicationKind } from '@/types/admin';
import type { OtpChallenge, RegistrationResult, VerifiedChallenge } from '@/types/registration';
import { APPLICATIONS, saveApplications, send } from './data/onboarding';
import { corporateByCode } from './data/personas';
import { mockApiError } from './handlers';
import { TEST_TRIGGERS } from './testTriggers';
import { newCorrelationId } from '@/services/correlation';

/**
 * Mock registration endpoints.
 *
 * These now DO something. Every completed registration creates an {@link Application}
 * that appears in the bank's staff portal, because that is the only way a customer can
 * come into existence: register, an admin creates the login, a manager approves it.
 *
 * Note what registration does NOT do any more: it does not set a password. The applicant
 * used to choose one at the end of the personal flow, which cannot coexist with an admin
 * issuing a temporary one — the account would have two passwords and nobody could say
 * which was current. The bank issues the credential; the applicant replaces it on first
 * sign-in.
 *
 * Error states stay reachable deliberately rather than by luck, because the blueprint
 * requires every one of them to be designed (section 47). The triggers are listed in
 * `TEST_TRIGGERS` and surfaced in the UI in development.
 */

const API = '*/api/v1';

function reference(prefix: string): string {
  return `${prefix}-${Date.now().toString(36).toUpperCase()}`;
}

function challenge(deliveryHint: string): OtpChallenge {
  return {
    challengeId: crypto.randomUUID(),
    otpLength: 6,
    resendAfterSeconds: 30,
    deliveryHint,
  };
}

/**
 * Masks an email the way it will be shown back to the applicant.
 *
 * Enough to recognise the address they typed, not enough to be worth harvesting from
 * someone else's screen: `nicaise@kwikkoders.com` becomes `n******@kwikkoders.com`.
 */
function hintFromEmail(email: unknown): string {
  const value = typeof email === 'string' ? email : '';
  const at = value.indexOf('@');
  if (at < 1) return '***';

  const name = value.slice(0, at);
  const domain = value.slice(at);
  return `${name[0] ?? ''}${'*'.repeat(Math.max(1, name.length - 1))}${domain}`;
}

/**
 * The code, and where it goes.
 *
 * There is NO mail server (docs/OPEN-ITEMS.md), so rather than pretend one sent
 * something, the code is written to the same development mailbox the approval emails
 * use and can be read under Developer tools. That keeps one rule consistent across the whole
 * pipeline: nothing in this app ever claims a message was delivered when it was not.
 *
 * The code itself is fixed at 123456 in the mock. A random one would be unreadable
 * without the mailbox and would make the tests flaky for no benefit — the real
 * generator belongs on the server, where the code can be hashed and rate limited
 * rather than sitting in browser memory.
 */
const MOCK_EMAIL_CODE = '123456';

function sendEmailCode(email: string): void {
  if (email === '') return;

  send({
    kind: 'EMAIL_VERIFICATION',
    to: email,
    subject: 'Your Zigama CSS verification code',
    body: [
      `Your verification code is ${MOCK_EMAIL_CODE}.`,
      '',
      'It expires in 10 minutes. Enter it on the registration page to confirm this',
      'email address belongs to you.',
      '',
      'If you did not start a registration with Zigama CSS, ignore this message —',
      'no account will be created. We will never ask you for this code by phone.',
    ].join('\n'),
  });
}

function str(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  return typeof value === 'string' ? value : '';
}

/**
 * Details captured at registration, in the order an admin should read them.
 *
 * Only the fields present are included — an empty row on a review screen reads as
 * missing data rather than as a field that was never asked for. Nothing sensitive is
 * echoed: no password (there isn't one any more), and identity numbers are recorded
 * because the admin has to check them against the documents, which is the whole job.
 */
function fieldsFrom(
  source: Record<string, unknown>,
  labels: readonly (readonly [string, string])[],
): readonly ApplicationField[] {
  return labels
    .map(([key, label]) => ({ label, value: str(source, key) }))
    .filter((field) => field.value !== '');
}

interface NewApplication {
  readonly kind: ApplicationKind;
  readonly prefix: string;
  readonly displayName: string;
  readonly email: string;
  readonly phone: string;
  readonly details: readonly ApplicationField[];
  /**
   * Whether the applicant entered a code sent to that address.
   *
   * Required rather than defaulted. A default would mean a new registration flow
   * silently inherits "verified" without anyone deciding, and the admin would be
   * reading a badge that means nothing.
   */
  readonly emailVerified: boolean;
}

function createApplication(input: NewApplication): Application {
  const application: Application = {
    id: `app-${crypto.randomUUID().slice(0, 8)}`,
    reference: reference(input.prefix),
    kind: input.kind,
    status: 'SUBMITTED',
    submittedAt: new Date().toISOString(),
    displayName: input.displayName,
    email: input.email,
    phone: input.phone,
    details: input.details,
    emailVerified: input.emailVerified,
  };

  APPLICATIONS.push(application);
  saveApplications();
  return application;
}

/**
 * Pending personal registrations, keyed by the token the OTP step issues.
 *
 * The details are held between `verify` and `complete` rather than resent, so the
 * applicant cannot change their national ID after the code was sent to their phone.
 */
const pendingPersonal = new Map<string, Record<string, unknown>>();
const verifiedTokens = new Map<string, Record<string, unknown>>();

const startPersonal: HttpResponseResolver = async ({ request }) => {
  const body = (await request.json()) as Record<string, unknown>;
  const accountNumber = str(body, 'accountNumber');

  if (accountNumber === TEST_TRIGGERS.serverError) {
    return mockApiError(500, 'INTERNAL_ERROR', 'This part of the bank is temporarily unavailable. Please try again in a few minutes.');
  }

  if (accountNumber === TEST_TRIGGERS.accountRateLimited) {
    return HttpResponse.json(
      {
        timestamp: new Date().toISOString(),
        status: 429,
        code: 'RATE_LIMITED',
        message: 'You have tried to register too many times in a row. Please wait a few minutes and try again.',
        correlationId: newCorrelationId(),
      },
      { status: 429, headers: { 'Retry-After': '60' } },
    );
  }

  if (accountNumber === TEST_TRIGGERS.accountNotFound) {
    /*
     * Deliberately vague, and deliberately a 422 rather than a 404.
     *
     * Saying "no such account" would let someone test account numbers for existence. The
     * message covers both "wrong number" and "details do not match", which is also what
     * a real customer who mistyped needs to hear.
     */
    return mockApiError(
      422,
      'BUSINESS_RULE_VIOLATION',
      'We could not match those details to an account. Check them and try again, or visit your branch.',
    );
  }

  /*
   * The code goes to the EMAIL address, not the phone.
   *
   * It used to go to the phone on file, which checked something real — that the
   * applicant holds the number the bank already has. But identity is already being
   * checked a line above: account number, national ID and date of birth have to match
   * the bank's records, and a mismatch stops the registration here.
   *
   * What was NOT being checked is the email, and the email is what the rest of the
   * pipeline runs on: the approval notice goes there, and it is the address a temporary
   * password gets arranged through. An application whose email nobody has verified can
   * be uncontactable, or can belong to somebody else — and the admin reading it had no
   * way to tell.
   */
  const email = str(body, 'email');
  const issued = challenge(hintFromEmail(email));
  sendEmailCode(email);
  pendingPersonal.set(issued.challengeId, body);
  return HttpResponse.json(issued);
};

const verifyOtp: HttpResponseResolver = async ({ request }) => {
  const body = (await request.json()) as Record<string, unknown>;
  const code = str(body, 'code');
  const challengeId = str(body, 'challengeId');

  if (code === TEST_TRIGGERS.otpExpired) {
    return mockApiError(
      422,
      'BUSINESS_RULE_VIOLATION',
      'That code has expired. Request a new one.',
    );
  }

  if (code === TEST_TRIGGERS.otpTooManyAttempts) {
    return mockApiError(
      429,
      'RATE_LIMITED',
      'Too many incorrect codes. For your security, registration is locked. Contact the bank.',
    );
  }

  if (code === TEST_TRIGGERS.otpInvalid || code.length !== 6) {
    return mockApiError(422, 'BUSINESS_RULE_VIOLATION', 'That code is not correct. Check the code we sent you and type it again.');
  }

  const verified: VerifiedChallenge = {
    verificationToken: crypto.randomUUID(),
    expiresInSeconds: 600,
  };

  // Carry the verified details forward; the applicant cannot alter them after this.
  verifiedTokens.set(verified.verificationToken, pendingPersonal.get(challengeId) ?? {});
  pendingPersonal.delete(challengeId);

  return HttpResponse.json(verified);
};

export const registrationHandlers = [
  http.post(`${API}/registration/personal/start`, startPersonal),
  http.post(`${API}/registration/personal/verify`, verifyOtp),

  /*
   * Resend re-issues against the SAME pending registration.
   *
   * It used to return a challenge with a hard-coded '***456' hint and no record of
   * anything being sent — so asking for a new code silently produced a screen claiming
   * a code had gone to an address that was not the applicant's.
   */
  http.post(`${API}/registration/otp/resend`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    const previous = pendingPersonal.get(str(body, 'challengeId'));

    if (previous === undefined) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'This registration has expired. Please start again.',
      );
    }

    const email = str(previous, 'email');
    const reissued = challenge(hintFromEmail(email));
    sendEmailCode(email);

    // The details move to the new challenge id; the old one stops working.
    pendingPersonal.delete(str(body, 'challengeId'));
    pendingPersonal.set(reissued.challengeId, previous);

    return HttpResponse.json(reissued);
  }),

  /**
   * Finishes a personal registration.
   *
   * Creates an application, not an account. The applicant is told the bank will review
   * it and email them — which is now true, rather than the old "you can sign in now".
   */
  http.post(`${API}/registration/personal/complete`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    const token = str(body, 'verificationToken');

    const details = verifiedTokens.get(token);
    if (details === undefined) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'This registration has expired. Please start again.',
      );
    }
    verifiedTokens.delete(token);

    const application = createApplication({
      kind: 'PERSONAL',
      prefix: 'REG',
      displayName: str(details, 'fullName') || str(details, 'email') || 'Personal applicant',
      email: str(details, 'email'),
      phone: str(details, 'phone'),
      details: fieldsFrom(details, [
        ['accountNumber', 'Existing account number'],
        ['nationalId', 'National ID'],
        ['dateOfBirth', 'Date of birth'],
      ]),
      // Reaching this endpoint at all required a code sent to that address.
      emailVerified: true,
    });

    const result: RegistrationResult = {
      reference: application.reference,
      status: 'SUBMITTED',
      message:
        'Your registration has been received. The bank will check your details and email you when your account is ready.',
    };
    return HttpResponse.json(result, { status: 201 });
  }),

  /*
   * THE BUSINESS HANDLER IS GONE, and that is the fix rather than a tidy-up.
   *
   * It answered `/registration/business` with a BRA- reference and "Your application has
   * been received. The bank will review it and contact your named representative." The
   * backend had no company registration at all, so this mock was the only thing that ever
   * replied: the applicant was shown a receipt and nothing was stored anywhere. Staff
   * opened Registrations and found no such application.
   *
   * It is now implemented for real, as two calls mirroring personal registration —
   * `/registration/business/start` emails a code to the named contact and
   * `/registration/business/complete` creates the application. Neither is mocked, so both
   * go to the service. See BusinessRegistrationTest on the backend, which asserts on the
   * database rather than on the response, because a confident 201 was exactly what this
   * handler used to return.
   */

  http.post(`${API}/registration/join`, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    const code = str(body, 'companyCode').toUpperCase();

    if (code === TEST_TRIGGERS.companyCodeRejected) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'That company is not accepting new access requests. Contact your administrator.',
      );
    }

    /*
     * An unknown code returns the SAME response as a valid one, and still creates an
     * application.
     *
     * Distinguishing them would turn this endpoint into a way to discover which
     * companies bank here, one guess at a time. The admin reviewing it sees whether the
     * code matched a real company; the applicant is told nothing either way.
     */
    const company = corporateByCode(code);

    const application = createApplication({
      kind: 'JOIN_BUSINESS',
      prefix: 'JOIN',
      displayName: str(body, 'fullName') || 'Access request',
      email: str(body, 'email'),
      phone: str(body, 'phone'),
      details: [
        { label: 'Company code given', value: code },
        {
          label: 'Matches a company',
          value: company === undefined ? 'No — check with the applicant' : company.name,
        },
        ...fieldsFrom(body, [
          ['nationalId', 'National ID'],
          ['jobTitle', 'Role at the company'],
          ['requestedAccess', 'Access requested'],
        ]),
      ],
      /*
       * Not verified: this flow has no code step yet. Recorded honestly rather than
       * defaulted to true, so the admin sees which applications arrived with a
       * confirmed contact address and which did not. Tracked in docs/OPEN-ITEMS.md.
       */
      emailVerified: false,
    });

    const result: RegistrationResult = {
      reference: application.reference,
      status: 'PENDING_APPROVAL',
      message:
        'Your request has been sent. The bank and your company administrator will review it, and you will be emailed.',
    };
    return HttpResponse.json(result, { status: 202 });
  }),
];
