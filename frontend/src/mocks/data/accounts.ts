import type { Account } from '@/types/banking';
import { accountsForCustomer, PROVISIONED_ACCOUNTS } from './onboarding';

/**
 * Accounts.
 *
 * THERE ARE NO SEEDED ACCOUNTS. An account exists because an admin opened one for an
 * approved applicant, and nowhere else — the same as at a real bank. A new account has
 * whatever the branch deposited (usually nothing) and no history, and the portal should
 * show precisely that rather than a demo balance.
 */

export const ACCOUNTS: readonly Account[] = PROVISIONED_ACCOUNTS;

/** Accounts a session can see: the customer's own, or the selected company's. */
export function accountsFor(userId: string, corporateId: string | undefined): readonly Account[] {
  if (corporateId !== undefined) {
    return PROVISIONED_ACCOUNTS.filter((account) => account.corporateId === corporateId);
  }
  return accountsForCustomer(userId);
}

export function accountById(id: string): Account | undefined {
  return PROVISIONED_ACCOUNTS.find((account) => account.id === id);
}
