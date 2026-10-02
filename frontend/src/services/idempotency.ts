/**
 * Idempotency keys for financial submissions.
 *
 * The rule the blueprint states twice (sections 3 and 44): one key per financial
 * submission, and the SAME key preserved while checking that submission's status. A new key
 * on a retry is a second transaction — which is how a customer gets debited twice.
 *
 * Keys live in memory only. They are intentionally not persisted: a key that outlives the
 * page is a key that can resurrect a transaction the customer believes they abandoned.
 */

const IDEMPOTENCY_HEADER = 'Idempotency-Key';

export { IDEMPOTENCY_HEADER };

/** Generates a key for one financial submission. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/**
 * Holds the key for a single in-progress submission.
 *
 * Create one when the customer reaches the review step, reuse it for the submit and for
 * every subsequent status check, and discard it only once the operation reaches a terminal
 * state or the customer starts a genuinely new transaction.
 */
export class IdempotencyScope {
  #key: string | undefined;

  /** The current key, creating one on first use. */
  get key(): string {
    this.#key ??= newIdempotencyKey();
    return this.#key;
  }

  /** True once a key has been issued. */
  get hasKey(): boolean {
    return this.#key !== undefined;
  }

  /**
   * Abandons the current key so the next submission is a new transaction.
   *
   * Call this only when starting a genuinely different operation — for example "Repeat
   * transfer", which the blueprint (section 10.5) requires to create a NEW transaction
   * rather than replay the old one. Never call it to retry a submission whose outcome is
   * unknown.
   */
  reset(): void {
    this.#key = undefined;
  }
}
