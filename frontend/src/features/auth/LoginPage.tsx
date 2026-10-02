import { useState, type ReactElement, type SyntheticEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Alert, Button, Checkbox, ErrorNotice, TextField } from '@/components/ui';
import { appConfig } from '@/config/env';
import { useSession } from '@/hooks/useSession';
import { homePathFor, PUBLIC_PATHS } from '@/routes/paths';
import { ApiError, authService } from '@/services';
import { readRememberedIdentifier, rememberIdentifier } from './rememberIdentifier';
import './auth.css';

/**
 * Sign in.
 *
 * Two steps, not one: credentials establish nothing on their own. A successful password
 * check issues a challenge, and only the one-time code creates a session. Building the UI
 * against a one-step flow and adding MFA later means rewriting this screen.
 *
 * Failure messages are deliberately uniform. A wrong password and an unknown customer
 * produce identical text, because a login form that distinguishes them is a way to find
 * out who banks here.
 */
/**
 * True when the failure is almost certainly "the backend has no auth yet" rather than a
 * wrong password: mocks are off, we are in development, and the API refused outright.
 *
 * Saves the next person the twenty minutes this cost once already.
 */
function backendHasNoAuth(cause: ApiError): boolean {
  return (
    import.meta.env.DEV &&
    !appConfig.enableMockApi &&
    (cause.kind === 'unauthenticated' || cause.kind === 'notFound')
  );
}

/**
 * Tells the DEVELOPER that they are pointing at a backend with no sign-in yet.
 *
 * This used to render on the page, under a "Developer note" heading, next to the message
 * the customer reads. Wrong audience and wrong place: the person who needs it is looking
 * at a terminal, and a bank's sign-in screen is not a debug surface. The console reaches
 * exactly the right person and nobody else.
 *
 * `import.meta.env.DEV` is a build-time literal, so this folds away in production.
 */
function warnBackendHasNoAuth(): void {
  if (!import.meta.env.DEV) return;

  /*
   * The only console call in the application, and the ban is right: logging in a banking
   * client is how a password or a one-time code ends up in a browser's console history.
   * This logs neither — it is a fixed string about the build configuration, behind a
   * build-time DEV guard, and it exists precisely so this note is not on screen instead.
   */
  // eslint-disable-next-line no-console
  console.warn(
    '[sign-in] The API refused this and mocks are off — the backend does not implement ' +
      'sign-in yet. Run `npm run dev` instead of `npm run dev:live`.',
  );
}

