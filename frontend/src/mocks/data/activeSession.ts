import { appConfig } from '@/config/env';
import type { SessionUser } from '@/types/banking';
import { corporateById, membershipsFor } from './personas';
import {
  customerById,
  sessionUserFor,
  staffById,
  type CustomerRecord,
  type StaffRecord,
} from './onboarding';
import { customerSessionId, getActiveCorporateId, staffSessionId } from './sessionStore';

/**
 * Who the current request belongs to.
 *
 * This is the one place the mock resolves a session cookie into a user, so the customer
 * handlers and the staff handlers cannot drift into disagreeing about who is signed in.
 *
 * The shape returned for a customer is `{ user }` on purpose: it is what the handlers
 * were already written against, so replacing the seeded personas with real records did
 * not mean rewriting every endpoint.
 */

export interface ActiveCustomer {
  readonly user: SessionUser;
  readonly record: CustomerRecord;
}

/** Whether sign-in and the session are answered by the real backend. */
function sessionIsLive(): boolean {
  return appConfig.liveApiPaths.some((prefix) => prefix === '/auth' || prefix === '/session');
}

/**
 * Who the mock scopes to when the session belongs to the real backend.
 *
 * The problem this solves is specific and was a real fault. With `/auth` and `/session`
 * live, the mock's own session cookie is never written, so this module found nobody — and
 * the handlers that call `unauthenticated()` then returned 401 to a customer who was
 * genuinely signed in. The client treats a 401 as "you have been signed out", so three
 * mocked widgets tore down a real session and bounced the customer back to the sign-in
 * page a second after they had arrived.
 *
 * A mock must never answer an authentication question it is not being asked. When the
 * server owns the session, the honest mock answer for data it does not have is "nothing",
 * not "you are not signed in".
 *
 * So this stands in purely for SCOPING, and it deliberately matches no provisioned
 * fixtures: every `...For(id)` lookup returns an empty list, so the portal shows a real
 * session with empty accounts, cards and loans. That is the truth — the backend does not
 * implement those endpoints yet. Inventing balances to fill the screen would be the one
 * unforgivable version of this, because a fabricated figure in a bank is indistinguishable
 * from a real one until somebody acts on it.
 */
function standIn(): ActiveCustomer {
  const record: CustomerRecord = {
    id: 'live-session',
    fullName: 'Signed in against the live API',
    email: '',
    phone: '',
    customerNumber: '',
    userType: 'RETAIL',
    status: 'ACTIVE',
    mustChangePassword: false,
    createdAt: new Date(0).toISOString(),
    createdByName: '',
    applicationId: '',
    // No masked account numbers, because there are no accounts. See above.
    accountMasks: [],
    // No accounts either: this stand-in matches no provisioned fixtures.
    accounts: [],
    password: '',
    /*
     * approvedAt, approvedByName and rejectionReason are omitted rather than set to
     * undefined: exactOptionalPropertyTypes makes "present and undefined" a different
     * type from "absent", and absent is what an optional field actually means here.
     */
    /*
     * Mirrors what the real /session grants a personal customer. These gate only the
     * MOCKED handlers' own checks; what the app shows comes from the live session, so
     * withholding them here would hide screens the server has actually authorised.
     */
    permissions: [
      'RETAIL_ACCOUNT_VIEW',
      'TRANSFER_CREATE',
      'PAYMENT_CREATE',
      'BENEFICIARY_MANAGE',
      'LOAN_VIEW',
      'CARD_MANAGE',
    ],
    corporateId: undefined,
  };

  return { record, user: sessionUserFor(record, []) };
}

/**
 * The signed-in customer, or null.
 *
 * Returns null for an account that is not ACTIVE. An account created but not yet
 * approved has no business reading balances, and the check belongs here rather than in
 * each endpoint — one endpoint that forgets it is a customer using an unapproved login.
 */
export function activeCustomer(userId?: string): ActiveCustomer | null {
  const id = userId ?? customerSessionId();
  if (id === null) return sessionIsLive() ? standIn() : null;

  const record = customerById(id);
  if (record?.status !== 'ACTIVE') return null;

  /*
   * A session still on the temporary password reads NOTHING.
   *
   * That password was typed by a member of bank staff and written on a slip of paper,
   * so until it is replaced the session is not the customer's — it is whoever holds the
   * slip. The screens redirect, but a redirect is a courtesy: this is the part that
   * makes typing /dashboard useless.
   */
  if (record.mustChangePassword) return null;

  const company = record.corporateId === undefined ? undefined : corporateById(record.corporateId);
  const role = company === undefined ? 'VIEWER' : 'ADMIN';

  return {
    record,
    user: sessionUserFor(record, membershipsFor(record.corporateId, role)),
  };
}

/** The signed-in staff member, or null. */
export function activeStaff(): StaffRecord | null {
  const id = staffSessionId();
  if (id === null) return null;
  return staffById(id) ?? null;
}

/** The company the customer is currently acting for, if any. */
export function activeCorporate(): string | undefined {
  return getActiveCorporateId();
}
