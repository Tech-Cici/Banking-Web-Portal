import { useState, type ReactElement } from 'react';
import { AsyncPanel } from '@/components/AsyncPanel';
import {
  Alert,
  Button,
  ErrorNotice,
  EmptyState,
  PageHeader,
  Panel,
  PasswordField,
  StatusBadge,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { ApiError, securityService } from '@/services';
import { formatDateTime } from '@/utils/datetime';
import { PASSWORD_RULES, password as validatePassword } from '@/utils/validation';
import './account.css';

/**
 * Password, trusted browsers and sign-in history.
 *
 * Three things in one place because they answer one question — "is my account safe?" —
 * and someone who suspects it is not should not have to hunt through a settings tree
 * while they are worried.
 *
 * WHAT THIS SCREEN USED TO BE. It called `/security/devices`, `/security/events` and
 * `/security/password`, and not one of the three existed on the server: MSW answered all
 * three from arrays that start empty on every page load. So the history showed nothing
 * whatever had happened to the account, and "Remove" ended trust in a browser that had
 * never been trusted. There is a real API behind all three now.
 *
 * AND IT MADE FOUR CLAIMS THE SERVER NEVER SUPPORTED, all of them removed here:
 *
 * <ul>
 *   <li>Each row showed a `location`. The tables have never held one and this service has
 *       no geo-IP lookup — see V19 for the literal "Kigali, Rwanda" that `sign_ins`
 *       carried at four call sites.
 *   <li>The activity panel said it showed "sign-ins, failed attempts and changes to your
 *       payees". It showed sign-ins. Nothing records a failed attempt.
 *   <li>Each row had an outcome badge reading "ok" or "failed", and nothing could ever
 *       produce "failed".
 *   <li>Changing the password said "other devices stay signed in until their sessions
 *       end". It now revokes every trusted browser, which is the point of doing it.
 * </ul>
 *
 * TRUSTED BROWSERS ARE NOT SESSIONS, and this screen used to call them "Devices signed
 * in". A trusted browser is permission to skip the emailed code and it outlives every
 * session on that machine; removing one does not sign anybody out of anything. Getting
 * that wrong made "Remove" read as a panic button that it is not.
 *
 * The password fields are never pre-filled, never persisted, and are cleared the moment
 * the request finishes either way. A password sitting in component state after a failed
 * attempt is a password waiting to be read out of a heap snapshot.
 */
export function SecurityPage(): ReactElement {
  const [version, setVersion] = useState(0);

  const devices = useAsync(`devices:${String(version)}`, (signal) =>
    securityService.devices(signal),
  );
  const history = useAsync('sign-ins', (signal) => securityService.signIns(signal));

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  /*
   * The count from the server, not a boolean. "Changed" is only half of what happened:
   * the browsers that could skip the emailed code were signed out too, and the customer
   * has to be told or their next sign-in asks for a code out of nowhere.
   */
  const [changed, setChanged] = useState<number | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [revoking, setRevoking] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<unknown>(null);

  const strength = next === '' ? undefined : validatePassword(next);
  const mismatch =
    confirm !== '' && next !== confirm ? 'The two passwords do not match.' : undefined;

  const ready =
    current !== '' && next !== '' && confirm !== '' && strength === undefined && mismatch === undefined;

  const clearSecrets = (): void => {
    setCurrent('');
    setNext('');
    setConfirm('');
  };

  const changePassword = (): void => {
    setBusy(true);
    setFailure(null);
    setFieldErrors({});
    setChanged(null);

    void securityService
      .changePassword(current, next)
      .then((result) => {
        setChanged(result.browsersSignedOut);
        /* The trusted-browser list just changed underneath us: every row is now revoked.
           Reloading it is what makes the page agree with itself. */
        setVersion((count) => count + 1);
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiError) {
          setFieldErrors(Object.fromEntries(cause.fieldErrors.map((v) => [v.field, v.message])));
        }
        setFailure(cause);
      })
      .finally(() => {
        // Cleared on success AND on failure. A retry means typing it again, deliberately.
        clearSecrets();
        setBusy(false);
      });
  };

  const revoke = (id: string): void => {
    setRevoking(id);
    setRevokeError(null);

    void securityService
      .revokeDevice(id)
      .then(() => {
        setVersion((count) => count + 1);
      })
      .catch((cause: unknown) => {
        setRevokeError(cause);
      })
      .finally(() => {
        setRevoking(null);
      });
  };

  return (
    <article className="account__page">
      <PageHeader
        title="Security"
        lead="Your password, the devices signed in, and what has happened on your account."
      />

      <Alert tone="warning" title="The bank will never ask for these">
        Nobody from the bank will ever ask for your password or a one-time code — not by
        phone, not by SMS, not by email. If someone does, they are not from the bank.
      </Alert>

      <div className="account__grid">
        <Panel title="Change your password">
          {changed !== null && (
            <Alert tone="success" title="Password changed">
              <p>Use the new one next time you sign in.</p>
              {/*
                SAID BECAUSE IT HAPPENED TO THEM. The old copy here promised the opposite
                — "other devices stay signed in until their sessions end" — which was true
                of a feature that did nothing. Changing a password now withdraws trust from
                every browser that could skip the emailed code, and a customer not told
                that meets a code request on their next sign-in with no explanation.
              */}
              {changed > 0 && (
                <p className="money__note">
                  {changed === 1
                    ? 'One browser that could sign in without an emailed code has been signed out.'
                    : `${String(changed)} browsers that could sign in without an emailed code have been signed out.`}{' '}
                  You will be asked for a code next time you sign in on them.
                </p>
              )}
              <p className="money__note">We have emailed you to say the password changed.</p>
            </Alert>
          )}

          {failure !== null && (
            <ErrorNotice error={failure} action="change your password" />
          )}

          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (ready) changePassword();
            }}
          >
            <PasswordField
              label="Current password"
              autoComplete="current-password"
              value={current}
              disabled={busy}
              error={fieldErrors['currentPassword']}
              onChange={setCurrent}
            />

            <PasswordField
              label="New password"
              autoComplete="new-password"
              rules={PASSWORD_RULES}
              value={next}
              disabled={busy}
              error={fieldErrors['newPassword']}
              onChange={setNext}
            />

            <PasswordField
              label="Repeat the new password"
              autoComplete="new-password"
              value={confirm}
              disabled={busy}
              error={mismatch}
              onChange={setConfirm}
            />

            <Button type="submit" loading={busy} disabled={!ready}>
              Change password
            </Button>
          </form>
        </Panel>

        <AsyncPanel
          title="Browsers that skip the emailed code"
          subtitle="Remove anything you do not recognise, then change your password."
          state={devices.state}
          reload={devices.reload}
        >
          {(list) => (
            <>
              {revokeError !== null && (
                <ErrorNotice error={revokeError} action="sign that device out" />
              )}

              {list.length === 0 ? (
                <EmptyState message="No browser can skip the emailed code. You will be sent one every time you sign in." />
              ) : (
                <ul className="dash__rows">
                  {list.map((device) => (
                    <li key={device.id} className="dash__row">
                      <div className="dash__row-main">
                        {/*
                          THE LABEL OR THE DATE, never an invented name. `device` is absent
                          when the browser sent no recognisable User-Agent, and the honest
                          fallback is to identify the row by when it was trusted — which is
                          something the customer can actually check.
                        */}
                        <p className="dash__row-title">
                          {device.device ?? 'A browser we could not identify'}
                        </p>
                        <p className="dash__row-meta">
                          Trusted {formatDateTime(device.trustedAt)}
                          {device.lastUsedAt !== undefined &&
                            ` · last used ${formatDateTime(device.lastUsedAt)}`}
                        </p>
                        {!device.revoked && (
                          <p className="dash__row-meta">
                            Stops asking for a code until {formatDateTime(device.expiresAt)}
                          </p>
                        )}
                      </div>
                      <div className="dash__row-side">
                        {/*
                          REVOKED ROWS STAY, labelled. "This browser was trusted and then it
                          was not" is the history somebody investigating an unauthorised
                          sign-in needs, and a customer who just pressed Remove should be
                          able to see that it took.
                        */}
                        {device.revoked ? (
                          <StatusBadge tone="neutral" label="Removed" />
                        ) : device.current ? (
                          /*
                            NO REMOVE BUTTON ON THIS BROWSER, matching the server, which
                            refuses it. It would work, and would then require an emailed
                            code from a machine the customer may have just told us they no
                            longer control.
                          */
                          <StatusBadge tone="info" label="This browser" />
                        ) : (
                          <Button
                            variant="secondary"
                            loading={revoking === device.id}
                            onClick={() => {
                              revoke(device.id);
                            }}
                          >
                            Remove
                          </Button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </AsyncPanel>

        {/*
          "YOUR SIGN-INS", not "recent security activity". The old subtitle promised
          "sign-ins, failed attempts and changes to your payees" and the panel showed
          sign-ins — a heading that overstates what a security log covers is worse than a
          narrow one, because a customer reads a clean list as evidence that nothing else
          happened. Failed attempts need their own table and a retention decision; see
          docs/OPEN-ITEMS.md.
        */}
        <AsyncPanel
          title="Your sign-ins"
          subtitle="The last twenty, newest first. Tell the bank about anything you do not recognise."
          state={history.state}
          reload={history.reload}
        >
          {(list) =>
            list.length === 0 ? (
              <EmptyState message="No sign-ins are recorded yet." />
            ) : (
              <ul className="dash__rows">
                {list.map((signIn) => (
                  /*
                    KEYED ON THE TIMESTAMP, because the row has no id and should not —
                    handing out the primary key of a sign-in record buys the client
                    nothing and there is no endpoint that takes one. Two sign-ins cannot
                    share an instant for one customer.
                  */
                  <li key={signIn.at} className="dash__row">
                    <div className="dash__row-main">
                      <p className="dash__row-title">{formatDateTime(signIn.at)}</p>
                      <p className="dash__row-meta">
                        {signIn.method}
                        {/* Nothing at all when the client sent no User-Agent. */}
                        {signIn.device !== undefined && ` · ${signIn.device}`}
                      </p>
                    </div>
                  </li>
                ))}
              </ul>
            )
          }
        </AsyncPanel>
      </div>
    </article>
  );
}
