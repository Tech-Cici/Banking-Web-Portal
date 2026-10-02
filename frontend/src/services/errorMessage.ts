import { ApiError, type ApiErrorKind } from './apiError';

/**
 * Turns a failure into something a customer can actually act on.
 *
 * The app already works out *what kind* of failure happened — offline, timed out, server
 * down, too many attempts, a rule refused it — and then threw that away at display time,
 * printing one of two sentences for every case:
 *
 *   "We could not complete your request. Please try again in a moment."
 *   "We could not load this right now."
 *
 * Someone on a dead connection, someone hitting an outage, and someone who has been rate
 * limited all read the same words, and "try again in a moment" is actively wrong advice
 * for two of the three. So every failure now resolves to two parts:
 *
 *   `summary`  — what happened, in words a customer would use
 *   `nextStep` — what to do about it, or undefined when there is genuinely nothing to do
 *
 * Two rules this file follows, both worth keeping:
 *
 *  1. It never invents detail. Where the server sent a specific, safe explanation — a
 *     business rule or a validation failure — that message is the summary, because the
 *     server knows why and we do not. This file supplies the next step around it.
 *
 *  2. It never states an outcome it cannot know. A financial request that timed out did
 *     not "fail"; the money may have moved. That case says so plainly rather than
 *     offering a reassuring falsehood in either direction.
 */

export interface FriendlyError {
  /** What happened, in plain words. Always present. */
  readonly summary: string;
  /** What the person should do next. Absent when there is nothing useful to say. */
  readonly nextStep: string | undefined;
  /** The support reference, when the server gave one. */
  readonly reference: string | undefined;
  /** Whether pressing the same button again is safe and likely to help. */
  readonly canRetry: boolean;
}

/**
 * Wording per failure kind.
 *
 * Written to be read by someone who does not know what a server is. No status codes, no
 * "request", no "resource", no "invalid" — a person does not experience their own typing
 * as invalid, they experience it as not working.
 */
function wordingFor(
  kind: ApiErrorKind,
  retryAfterSeconds: number | undefined,
  hadSession: boolean,
): {
  summary: string;
  nextStep: string | undefined;
} {
  switch (kind) {
    case 'network':
      return {
        summary: 'Your device is not connected to the internet, or the connection dropped.',
        nextStep: 'Check your mobile data or Wi-Fi, then try again. Nothing was sent.',
      };

    case 'timeout':
      return {
        summary: 'The bank took too long to answer.',
        nextStep: 'This is usually temporary. Please try again.',
      };

    case 'server':
      return {
        summary: 'Something went wrong on our side. This is not a problem with anything you did.',
        nextStep: 'Please try again in a few minutes. If it keeps happening, call us.',
      };

    case 'rateLimited':
      return {
        summary: 'You have tried this too many times in a row, so we have paused it for a moment.',
        nextStep:
          retryAfterSeconds === undefined
            ? 'Please wait a minute and try again.'
            : `Please wait ${describeWait(retryAfterSeconds)} and try again.`,
      };

    case 'unauthenticated':
      /*
       * Who is reading this matters more than what the status code was.
       *
       * "You have been signed out" is right for someone whose session lapsed mid-task,
       * and nonsense for someone filling in a registration form who has never had an
       * account — they read that the bank has signed them out of something they were
       * never in, and reasonably conclude the site is broken. Same 401, two audiences.
       */
      return hadSession
        ? {
            summary:
              'You have been signed out, either because you were inactive for a while or because you signed in somewhere else.',
            nextStep: 'Please sign in again. This is normal and your money is unaffected.',
          }
        : {
            summary: 'The bank could not complete that request.',
            nextStep: 'Please try again. If it keeps happening, call us and quote the reference below.',
          };

    case 'forbidden':
      return {
        summary: 'Your account does not have permission to do this.',
        nextStep:
          'If you think you should be able to, speak to whoever manages your access, or call us.',
      };

    case 'notFound':
      /*
       * "Not found" is very often a lie to the customer. The server returns it both for
       * something that was deleted and for something that belongs to someone else, and it
       * must not distinguish the two — saying "that exists but is not yours" tells a
       * stranger their guess was right. So the wording covers both honestly.
       */
      return {
        summary: 'We could not find that, so it may have been removed or it may not be yours to view.',
        nextStep: 'Go back and try again from the list. If you expected it to be there, call us.',
      };

    case 'conflict':
      return {
        summary: 'This has already been dealt with, or something changed while you were on this page.',
        nextStep: 'Refresh the page to see the latest before you try again.',
      };

    case 'pendingConfirmation':
      /*
       * The one case where the honest answer is "we do not know". Never call this a
       * failure and never suggest sending it again: the first attempt may already have
       * moved the money, and a customer told "that failed" will send it twice.
       */
      return {
        summary:
          'We lost contact with the bank while this payment was being processed, so we cannot yet tell you whether it went through.',
        nextStep:
          'Do NOT send it again. Check your transactions in a few minutes — if it is not there, then try again.',
      };

    case 'validation':
      return {
        summary: 'Some of the details you entered need fixing.',
        nextStep: 'Check the highlighted boxes above and try again.',
      };

    case 'businessRule':
      /* The server explains these; see `friendlyError` below. This is the bare fallback. */
      return {
        summary: 'We cannot do this right now.',
        nextStep: undefined,
      };

    case 'unknown':
      return {
        summary: 'Something unexpected went wrong.',
        nextStep: 'Please try again. If it keeps happening, call us and quote the reference below.',
      };
  }
}

