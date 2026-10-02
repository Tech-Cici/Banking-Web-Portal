import { useState, type ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, PasswordField } from '@/components/ui';
import { useSession } from '@/hooks/useSession';
import { RETAIL_PATHS } from '@/routes/paths';
import { ApiError, temporaryPasswordService } from '@/services';
import { PASSWORD_RULES, password as validatePassword } from '@/utils/validation';
import './auth.css';

/**
 * Where a temporary password lands.
 *
 * The customer is signed in when they reach this screen, and this is the only thing
 * that session can do. The password they used was typed by a member of bank staff and
 * written on a slip of paper — until it is replaced, the account has a password the
 * customer did not choose and at least one other person knows.
 *
 * It asks for the temporary password again even though the session already exists. The
 * session was opened by whoever held that slip; asking again is what ties the change to
 * the person who is meant to have it.
 *
 * There is no "skip" and no "remind me later". A prompt that can be dismissed is a
 * prompt that is dismissed, and the staff-known password would stay live.
 */
export function NewPasswordPage(): ReactElement {
  const navigate = useNavigate();
  const session = useSession();

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ message: string; reference?: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const policy = next === '' ? undefined : validatePassword(next);
  const mismatch =
    confirm !== '' && next !== confirm ? 'The two passwords do not match.' : undefined;

  const ready =
    current !== '' && next !== '' && confirm !== '' && policy === undefined && mismatch === undefined;

  const submit = (): void => {
    setBusy(true);
    setFailure(null);
    setFieldErrors({});

    void temporaryPasswordService
      .change({ currentPassword: current, newPassword: next })
      .then(async () => {
        /*
         * Reload the session before navigating. The server has cleared the
         * change-required flag, and going to the dashboard on a stale session would
         * bounce straight back here.
         */
        const user = await session.refreshAndGet();
        void navigate(user?.userType === 'CORPORATE' ? '/corporate/dashboard' : RETAIL_PATHS.dashboard, {
          replace: true,
        });
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiError) {
          setFieldErrors(Object.fromEntries(cause.fieldErrors.map((v) => [v.field, v.message])));
          setFailure({
            message: cause.message,
            ...(cause.correlationId === undefined ? {} : { reference: cause.correlationId }),
          });
        } else {
          setFailure({ message: 'We could not change your password. Please try again.' });
        }
      })
      .finally(() => {
        // Cleared whatever happened. A retry means typing them again, deliberately.
        setCurrent('');
        setNext('');
        setConfirm('');
        setBusy(false);
      });
  };

  return (
    <div>
      <h1 className="auth__title">Choose your password</h1>
      <p className="auth__lead">
        Your account is approved. Before you can use it, replace the temporary password the
        bank gave you with one only you know.
      </p>

      <Alert tone="info" title="Why you have to do this now">
        The password you just used was typed by a member of bank staff, so it is not
        private. Once you set your own, the temporary one stops working.
      </Alert>

      {failure !== null && (
        <Alert tone="error" title="We could not change your password" reference={failure.reference}>
          {failure.message}
        </Alert>
      )}

      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) submit();
        }}
      >
        <PasswordField
          label="Temporary password"
          autoComplete="current-password"
          hint="The one the bank gave you."
          value={current}
          disabled={busy}
          error={fieldErrors['currentPassword']}
          onChange={setCurrent}
        />

        <PasswordField
          label="Your new password"
          autoComplete="new-password"
          rules={PASSWORD_RULES}
          value={next}
          disabled={busy}
          error={fieldErrors['newPassword']}
          onChange={setNext}
        />

        <PasswordField
          label="Repeat your new password"
          autoComplete="new-password"
          value={confirm}
          disabled={busy}
          error={mismatch}
          onChange={setConfirm}
        />

        <Button type="submit" block loading={busy} disabled={!ready}>
          Set my password and continue
        </Button>
      </form>

      <p className="auth__security">
        The bank will never ask for your password or a one-time code by phone, SMS or email.
        If someone asks you for the temporary password, they are not from the bank.
      </p>
    </div>
  );
}
