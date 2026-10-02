import { http, HttpResponse } from 'msw';
import type { AuthChallenge, LoginResult, StaffLoginResult } from '@/types/auth';
import { activeStaff } from './data/activeSession';
import {
  customerById,
  customerByIdentifier,
  PASSWORD_REQUESTS,
  savePasswordRequests,
  staffByEmail,
  STAFF,
  updateCustomer,
} from './data/onboarding';
import { customerSessionId, signInCustomer, signInStaff, signOut } from './data/sessionStore';
import { mockApiError } from './handlers';
import { newCorrelationId } from '@/services/correlation';

/**
 * Mock authentication.
 *
 * Two populations, two endpoints:
 *
 *  - `/auth/login` is the customer portal. It authenticates against accounts an admin
 *    created and a manager approved. There are no fixture customers, so nothing can
 *    sign in here until that chain has actually been walked.
 *  - `/auth/staff/login` is the bank's own portal. Two staff logins are seeded, because
 *    somebody has to be able to start the chain.
 *
 * Behaviours worth keeping when this becomes real:
 *  - A wrong password and an unknown user return the SAME message. Distinguishing them
 *    turns the login form into a way to discover who banks here.
 *  - An account awaiting approval IS told so plainly. Unlike a wrong password, the
 *    customer cannot resolve it by retrying, and needs to know to wait rather than to
 *    worry that they have been refused.
 *  - A temporary password opens a session that goes no further than the change-password
 *    screen. The portal does not open until the password the admin typed has been
 *    replaced by one only the customer knows.
 */

const API = '*/api/v1';

/** Challenges live only as long as the page; a reload invalidates them, as it should. */
const pendingChallenges = new Map<string, string>();

function challengeFor(userId: string, phone: string): AuthChallenge {
  const challengeId = crypto.randomUUID();
  pendingChallenges.set(challengeId, userId);

  const digits = phone.replace(/\D/g, '');

  return {
    challengeId,
    otpLength: 6,
    resendAfterSeconds: 30,
    deliveryHint: digits.length >= 3 ? `***${digits.slice(-3)}` : '***',
  };
}

const GENERIC_FAILURE =
  'The sign-in details you entered are not correct. Please check and try again.';

async function body(request: Request): Promise<Record<string, unknown>> {
  return (await request.json()) as Record<string, unknown>;
}

function str(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  return typeof value === 'string' ? value : '';
}

function validationFailure(field: string, message: string): Response {
  return HttpResponse.json(
    {
      timestamp: new Date().toISOString(),
      status: 422,
      code: 'VALIDATION_FAILED',
      message,
      correlationId: newCorrelationId(),
      fieldErrors: [{ field, code: 'INVALID', message }],
    },
    { status: 422 },
  );
}

/**
 * Password policy, enforced here rather than only in the browser.
 *
 * The form shows the rules and checks them as you type, which is a courtesy. This is
 * the check that counts, because a client-side rule is a rule anyone can skip.
 */
/**
 * Everything wrong with a password, in one message.
 *
 * Returning the first fault only makes the customer guess the rules one submit at a
 * time: too short, then no capital, then no number — three rejections to learn what
 * could have been said once. People give up somewhere around the third.
 */
function passwordProblem(value: string): string | undefined {
  const missing: string[] = [];
  if (value.length < 12) missing.push('at least 12 characters');
  if (!/[a-z]/.test(value)) missing.push('a small letter');
  if (!/[A-Z]/.test(value)) missing.push('a capital letter');
  if (!/\d/.test(value)) missing.push('a number');

  if (missing.length === 0) return undefined;

  const last = missing[missing.length - 1] ?? '';
  const list = missing.length === 1 ? last : `${missing.slice(0, -1).join(', ')} and ${last}`;

  return `Your password still needs ${list}.`;
}