/** "30 seconds", "2 minutes" — never "retryAfterSeconds: 120". */
function describeWait(seconds: number): string {
  if (seconds < 60) return `${String(Math.max(1, Math.round(seconds)))} seconds`;
  const minutes = Math.round(seconds / 60);
  return minutes === 1 ? 'a minute' : `${String(minutes)} minutes`;
}

/**
 * Whether the server's own message is fit to show a customer.
 *
 * Business-rule and validation messages are written for people and carry the detail only
 * the server has ("You cannot send more than X today"). Everything else is a translated
 * status code, and this file has better words for those.
 */
function serverMessageIsUsable(error: ApiError): boolean {
  /*
   * `notFound` IS INCLUDED, and it was the omission that cost a whole afternoon.
   *
   * One endpoint can refuse a request as "not found" for several different reasons —
   * sending money has three — and the server writes a distinct, customer-safe sentence
   * for each. Discarding all of them for one generic line meant the screen, the
   * support reference and the screenshot all said the same thing no matter which
   * refusal had fired, and there was no way to tell from outside which one it was.
   *
   * Every ApiException message is already written to be shown to a customer, and the
   * not-found ones are deliberately vague about whether a thing exists — that is the
   * property that makes them safe, and it does not depend on hiding them.
   *
   * The next step still comes from the wording table below: the server says what could
   * not be found, this file says what to do about it.
   */
  if (
    error.kind !== 'businessRule' &&
    error.kind !== 'validation' &&
    error.kind !== 'notFound'
  ) {
    return false;
  }

  const message = error.message.trim();
  if (message === '') return false;

  // A message that is really a code — "BUSINESS_RULE_VIOLATION", "ERR_LIMIT" — is not English.
  if (/^[A-Z][A-Z0-9_]{3,}$/.test(message)) return false;

  return true;
}

/**
 * The single entry point. Give it anything thrown; get back something showable.
 *
 * Accepts `unknown` deliberately: a `catch` block receives `unknown`, and making every
 * caller narrow before asking for wording is how ad hoc fallback strings got scattered
 * across twenty screens in the first place.
 */
export interface FriendlyErrorContext {
  /**
   * Whether the person had a session when this failed.
   *
   * Only affects the wording for a 401, and only because the honest sentence differs:
   * a session can end, but it cannot end for someone who never had one. Defaults to
   * true, so an unaware caller gets the wording for a signed-in customer.
   */
  readonly hadSession?: boolean;
}

export function friendlyError(
  cause: unknown,
  fallbackSummary?: string,
  context: FriendlyErrorContext = {},
): FriendlyError {
  if (!(cause instanceof ApiError)) {
    /*
     * Not an API failure at all — a bug in our own code, most likely. The customer is
     * owed an apology and no blame, and we must not repeat a raw JS error at them
     * ("undefined is not a function" has reached production at other banks).
     */
    return {
      summary: fallbackSummary ?? 'Something went wrong on our side, and it is not something you did.',
      nextStep: 'Please try again. If it keeps happening, call us.',
      reference: undefined,
      canRetry: true,
    };
  }

  const wording = wordingFor(cause.kind, cause.retryAfterSeconds, context.hadSession ?? true);

  return {
    summary: serverMessageIsUsable(cause) ? cause.message : wording.summary,
    nextStep: serverMessageIsUsable(cause) && cause.kind === 'businessRule'
      ? undefined
      : wording.nextStep,
    reference: cause.correlationId,
    canRetry: cause.isSafeToRetry,
  };
}

/**
 * A short title for the alert, describing the action that failed rather than the failure.
 *
 * "We could not send your payment" tells someone what state they are in; "Error" does
 * not. Callers pass the action in their own words.
 */
export function failureTitle(action: string): string {
  return `We could not ${action}`;
}
