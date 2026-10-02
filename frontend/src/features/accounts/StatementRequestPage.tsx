import { useState, type ReactElement } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Alert, Button, PageHeader, Select, Skeleton, TextField } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { RETAIL_PATHS, routeTo } from '@/routes/paths';
import { accountService, ApiError, statementService } from '@/services';
import type { Account } from '@/types/banking';
import './accounts.css';

/**
 * Today, in the BROWSER'S timezone, as `YYYY-MM-DD`.
 *
 * <p>NOT `toISOString().slice(0, 10)`, which is what this used and which is wrong by a
 * day for part of every day in Kigali. `toISOString` converts to UTC first, so at 00:30
 * on the 29th in Rwanda (UTC+2) it answers the 28th — and `max` on the date inputs then
 * refuses today, while the default end date silently backs up to yesterday. Two hours of
 * every night, on a field a customer would have no way to argue with.
 *
 * `en-CA` is the shortest honest way to get ISO order out of `Intl`; the alternative is
 * three `padStart` calls on the local date parts.
 */
function localToday(at: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(at);
}

/** `YYYY-MM-DD`, some number of days before the given day. */
function daysBefore(days: number, from: Date = new Date()): string {
  const shifted = new Date(from);
  shifted.setDate(shifted.getDate() - days);
  return localToday(shifted);
}

/** The first of January of the given year, as `YYYY-MM-DD`. */
function startOfYear(year: number): string {
  return `${String(year)}-01-01`;
}

/**
 * How the chosen range reads to a person, spelled out.
 *
 * <p>THE REASON THIS EXISTS: `<input type="date">` renders in the BROWSER's locale, not
 * the bank's. On a machine set to US English it shows `08/29/2026`, and for a Rwandan
 * bank "08/29" against "29/08" is not a cosmetic difference — one of them is a date in
 * August and the other does not exist. The native picker is still the right control
 * (it is keyboard-accessible and it is what the platform gives), so the fix is to say
 * the range back unambiguously rather than to rebuild it.
 */
function describeRange(from: string, to: string): string | undefined {
  const start = Date.parse(from);
  const end = Date.parse(to);
  if (Number.isNaN(start) || Number.isNaN(end) || start > end) return undefined;

  const long = new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  // Inclusive: a from and to of the same day is one day, not zero.
  const days = Math.round((end - start) / 86_400_000) + 1;

  return `${long.format(start)} to ${long.format(end)} · ${String(days)} ${days === 1 ? 'day' : 'days'}`;
}

interface Preset {
  readonly label: string;
  readonly from: () => string;
  readonly to: () => string;
}

/**
 * The ranges people actually ask for.
 *
 * <p>Three taps of a date picker to get last month's statement is three chances to pick
 * the wrong year, and the request that comes back is then empty for a reason nobody can
 * see. These are the shapes a statement request takes in practice; the pickers stay
 * below for everything else.
 */
const PRESETS: readonly Preset[] = [
  { label: 'Last 30 days', from: () => daysBefore(29), to: () => localToday() },
  { label: 'Last 3 months', from: () => daysBefore(89), to: () => localToday() },
  {
    label: 'This year',
    from: () => startOfYear(new Date().getFullYear()),
    to: () => localToday(),
  },
  {
    label: 'Last year',
    from: () => startOfYear(new Date().getFullYear() - 1),
    to: () => `${String(new Date().getFullYear() - 1)}-12-31`,
  },
];

/**
 * Requests a statement for one account over a date range.
 *
 * <p>Not a money-moving flow, so no review step and no idempotency key: asking twice
 * produces two statements, which is harmless and obvious. The dates are bounded in the
 * markup as well as validated, because a picker that allows next year invites a request
 * that can never be fulfilled.
 *
 * <p>IT NAMES THE ACCOUNT, and that is not decoration. This screen is reached from one
 * account and scoped to it, and it used to identify that account nowhere at all — the
 * id was in the URL and the page said "Request a statement" over three empty fields. A
 * customer with a current and a savings account had no way to tell which one they were
 * about to request, and the answer arrives overnight, by which time the mistake is a
 * second request rather than a correction.
 */
