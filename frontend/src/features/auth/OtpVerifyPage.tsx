import { useState, type ReactElement, type SyntheticEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Alert, Button, ErrorNotice, OtpInput, ResendTimer } from '@/components/ui';
import { useSession } from '@/hooks/useSession';
import { homePathFor, PUBLIC_PATHS } from '@/routes/paths';
import { authService } from '@/services';
import type { AuthChallenge } from '@/types/auth';
import './auth.css';

/**
 * Second factor.
 *
 * The challenge arrives in router state, never in the URL — a one-time code challenge in
 * a query string ends up in browser history and server logs (blueprint sections 5.2, 24).
 *
 * A direct visit with no challenge is sent back to sign-in rather than shown an empty
 * form, because there is nothing here to complete.
 */
/**
 * How the code was delivered, read off the hint rather than asserted.
 *
 * This page used to say "by SMS" unconditionally while the code was going out by email,
 * so it told the customer to check their phone and then printed an email address beside
 * the instruction. Somebody following that advice waits for a message that never arrives
 * and concludes the bank's sign-in is broken.
 *
 * Deriving it means the sentence stays true when SMS delivery is added, rather than being
 * a second place that has to be remembered and changed.
 */
function deliveryWording(hint: string): string {
  return hint.includes('@') ? 'by email to' : 'by SMS to';
}

export function OtpVerifyPage(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();

  const stateChallenge = (location.state as { challenge?: AuthChallenge } | null)?.challenge;
  const [challenge, setChallenge] = useState<AuthChallenge | undefined>(stateChallenge);

  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | undefined>(undefined);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);

  /*
   * Kept apart from `failure`. "We could not send a new code" and "that code is wrong"
   * are different problems with different answers, and sharing one slot meant asking for
   * a fresh code silently erased the message explaining why the last one was rejected.
   */
  const [resendFailure, setResendFailure] = useState<unknown>(null);

  if (challenge === undefined) {
    return (
      <div>
        <h1 className="auth__title">Sign in</h1>
        <Alert tone="info" title="Start again">
          This verification link is no longer valid. Please sign in again.
        </Alert>
        <Link to={PUBLIC_PATHS.login} className="ui-btn ui-btn--primary ui-btn--block">
          Back to sign in
        </Link>
      </div>
    );
  }

  const onSubmit = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();

    if (code.length !== challenge.otpLength) {
      setCodeError(`Enter the ${String(challenge.otpLength)}-digit code.`);
      return;
    }
    setCodeError(undefined);
    setFailure(null);
    setPending(true);

    try {
      await authService.verify({ challengeId: challenge.challengeId, code });
      // The code has done its job; drop it before anything else.
      setCode('');

      // Load the session first: the destination depends on who just signed in.
      const loaded = await session.refreshAndGet();
      const attempted = (location.state as { from?: string } | null)?.from;
      const destination = attempted ?? homePathFor(loaded?.userType ?? 'RETAIL');
      void navigate(destination, { replace: true });
    } catch (cause) {
      setCode('');
      setFailure(cause);
    } finally {
      setPending(false);
    }
  };

  const onResend = async (): Promise<void> => {
    setResendFailure(null);
    setFailure(null);
    try {
      setChallenge(await authService.resend(challenge.challengeId));
      setCode('');
    } catch (cause) {
      setResendFailure(cause);
    }
  };

  return (
    <div>
      <h1 className="auth__title">Verify it&rsquo;s you</h1>
      <p className="auth__lead">
        We sent a {challenge.otpLength}-digit code {deliveryWording(challenge.deliveryHint)}{' '}
        {challenge.deliveryHint}.
      </p>

      {failure !== null && <ErrorNotice error={failure} action="check that code" />}
      {resendFailure !== null && <ErrorNotice error={resendFailure} action="send you a new code" />}

      <form onSubmit={(event) => void onSubmit(event)} noValidate>
        <OtpInput
          label="Verification code"
          value={code}
          length={challenge.otpLength}
          error={codeError}
          disabled={pending}
          onChange={(value) => {
            setCode(value);
            setCodeError(undefined);
          }}
        />

        <ResendTimer
          seconds={challenge.resendAfterSeconds}
          disabled={pending}
          onResend={() => void onResend()}
        />

        <Button type="submit" block loading={pending}>
          Verify and sign in
        </Button>
      </form>

      {/*
        NO PROMISE ABOUT NEXT TIME.
        
        This said "we will not ask for a code again on this browser for 30 days", which
        was true only while the emailed code was required and the browser was remembered.
        The code is off by default now (ibanking.sign-in.code-required), so this page is
        reached only where it has been turned back on — and there the promise depends on
        which browser and which account, which is more than a sentence here can honestly
        say. A screen that promises what the server may not do is the thing this codebase
        keeps having to fix.
      */}

      <div className="auth__links">
        <Link to={PUBLIC_PATHS.login}>Cancel and sign in again</Link>
      </div>

      <p className="auth__security">
        Nobody from the bank will ever ask you for this code. If someone does, hang up and report
        it.
      </p>
    </div>
  );
}
