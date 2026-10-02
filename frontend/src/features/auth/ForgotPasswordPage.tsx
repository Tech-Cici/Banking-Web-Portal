import { useState, type ReactElement, type SyntheticEvent } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, ErrorNotice, TextField } from '@/components/ui';
import { PUBLIC_PATHS } from '@/routes/paths';
import { authService } from '@/services';
import './auth.css';

/**
 * "I have forgotten my password."
 *
 * <p>This screen used to say the service was not available online and to telephone the
 * bank — on the one page a customer reaches when they are already stuck, and for the
 * commonest reason anybody contacts a bank about internet banking.
 *
 * <p>WHAT IT DOES AND DOES NOT DO. There is no reset link and no token: the request goes
 * to the bank, a manager re-issues a temporary password through the same path used at
 * approval, and it arrives by email. The customer's existing password keeps working until
 * then, so nothing is lost by asking and nobody can lock somebody else out by typing
 * their address here.
 *
 * <p>THE ANSWER IS THE SAME WHETHER THE ADDRESS BANKS HERE OR NOT, and the wording is the
 * whole reason this component is careful. "We have sent you an email" would be a lie for
 * an unknown address and a promise this flow cannot keep for a known one — a manager has
 * to act first. "If that address is registered" is the only sentence that is true in both
 * cases without telling a stranger which one they are in.
 */
export function ForgotPasswordPage(): ReactElement {
  const [identifier, setIdentifier] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>(undefined);
  const [pending, setPending] = useState(false);
  const [asked, setAsked] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);

  const onSubmit = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();

    if (identifier.trim() === '') {
      setFieldError('Enter the email address the bank holds for you.');
      return;
    }
    setFieldError(undefined);
    setFailure(null);
    setPending(true);

    try {
      await authService.requestPasswordReset(identifier.trim());
      setAsked(true);
    } catch (cause) {
      /*
       * EVERY failure goes to the shared translator, including a rate limit.
       *
       * That is safe here, and worth saying why: the limit is counted on a real
       * customer's own requests, so it cannot fire for an address that is not a
       * customer's — showing its message reveals nothing the uniform success wording was
       * protecting. What matters is that a network failure must NOT be dressed up as
       * success, because "we have passed this to the bank" when nothing left the browser
       * is a customer waiting for an email that will never come.
       */
      setFailure(cause);
    } finally {
      setPending(false);
    }
  };

  if (asked) {
    return (
      <div>
        <h1 className="auth__title">Ask us for a new password</h1>

        <Alert tone="success" title="We have passed this to the bank">
          <p>
            If <strong>{identifier.trim()}</strong> is registered for internet banking, a member of
            our staff will issue a new temporary password and email it to that address.
          </p>
          <p className="ui-alert__next">
            Until it arrives, your current password still works — nothing has been changed yet.
          </p>
        </Alert>

        {/*
          THE WAITING IS STATED, because a flow with a human in it and no timescale is a
          flow that generates a telephone call. No promise of minutes or hours is made:
          the bank has not told us how quickly its managers work this queue, and a
          guessed service level printed on a customer's screen is a guess the branch has
          to defend.
        */}
        <p className="auth__note">
          This needs a member of our staff, so it is not instant. If you have not heard anything and
          you need access urgently, call the number on the back of your card — and please do not ask
          again from this page in the meantime, as that does not move you up the queue.
        </p>

        <div className="auth__links">
          <Link to={PUBLIC_PATHS.login} className="auth__links-primary">
            Back to sign in
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div>
      <h1 className="auth__title">Ask us for a new password</h1>
      <p className="auth__lead">
        We will email a temporary password to the address the bank holds for you, once a member of
        staff has checked the request.
      </p>

      {failure !== null && <ErrorNotice error={failure} action="pass that request to the bank" />}

      <form onSubmit={(event) => void onSubmit(event)} noValidate>
        <TextField
          label="Email address"
          type="email"
          value={identifier}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          disabled={pending}
          error={fieldError}
          onChange={(event) => {
            setIdentifier(event.target.value);
          }}
        />

        <Button type="submit" block loading={pending}>
          Ask for a new password
        </Button>
      </form>

      {/*
        WHAT WILL HAPPEN, before they press the button rather than after.
        A customer who expects a reset link and gets a wait would conclude the page is
        broken; one who knows a person is involved waits.
      */}
      <Alert tone="info" title="What happens next">
        <p>
          There is no reset link. A manager at the bank issues the new password and we email it to
          you, so this is not instant.
        </p>
        <p className="ui-alert__next">
          Your current password keeps working until the new one arrives, and you will be asked to
          replace the temporary one as soon as you sign in with it.
        </p>
      </Alert>

      <div className="auth__links">
        <Link to={PUBLIC_PATHS.login} className="auth__links-primary">
          I remembered it — back to sign in
        </Link>
        <Link to={PUBLIC_PATHS.register}>Not registered yet? Register</Link>
      </div>
    </div>
  );
}
