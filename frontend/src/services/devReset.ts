import { apiClient } from './apiClient';

/**
 * Empties the bank so the onboarding flow can be walked from nothing.
 *
 * <p>DEVELOPMENT ONLY. There is no production counterpart and there must never be one — an
 * endpoint that deletes every customer is not something to leave reachable.
 *
 * <p>IT LIVES IN ITS OWN MODULE, reached only through a dynamic {@code import()} from the
 * Developer tools page — and that page's ROUTE is registered only when
 * {@code VITE_ENABLE_DEV_TOOLS} is true, which is what actually keeps this file out of a
 * build. The dynamic import alone does not: it was written that way first, with the route
 * registered unconditionally and only the navigation link hidden, and a production build
 * duly emitted {@code devReset-*.js} carrying the literal {@code /admin/dev/reset} path.
 * That was found by building the bundle and reading the chunk, not by reasoning about it.
 *
 * <p>The module boundary still earns its place: as a property of {@code adminService} this
 * survived tree-shaking even behind a guard, which is what had already happened to
 * {@code /dev/persona}. Both things are needed — a separate module, and a route that does
 * not exist.
 *
 * <p>THE SERVER IS THE CONTROL, not this file and not the hidden link that reaches it. The
 * endpoint requires staff credentials and refuses outside the {@code dev}, {@code h2} and
 * {@code test} profiles, so no arrangement of the client can point it at production.
 *
 * <p>There is a terminal equivalent, {@code npm run reset-bank}, for use without a browser.
 */
export async function resetMockBank(signal?: AbortSignal): Promise<void> {
  await apiClient.post<undefined>(
    '/admin/dev/reset',
    signal === undefined ? {} : { signal },
  );
}
