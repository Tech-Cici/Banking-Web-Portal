import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/app/App';
import { appConfig } from '@/config/env';
import '@/styles/global.css';

/**
 * Entry point.
 *
 * Starts the mock API first when enabled, and awaits it, so the first request cannot race
 * the worker's registration and hit the network instead.
 */
async function startMockApi(): Promise<void> {
  // `import.meta.env.DEV` is replaced with a literal at build time, so this guard lets the
  // bundler eliminate the dynamic import below entirely. Without it MSW ships as a ~420 kB
  // chunk in the production bundle — never executed, but deployed, and a mock request
  // interceptor is not something to leave lying around in a banking client.
  if (!import.meta.env.DEV) return;
  if (!appConfig.enableMockApi) return;

  const { worker } = await import('@/mocks/browser');
  await worker.start({
    // An unhandled request should be obvious, not silently proxied to a real backend.
    onUnhandledRequest: 'warn',
  });

  /*
   * AND THEN CHECK THAT IT IS ACTUALLY ANSWERING, because `start()` resolving does not
   * mean it is. A service worker can be registered, controlling the page and bypassed all
   * at once: Chrome bypasses it for a hard reload, and DevTools has a "Bypass for network"
   * switch that does the same until it is turned off. Every mocked area then answers 404
   * from the real backend, and the customer-facing panels say "We could not load loans —
   * the requested endpoint does not exist", which sends whoever sees it looking for a bug
   * in the portal.
   *
   * NOT AWAITED. The check costs a round trip and the app has no reason to wait for it; if
   * it fails, a banner arrives a moment after the page does. See assertIntercepting.
   */
  const { assertIntercepting } = await import('@/mocks/assertIntercepting');
  void assertIntercepting();
}

function mount(): void {
  const container = document.getElementById('root');

  if (container === null) {
    throw new Error('Root element #root is missing from index.html.');
  }

  createRoot(container).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

startMockApi()
  .then(mount)
  .catch((cause: unknown) => {
    // Nothing is mounted at this point, so render a plain, non-React message. This is the
    // one place a failure cannot be shown through the UI.
    const container = document.getElementById('root');
    if (container !== null) {
      container.textContent =
        'Zigama CSS Internet Banking could not start. Please reload the page or contact support.';
    }
    throw cause;
  });
