import type { DownloadedFile } from '@/services';

/**
 * Hands a file the server sent to the browser's download machinery.
 *
 * WHY AN ANCHOR AND NOT A WINDOW. Opening the URL in a tab asks the browser to display the
 * file, and a browser that cannot display a PDF leaves the customer on a blank tab wondering
 * what happened. An anchor with `download` always saves, and the name the server chose comes
 * with it.
 *
 * WHY THE URL IS REVOKED. An object URL pins its blob in memory until it is released or the
 * page unloads, so a customer taking a dozen statements in one session would hold a dozen
 * documents they have already saved. Revoking is deferred rather than immediate because
 * Safari has historically read the URL after the click returns.
 */
export function saveFile(file: DownloadedFile): void {
  const url = URL.createObjectURL(file.blob);

  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = file.filename;
  anchor.rel = 'noopener';

  /*
   * APPENDED BEFORE THE CLICK. A detached anchor is ignored by Firefox, which is the kind of
   * difference that works in every browser the developer tried.
   */
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();

  setTimeout(() => {
    URL.revokeObjectURL(url);
  }, 10_000);
}
