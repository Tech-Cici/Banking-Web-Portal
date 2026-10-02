import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, IdempotencyScope } from '@/services';
import type { ApiFieldViolation } from '@/types/api';

/**
 * The state machine behind every money-moving screen.
 *
 * Transfers, bill payments, FX conversions and standing orders differ in the fields they
 * collect and in nothing else that matters. They all have to:
 *
 *  1. Collect details, then STOP and show exactly what is about to happen. No screen in
 *     this app moves money straight from a form submit.
 *  2. Send once, under one idempotency key, with the button disabled while in flight.
 *  3. Treat a timeout as UNKNOWN. Not a failure, not a success — the money may have
 *     moved, and "try again" is the one thing that must not be offered.
 *
 * Writing that four times means getting it right three times and wrong once. So it lives
 * here, and each flow supplies its own `submit`.
 */

export type FlowStep = 'form' | 'review' | 'result';

export interface FlowSuccess<TResult> {
  readonly kind: 'done';
  readonly result: TResult;
}

export interface FlowPending {
  /**
   * The request did not come back. The transaction may or may not exist.
   *
   * Deliberately its own outcome rather than an error: the screen must say "we do not
   * know yet", offer a way to CHECK, and must not offer a way to resend.
   */
  readonly kind: 'pending';
  readonly message: string;
  readonly reference: string | undefined;
}

export interface FlowFailure {
  readonly kind: 'failed';
  readonly message: string;
  readonly reference: string | undefined;
  /** True when it is safe to change the details and send again. */
  readonly retryable: boolean;
}

export type FlowOutcome<TResult> = FlowSuccess<TResult> | FlowPending | FlowFailure;

export interface MoneyFlow<TResult> {
  readonly step: FlowStep;
  readonly busy: boolean;
  readonly outcome: FlowOutcome<TResult> | null;
  /** Field errors the server sent back, keyed by field name. */
  readonly fieldErrors: Readonly<Record<string, string>>;

  /** Form → review. The idempotency key is minted here, once. */
  readonly toReview: () => void;
  /** Review → form, to correct something. Keeps the same key: same transaction. */
  readonly backToForm: () => void;
  /** Sends. Safe to call once; ignored while a send is in flight. */
  readonly confirm: () => void;
  /** Clears everything for a genuinely NEW transaction, with a new key. */
  readonly startOver: () => void;
}

export function useMoneyFlow<TResult>(
  submit: (idempotencyKey: string, signal: AbortSignal) => Promise<TResult>,
): MoneyFlow<TResult> {
  const [step, setStep] = useState<FlowStep>('form');
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<FlowOutcome<TResult> | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({});

  /*
   * One key per transaction, held across the review step and any retry of the SAME
   * submission. A new key on retry is a second transfer — which is how a customer gets
   * debited twice. `startOver` is the only thing that resets it.
   */
  const scope = useRef(new IdempotencyScope());
  /* Assigned in an effect, never during render — see the note in `useAsync`. */
  const submitRef = useRef(submit);
  useEffect(() => {
    submitRef.current = submit;
  });

  const toReview = useCallback(() => {
    setFieldErrors({});
    setOutcome(null);
    /*
     * Reading `.key` is what mints it. Doing it here rather than at submit time means
     * the key is fixed before the customer sees the review screen, so going back to
     * correct a typo does not quietly turn one transaction into two.
     */
    if (!scope.current.hasKey) {
      const minted = scope.current.key;
      if (minted === '') throw new Error('Could not create an idempotency key.');
    }
    setStep('review');
  }, []);

  const backToForm = useCallback(() => {
    setStep('form');
  }, []);

  const confirm = useCallback(() => {
    if (busy) return;

    setBusy(true);
    setFieldErrors({});

    const controller = new AbortController();
    const key = scope.current.key;

    void submitRef
      .current(key, controller.signal)
      .then((result) => {
        setOutcome({ kind: 'done', result });
        setStep('result');
      })
      .catch((cause: unknown) => {
        if (!(cause instanceof ApiError)) {
          /*
           * An unrecognised failure on a money-moving call is treated as UNKNOWN, not as
           * a failure. Anything else risks telling someone their payment did not go
           * through when it did.
           */
          setOutcome({
            kind: 'pending',
            message: 'We did not get a reply from the bank.',
            reference: undefined,
          });
          setStep('result');
          return;
        }

        if (cause.kind === 'pendingConfirmation' || cause.kind === 'timeout') {
          setOutcome({ kind: 'pending', message: cause.message, reference: cause.correlationId });
          setStep('result');
          return;
        }

        if (cause.kind === 'validation' && cause.fieldErrors.length > 0) {
          // Back to the form with the server's own field messages attached.
          setFieldErrors(
            Object.fromEntries(
              cause.fieldErrors.map((violation: ApiFieldViolation) => [
                violation.field,
                violation.message,
              ]),
            ),
          );
          setStep('form');
          return;
        }

        setOutcome({
          kind: 'failed',
          message: cause.message,
          reference: cause.correlationId,
          /*
           * A refusal the customer can act on — insufficient funds, a limit, a blocked
           * beneficiary — is safe to correct and resend. A conflict or a server fault is
           * not, because the first attempt's fate is not established.
           */
          retryable:
            cause.kind === 'businessRule' ||
            cause.kind === 'validation' ||
            cause.kind === 'rateLimited',
        });
        setStep('result');
      })
      .finally(() => {
        setBusy(false);
      });
  }, [busy]);

  const startOver = useCallback(() => {
    scope.current.reset();
    setOutcome(null);
    setFieldErrors({});
    setStep('form');
  }, []);

  return { step, busy, outcome, fieldErrors, toReview, backToForm, confirm, startOver };
}
