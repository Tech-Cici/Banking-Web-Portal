import { useState, type ReactElement } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Alert, Button, PageHeader, Select, TextField } from '@/components/ui';
import { RETAIL_PATHS } from '@/routes/paths';
import { ApiError, beneficiaryService } from '@/services';
import type { Beneficiary } from '@/types/banking';
import './products.css';

const TYPES: readonly { value: Beneficiary['beneficiaryType']; label: string; provider: string }[] =
  [
    { value: 'INTERNAL', label: 'An account at this bank', provider: 'Same bank' },
    { value: 'DOMESTIC', label: 'Another bank in Rwanda', provider: '' },
    { value: 'WALLET', label: 'A mobile money wallet', provider: '' },
    { value: 'INTERNATIONAL', label: 'A bank abroad', provider: '' },
  ];

const PROVIDERS: Readonly<Record<string, readonly string[]>> = {
  INTERNAL: ['Same bank'],
  DOMESTIC: ['Bank of Kigali', 'Equity Bank', 'I&M Bank', 'Ecobank', 'Cogebanque'],
  WALLET: ['MTN', 'Airtel'],
  INTERNATIONAL: ['SWIFT correspondent'],
};

/**
 * Adds a payee.
 *
 * Two things are deliberate here.
 *
 * The number is typed TWICE and the two must match. Copy-paste hides a transposed digit;
 * typing it again catches it. The bank cannot recall money sent to a valid account that
 * simply belongs to the wrong person, so this is the last cheap place to catch it.
 *
 * The hold is stated BEFORE the form, not after submitting. Someone being coached through
 * this by a fraudster needs to hit the delay as a fact about the bank, not as a surprise
 * they can be talked past.
 *
 * AND IT IS STATED AS WHAT IT IS: a member of staff comparing the name typed here with the
 * name the bank holds for that account. This page used to call it a "cooling-off period",
 * which describes a clock — and no clock existed, so a customer who waited got nothing.
 * Naming the real control also makes the warning harder to talk past: "the bank is
 * checking the name on that account" is a specific thing a fraudster has to explain away.
 */
export function BeneficiaryNewPage(): ReactElement {
  const navigate = useNavigate();

  const [type, setType] = useState<Beneficiary['beneficiaryType']>('INTERNAL');
  const [provider, setProvider] = useState('Same bank');
  const [name, setName] = useState('');
  const [destination, setDestination] = useState('');
  const [confirmDestination, setConfirmDestination] = useState('');
  const [currency, setCurrency] = useState('RWF');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ message: string; reference?: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const mismatch =
    confirmDestination !== '' &&
    destination.replace(/\s+/g, '') !== confirmDestination.replace(/\s+/g, '')
      ? 'The two numbers do not match. Check them both.'
      : undefined;

  const ready =
    name.trim() !== '' &&
    destination.trim() !== '' &&
    mismatch === undefined &&
    confirmDestination !== '';

  const submit = async (): Promise<void> => {
    setBusy(true);
    setFailure(null);
    setFieldErrors({});

    try {
      await beneficiaryService.create({
        name: name.trim(),
        beneficiaryType: type,
        provider,
        destination: destination.replace(/\s+/g, ''),
        currency,
      });
      void navigate(RETAIL_PATHS.beneficiaries, { replace: true });
    } catch (cause) {
      if (cause instanceof ApiError) {
        setFieldErrors(Object.fromEntries(cause.fieldErrors.map((v) => [v.field, v.message])));
        setFailure({
          message: cause.message,
          ...(cause.correlationId === undefined ? {} : { reference: cause.correlationId }),
        });
      } else {
        setFailure({ message: 'We could not add that payee.' });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <article className="products__narrow">
      <PageHeader
        title="Add a payee"
        crumbs={[{ label: 'Beneficiaries', to: RETAIL_PATHS.beneficiaries }]}
      />

      <Alert tone="info" title="A new payee cannot be paid straight away">
        <p>
          A member of our staff compares the name you give below with the name we hold for that
          account. You cannot send money to the payee until they have, and we will email you as soon
          as it is done.
        </p>
        <p style={{ marginTop: 'var(--space-2)' }}>
          If someone is pressing you to add an account and send money immediately, stop and call the
          bank on the number printed on your card.
        </p>
      </Alert>

      {failure !== null && (
        <Alert tone="error" title="We could not add that payee" reference={failure.reference}>
          {failure.message}
        </Alert>
      )}

      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) void submit();
        }}
      >
        <Select
          label="Where is the money going?"
          value={type}
          disabled={busy}
          options={TYPES.map((entry) => ({ value: entry.value, label: entry.label }))}
          onChange={(event) => {
            const next = event.target.value as Beneficiary['beneficiaryType'];
            setType(next);
            setProvider(PROVIDERS[next]?.[0] ?? '');
            setCurrency(next === 'INTERNATIONAL' ? 'USD' : 'RWF');
          }}
        />

        <Select
          label={type === 'WALLET' ? 'Network' : 'Bank'}
          value={provider}
          disabled={busy}
          options={(PROVIDERS[type] ?? []).map((item) => ({ value: item, label: item }))}
          onChange={(event) => {
            setProvider(event.target.value);
          }}
        />

        <TextField
          label="Payee name"
          hint="Exactly as it appears on their account."
          value={name}
          disabled={busy}
          error={fieldErrors['name']}
          onChange={(event) => {
            setName(event.target.value);
          }}
        />

        <TextField
          label={type === 'WALLET' ? 'Mobile money number' : 'Account number'}
          inputMode="numeric"
          autoComplete="off"
          value={destination}
          disabled={busy}
          error={fieldErrors['destination']}
          onChange={(event) => {
            setDestination(event.target.value);
          }}
        />

        <TextField
          label={type === 'WALLET' ? 'Re-enter the number' : 'Re-enter the account number'}
          inputMode="numeric"
          autoComplete="off"
          /* Pasting defeats the point of asking twice. */
          onPaste={(event) => {
            event.preventDefault();
          }}
          hint="Type it again rather than pasting — that is what catches a wrong digit."
          value={confirmDestination}
          disabled={busy}
          error={mismatch}
          onChange={(event) => {
            setConfirmDestination(event.target.value);
          }}
        />

        {type === 'INTERNATIONAL' && (
          <Select
            label="Currency"
            value={currency}
            disabled={busy}
            options={[
              { value: 'USD', label: 'USD' },
              { value: 'EUR', label: 'EUR' },
              { value: 'GBP', label: 'GBP' },
            ]}
            onChange={(event) => {
              setCurrency(event.target.value);
            }}
          />
        )}

        <div className="money__actions">
          <Button type="submit" loading={busy} disabled={!ready}>
            Add payee
          </Button>
          <Link to={RETAIL_PATHS.beneficiaries} className="money__link">
            Cancel
          </Link>
        </div>
      </form>
    </article>
  );
}
