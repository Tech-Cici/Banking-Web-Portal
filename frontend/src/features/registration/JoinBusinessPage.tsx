import { useState, type SyntheticEvent, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  Alert,
  Button,
  Checkbox,
  ReviewPanel,
  Select,
  TextArea,
  TextField,
  type SelectOption,
} from '@/components/ui';
import { PUBLIC_PATHS } from '@/routes/paths';
import { newIdempotencyKey, registrationService } from '@/services';
import type { RegistrationResult } from '@/types/registration';
import { maskEmail, maskPhoneNumber } from '@/utils/mask';
import {
  collect,
  digits,
  email as validateEmail,
  isValid,
  minLength,
  normalisePhone,
  required,
  rwandanPhone,
  type FieldErrors,
} from '@/utils/validation';
import { RegistrationShell } from './RegistrationShell';
import { useSubmitState } from './useSubmitState';

/**
 * Request access to a company that already banks here.
 *
 * Access is always granted by someone inside the company. Submitting this form creates a
 * request for that company's administrator, who approves it and assigns the role — it
 * grants nothing on its own.
 *
 * The company code is never resolved to a company name before submission, and an unknown
 * code produces the same response as a valid one. Echoing the name back would turn this
 * form into a way to discover which businesses bank here, one guess at a time. The
 * trade-off is that a mistyped code fails silently, which is why the confirmation step
 * tells the applicant to check with their administrator if nothing happens.
 *
 * Flow: company code and your details → review → request sent.
 */

const STEPS = ['Your request', 'Review', 'Sent'] as const;

/*
 * PROVISIONAL roles. The real permission matrix is owed by business/security (blueprint
 * section 29). These are the maker–checker roles the brief names; the administrator can
 * assign something different regardless of what is requested here.
 */
const REQUESTED_ROLES: readonly SelectOption[] = [
  { value: 'VIEWER', label: 'Viewer — see accounts and history only' },
  { value: 'MAKER', label: 'Maker — prepare and submit transactions' },
  { value: 'APPROVER', label: 'Approver — review and approve transactions' },
];

interface JoinForm {
  companyCode: string;
  fullName: string;
  nationalId: string;
  staffNumber: string;
  email: string;
  phone: string;
  requestedRole: string;
  justification: string;
}

const EMPTY: JoinForm = {
  companyCode: '',
  fullName: '',
  nationalId: '',
  staffNumber: '',
  email: '',
  phone: '',
  requestedRole: '',
  justification: '',
};

function validate(form: JoinForm): FieldErrors<JoinForm> {
  return collect<JoinForm>([
    ['companyCode', minLength(form.companyCode, 4, 'Company code')],
    ['fullName', minLength(form.fullName, 2, 'Full name')],
    ['nationalId', digits(form.nationalId, 16, 'National ID number')],
    ['staffNumber', required(form.staffNumber, 'Staff or employee number')],
    ['email', validateEmail(form.email, 'Work email')],
    ['phone', rwandanPhone(form.phone)],
    ['requestedRole', required(form.requestedRole, 'Requested role')],
    ['justification', minLength(form.justification, 10, 'Reason for access')],
  ]);
}

