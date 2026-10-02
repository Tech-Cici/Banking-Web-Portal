import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from '@/mocks/server';

/**
 * Test setup.
 *
 * The MSW server runs for every test so components exercise the real API client, real
 * error normalisation and real loading states against mocked responses — rather than
 * module-level stubs, which would prove nothing about the code that actually ships.
 *
 * `onUnhandledRequest: 'error'` is deliberate: a request nobody mocked is a test quietly
 * hitting the network, and it should fail loudly.
 */
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

// Reset between tests so a per-test handler override cannot leak into the next one.
//
// `cleanup` is called explicitly because Testing Library only self-registers it when
// Vitest runs with `globals: true`. This project keeps globals off, so without this the
// DOM accumulates across tests in a file and every query finds duplicates.
afterEach(() => {
  cleanup();
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});