export const authHandlers = [
  /* ------------------------------------------------ customer sign-in */

  http.post(`${API}/auth/login`, async ({ request }) => {
    const payload = await body(request);
    const customer = customerByIdentifier(str(payload, 'identifier'));
    const password = str(payload, 'password');

    // Same response for an unknown user and a wrong password.
    if (customer?.password !== password) {
      return mockApiError(401, 'UNAUTHENTICATED', GENERIC_FAILURE);
    }

    if (customer.status === 'PENDING_APPROVAL') {
      return mockApiError(
        423,
        'BUSINESS_RULE_VIOLATION',
        'Your account has been created but is still waiting for approval. We will email you as soon as it is ready.',
      );
    }

    if (customer.status === 'REJECTED') {
      return mockApiError(
        423,
        'BUSINESS_RULE_VIOLATION',
        'This application was not approved. Please contact the bank.',
      );
    }

    if (customer.status === 'SUSPENDED') {
      return mockApiError(
        423,
        'BUSINESS_RULE_VIOLATION',
        'This account is suspended. Please contact the bank.',
      );
    }

    /*
     * A temporary password does NOT get a one-time code.
     *
     * It is single-use and about to be discarded, and requiring two factors before
     * someone is even allowed into the portal teaches them the code is bureaucracy. The
     * session opens onto the change-password screen and nothing else; the second factor
     * applies from the next sign-in, once they have a password of their own.
     */
    if (customer.mustChangePassword) {
      signInCustomer(customer.id, customer.corporateId);
      const result: LoginResult = { outcome: 'PASSWORD_CHANGE_REQUIRED' };
      return HttpResponse.json(result);
    }

    const result: LoginResult = {
      outcome: 'CHALLENGE_REQUIRED',
      challenge: challengeFor(customer.id, customer.phone),
    };
    return HttpResponse.json(result);
  }),

  http.post(`${API}/auth/verify`, async ({ request }) => {
    const payload = await body(request);
    const challengeId = str(payload, 'challengeId');
    const code = str(payload, 'code');

    const userId = pendingChallenges.get(challengeId);
    if (userId === undefined) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'This sign-in attempt has expired. Please sign in again.',
      );
    }

    if (code === '000000') {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'That code is not correct. Check the code we sent you and type it again.',
      );
    }
    if (code === '111111') {
      pendingChallenges.delete(challengeId);
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'That code has expired — they only last a few minutes. Please sign in again to get a new one.',
      );
    }
    if (code.length !== 6) {
      return mockApiError(422, 'BUSINESS_RULE_VIOLATION', 'Enter the 6-digit code we sent you.');
    }

    const record = customerById(userId);
    if (record === undefined) {
      return mockApiError(401, 'UNAUTHENTICATED', GENERIC_FAILURE);
    }

    // Only now does a session exist.
    pendingChallenges.delete(challengeId);
    signInCustomer(record.id, record.corporateId);

    const result: LoginResult = { outcome: 'COMPLETE' };
    return HttpResponse.json(result);
  }),

  http.post(`${API}/auth/resend`, async ({ request }) => {
    const payload = await body(request);
    const userId = pendingChallenges.get(str(payload, 'challengeId'));
    const record = userId === undefined ? undefined : customerById(userId);

    return record === undefined
      ? mockApiError(
          422,
          'BUSINESS_RULE_VIOLATION',
          'This sign-in attempt took too long and has expired. Please start again.',
        )
      : HttpResponse.json(challengeFor(record.id, record.phone));
  }),

  /* ------------------------------------------------------ a forgotten password */

  /**
   * "I have forgotten my password."
   *
   * <p>202 AND AN EMPTY BODY, whether the address banks here or not, because that identical
   * answer is the security property and not a detail. A mock that said "no such customer"
   * would make the screen look right while teaching whoever develops against it that the
   * real endpoint has an answer to branch on.
   *
   * <p>Writes nothing for an unknown address, and sends no email for a known one: asking
   * is not an event worth telling the customer about — they just did it — and a "somebody
   * asked to reset your password" message would be mail the bank sends on a stranger's
   * command.
   */
  http.post(`${API}/auth/password/forgot`, async ({ request }) => {
    const payload = (await request.json()) as Record<string, unknown>;
    const customer = customerByIdentifier(str(payload, 'identifier'));

    if (customer !== undefined) {
      const already = PASSWORD_REQUESTS.find(
        (entry) => entry.customerId === customer.id && entry.status === 'PENDING',
      );

      /* Asking again is not an error, and must not look like one from outside. */
      if (already === undefined) {
        PASSWORD_REQUESTS.push({
          id: `pwreq-${crypto.randomUUID().slice(0, 8)}`,
          customerId: customer.id,
          requestedAt: new Date().toISOString(),
          status: 'PENDING',
        });
        savePasswordRequests();
      }
    }

    return new HttpResponse(null, { status: 202 });
  }),

  /* ------------------------------------------------ replacing a temporary password */

  /**
   * Replaces the password an admin issued.
   *
   * It asks for the temporary password again even though a session already exists. That
   * session was opened by whoever held the temporary password — which at this moment
   * might be the admin who typed it, or anyone who saw the letter. Asking again is what
   * ties the change to the person who is meant to have it.
   */
  http.post(`${API}/auth/password/change-temporary`, async ({ request }) => {
    const payload = await body(request);
    const current = str(payload, 'currentPassword');
    const next = str(payload, 'newPassword');

    const id = customerSessionId();
    const record = id === null ? undefined : customerById(id);

    if (record === undefined) {
      return mockApiError(
        401,
        'UNAUTHENTICATED',
        'You have been signed out. Please sign in again — your money is unaffected.',
      );
    }

    if (current !== record.password) {
      return validationFailure(
        'currentPassword',
        'That is not the temporary password you were given.',
      );
    }

    const problem = passwordProblem(next);
    if (problem !== undefined) return validationFailure('newPassword', problem);

    if (next === current) {
      return mockApiError(
        422,
        'BUSINESS_RULE_VIOLATION',
        'Choose a password that is different from the temporary one.',
      );
    }

    /*
     * The temporary password is OVERWRITTEN, not kept alongside the new one. It was
     * known to at least one member of staff, and leaving it usable would mean the
     * account has two passwords, one of which the customer never chose.
     */
    updateCustomer(record.id, { password: next, mustChangePassword: false });

    return new HttpResponse(null, { status: 204 });
  }),

  /* ------------------------------------------------ staff sign-in */

  http.post(`${API}/auth/staff/login`, async ({ request }) => {
    const payload = await body(request);
    const member = staffByEmail(str(payload, 'identifier'));
    const password = str(payload, 'password');

    if (member?.password !== password) {
      return mockApiError(401, 'UNAUTHENTICATED', GENERIC_FAILURE);
    }

    signInStaff(member.id);

    const index = STAFF.findIndex((entry) => entry.id === member.id);
    const existing = STAFF[index];
    if (existing !== undefined) {
      STAFF[index] = { ...existing, lastLoginAt: new Date().toISOString() };
    }

    const { password: _secret, ...safe } = member;
    const result: StaffLoginResult = { outcome: 'COMPLETE', staff: safe };
    return HttpResponse.json(result);
  }),

  http.get(`${API}/auth/staff/session`, () => {
    const member = activeStaff();
    if (member === null) {
      return mockApiError(
        401,
        'UNAUTHENTICATED',
        'You have been signed out. Please sign in again — your money is unaffected.',
      );
    }
    const { password: _secret, ...safe } = member;
    return HttpResponse.json(safe);
  }),

  /* ------------------------------------------------ sign out */

  http.post(`${API}/auth/logout`, () => {
    signOut();
    return new HttpResponse(null, { status: 204 });
  }),
];