export function StatementRequestPage(): ReactElement {
  const { accountId = '' } = useParams();
  const navigate = useNavigate();

  const { state } = useAsync(`account:${accountId}`, (signal) =>
    accountService.byId(accountId, signal),
  );

  /*
   * Read the clock once, in lazy state initialisers, not on every render. A component
   * that calls Date.now() while rendering is not idempotent, and React is entitled to
   * render it twice.
   */
  const [today] = useState(() => localToday());
  const [from, setFrom] = useState(() => daysBefore(29));
  const [to, setTo] = useState(() => localToday());
  const [format, setFormat] = useState<'PDF' | 'CSV'>('PDF');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ message: string; reference?: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const rangeError =
    from === '' || to === ''
      ? 'Choose both dates.'
      : Date.parse(from) > Date.parse(to)
        ? 'The end date is before the start date.'
        : undefined;

  const summary = describeRange(from, to);

  const submit = async (): Promise<void> => {
    setBusy(true);
    setFailure(null);
    setFieldErrors({});

    try {
      await statementService.request({ accountId, from, to, format });
      void navigate(RETAIL_PATHS.statements, { replace: true });
    } catch (cause) {
      if (cause instanceof ApiError) {
        setFieldErrors(Object.fromEntries(cause.fieldErrors.map((v) => [v.field, v.message])));
        setFailure({
          message: cause.message,
          ...(cause.correlationId === undefined ? {} : { reference: cause.correlationId }),
        });
      } else {
        setFailure({ message: 'We could not request that statement.' });
      }
    } finally {
      setBusy(false);
    }
  };

  if (state.status === 'loading') return <Skeleton rows={5} label="Loading account" />;

  /*
   * The account failing to load is not a reason to hide the form — the request is keyed
   * on the id from the URL, which is still there, and the server is the thing that
   * decides whether it is reachable. So the form stays and the strip says plainly that
   * the account could not be named, rather than the page pretending to know it.
   */
  const account: Account | null = state.status === 'error' ? null : state.data;

  return (
    <article className="accounts__narrow">
      <PageHeader
        title="Request a statement"
        crumbs={[
          { label: 'Accounts', to: RETAIL_PATHS.accounts },
          {
            label: account === null ? 'Account' : `${account.nickname} ${account.maskedNumber}`,
            to: routeTo.accountDetail(accountId),
          },
        ]}
      />

      {/*
        WHICH ACCOUNT. First thing on the page, above the controls, because it is the one
        fact the rest of the page is about and the only one the customer cannot change
        here. Masked, as everywhere — the client is never sent a full number.
      */}
      <div className="stmt__account">
        {account === null ? (
          <p className="stmt__account-name">
            We could not load this account&rsquo;s details. You can still request the
            statement; check it is the right account first.
          </p>
        ) : (
          <>
            <p className="stmt__account-name">
              {account.nickname} <span className="stmt__mask">{account.maskedNumber}</span>
            </p>
            <p className="stmt__account-meta">
              {account.accountType.charAt(0) + account.accountType.slice(1).toLowerCase()} ·{' '}
              {account.currency}
            </p>
          </>
        )}
      </div>

      {failure !== null && (
        <Alert
          tone="error"
          title="We could not request that statement"
          reference={failure.reference}
        >
          {failure.message}
        </Alert>
      )}

      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (rangeError === undefined) void submit();
        }}
      >
        <fieldset className="stmt__fieldset">
          <legend className="stmt__legend">Period</legend>

          {/*
            Buttons, not links or tabs: each one sets two fields and changes nothing else
            on the page. `aria-pressed` is what tells a screen reader which range is
            currently chosen, since the visual cue is a filled chip.
          */}
          <div className="stmt__presets">
            {PRESETS.map((preset) => {
              const active = from === preset.from() && to === preset.to();
              return (
                <button
                  key={preset.label}
                  type="button"
                  className="stmt__preset"
                  aria-pressed={active}
                  disabled={busy}
                  onClick={() => {
                    setFrom(preset.from());
                    setTo(preset.to());
                    setFieldErrors({});
                  }}
                >
                  {preset.label}
                </button>
              );
            })}
          </div>

          <div className="stmt__dates">
            <TextField
              label="From"
              type="date"
              max={to === '' ? today : to}
              value={from}
              disabled={busy}
              error={fieldErrors['from']}
              onChange={(event) => {
                setFrom(event.target.value);
              }}
            />

            <TextField
              label="To"
              type="date"
              max={today}
              value={to}
              disabled={busy}
              error={fieldErrors['to'] ?? rangeError}
              onChange={(event) => {
                setTo(event.target.value);
              }}
            />
          </div>

          {/*
            The range in words. See describeRange — the native picker shows whatever the
            browser's locale says, and 08/29 versus 29/08 is a different month.
          */}
          {summary !== undefined && (
            <p className="stmt__summary" aria-live="polite">
              {summary}
            </p>
          )}
        </fieldset>

        <div className="stmt__format">
          <Select
            label="Format"
            value={format}
            disabled={busy}
            options={[
              { value: 'PDF', label: 'PDF — for printing or filing' },
              { value: 'CSV', label: 'CSV — for a spreadsheet' },
            ]}
            onChange={(event) => {
              setFormat(event.target.value === 'CSV' ? 'CSV' : 'PDF');
            }}
          />
        </div>

        <div className="stmt__actions">
          <Button type="submit" loading={busy} disabled={rangeError !== undefined}>
            Request statement
          </Button>
          <Link to={routeTo.accountDetail(accountId)} className="stmt__cancel">
            Cancel
          </Link>
        </div>

        <p className="stmt__note">
          Statements are produced overnight. This one will appear in{' '}
          <Link to={RETAIL_PATHS.statements}>Statements</Link> when it is ready &mdash; there is
          nothing to wait for on this screen.
        </p>
      </form>
    </article>
  );
}
