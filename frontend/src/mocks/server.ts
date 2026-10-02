import { setupServer } from 'msw/node';
import { handlers } from './handlers';

/**
 * Node-side mock server for Vitest.
 *
 * Not yet wired into `src/test/setup.ts`: Phase 1 has no test that performs a request.
 * The phase that adds the first service test starts it there with the standard
 * beforeAll/afterEach/afterAll lifecycle, so handler overrides cannot leak between tests.
 */
export const server = setupServer(...handlers);
