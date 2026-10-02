import type { BadgeTone } from './StatusBadge';

/**
 * Tones for the statuses this app renders.
 *
 * Kept in one place so the same state never appears amber on one screen and red on
 * another. Anything unmapped falls back to neutral rather than guessing: an unknown
 * status rendered in green is a lie, and rendering it in red is a false alarm.
 *
 * In its own module, apart from the component, so fast refresh stays clean.
 */
const TONES: Readonly<Record<string, BadgeTone>> = {
  /* accounts */
  ACTIVE: 'success',
  DORMANT: 'neutral',
  FROZEN: 'warning',
  CLOSED: 'neutral',

  /* transactions and money movement */
  COMPLETED: 'success',
  PROCESSING: 'info',
  PENDING_CONFIRMATION: 'warning',
  PENDING_APPROVAL: 'warning',
  FAILED: 'error',
  REJECTED: 'error',

  /*
   * cards and cheque books a customer has asked for
   *
   * FOUR STATES, which is all `service_requests.status` can hold. DRAFT, UNDER_REVIEW and
   * ADDITIONAL_INFO_REQUIRED used to be in this group and are gone: they came from a
   * speculative eight-value union written before anything implemented the feature, so no
   * row ever carried one and no screen could render it.
   *
   * DECLINED IS AN ERROR TONE, for the same reason recorded against REFUSED below — it is
   * something the customer has to act on, and grey reads as "archived".
   */
  SUBMITTED: 'info',
  READY: 'success',
  COLLECTED: 'neutral',
  DECLINED: 'error',

  /* applications, and payments a corporate approver has cleared */
  APPROVED: 'success',

  /* cards and beneficiaries */
  BLOCKED: 'error',
  EXPIRED: 'neutral',
  PENDING_COLLECTION: 'info',
  PENDING_VERIFICATION: 'warning',
  DISABLED: 'neutral',
  /*
   * A REFUSED PAYEE IS AN ERROR TONE, not a neutral one. It is a thing the customer has to
   * act on — usually by checking the number and adding it again — and a grey badge reads as
   * "archived", which is the one reading that leads nowhere.
   */
  REFUSED: 'error',

  /* batches */
  UPLOADED: 'neutral',
  VALIDATING: 'info',

  /* loans */
  IN_ARREARS: 'error',
  SETTLED: 'neutral',
};

export function toneForStatus(status: string): BadgeTone {
  return TONES[status] ?? 'neutral';
}

/** `PENDING_APPROVAL` → `Pending approval`. */
export function humaniseStatus(status: string): string {
  const words = status.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
