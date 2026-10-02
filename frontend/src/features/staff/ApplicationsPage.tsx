import { useState, type ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  DataTable,
  EmptyState,
  ErrorNotice,
  PageHeader,
  ReviewPanel,
  Select,
  Skeleton,
  TextField,
  StatusBadge,
  humaniseStatus,
  toneForStatus,
  type Column,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { routeTo, STAFF_PATHS } from '@/routes/paths';
import { adminService, ApiError, newIdempotencyKey } from '@/services';
import {
  ACCOUNT_CURRENCIES,
  ACCOUNT_TYPES,
  describeAccountMasks,
  describeCustomerAccounts,
  type Application,
  type CreatedCustomer,
  type NewAccount,
} from '@/types/admin';
import { formatDate, formatDateTime } from '@/utils/datetime';
import { useStaffSession } from './useStaffSession';
import './staff.css';

const KIND_LABELS: Readonly<Record<string, string>> = {
  PERSONAL: 'Personal customer',
  BUSINESS: 'Business',
  JOIN_BUSINESS: 'Joining a business',
};

/**
 * Everyone who has registered, and what stage they are at.
 *
 * Newest first, because the job is clearing a queue. The status column is the whole
 * point of the screen: SUBMITTED needs an admin, ACCOUNT_CREATED is waiting on a
 * manager, and anything else is done.
 */
export function ApplicationsPage(): ReactElement {
  const { state, reload } = useAsync('applications', (signal) =>
    adminService.applications(undefined, signal),
  );

  const columns: readonly Column<Application>[] = [
    {
      key: 'who',
      header: 'Applicant',
      render: (row) => (
        <>
          <Link to={routeTo.staffApplication(row.id)}>{row.displayName}</Link>
          <br />
          <span style={{ color: 'var(--color-text-muted)' }}>{row.email}</span>
        </>
      ),
    },
    {
      key: 'kind',
      header: 'Applying as',
      render: (row) => KIND_LABELS[row.kind] ?? row.kind,
    },
    {
      key: 'ref',
      header: 'Reference',
      secondary: true,
      render: (row) => <span className="numeric">{row.reference}</span>,
    },
    {
      key: 'when',
      header: 'Registered',
      secondary: true,
      render: (row) => formatDateTime(row.submittedAt),
    },
    {
      key: 'status',
      header: 'Stage',
      render: (row) => (
        <>
          <StatusBadge tone={toneForStatus(row.status)} label={humaniseStatus(row.status)} />
          {row.status === 'SUBMITTED' && (
            <p className="dash__row-meta">Needs an account creating.</p>
          )}
          {row.status === 'ACCOUNT_CREATED' && (
            <p className="dash__row-meta">Waiting for a manager.</p>
          )}
        </>
      ),
    },
  ];

  return (
    <article>
      <PageHeader
        title="Registrations"
        lead="Everyone who has registered through the portal. Open one to create their account."
      />

      {state.status === 'loading' && <Skeleton rows={6} label="Loading registrations" />}

      {state.status === 'error' && (
        <Alert tone="error" title="We could not load the registrations" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Button variant="secondary" onClick={reload}>
              Try again
            </Button>
          </p>
        </Alert>
      )}

      {state.status === 'ready' &&
        (state.data.length === 0 ? (
          <EmptyState message="Nobody has registered yet.">
            <p className="dash__note">
              Registrations arrive here as soon as someone completes the form on the public site.
            </p>
          </EmptyState>
        ) : (
          <DataTable
            caption="Registrations, newest first"
            columns={columns}
            rows={state.data}
            rowKey={(row) => row.id}
            rowTone={(row) =>
              row.status === 'SUBMITTED'
                ? 'warning'
                : row.status === 'REJECTED'
                  ? 'error'
                  : 'default'
            }
          />
        ))}
    </article>
  );
}

/**
 * One registration, and the form that turns it into an account.
 *
 * The temporary password appears exactly once, in the panel that follows a successful
 * creation, and there is no way back to it. That is deliberate and it is stated on the
 * screen: an admin who can look a password up later can use it, and the audit log cannot
 * tell that apart from the customer signing in.
 */
/** One row of the create-account form: a {@link NewAccount} plus a key for React. */
interface AccountRow extends NewAccount {
  readonly id: string;
}

export function ApplicationDetailPage(): ReactElement {
  const { id = '' } = useParams();
  const session = useStaffSession();

  const { state } = useAsync(`application:${id}`, (signal) => adminService.application(id, signal));

  /*
   * WHAT THIS FORM RECORDS.
   *
   * The accounts this person already holds at Zigama, as the administrator reads them off
   * the bank's own records, plus what each one starts with. It does not open accounts and
   * it does not verify them — the assigning administrator's name is the record that they
   * were checked, exactly as the approving manager's name records the identity check.
   *
   * IT USED TO RECORD NOTHING AT ALL. The screen posted an account type, a currency and
   * an opening balance to an endpoint with no `@RequestBody`, so Spring bound the path
   * variable and discarded the JSON: the administrator chose "Current account", saw a
   * success message, and nothing was stored — there was no table it could have gone in.
   *
   * THE OPENING BALANCE IS REAL NOW. It was removed once on the grounds that money the
   * branch has taken belongs in the core banking ledger and a figure here would be an
   * unreconciled second copy. The bank decided the portal holds the balances. The
   * divergence risk is unchanged and is written down in docs/OPEN-ITEMS.md; what this
   * screen must never do is calculate a figure rather than submit the one that was typed.
   *
   * THE ACCOUNT TYPES ARE A PLACEHOLDER the bank has not confirmed. See
   * ACCOUNT_TYPES in src/types/admin.ts and docs/OPEN-ITEMS.md.
   */
  /*
   * A LIST, not a dropdown of pairings.
   *
   * "A current account and a savings account" is two accounts, and an FCY account is one
   * of these with a currency that is not RWF. A single field with values like
   * CURRENT_AND_SAVINGS could not express closing one of the pair, could not be counted,
   * and would need a new value for every combination somebody asked for.
   */
  /*
   * A STABLE ID PER ROW, and it never leaves this component.
   *
   * The rows used to be keyed by array index. On a list somebody can delete from the
   * middle of, that is a real defect and not a lint preference: React matches the old
   * row 1 to the new row 1, so removing the first of three accounts leaves the second
   * row's DOM node — its focus, its selection, its scroll position — showing the third
   * row's data. With controlled inputs the VALUES follow the state, which is exactly what
   * makes it hard to spot: it looks right and behaves oddly.
   *
   * `id` is stripped before the request is built, because it is a rendering concern and
   * has no meaning to the server.
   */
  const [accounts, setAccounts] = useState<readonly AccountRow[]>([
    {
      id: crypto.randomUUID(),
      accountNumber: '',
      accountType: 'CURRENT',
      currency: 'RWF',
      openingBalance: '',
    },
  ]);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [created, setCreated] = useState<CreatedCustomer | null>(null);

  /**
   * The server's per-field complaints, indexed by the input they belong to.
   *
   * WHY THIS EXISTS. The error the screen showed was "Some of the details supplied are
   * not valid. Check the highlighted boxes above" — while highlighting nothing. The
   * backend had said exactly which row and which field, in `fieldErrors`, and this page
   * threw all of it away. Telling somebody to look at a highlight that is not there is
   * worse than saying nothing: they check every box, find no mark, and conclude the
   * screen is broken.
   *
   * Spring names a nested field `accounts[0].accountNumber`, which is what this keys on.
   */
  const fieldErrors: Readonly<Record<string, string>> =
    failure instanceof ApiError
      ? Object.fromEntries(failure.fieldErrors.map((entry) => [entry.field, entry.message]))
      : {};

  const errorFor = (index: number, field: keyof NewAccount): string | undefined =>
    fieldErrors[`accounts[${String(index)}].${field}`];

  /*
   * Anything the server complained about that does not belong to a visible input — a
   * whole-list rule such as "enter at least one account", or a field this screen does not
   * know about. It must still be shown somewhere, or the request fails for a reason
   * nobody can see.
   */
  const unplacedErrors = Object.entries(fieldErrors).filter(
    ([field]) =>
      !/^accounts\[\d+]\.(accountNumber|accountType|currency|openingBalance)$/.test(field),
  );

  const updateAccount = (index: number, patch: Partial<AccountRow>): void => {
    setAccounts((current) =>
      current.map((entry, position) => (position === index ? { ...entry, ...patch } : entry)),
    );
  };

  /** One key for this creation, reused if the request has to be retried. */
  const [key] = useState(() => newIdempotencyKey());

  const create = (): void => {
    setBusy(true);
    setFailure(null);

    void adminService
      // The row ids are a rendering concern; the server is sent the accounts alone.
      .createAccount(id, { accounts: accounts.map(({ id: _row, ...account }) => account) }, key)
      .then(setCreated)
      .catch(setFailure)
      .finally(() => {
        setBusy(false);
      });
  };

  if (state.status === 'loading') return <Skeleton rows={6} label="Loading the registration" />;

  if (state.status === 'error') {
    return (
      <article className="staff__narrow">
        <PageHeader
          title="Registration"
          crumbs={[{ label: 'Registrations', to: STAFF_PATHS.applications }]}
        />
        <Alert tone="error" title="We could not open that registration" reference={state.reference}>
          <p>{state.message}</p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            <Link to={STAFF_PATHS.applications}>Back to registrations</Link>
          </p>
        </Alert>
      </article>
    );
  }

  const application = state.data;

  /* ------------------------------------------ what happens after creating */

  if (created !== null) {
    return (
      <article className="staff__narrow">
        <PageHeader
          title="Account created"
          crumbs={[{ label: 'Registrations', to: STAFF_PATHS.applications }]}
        />

        <Alert tone="success" title={`Account created for ${created.customer.fullName}`}>
          It is not usable yet. A manager has to approve it, and the customer is emailed their
          temporary password at that point — not before.
        </Alert>

        {/*
          There is deliberately no password on this screen any more.

          It used to show one, once, for the administrator to hand over in person. The
          bank decided the temporary password should be emailed instead, so it is
          generated when a manager approves and exists only in that email. Nobody at the
          bank sees a working credential for a customer's account, including whoever
          created it — which is a stronger position than the one this screen used to
          leave them in, and worth not quietly undoing.
        */}
        <Alert tone="info" title="You will not see a password">
          The temporary password is created when the account is approved and goes straight to the
          customer by email. There is nothing here to write down, and nothing for anyone at the bank
          to look up later.
        </Alert>

        <ReviewPanel
          caption="What was created"
          rows={[
            { label: 'Customer', value: created.customer.fullName },
            { label: 'Customer number', value: created.customer.customerNumber },
            { label: 'Account', value: describeAccountMasks(created.customer.accountMasks) },
            {
              label: 'Profile',
              value: created.customer.userType === 'CORPORATE' ? 'Business' : 'Personal',
            },
            { label: 'Status', value: humaniseStatus(created.customer.status) },
            {
              /*
               * Read back from the RESPONSE, not from the form state.
               *
               * The administrator should see what the bank recorded rather than what
               * they believe they typed. The two were silently different for as long as
               * the endpoint was discarding the request body, and nothing on this screen
               * would have shown it.
               */
              label: 'Accounts',
              value: describeCustomerAccounts(created.customer.accounts),
            },
          ]}
        />


        <div className="money__actions">
          <Link to={STAFF_PATHS.applications} className="dash__action">
            Back to registrations
          </Link>
        </div>
      </article>
    );
  }

  /* ---------------------------------------------- the creation form */

  const alreadyHandled = application.status !== 'SUBMITTED';

  /*
   * WHETHER THIS SCREEN'S ACTION CAN SUCCEED AT ALL — which is what the server says, and
   * this is a copy of the server's rule rather than a rule of its own.
   *
   * It said PERSONAL, because for a while a personal login was the only kind the service
   * could build. Creating one for a company is now implemented: the company is admitted
   * as a company, the accounts are held by IT, and the named contact on the application
   * becomes its first administrator.
   *
   * Joining an existing business is still not, and is the one case left here. That flow
   * needs to establish that somebody an existing company has never mentioned may act for
   * it, and nothing in the portal can establish that yet.
   *
   * The reason this gate exists at all: the screen used to offer the form for every
   * kind. An administrator read the details, typed an account number, chose a type,
   * typed an opening balance, pressed the button, and got "We could not create that
   * account" — after the work, for a reason that was never about anything they had
   * entered. A form that cannot succeed should not be on the page.
   */
  const canCreateLogin = application.kind !== 'JOIN_BUSINESS';

  /** A company application: the accounts will belong to the company, not to the contact. */
  const isCompany = application.kind === 'BUSINESS';

  return (
    <article className="staff__narrow">
      <PageHeader
        title={application.displayName}
        lead={`${KIND_LABELS[application.kind] ?? application.kind} · ${application.reference}`}
        crumbs={[{ label: 'Registrations', to: STAFF_PATHS.applications }]}
        action={
          <StatusBadge
            tone={toneForStatus(application.status)}
            label={humaniseStatus(application.status)}
          />
        }
      />

      {failure !== null && (
        <>
          <ErrorNotice error={failure} action="create that account" />
          {unplacedErrors.length > 0 && (
            <Alert tone="error" title="The server refused these">
              <ul>
                {unplacedErrors.map(([field, message]) => (
                  <li key={field}>{message}</li>
                ))}
              </ul>
            </Alert>
          )}
        </>
      )}

      {!application.emailVerified && !alreadyHandled && (
        <Alert tone="warning" title="This email address has not been confirmed">
          The applicant never entered a code sent to it, so nobody has shown they can read it.
          Everything after this point — the approval notice, and arranging the temporary password —
          goes to that address. Confirm it another way before you create the login.
        </Alert>
      )}

      <ReviewPanel
        caption="What they submitted"
        rows={[
          { label: 'Name', value: application.displayName },
          {
            label: 'Email',
            /*
             * Whether the address was confirmed sits next to the address itself, not in
             * a badge elsewhere on the page. This is the line the admin reads before
             * deciding to create a login, and an unconfirmed address means the approval
             * notice may go nowhere — or to somebody else.
             */
            value: application.emailVerified
              ? `${application.email} — confirmed by the applicant`
              : `${application.email} — NOT confirmed`,
          },
          { label: 'Phone', value: application.phone },
          { label: 'Registered', value: formatDateTime(application.submittedAt) },

          /*
           * The identity details, for the admin to compare against the bank's record.
           * Shown in full rather than masked: checking them IS this screen's job, and a
           * masked national ID cannot be checked against anything. The screen is behind
           * staff authentication and every action on it is recorded against a name.
           */
          ...(application.accountNumber === undefined
            ? []
            : [{ label: 'Account number', value: application.accountNumber }]),
          ...(application.nationalId === undefined
            ? []
            : [{ label: 'National ID number', value: application.nationalId }]),
          ...(application.dateOfBirth === undefined
            ? []
            : [{ label: 'Date of birth', value: formatDate(application.dateOfBirth) }]),

          // Business and company-join applications still carry free-form fields.
          ...(application.details ?? []).map((field) => ({
            label: field.label,
            value: field.value,
          })),
        ]}
      />

      {alreadyHandled && (
        <Alert tone="info" title="Already processed">
          {application.status === 'ACCOUNT_CREATED'
            ? 'An account has been created for this registration and is waiting for a manager to approve it.'
            : application.status === 'APPROVED'
              ? 'This account has been approved and the customer has been emailed.'
              : `This application was rejected. ${application.rejectionReason ?? ''}`}
        </Alert>
      )}

      {!alreadyHandled && !session.isAdmin && (
        <Alert tone="info" title="Only an administrator can create an account">
          You can see this registration, but creating the login is an administrator&rsquo;s job.
          Your part comes after: approving what they created.
        </Alert>
      )}

      {!alreadyHandled && session.isAdmin && !canCreateLogin && (
        <Alert tone="info" title="Joining an existing business is not set up here yet">
          <p>
            Everything above is stored and nothing is lost &mdash; this application stays in the
            queue. What the portal cannot do yet is give this person access to a company that
            already banks here, because that needs the company to confirm they may act for it,
            and there is no way to ask it for that confirmation yet.
          </p>
          <p style={{ marginTop: 'var(--space-3)' }}>
            Arrange it with the corporate banking team, using the details above. Nothing on this
            screen needs to be filled in first.
          </p>
        </Alert>
      )}

      {!alreadyHandled && session.isAdmin && canCreateLogin && (
        <>
          {isCompany ? (
            <Alert tone="warning" title="This creates the company and its first administrator">
              <p>
                The accounts below are entered against <strong>{application.displayName}</strong>
                {' '}and belong to the company, not to any one person. {application.displayName}{' '}
                is admitted as a customer in its own right, so removing somebody&rsquo;s access
                later leaves the accounts where they are.
              </p>
              <p style={{ marginTop: 'var(--space-3)' }}>
                The login goes to the named contact on the application, who becomes the
                company&rsquo;s first administrator and can be given the company&rsquo;s accounts
                to operate. Check their identification, and the company&rsquo;s registration
                documents, against what is on screen first.
              </p>
              <p style={{ marginTop: 'var(--space-3)' }}>
                The other signatories listed above get <strong>nothing</strong> from this. Adding
                them, and setting what each of them may approve and up to how much, is not
                something the portal can do yet — arrange it with the corporate banking team.
              </p>
            </Alert>
          ) : (
            <Alert tone="warning" title="Check the identity documents first">
              Creating this account issues working credentials in this person&rsquo;s name. Confirm
              their identification against what is on screen before you continue — a manager will
              approve your work, but they are checking the decision, not the documents.
            </Alert>
          )}

          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              create();
            }}
          >
            <fieldset className="reg__fieldset">
              <legend className="reg__legend">
                {isCompany ? 'The company\u2019s accounts' : 'Accounts to open'}
              </legend>

              <p className="dash__note">
                {isCompany
                  ? `One row per account ${application.displayName} holds, read from the bank's records. A company with a current and a savings account needs two rows; a foreign-currency account is a row whose currency is not RWF.`
                  : "One row per account the customer holds, read from the bank's records. Somebody with a current and a savings account needs two rows; a foreign-currency account is a row whose currency is not RWF."}
              </p>

              {accounts.map((entry, index) => (
                <div className="staff__account-row" key={entry.id}>
                  <TextField
                    label={`Account ${String(index + 1)} number`}
                    value={entry.accountNumber}
                    error={errorFor(index, 'accountNumber')}
                    required
                    /*
                     * `inputMode` rather than type="number": a number input strips leading
                     * zeros, and an account number is a string of digits, not a quantity.
                     */
                    inputMode="numeric"
                    autoComplete="off"
                    disabled={busy}
                    onChange={(event) => {
                      updateAccount(index, { accountNumber: event.target.value });
                    }}
                  />

                  <Select
                    label={`Account ${String(index + 1)} type`}
                    value={entry.accountType}
                    disabled={busy}
                    options={ACCOUNT_TYPES.map((type) => ({
                      value: type.value,
                      label: type.label,
                    }))}
                    error={errorFor(index, 'accountType')}
                    hint={ACCOUNT_TYPES.find((type) => type.value === entry.accountType)?.hint}
                    onChange={(event) => {
                      updateAccount(index, {
                        accountType: event.target.value as NewAccount['accountType'],
                      });
                    }}
                  />

                  <Select
                    label="Currency"
                    value={entry.currency}
                    disabled={busy}
                    error={errorFor(index, 'currency')}
                    options={ACCOUNT_CURRENCIES.map((code) => ({ value: code, label: code }))}
                    onChange={(event) => {
                      updateAccount(index, { currency: event.target.value });
                    }}
                  />

                  <TextField
                    label="Opening balance"
                    value={entry.openingBalance}
                    error={errorFor(index, 'openingBalance')}
                    hint={`What this account holds today, in ${entry.currency}. Leave blank for zero.`}
                    /*
                     * `inputMode="decimal"` rather than type="number", and the value stays
                     * a STRING from this box all the way to the database.
                     *
                     * A number input hands back a JS number, and a JS number cannot hold
                     * 1234.30 exactly — it becomes 1234.2999999999999, which is a rounding
                     * decision nobody made on somebody's opening balance. Nothing on this
                     * screen parses, totals or reformats it; the server decides whether
                     * the currency even allows decimals (RWF does not).
                     */
                    inputMode="decimal"
                    autoComplete="off"
                    disabled={busy}
                    onChange={(event) => {
                      updateAccount(index, { openingBalance: event.target.value });
                    }}
                  />

                  {accounts.length > 1 && (
                    <Button
                      variant="tertiary"
                      disabled={busy}
                      onClick={() => {
                        setAccounts((current) =>
                          current.filter((_, position) => position !== index),
                        );
                      }}
                    >
                      Remove
                    </Button>
                  )}
                </div>
              ))}

              <Button
                variant="secondary"
                /*
                 * Ten matches the server's @Size cap. Letting the screen submit more
                 * than the API accepts turns a considered limit into a validation error
                 * the administrator cannot act on.
                 */
                disabled={busy || accounts.length >= 10}
                onClick={() => {
                  setAccounts((current) => [
                    ...current,
                    {
                      id: crypto.randomUUID(),
                      accountNumber: '',
                      accountType: 'SAVINGS',
                      currency: 'RWF',
                      openingBalance: '',
                    },
                  ]);
                }}
              >
                Add another account
              </Button>
            </fieldset>

            <Alert tone="info" title="Check these against the bank's records">
              {isCompany
                ? `Nothing here verifies that these accounts exist, belong to ${application.displayName}, or hold the balances you have typed — you have. Your name goes on the record and on every opening-balance entry, and the company will see exactly these accounts and these balances when its administrator signs in.`
                : 'Nothing here verifies that these accounts exist, belong to this person, or hold the balances you have typed — you have. Your name goes on the record and on the opening-balance entry, and the customer will see exactly these accounts and these balances when they sign in.'}
            </Alert>

            <div className="money__actions">
              <Button
                type="submit"
                loading={busy}
                /*
                 * Every row needs a number. The server refuses a blank one anyway; this
                 * means the administrator finds out before the request rather than after.
                 */
                disabled={accounts.some((entry) => entry.accountNumber.trim() === '')}
              >
                {isCompany
                  ? 'Create the company and its first login'
                  : 'Create the login and assign these accounts'}
              </Button>
              <Link to={STAFF_PATHS.applications} className="money__link">
                Cancel
              </Link>
            </div>
          </form>
        </>
      )}
    </article>
  );
}
