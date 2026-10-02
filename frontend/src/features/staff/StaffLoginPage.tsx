import { useState, type ReactElement } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Alert, Button, PasswordField, TextField } from '@/components/ui';
import { BRAND } from '@/config/brand';
import { PUBLIC_PATHS, STAFF_PATHS } from '@/routes/paths';
import { ApiError, staffAuthService } from '@/services';
import './staff.css';

/*
 * A panel listing the two bootstrap staff logins, with their shared password and a
 * click-to-fill button, used to render at the bottom of this page.
 *
 * It is gone. It was guarded out of the production bundle, but "the credentials for this
 * bank's staff portal are printed on the staff portal" is a sentence that should not be
 * true of any build — and a guard is one careless edit away from not holding. Convenience
 * on a login screen is the wrong thing to optimise.
 *
 * The logins are in docs/OPEN-ITEMS.md.
 */

/**
 * Sign-in for bank staff.
 *
 * Its own page at its own URL, and it looks different from the customer one on purpose.
 * A member of staff should never be in any doubt about which system they are typing a
 * password into, and a customer who lands here by accident should see immediately that
 * it is not for them.
 *
 * No one-time code, for now, and that is a gap rather than a decision. Staff
 * credentials open other people's accounts, so they warrant more verification than a
 * customer's, not less. Tracked in docs/OPEN-ITEMS.md — deliberately not stated on the
 * page itself, where it would tell a stranger exactly what this screen is missing.
 */
export function StaffLoginPage(): ReactElement {
  const navigate = useNavigate();

  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<{ message: string; reference?: string } | null>(null);

  const onSubmit = async (): Promise<void> => {
    setFailure(null);
    setPending(true);

    try {
      await staffAuthService.signIn(identifier.trim(), password);
      // The password is no longer needed anywhere. Drop it before navigating.
      setPassword('');
      void navigate(STAFF_PATHS.dashboard, { replace: true });
    } catch (cause) {
      setPassword('');
      if (cause instanceof ApiError) {
        setFailure({
          message:
            cause.kind === 'unauthenticated'
              ? 'Those staff details are not correct. Please check and try again.'
              : cause.message,
          ...(cause.correlationId === undefined ? {} : { reference: cause.correlationId }),
        });
      } else {
        setFailure({ message: 'We could not sign you in. Please try again.' });
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="staff-login">
      <div className="staff-login__card">
        <p className="staff-login__eyebrow">{BRAND.SHORT}</p>
        <h1 className="staff-login__title">Staff sign-in</h1>
        <p className="staff-login__lead">
          For bank employees. If you are a customer, use the{' '}
          <Link to={PUBLIC_PATHS.login}>customer sign-in</Link> instead.
        </p>

        {failure !== null && (
          <Alert tone="error" title="We could not sign you in" reference={failure.reference}>
            {failure.message}
          </Alert>
        )}

        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            if (identifier.trim() !== '' && password !== '') void onSubmit();
          }}
        >
          <TextField
            label="Staff email"
            value={identifier}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            disabled={pending}
            onChange={(event) => {
              setIdentifier(event.target.value);
            }}
          />

          <PasswordField
            label="Password"
            autoComplete="current-password"
            value={password}
            disabled={pending}
            onChange={setPassword}
          />

          <Button type="submit" block loading={pending}>
            Sign in
          </Button>
        </form>

        <p className="staff-login__security">
          Everything you do here is recorded against your name. Creating an account for a
          customer who does not exist is the most damaging thing anyone can do from this
          screen, which is why a second person has to approve every one.
        </p>

        {/*
          This screen has no second factor, no device binding and no IP restriction, and
          that is the largest open gap in the portal — see docs/OPEN-ITEMS.md.
          It was a banner here. Announcing to whoever is looking at a bank's staff login
          that it is protected by a password alone is an invitation, so it now lives in
          the repository where the people who can fix it will read it.
        */}

      </div>
    </div>
  );
}
