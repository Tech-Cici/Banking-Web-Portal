/**
 * Persistence for the mock store.
 *
 * The mock layer stands in for a database, and until now it stood in for one with the
 * memory of a goldfish: every hot reload emptied the onboarding queue, so a
 * registration made a minute ago was gone before an admin could act on it. That makes
 * the register → create → approve → sign-in chain impossible to walk, which is the one
 * flow that most needs walking.
 *
 * So the store is written to `localStorage`. Two consequences worth knowing:
 *
 *  1. It survives a reload, and it is shared between tabs on the same origin — so the
 *     customer portal in one tab and the staff portal in another see the same data,
 *     which is how the flow is actually tested.
 *  2. It does NOT cross browsers or profiles. Two separate browsers are two separate
 *     banks.
 *
 * WEB STORAGE IS BANNED EVERYWHERE ELSE IN THIS APP, by a lint rule, because
 * application code must not persist tokens or sensitive drafts (blueprint section 24).
 * The exemption is scoped to `src/mocks/` and none of this reaches a production build.
 *
 * Every read and write is wrapped: storage throws in a private window, can be disabled
 * by policy, and can come back with content from an older shape of the code. A mock
 * that crashes the app because a browser setting changed is worse than a forgetful one.
 */

const PREFIX = 'ib_mock_';

/** Bumped when a stored shape changes, so stale data is dropped rather than misread. */
const VERSION = 1;

interface Envelope<T> {
  readonly version: number;
  readonly value: T;
}

export function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(`${PREFIX}${key}`);
    if (raw === null) return fallback;

    const parsed = JSON.parse(raw) as Envelope<T>;
    // A different version is not an error, it is last week's data. Discard it.
    if (parsed.version !== VERSION) return fallback;

    return parsed.value;
  } catch {
    return fallback;
  }
}

export function save(key: string, value: unknown): void {
  try {
    const envelope: Envelope<unknown> = { version: VERSION, value };
    localStorage.setItem(`${PREFIX}${key}`, JSON.stringify(envelope));
  } catch {
    // Full, disabled, or a private window. The store keeps working in memory.
  }
}

/**
 * An array that writes itself back whenever it changes.
 *
 * Returns the loaded array plus a `commit` to call after mutating it. Deliberately not
 * a Proxy: the handlers push and splice these arrays in a dozen places, and a magic
 * array that persists invisibly is harder to reason about than one explicit call.
 */
export function persistedArray<T>(key: string): PersistedArray<T> {
  const items = load<T[]>(key, []);
  return {
    items,
    commit: () => {
      save(key, items);
    },
  };
}

export interface PersistedArray<T> {
  readonly items: T[];
  readonly commit: () => void;
}

/** Clears the whole mock store. Exposed for the reset control in development. */
export function clearAll(): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (key?.startsWith(PREFIX) === true) keys.push(key);
    }
    for (const key of keys) localStorage.removeItem(key);
  } catch {
    // Nothing to clear if storage is unavailable.
  }
}
