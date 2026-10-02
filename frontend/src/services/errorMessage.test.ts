import { describe, expect, it } from 'vitest';
import { ApiError, type ApiErrorKind } from './apiError';
import { friendlyError } from './errorMessage';

/**
 * These assert on the WORDS, not on the shape.
 *
 * The defect being fixed was never "no error is shown" — an alert always appeared. It was
 * that every alert said the same twelve useless words regardless of what had happened. A
 * test that only checks `summary` is a non-empty string would have passed throughout, so
 * the tests here check that the sentences differ, that they say what to do, and that the
 * dangerous cases say the dangerous thing.
 */

const ALL_KINDS: readonly ApiErrorKind[] = [
  'validation',
  'unauthenticated',
  'forbidden',
  'notFound',
  'conflict',
  'businessRule',
  'rateLimited',
  'server',
  'network',
  'timeout',
  'pendingConfirmation',
  'unknown',
];

function errorOf(kind: ApiErrorKind, overrides: Partial<ConstructorParameters<typeof ApiError>[0]> = {}) {
  return new ApiError({ kind, message: 'raw server text', ...overrides });
}

describe('friendlyError', () => {
  it('gives every kind of failure its own explanation', () => {
    /*
     * An error CODE rather than a sentence, so the server's message is correctly
     * rejected and each kind has to fall back on its own words. With a usable server
     * message, validation and businessRule both echo it — which is the intended
     * behaviour, and would hide the thing this test is checking.
     */
    const summaries = ALL_KINDS.map(
      (kind) => friendlyError(errorOf(kind, { message: 'SOME_CODE' })).summary,
    );
    expect(new Set(summaries).size).toBe(ALL_KINDS.length);
  });

  it('tells the person what to do next for every kind', () => {
    for (const kind of ALL_KINDS) {
      // businessRule is the one exception: the server's own message carries the advice.
      if (kind === 'businessRule') continue;
      expect(friendlyError(errorOf(kind)).nextStep, kind).toBeDefined();
    }
  });

  it('never leaks developer vocabulary into what a customer reads', () => {
    const forbidden =
      /\b(40\d|50\d|status code|null|undefined|API|endpoint|JSON|HTTP|token|payload|exception|stack)\b/i;

    for (const kind of ALL_KINDS) {
      const friendly = friendlyError(errorOf(kind));
      const text = `${friendly.summary} ${friendly.nextStep ?? ''}`;
      expect(text, kind).not.toMatch(forbidden);
    }
  });

  describe('being offline', () => {
    it('names the real problem rather than blaming the bank', () => {
      const friendly = friendlyError(errorOf('network'));
      expect(friendly.summary).toMatch(/not connected to the internet/i);
      expect(friendly.nextStep).toMatch(/Wi-Fi|mobile data/i);
    });

    it('reassures that nothing was sent, because nothing was', () => {
      expect(friendlyError(errorOf('network')).nextStep).toMatch(/nothing was sent/i);
    });
  });

  describe('an outage', () => {
    it('takes the blame instead of leaving the customer wondering', () => {
      const friendly = friendlyError(errorOf('server'));
      expect(friendly.summary).toMatch(/on our side/i);
      expect(friendly.summary).toMatch(/not.*you did/i);
    });

    it('reads differently from being offline', () => {
      expect(friendlyError(errorOf('server')).summary).not.toBe(
        friendlyError(errorOf('network')).summary,
      );
    });
  });

  describe('rate limiting', () => {
    it('says how long to wait, in units a person uses', () => {
      expect(friendlyError(errorOf('rateLimited', { retryAfterSeconds: 120 })).nextStep).toMatch(
        /2 minutes/,
      );
      expect(friendlyError(errorOf('rateLimited', { retryAfterSeconds: 60 })).nextStep).toMatch(
        /a minute/,
      );
      expect(friendlyError(errorOf('rateLimited', { retryAfterSeconds: 30 })).nextStep).toMatch(
        /30 seconds/,
      );
    });

    it('still gives advice when the server did not say how long', () => {
      expect(friendlyError(errorOf('rateLimited')).nextStep).toMatch(/wait a minute/i);
    });
  });

  describe('a payment whose outcome is unknown', () => {
    /*
     * The single most expensive message in the application. Telling someone their
     * payment failed when it may have succeeded is how one transfer becomes two.
     */
    it('does not claim the payment failed', () => {
      const friendly = friendlyError(errorOf('pendingConfirmation'));
      expect(friendly.summary).not.toMatch(/failed|did not go|was not sent|unsuccessful/i);
      expect(friendly.summary).toMatch(/cannot yet tell you whether/i);
    });

    it('tells them explicitly not to send it again', () => {
      expect(friendlyError(errorOf('pendingConfirmation')).nextStep).toMatch(
        /do not send it again/i,
      );
    });

    it('is never offered as safe to retry', () => {
      expect(friendlyError(errorOf('pendingConfirmation')).canRetry).toBe(false);
    });
  });

  describe('deciding whether a retry button appears', () => {
    it('offers one where repeating is harmless', () => {
      for (const kind of ['network', 'timeout', 'server', 'rateLimited'] as const) {
        expect(friendlyError(errorOf(kind)).canRetry, kind).toBe(true);
      }
    });

    it('withholds it where repeating could double a payment or is pointless', () => {
      for (const kind of ['pendingConfirmation', 'conflict', 'validation', 'forbidden'] as const) {
        expect(friendlyError(errorOf(kind)).canRetry, kind).toBe(false);
      }
    });
  });

  describe('the server’s own message', () => {
    it('wins for a business rule, because only the server knows the rule', () => {
      const friendly = friendlyError(
        errorOf('businessRule', { message: 'You cannot send more than 5,000,000 RWF in one day.' }),
      );
      expect(friendly.summary).toBe('You cannot send more than 5,000,000 RWF in one day.');
    });

    it('is discarded for an outage, where it is a stack trace or a gateway page', () => {
      const friendly = friendlyError(
        errorOf('server', { message: 'NullPointerException at com.zigama.AccountService:184' }),
      );
      expect(friendly.summary).not.toMatch(/NullPointerException|AccountService/);
    });

    it('is discarded when it is really an error code rather than a sentence', () => {
      const friendly = friendlyError(errorOf('businessRule', { message: 'LIMIT_EXCEEDED' }));
      expect(friendly.summary).not.toBe('LIMIT_EXCEEDED');
    });

    it('is discarded when it is empty', () => {
      const friendly = friendlyError(errorOf('businessRule', { message: '   ' }));
      expect(friendly.summary.trim()).not.toBe('');
    });
  });

  describe('something that is not an API failure at all', () => {
    it('apologises rather than repeating a raw JavaScript error', () => {
      const friendly = friendlyError(new TypeError('x.map is not a function'));
      expect(friendly.summary).not.toMatch(/is not a function|TypeError/);
      expect(friendly.summary).toMatch(/not something you did/i);
    });

    it('handles things that are not even errors', () => {
      expect(friendlyError(undefined).summary).toBeTruthy();
      expect(friendlyError('a bare string').summary).toBeTruthy();
      expect(friendlyError(null).nextStep).toBeDefined();
    });
  });

  it('carries the support reference through so it can be quoted', () => {
    expect(friendlyError(errorOf('server', { correlationId: 'abc-123' })).reference).toBe('abc-123');
  });
});
