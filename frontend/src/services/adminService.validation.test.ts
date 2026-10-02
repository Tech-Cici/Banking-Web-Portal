import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { adminService, ApiError } from '@/services';

/**
 * A rejected create-account carries which field was wrong, all the way to the caller.
 *
 * THE REPORTED SYMPTOM. Creating an account failed with "Some of the details supplied are
 * not valid. Check the highlighted boxes above and try again." Nothing was highlighted,
 * because the screen rendered only the summary and discarded `fieldErrors` — the server
 * had named the exact row and field the whole time.
 *
 * Telling somebody to look for a highlight that is not there is worse than saying nothing:
 * they check every box, find no mark, and conclude the screen is broken rather than their
 * input. So the violations have to survive the client, and this pins that they do.
 */

const API = '*/api/v1';

describe('create-account validation', () => {
  it('keeps the per-field violations the server sent', async () => {
    server.use(
      http.post(`${API}/admin/applications/:id/create-account`, () =>
        HttpResponse.json(
          {
            timestamp: '2026-09-28T06:00:00Z',
            status: 400,
            code: 'VALIDATION_FAILED',
            message: 'Some of the details supplied are not valid.',
            fieldErrors: [
              {
                field: 'accounts[0].accountNumber',
                code: 'Pattern',
                message: 'An account number is 10 to 16 digits.',
              },
            ],
          },
          { status: 400 },
        ),
      ),
    );

    /*
     * Asserted on the ApiError rather than on a rendered string: the screen reads
     * `fieldErrors` to decide which input to mark, so if the client drops them there is
     * nothing for it to mark and the message becomes a lie again.
     */
    await expect(
      adminService.createAccount(
        'app-1',
        { accounts: [{ accountNumber: '123', accountType: 'CURRENT', currency: 'RWF', openingBalance: '50000' }] },
        'idem-1',
      ),
    ).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError);
      const api = error as ApiError;
      expect(api.fieldErrors).toHaveLength(1);
      expect(api.fieldErrors[0]?.field).toBe('accounts[0].accountNumber');
      expect(api.fieldErrors[0]?.message).toContain('10 to 16 digits');
      return true;
    });
  });

  it('names the row, so a second account can be told from the first', async () => {
    server.use(
      http.post(`${API}/admin/applications/:id/create-account`, () =>
        HttpResponse.json(
          {
            timestamp: '2026-09-28T06:00:00Z',
            status: 400,
            code: 'VALIDATION_FAILED',
            message: 'Some of the details supplied are not valid.',
            fieldErrors: [
              {
                field: 'accounts[1].accountNumber',
                code: 'NotBlank',
                message: 'Enter the account number.',
              },
            ],
          },
          { status: 400 },
        ),
      ),
    );

    /*
     * An administrator entering three accounts needs to know WHICH one is wrong. A
     * summary that says "some details" sends them checking all three.
     */
    await expect(
      adminService.createAccount(
        'app-2',
        {
          accounts: [
            { accountNumber: '4001111111111', accountType: 'CURRENT', currency: 'RWF', openingBalance: '0' },
            { accountNumber: '', accountType: 'SAVINGS', currency: 'RWF', openingBalance: '50000' },
          ],
        },
        'idem-2',
      ),
    ).rejects.toSatisfy((error: unknown) => {
      expect((error as ApiError).fieldErrors[0]?.field).toBe('accounts[1].accountNumber');
      return true;
    });
  });
});