export function LoginPage(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();

  /** Where the guard wanted to send them before sign-in interrupted. */
  const routerState = location.state as { from?: string; returning?: boolean } | null;
  const attemptedPath = routerState?.from;

  /**
   * Whether the guard sent them here from a page inside the portal.
   *
   * WHY THIS IS ON THE SCREEN AT ALL. Reloading any signed-in page after the API had
   * restarted, or after the thirty-minute idle timeout, landed the customer on a bare
   * sign-in form with no explanation — the commonest reading of which is "my password has
   * stopped working". Nothing told them their session had simply ended, and nothing said
   * they would be returned to the page they were on, which they are: `attemptedPath` is
   * navigated to after sign-in.
   */
  const returning = routerState?.returning === true;

  const [identifier, setIdentifier] = useState(() => readRememberedIdentifier() ?? '');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(() => readRememberedIdentifier() !== null);

  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);
  /**
   * Either a message this screen insists on wording itself (rejected credentials), or
   * the raw failure for the shared translator to explain.
   */
  const [failure, setFailure] = useState<{
    message?: string;
    error?: unknown;
    reference?: string;
  } | null>(null);

  /*
   * Someone who is already signed in has no business on the sign-in form — showing it
   * invites them to re-enter a password they do not need to, which is the habit phishing
   * relies on. Send them where they were going instead.
   */
  if (session.status === 'authenticated' && session.user !== null) {
    return <Navigate to={attemptedPath ?? homePathFor(session.user.userType)} replace />;
  }

  const onSubmit = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();

    const found: Record<string, string> = {};
    if (identifier.trim() === '')
      found['identifier'] = 'Enter your username, email or customer ID.';
    if (password === '') found['password'] = 'Enter your password.';
    setFieldErrors(found);
    if (Object.keys(found).length > 0) return;

    setFailure(null);
    setPending(true);

    try {
      const result = await authService.login({ identifier: identifier.trim(), password });

      // The password is no longer needed anywhere. Drop it before navigating.
      setPassword('');
      rememberIdentifier(remember ? identifier.trim() : null);

      if (result.outcome === 'PASSWORD_CHANGE_REQUIRED') {
        /*
         * They signed in with the temporary password an admin issued. A session exists,
         * but it may do exactly one thing until the password is replaced — so go
         * straight there and nowhere else, ignoring any page they were originally
         * heading for.
         */
        await session.refreshAndGet();
        void navigate(PUBLIC_PATHS.changeTemporaryPassword, { replace: true });
        return;
      }

      if (result.outcome === 'COMPLETE') {
        /*
         * A SESSION ALREADY, WITHOUT A CODE — and not because the second factor was
         * skipped. This browser completed the emailed-code step within the last thirty
         * days and the server remembers it, so the password was the only step left.
         *
         * The previous comment here said "no second factor required for this user",
         * which was never true of anybody and would now be read as a per-customer
         * exemption that does not exist.
         */
        const loaded = await session.refreshAndGet();
        void navigate(attemptedPath ?? homePathFor(loaded?.userType ?? 'RETAIL'), {
          replace: true,
        });
        return;
      }

      if (result.challenge === undefined) {
        setFailure({
          message:
            'We could not finish signing you in. Please try again, and call us if it happens again.',
        });
        return;
      }

      // The challenge travels in router state, never in the URL (blueprint section 5.2).
      void navigate(PUBLIC_PATHS.verify, {
        replace: true,
        state: { challenge: result.challenge, from: attemptedPath },
      });
    } catch (cause) {
      setPassword('');
      if (cause instanceof ApiError) {
        /*
         * A 401 on THIS screen means the credentials were rejected, not that a session
         * lapsed — so the shared wording ("You have been signed out") would be nonsense
         * in front of someone who is visibly signing in. Everything else is an ordinary
         * failure and gets the shared plain-language treatment.
         *
         * The message is identical for a wrong password and an unknown customer. A login
         * form that tells the two apart is a way to find out who banks here.
         */
        if (backendHasNoAuth(cause)) warnBackendHasNoAuth();

        if (cause.kind === 'unauthenticated') {
          setFailure({
            message:
              'The details you entered do not match an account. Check your username and password — remember that capital letters matter — and try again.',
            ...(cause.correlationId === undefined ? {} : { reference: cause.correlationId }),
          });
        } else {
          setFailure({ error: cause });
        }
      } else {
        setFailure({ error: cause });
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <div>
      <h1 className="auth__title">Sign in</h1>
      <p className="auth__lead">Use the email address and password the bank holds for you.</p>

      {/*
        SAYS WHY THEY ARE LOOKING AT THIS FORM.

        DELIBERATELY NOT "YOUR SESSION EXPIRED" AS A FLAT STATEMENT. The app cannot know
        that: after a reload the page is fresh, so it has no memory of ever having been
        signed in, and the cause may equally have been a bank restart or a session that
        timed out. The wording covers both without claiming either — which is the same
        rule applied to the sign-in location that used to say "Kigali, Rwanda".

        It also promises the return, because that is what makes the interruption tolerable
        and because the promise is kept a few lines down in `onSubmit`.
      */}
      {returning && (
        <Alert tone="info" title="Please sign in again">
          <p>
            Your session has ended. This happens after a while without activity, and
            whenever the bank's service restarts — nothing has happened to your account.
          </p>
          <p className="ui-alert__next">
            Sign in and we will take you straight back to the page you were on.
          </p>
        </Alert>
      )}

      {failure !== null &&
        (failure.message !== undefined ? (
          <Alert tone="error" title="We could not sign you in" reference={failure.reference}>
            <p>{failure.message}</p>
            <p className="ui-alert__next">
              Forgotten your password? <Link to={PUBLIC_PATHS.forgotPassword}>Reset it here</Link>.
            </p>
          </Alert>
        ) : (
          <ErrorNotice error={failure.error} action="sign you in" />
        ))}

      <form onSubmit={(event) => void onSubmit(event)} noValidate>
        {/*
          "EMAIL ADDRESS", because that is the only thing sign-in accepts.
          
          This said "Username, email or customer ID". There is no username in this
          service and no lookup by customer number anywhere in it — CustomerAuthService
          calls findByEmailIgnoreCase and nothing else. So two of those three invited a
          customer to type something that could only fail, with the generic "the details
          you entered do not match an account" that a wrong password also produces.

          It mattered most to the people least able to work it out: the approval email
          prints the customer number directly above the temporary password, so a new
          customer's first instinct is to type the number, and the screen agreed with them.
        */}
        <TextField
          label="Email address"
          type="email"
          value={identifier}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          disabled={pending}
          /*
            NO HINT UNDER THIS FIELD ANY MORE. It read "The address the bank emailed your
            temporary password to", which is true exactly once — on the first sign-in —
            and wrong for every visit after it, on the screen a customer sees most often.
            What it was there to prevent (somebody typing their customer number, which
            the approval email prints above the temporary password) is now said once, in
            the line under the heading, for both fields at once.
          */
          error={fieldErrors['identifier']}
          onChange={(event) => {
            setIdentifier(event.target.value);
          }}
        />

        {/*
          ABOVE THE PASSWORD, not below the whole form, and the move is about tab order
          rather than tidiness. The submit button now sits inside the password field, so
          anything after that field is reached AFTER the control that sends the form —
          somebody tabbing to this checkbox would already have signed in. It also belongs
          next to the thing it remembers: the email address, not the password.
        */}
        <Checkbox
          label="Remember my email address on this device"
          checked={remember}
          disabled={pending}
          onChange={(event) => {
            setRemember(event.target.checked);
          }}
        />

        <TextField
          label="Password"
          type="password"
          value={password}
          autoComplete="current-password"
          disabled={pending}
          error={fieldErrors['password']}
          onChange={(event) => {
            setPassword(event.target.value);
          }}
          /*
            THE SUBMIT LIVES IN THE FIELD, as the design the bank supplied draws it.

            It is a real `Button`, wrapped rather than restyled, so it keeps the one
            behaviour that matters on a credential form: `loading` disables it, which is
            the double-submit guard. Pressing Enter in either field still submits, so the
            arrow is not the only way in.

            The arrow is decorative and the accessible name is on the button, because
            "→" read aloud is nothing at all.
          */
          adornment={
            <span className="ui-input__adornment brand-go">
              <Button type="submit" loading={pending} aria-label="Sign in" title="Sign in">
                {pending ? <></> : <span aria-hidden="true">→</span>}
              </Button>
            </span>
          }
        />
      </form>

      <div className="auth__links">
        {/*
          ONE PROMINENT WAY OUT OF TROUBLE, worded as the problem rather than as the
          remedy. "Forgot your password?" only helps the person who knows that is what
          went wrong; somebody whose account is locked, or who is typing an address the
          bank does not hold, reads it and concludes there is nothing here for them.
        */}
        <Link to={PUBLIC_PATHS.forgotPassword} className="auth__links-primary">
          Having trouble signing in?
        </Link>
        <Link to={PUBLIC_PATHS.register}>Not registered yet? Register</Link>
      </div>

      {/*
        There is no list of seeded customers, because there are none — the only way into
        the customer portal is to register and be approved.
        A note saying so, with a link to the staff portal, used to render here. It was a
        development aid on the bank's public sign-in page: it advertised where the staff
        portal lives to anyone who loaded the page with a dev build, which is not a URL
        to hand out. See docs/OPEN-ITEMS.md.
      */}
    </div>
  );
}
