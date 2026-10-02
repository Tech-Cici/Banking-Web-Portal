import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { adminService } from './adminService';

/**
 * Every array the admin API returns is an array by the time a screen sees it.
 *
 * THIS IS THE THIRD TIME, which is the whole reason the test is shaped like this.
 *
 * The backend runs `default-property-inclusion: non_null`, so a null field is omitted
 * rather than sent as null; an older or half-deployed server omits fields it has never
 * heard of. Either way a property typed `readonly T[]` arrives as `undefined`, and
 * TypeScript cannot help — the type is a promise about the server, not a fact about the
 * response body.
 *
 *   `application.details.map(...)`      → white screen on the registration detail page
 *   `customer.accountMasks.join(...)`   → white screen after "create account"
 *   `created.customer.accounts.length`  → white screen after "create account", again
 *
 * Each was fixed where it crashed, so the next field to be added brought the next crash.
 * These tests serve the response WITH THE FIELD ABSENT — the exact thing a stale backend
 * sends — and assert the client copes, rather than asserting on a well-formed fixture
 * that could never reproduce the fault.
 *
 * A NEW ARRAY FIELD ON AN ADMIN RESPONSE NEEDS A CASE HERE and a line in
 * `normalise*` in types/admin.ts. If you are reading this because something crashed on
 * `.length` again, that is the step that was missed.
 */

const API = '*/api/v1';

/** A customer as the server sends one, minus every optional and array field. */
const BARE_CUSTOMER = {
  id: 'cus-1',
  applicationId: 'app-1',
  fullName: 'Ciara Teta',
  email: 'ciara@example.rw',
  phone: '0781000000',
  customerNumber: 'ZG3100001',
  userType: 'RETAIL',
  status: 'PENDING_APPROVAL',
  createdAt: '2026-09-25T10:00:00Z',
  createdByName: 'Admin',
  mustChangePassword: true,
  // accountMasks DELIBERATELY ABSENT.
};

describe('admin responses survive a server that omits arrays', () => {
  it('createAccount: requestedAccounts absent becomes an empty array', async () => {
    /*
     * This is precisely what the currently-deployed jar returns: it predates the field,
     * so the JSON has no `requestedAccounts` at all. The screen then read `.length` on
     * undefined and took the whole portal down.
     */
    server.use(
      http.post(`${API}/admin/applications/:id/create-account`, () =>
        HttpResponse.json({ customer: BARE_CUSTOMER }, { status: 201 }),
      ),
    );

    const created = await adminService.createAccount(
      'app-1',
      { accounts: [{ accountNumber: '4001111111111', accountType: 'CURRENT', currency: 'RWF', openingBalance: '50000' }] },
      'idem-1',
    );

    expect(created.customer.accounts).toEqual([]);
    expect(created.customer.accounts.length).toBe(0);
    expect(created.customer.accountMasks).toEqual([]);
    /*
     * The nested customer carries its own array field now, so it needs the same
     * defence — a normaliser that only reaches the top level is the same bug one
     * level down.
     */
    expect(created.customer.accounts).toEqual([]);
  });

  it('customers: accountMasks absent on every row becomes an empty array', async () => {
    server.use(
      http.get(`${API}/admin/customers`, () =>
        HttpResponse.json([BARE_CUSTOMER, { ...BARE_CUSTOMER, id: 'cus-2' }]),
      ),
    );

    const customers = await adminService.customers();

    expect(customers).toHaveLength(2);
    for (const customer of customers) {
      expect(Array.isArray(customer.accountMasks)).toBe(true);
      expect(Array.isArray(customer.accounts)).toBe(true);
    }
  });

  it('approve, reject, freeze and unfreeze all normalise the customer they return', async () => {
    /*
     * Enumerated rather than spot-checked. Four endpoints return the same shape, and
     * normalising three of them is the version of this bug that survives a review.
     */
    server.use(
      http.post(`${API}/admin/customers/:id/:action`, () => HttpResponse.json(BARE_CUSTOMER)),
    );

    const results = await Promise.all([
      adminService.approve('cus-1', 'k1'),
      adminService.reject('cus-1', 'no', 'k2'),
      adminService.freeze('cus-1', 'suspected compromise', 'k3'),
      adminService.unfreeze('cus-1', 'resolved', 'k4'),
    ]);

    for (const customer of results) {
      expect(Array.isArray(customer.accountMasks)).toBe(true);
      expect(Array.isArray(customer.accounts)).toBe(true);
    }
  });

  it('application: details absent becomes an empty array', async () => {
    server.use(
      http.get(`${API}/admin/applications/:id`, () =>
        HttpResponse.json({
          id: 'app-1',
          reference: 'REG-1',
          kind: 'PERSONAL',
          status: 'SUBMITTED',
          displayName: 'Ciara Teta',
          email: 'ciara@example.rw',
          phone: '0781000000',
          submittedAt: '2026-09-25T10:00:00Z',
          // details DELIBERATELY ABSENT.
        }),
      ),
    );

    const application = await adminService.application('app-1');

    expect(application.details).toEqual([]);
  });

  it('a null array, not just a missing one, is also tolerated', async () => {
    /*
     * `non_null` should mean null never arrives. "Should" is doing a lot of work in a
     * sentence about a server somebody may reconfigure, and the cost of covering it is
     * one `Array.isArray` check.
     */
    server.use(
      http.post(`${API}/admin/applications/:id/create-account`, () =>
        HttpResponse.json(
          { customer: { ...BARE_CUSTOMER, accountMasks: null }, accounts: null },
          { status: 201 },
        ),
      ),
    );

    const created = await adminService.createAccount(
      'app-1',
      { accounts: [{ accountNumber: '4001111111111', accountType: 'CURRENT', currency: 'RWF', openingBalance: '0' }] },
      'idem-2',
    );

    expect(created.customer.accounts).toEqual([]);
    expect(created.customer.accountMasks).toEqual([]);
  });
});
