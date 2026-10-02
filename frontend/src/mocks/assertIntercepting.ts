import { appConfig } from '@/config/env';
import { MOCK_PROBE_PATH } from './handlers';

/**
 * CHECKS THAT THE MOCK WORKER IS ACTUALLY ANSWERING, and says so loudly when it is not.
 *
 * <p>THE BUG THIS EXISTS FOR. MSW intercepts through a service worker, and a service worker
 * can be bypassed while still being registered. Chrome bypasses it for a hard reload
 * (Cmd+Shift+R), and DevTools has an Application → Service Workers → "Bypass for network"
 * checkbox that does the same for as long as it is ticked. `worker.start()` resolves
 * normally, `navigator.serviceWorker.controller` is not null, and everything looks fine —
 * but every request goes straight to the network.
 *
 * <p>WHAT THAT LOOKED LIKE. The areas still mocked — notifications, loans, cards — reached
 * the real Spring service, which does not implement them, and answered 404. The dashboard
 * then showed three customer-facing errors: "We could not load loans. The requested
 * endpoint does not exist." Which panels failed depended on which page was open, so it
 * read as intermittent, and a normal reload cleared it, so it read as a fluke. It is
 * neither: it is a browser setting, and nothing in the app was wrong.
 *
 * <p>WHY A PROBE AND NOT A PROPERTY. There is no flag to read. The worker is registered,
 * the page is controlled, and `start()` has resolved; the only way to know whether the
 * worker is answering is to ask it something only it can answer.
 *
 * <p>DEV ONLY, AND IT NEVER BLOCKS START-UP. Everything here is behind
 * `import.meta.env.DEV`, which the bundler folds away, and a failed probe only prints and
 * draws a banner — the portal still runs, because a developer who is deliberately pointing
 * at a real backend must not be stopped by a mock's self-check.
 */

/** How long to wait before deciding the probe is not coming back. */
const PROBE_TIMEOUT_MS = 4000;

const BANNER_ID = 'mock-not-intercepting';

/**
 * Asks the worker a question only the worker can answer.
 *
 * @returns true when the mock answered, false when something else did (or nothing).
 */
async function probe(): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, PROBE_TIMEOUT_MS);

  try {
    /*
     * Through the SAME base URL the app uses, so the probe travels the same path as a real
     * request. Asking a relative path instead would prove nothing: the app's requests are
     * absolute, cross-origin calls to the API host, and it is those that have to be
     * intercepted.
     */
    const response = await fetch(`${appConfig.apiBaseUrl}${MOCK_PROBE_PATH}`, {
      signal: controller.signal,
      /* No cookies: this is not an authenticated call and must work before sign-in. */
      credentials: 'omit',
    });

    if (!response.ok) return false;

    const body: unknown = await response.json();
    return (
      typeof body === 'object' &&
      body !== null &&
      (body as { intercepting?: unknown }).intercepting === true
    );
  } catch {
    /*
     * A network failure, a CORS rejection or the timeout. All of them mean the same thing
     * here — the mock did not answer — and none of them is worth distinguishing, because
     * the remedy is identical.
     */
    return false;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * The banner. Plain DOM rather than a component, because this has to be able to appear
 * before React mounts and must not depend on anything the app renders.
 */
function warnOnScreen(): void {
  if (document.getElementById(BANNER_ID) !== null) return;

  const banner = document.createElement('div');
  banner.id = BANNER_ID;
  banner.setAttribute(
    'style',
    [
      'position:fixed',
      'inset:0 0 auto 0',
      'z-index:2147483647',
      'padding:12px 16px',
      'font:14px/1.45 system-ui,sans-serif',
      /*
       * Deliberately NOT the portal's palette. This is a message to a developer about
       * their browser, and it must not be mistakable for something a customer is meant to
       * read — so it looks like a tool, not like the bank.
       */
      'color:#111',
      'background:#ffd84d',
      'border-bottom:3px solid #8a6a00',
      'box-shadow:0 2px 8px rgb(0 0 0 / 25%)',
    ].join(';'),
  );

  banner.innerHTML = `
    <strong>The mock API is not intercepting requests.</strong>
    Every mocked area will fail with “The requested endpoint does not exist”, because those
    requests are reaching the real backend, which has not implemented them.
    <br />
    This is almost always a <strong>hard reload</strong> (⌘⇧R), which makes Chrome bypass the
    service worker for that page load, or DevTools →
    Application → Service Workers → <strong>Bypass for network</strong> left ticked.
    <strong>Reload the page normally</strong> (⌘R) and it will work again.
    <br />
    <small>Development only — this banner cannot appear in a production build.</small>
  `;

  const dismiss = document.createElement('button');
  dismiss.textContent = 'Dismiss';
  dismiss.setAttribute(
    'style',
    'margin-left:12px;padding:4px 10px;font:inherit;cursor:pointer;border:1px solid #8a6a00;border-radius:999px;background:#fff',
  );
  dismiss.addEventListener('click', () => {
    banner.remove();
  });
  banner.append(dismiss);

  document.body.prepend(banner);
}

/**
 * Runs the check. Resolves either way; never throws.
 *
 * <p>Called from `main.tsx` after `worker.start()` and deliberately NOT awaited, so the
 * app mounts at once and the banner arrives a moment later if it is needed.
 */
export async function assertIntercepting(): Promise<void> {
  if (!import.meta.env.DEV) return;

  if (await probe()) return;

  /*
   * The only `console` call in the application besides the one on the sign-in screen, and
   * the same justification: the person who needs this is looking at a terminal or a
   * DevTools pane, and it carries no customer data — just a fixed string about the
   * browser's own configuration. It is behind the DEV guard above.
   */
  // eslint-disable-next-line no-console
  console.error(
    '[mocks] The mock service worker is registered but NOT intercepting requests.\n' +
      '        Mocked areas will answer 404 from the real backend.\n' +
      '        Cause: a hard reload (Cmd+Shift+R) bypasses the service worker, as does\n' +
      '        DevTools > Application > Service Workers > "Bypass for network".\n' +
      '        Fix: reload the page normally (Cmd+R).',
  );

  /*
   * `document.body` is typed as always present, and by the time this runs it is: the probe
   * has already awaited a round trip. The earlier guard here was dead code the linter was
   * right to reject.
   */
  warnOnScreen();
}
