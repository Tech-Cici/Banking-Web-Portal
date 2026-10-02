/**
 * Date and time formatting for display.
 *
 * Timestamps cross the wire as ISO 8601 in UTC and are rendered in the reader's own time
 * zone. A banking timestamp shown in the wrong zone is not a cosmetic bug: "did this
 * payment leave before the cut-off?" is answered by the clock on the customer's wall.
 *
 * Every function is defensive about unparseable input. A malformed date should degrade to
 * a dash, not throw an exception that takes a dashboard panel down with it.
 */

const LOCALE = 'en-GB';

const DATE: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' };

const DATE_TIME: Intl.DateTimeFormatOptions = { ...DATE, hour: '2-digit', minute: '2-digit' };

const PLACEHOLDER = '—';

function parse(iso: string): Date | null {
  const value = new Date(iso);
  return Number.isNaN(value.getTime()) ? null : value;
}

/** e.g. `5 Oct 2026`. */
export function formatDate(iso: string): string {
  const value = parse(iso);
  return value === null ? PLACEHOLDER : new Intl.DateTimeFormat(LOCALE, DATE).format(value);
}

/** e.g. `22 Sep 2026, 18:42`. */
export function formatDateTime(iso: string): string {
  const value = parse(iso);
  return value === null ? PLACEHOLDER : new Intl.DateTimeFormat(LOCALE, DATE_TIME).format(value);
}

/**
 * Coarse relative age, e.g. `2 days ago`.
 *
 * Coarse on purpose. "14 minutes ago" on a balance invites the reader to treat it as
 * live, and these figures are snapshots from the core banking system.
 */
export function formatAge(iso: string, now: Date = new Date()): string {
  const value = parse(iso);
  if (value === null) return PLACEHOLDER;

  const minutes = Math.round((now.getTime() - value.getTime()) / 60000);

  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${String(minutes)} min ago`;

  const hours = Math.round(minutes / 60);
  if (hours < 24) return hours === 1 ? '1 hour ago' : `${String(hours)} hours ago`;

  const days = Math.round(hours / 24);
  if (days < 30) return days === 1 ? 'yesterday' : `${String(days)} days ago`;

  return formatDate(iso);
}

/**
 * Whole days from today until a date, negative once it has passed.
 *
 * Used for "instalment due in 12 days". Both ends are floored to midnight so the answer
 * does not flip because of the hour of day.
 */
export function daysUntil(iso: string, now: Date = new Date()): number | null {
  const value = parse(iso);
  if (value === null) return null;

  const target = Date.UTC(value.getFullYear(), value.getMonth(), value.getDate());
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());

  return Math.round((target - today) / 86400000);
}