export function JoinBusinessPage(): ReactElement {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<JoinForm>(EMPTY);
  const [errors, setErrors] = useState<FieldErrors<JoinForm>>({});
  const [confirmed, setConfirmed] = useState(false);
  const [confirmError, setConfirmError] = useState<string | undefined>(undefined);
  const [result, setResult] = useState<RegistrationResult | null>(null);

  /** One key per request, reused across retries so an administrator is not asked twice. */
  const [idempotencyKey] = useState(() => newIdempotencyKey());
  const submit = useSubmitState();

  const set = <K extends keyof JoinForm>(key: K, value: JoinForm[K]): void => {
    setForm((current) => ({ ...current, [key]: value }));
  };

  const onSubmitDetails = (event: SyntheticEvent): void => {
    event.preventDefault();
    const found = validate(form);
    setErrors(found);
    if (isValid(found)) setStep(1);
  };

  const onSubmitRequest = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();
    if (!confirmed) {
      setConfirmError('Please confirm before sending your request.');
      return;
    }
    setConfirmError(undefined);

    const sent = await submit.run(() =>
      registrationService.submitJoinRequest(
        {
          ...form,
          companyCode: form.companyCode.trim().toUpperCase(),
          phone: normalisePhone(form.phone),
        },
        idempotencyKey,
      ),
    );

    if (sent !== undefined) {
      setResult(sent);
      setStep(2);
    }
  };

  const roleLabel =
    REQUESTED_ROLES.find((r) => r.value === form.requestedRole)?.label ?? form.requestedRole;

  return (
    <RegistrationShell
      title="Join a business"
      lead="Request access to a company that already banks with us. Your administrator approves the request and sets your role."
      steps={STEPS}
      current={step}
    >
      {submit.state.message !== undefined && (
        <Alert
          tone="error"
          title="We could not send your request"
          reference={submit.state.reference}
        >
          <p>{submit.state.message}</p>
          {submit.state.nextStep !== undefined && (
            <p className="ui-alert__next">{submit.state.nextStep}</p>
          )}
        </Alert>
      )}

      {step === 0 && (
        <form onSubmit={onSubmitDetails} noValidate>
          <fieldset className="reg__fieldset">
            <legend className="reg__legend">The company</legend>

            <TextField
              label="Company code"
              value={form.companyCode}
              autoComplete="off"
              autoCapitalize="characters"
              error={errors.companyCode ?? submit.state.fieldErrors['companyCode']}
              hint="Ask your company administrator for this. We cannot look it up for you."
              onChange={(e) => {
                set('companyCode', e.target.value);
              }}
            />
          </fieldset>

          <fieldset className="reg__fieldset">
            <legend className="reg__legend">About you</legend>

            <div className="reg__grid-2">
              <TextField
                label="Full name"
                value={form.fullName}
                autoComplete="name"
                error={errors.fullName}
                onChange={(e) => {
                  set('fullName', e.target.value);
                }}
              />
              <TextField
                label="National ID number"
                value={form.nationalId}
                inputMode="numeric"
                error={errors.nationalId}
                onChange={(e) => {
                  set('nationalId', e.target.value);
                }}
              />
              <TextField
                label="Staff or employee number"
                value={form.staffNumber}
                error={errors.staffNumber}
                hint="So your administrator can identify you."
                onChange={(e) => {
                  set('staffNumber', e.target.value);
                }}
              />
              <TextField
                label="Work email"
                type="email"
                value={form.email}
                autoComplete="email"
                error={errors.email}
                onChange={(e) => {
                  set('email', e.target.value);
                }}
              />
              <TextField
                label="Mobile number"
                type="tel"
                value={form.phone}
                autoComplete="tel"
                error={errors.phone}
                onChange={(e) => {
                  set('phone', e.target.value);
                }}
              />
            </div>
          </fieldset>

          <fieldset className="reg__fieldset">
            <legend className="reg__legend">Access you need</legend>

            <Select
              label="Requested role"
              options={REQUESTED_ROLES}
              value={form.requestedRole}
              error={errors.requestedRole}
              hint="Your administrator decides the final role — this is a request."
              onChange={(e) => {
                set('requestedRole', e.target.value);
              }}
            />

            <TextArea
              label="Reason for access"
              value={form.justification}
              rows={3}
              error={errors.justification}
              hint="A sentence on what you need to do, so your administrator can approve quickly."
              onChange={(e) => {
                set('justification', e.target.value);
              }}
            />
          </fieldset>

          <div className="reg__actions reg__actions--end">
            <Button type="submit">Continue to review</Button>
          </div>
        </form>
      )}

      {step === 1 && (
        <form onSubmit={(event) => void onSubmitRequest(event)} noValidate>
          <h2 className="reg__step-title">Review your request</h2>
          <p className="reg__step-lead">This is what your company administrator will see.</p>

          <ReviewPanel
            rows={[
              { label: 'Company code', value: form.companyCode.toUpperCase() },
              { label: 'Name', value: form.fullName },
              { label: 'Staff number', value: form.staffNumber },
              { label: 'Work email', value: form.email },
              { label: 'Mobile', value: form.phone },
              { label: 'Requested role', value: roleLabel },
              { label: 'Reason', value: form.justification },
            ]}
          />

          <Checkbox
            label="I confirm these details are correct and that I am authorised to request access to this company's banking."
            checked={confirmed}
            error={confirmError}
            disabled={submit.state.pending}
            onChange={(e) => {
              setConfirmed(e.target.checked);
            }}
          />

          <div className="reg__actions">
            <Button
              variant="secondary"
              disabled={submit.state.pending}
              onClick={() => {
                setStep(0);
              }}
            >
              Back
            </Button>
            <Button type="submit" loading={submit.state.pending}>
              Send request
            </Button>
          </div>
        </form>
      )}

      {step === 2 && result !== null && (
        <div>
          <Alert tone="success" title="Request sent">
            {result.message}
          </Alert>

          <ReviewPanel
            rows={[
              { label: 'Reference', value: result.reference },
              { label: 'Status', value: 'Awaiting administrator approval' },
              { label: 'Email', value: maskEmail(form.email) },
              { label: 'Mobile', value: maskPhoneNumber(form.phone) },
            ]}
          />

          <p className="reg__note">
            You will hear from us once your administrator responds. If you hear nothing, check the
            company code with them — for security we cannot confirm whether a code is valid, so a
            mistyped one simply goes nowhere.
          </p>

          <div className="reg__actions reg__actions--end">
            <Link to={PUBLIC_PATHS.landing} className="ui-btn ui-btn--secondary">
              Back to home
            </Link>
          </div>
        </div>
      )}
    </RegistrationShell>
  );
}
