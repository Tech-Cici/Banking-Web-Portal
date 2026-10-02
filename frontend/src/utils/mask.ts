/**
 * Masking for identifiers shown in ordinary views.
 *
 * The blueprint requires masking of account, card and other sensitive identifiers
 * (sections 1 and 3) and forbids logging full PANs or account numbers (section 24).
 *
 * The server is the authority: where it returns an already-masked value, display that
 * rather than re-masking. These helpers exist for values the client legitimately holds in
 * full, such as a number the customer has just typed and is about to confirm.
 */

const DEFAULT_VISIBLE = 4;

function visibleTail(value: string, visible: number): string {
  return value.slice(-visible);
}

/**
 * Masks an account number to the blueprint's `**** 4582` form.
 *
 * A value too short to mask meaningfully is fully masked rather than partly revealed.
 */
export function maskAccountNumber(accountNumber: string, visible = DEFAULT_VISIBLE): string {
  const digits = accountNumber.replace(/\s+/g, '');

  if (digits.length <= visible) {
    return '*'.repeat(Math.max(digits.length, visible));
  }

  return `**** ${visibleTail(digits, visible)}`;
}

/**
 * Masks a card PAN, keeping the last four digits.
 *
 * The first six (the BIN) are deliberately NOT shown: combined with the last four they
 * narrow a card considerably, and no screen in this portal needs them.
 */
export function maskPan(pan: string): string {
  const digits = pan.replace(/[\s-]/g, '');

  if (digits.length <= DEFAULT_VISIBLE) {
    return '*'.repeat(12) + digits;
  }

  return `**** **** **** ${visibleTail(digits, DEFAULT_VISIBLE)}`;
}

/**
 * Masks a phone number, keeping the last three digits.
 *
 * Matches the delivery hint the OTP screen shows (`SMS to ***123`, blueprint section 5.2).
 */
export function maskPhoneNumber(phone: string): string {
  const digits = phone.replace(/[\s()-]/g, '');

  if (digits.length <= 3) return '*'.repeat(3);

  return `***${visibleTail(digits, 3)}`;
}

/**
 * Masks an email address, keeping the first character and the domain.
 *
 * `nicaise@example.com` becomes `n*****@example.com`.
 */
export function maskEmail(email: string): string {
  const atIndex = email.lastIndexOf('@');
  if (atIndex <= 0) return '*'.repeat(email.length);

  const local = email.slice(0, atIndex);
  const domain = email.slice(atIndex);
  const first = local[0] ?? '';

  return `${first}${'*'.repeat(Math.max(local.length - 1, 1))}${domain}`;
}

/** Masks a customer/taxpayer identifier, keeping the last four characters. */
export function maskIdentifier(identifier: string, visible = DEFAULT_VISIBLE): string {
  const trimmed = identifier.trim();
  if (trimmed.length <= visible) return '*'.repeat(Math.max(trimmed.length, visible));

  return `${'*'.repeat(trimmed.length - visible)}${visibleTail(trimmed, visible)}`;
}
