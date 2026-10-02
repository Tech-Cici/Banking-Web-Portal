import { useState, type SyntheticEvent, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  Alert,
  Button,
  Checkbox,
  FileUpload,
  OtpInput,
  ResendTimer,
  ReviewPanel,
  Select,
  TextArea,
  TextField,
  type SelectOption,
  type UploadedFile,
} from '@/components/ui';
import { PUBLIC_PATHS } from '@/routes/paths';
import { newIdempotencyKey, registrationService } from '@/services';
import type {
  BusinessSignatory,
  OtpChallenge,
  RegistrationResult,
} from '@/types/registration';
import {
  collect,
  digits,
  digitsBetween,
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
 * Corporate internet banking application.
 *
 * Submitting creates an APPLICATION for the bank to review — not an active corporate.
 * No company can move money before the bank has completed its checks on the company, its
 * directors and its signatories, so there is deliberately no instant-activation path.
 *
 * Statuses reuse the blueprint's service-request model (sections 10.4 and 14.4):
 * Submitted → Under review → Additional information required → Approved / Rejected.
 *
 * Flow: company → signatories → documents → review → confirm the contact's email →
 * submitted.
 *
 * THE EMAIL STEP IS NEW, AND IT IS WHY THIS FLOW NOW WORKS AT ALL. Submitting used to be
 * a single POST to `/registration/business`, which the backend did not implement — so
 * this app's own mock answered it. The applicant reached the Submitted panel with a
 * reference number and nothing had been stored anywhere; staff opened Registrations and
 * found no such application. A false receipt is worse than an error: an error sends
 * somebody to a branch, a receipt sends them home to wait for a call.
 *
 * It mirrors personal registration on purpose. Nothing reaches the bank's queue until the
 * named contact has shown they can read the address they gave, because every later step —
 * the approval, the conversation in which credentials are arranged — runs on that address.
 */

const STEPS = [
  'Company',
  'Signatories',
  'Documents',
  'Review',
  'Confirm email',
  'Submitted',
] as const;

/*
 * PROVISIONAL reference data. Business types and sectors must come from the bank's own
 * reference-data API (blueprint section 29, "Branch/bank/reference data APIs") — these
 * are placeholders so the control is usable, not a proposed taxonomy.
 */
const BUSINESS_TYPES: readonly SelectOption[] = [
  { value: 'SOLE_PROPRIETOR', label: 'Sole proprietorship' },
  { value: 'PRIVATE_LIMITED', label: 'Private limited company' },
  { value: 'PUBLIC_LIMITED', label: 'Public limited company' },
  { value: 'PARTNERSHIP', label: 'Partnership' },
  { value: 'COOPERATIVE', label: 'Cooperative' },
  { value: 'NGO', label: 'NGO or non-profit' },
  { value: 'GOVERNMENT', label: 'Government or parastatal' },
];

const SECTORS: readonly SelectOption[] = [
  { value: 'AGRICULTURE', label: 'Agriculture' },
  { value: 'CONSTRUCTION', label: 'Construction' },
  { value: 'EDUCATION', label: 'Education' },
  { value: 'FINANCIAL', label: 'Financial services' },
  { value: 'HEALTH', label: 'Health' },
  { value: 'HOSPITALITY', label: 'Hospitality and tourism' },
  { value: 'ICT', label: 'ICT' },
  { value: 'MANUFACTURING', label: 'Manufacturing' },
  { value: 'MINING', label: 'Mining' },
  { value: 'RETAIL', label: 'Retail and trade' },
  { value: 'TRANSPORT', label: 'Transport and logistics' },
  { value: 'OTHER', label: 'Other' },
];

const SIGNATORY_ROLES: readonly SelectOption[] = [
  { value: 'DIRECTOR', label: 'Director' },
  { value: 'COMPANY_SECRETARY', label: 'Company secretary' },
  { value: 'FINANCE_MANAGER', label: 'Finance manager' },
  { value: 'AUTHORISED_SIGNATORY', label: 'Authorised signatory' },
];

/** Accepted document formats and limits. Server rules are authoritative. */
const DOC_ACCEPT = ['.pdf', '.jpg', '.jpeg', '.png'] as const;
const DOC_MAX_BYTES = 5 * 1024 * 1024;
const DOC_MAX_FILES = 10;

/** Rwandan TIN is 9 digits. Confirm against the RRA integration contract before go-live. */
const TIN_DIGITS = 9;

interface CompanyForm {
  companyName: string;
  registrationNumber: string;
  tin: string;
  businessType: string;
  sector: string;
  address: string;
  companyEmail: string;
  companyPhone: string;
  existingAccountNumber: string;
  contactFullName: string;
  contactRole: string;
  contactEmail: string;
  contactPhone: string;
}

const EMPTY_COMPANY: CompanyForm = {
  companyName: '',
  registrationNumber: '',
  tin: '',
  businessType: '',
  sector: '',
  address: '',
  companyEmail: '',
  companyPhone: '',
  existingAccountNumber: '',
  contactFullName: '',
  contactRole: '',
  contactEmail: '',
  contactPhone: '',
};

const EMPTY_SIGNATORY: BusinessSignatory = {
  fullName: '',
  nationalId: '',
  role: '',
  email: '',
  phone: '',
};

function validateCompany(form: CompanyForm): FieldErrors<CompanyForm> {
  return collect<CompanyForm>([
    ['companyName', minLength(form.companyName, 2, 'Company name')],
    ['registrationNumber', required(form.registrationNumber, 'Company registration number')],
    ['tin', digits(form.tin, TIN_DIGITS, 'TIN')],
    ['businessType', required(form.businessType, 'Business type')],
    ['sector', required(form.sector, 'Sector')],
    ['address', minLength(form.address, 5, 'Registered address')],
    ['companyEmail', validateEmail(form.companyEmail, 'Company email')],
    ['companyPhone', rwandanPhone(form.companyPhone, 'Company phone')],
    ['existingAccountNumber', digitsBetween(form.existingAccountNumber, 10, 16, 'Account number')],
    ['contactFullName', minLength(form.contactFullName, 2, 'Full name')],
    ['contactRole', required(form.contactRole, 'Role')],
    ['contactEmail', validateEmail(form.contactEmail, 'Contact email')],
    ['contactPhone', rwandanPhone(form.contactPhone, 'Contact phone')],
  ]);
}

function validateSignatory(s: BusinessSignatory): FieldErrors<BusinessSignatory> {
  return collect<BusinessSignatory>([
    ['fullName', minLength(s.fullName, 2, 'Full name')],
    ['nationalId', digits(s.nationalId, 16, 'National ID number')],
    ['role', required(s.role, 'Role')],
    ['email', validateEmail(s.email)],
    ['phone', rwandanPhone(s.phone)],
  ]);
}

export function BusinessRegistrationPage(): ReactElement {
  const [step, setStep] = useState(0);
  const [company, setCompany] = useState<CompanyForm>(EMPTY_COMPANY);
  const [companyErrors, setCompanyErrors] = useState<FieldErrors<CompanyForm>>({});

  const [signatories, setSignatories] = useState<BusinessSignatory[]>([{ ...EMPTY_SIGNATORY }]);
  const [signatoryErrors, setSignatoryErrors] = useState<FieldErrors<BusinessSignatory>[]>([{}]);

  const [files, setFiles] = useState<readonly UploadedFile[]>([]);
  const [docError, setDocError] = useState<string | undefined>(undefined);

  const [declaration, setDeclaration] = useState(false);
  const [declarationError, setDeclarationError] = useState<string | undefined>(undefined);

  /*
   * One idempotency key per application, created when the form is first opened and reused
   * for every retry of THIS submission. A new key on retry would create a second review
   * case for the same company.
   */
  const [idempotencyKey] = useState(() => newIdempotencyKey());
  const [result, setResult] = useState<RegistrationResult | null>(null);

  /* The code challenge, once the details have been sent and a code is in flight. */
  const [challenge, setChallenge] = useState<OtpChallenge | null>(null);
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | undefined>(undefined);

  const submit = useSubmitState();

  const setField = <K extends keyof CompanyForm>(key: K, value: CompanyForm[K]): void => {
    setCompany((current) => ({ ...current, [key]: value }));
  };

  const setSignatory = <K extends keyof BusinessSignatory>(
    index: number,
    key: K,
    value: BusinessSignatory[K],
  ): void => {
    setSignatories((current) =>
      current.map((entry, i) => (i === index ? { ...entry, [key]: value } : entry)),
    );
  };

  const onSubmitCompany = (event: SyntheticEvent): void => {
    event.preventDefault();
    const found = validateCompany(company);
    setCompanyErrors(found);
    if (isValid(found)) setStep(1);
  };

  const onSubmitSignatories = (event: SyntheticEvent): void => {
    event.preventDefault();
    const found = signatories.map(validateSignatory);
    setSignatoryErrors(found);
    if (found.every(isValid)) setStep(2);
  };

  const onSubmitDocuments = (event: SyntheticEvent): void => {
    event.preventDefault();
    if (files.length === 0) {
      setDocError('Attach at least one supporting document.');
      return;
    }
    setDocError(undefined);
    setStep(3);
  };

  const onSubmitApplication = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();
    if (!declaration) {
      setDeclarationError('You must confirm the declaration to submit.');
      return;
    }
    setDeclarationError(undefined);

    /*
     * Sends the details and asks for a code. NOTHING IS STORED BY THIS CALL — the
     * server holds the application against the verification until the code is entered,
     * so abandoning the flow here leaves no half-made application in the bank's queue.
     *
     * Only the FILE NAMES go up. There is no upload endpoint behind the Documents step,
     * and the server records each name as promised-but-not-received so an administrator
     * knows to ask for it. See docs/OPEN-ITEMS.md.
     */
    const issued = await submit.run(() =>
      registrationService.startBusiness({
        ...company,
        companyPhone: normalisePhone(company.companyPhone),
        contactPhone: normalisePhone(company.contactPhone),
        signatories: signatories.map((s) => ({ ...s, phone: normalisePhone(s.phone) })),
        documentNames: files.map((f) => f.file.name),
      }),
    );

    if (issued !== undefined) {
      setChallenge(issued);
      setStep(4);
    }
  };

  /* ------------------------------------- step 5: confirm the contact's email */

  const onSubmitCode = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();
    if (challenge === null) return;

    if (code.length !== challenge.otpLength) {
      setCodeError(`Enter the ${challenge.otpLength}-digit code.`);
      return;
    }
    setCodeError(undefined);

    const verified = await submit.run(() =>
      registrationService.verifyOtp(challenge.challengeId, code),
    );
    if (verified === undefined) return;

    // The code has served its purpose; drop it rather than leave it in memory.
    setCode('');

    /*
     * The same idempotency key for every retry of THIS application, created when the
     * form was opened. A fresh key on a retry would put a second review case for the
     * same company in front of the bank.
     */
    const submitted = await submit.run(() =>
      registrationService.completeBusiness(verified.verificationToken, idempotencyKey),
    );

    if (submitted !== undefined) {
      setResult(submitted);
      setStep(5);
    }
  };

  const onResend = async (): Promise<void> => {
    if (challenge === null) return;
    const issued = await submit.run(() => registrationService.resendOtp(challenge.challengeId));
    if (issued !== undefined) {
      setChallenge(issued);
      setCode('');
    }
  };

  const businessTypeLabel =
    BUSINESS_TYPES.find((t) => t.value === company.businessType)?.label ?? company.businessType;
  const sectorLabel = SECTORS.find((s) => s.value === company.sector)?.label ?? company.sector;

  return (
    <RegistrationShell
      title="Register a business"
      lead="Apply for corporate internet banking. The bank reviews every application before access is granted."
      steps={STEPS}
      current={step}
    >
      {submit.state.message !== undefined && (
        <Alert
          tone="error"
          title="We could not submit your application"
          reference={submit.state.reference}
        >
          <p>{submit.state.message}</p>
          {submit.state.nextStep !== undefined && (
            <p className="ui-alert__next">{submit.state.nextStep}</p>
          )}
        </Alert>
      )}

      {step === 0 && (
        <form onSubmit={onSubmitCompany} noValidate>
          <fieldset className="reg__fieldset">
            <legend className="reg__legend">Company details</legend>

            <TextField
              label="Registered company name"
              value={company.companyName}
              autoComplete="organization"
              error={companyErrors.companyName}
              onChange={(e) => {
                setField('companyName', e.target.value);
              }}
            />

            <div className="reg__grid-2">
              <TextField
                label="Company registration number"
                value={company.registrationNumber}
                error={companyErrors.registrationNumber}
                hint="As issued at incorporation."
                onChange={(e) => {
                  setField('registrationNumber', e.target.value);
                }}
              />
              <TextField
                label="TIN"
                value={company.tin}
                inputMode="numeric"
                error={companyErrors.tin}
                hint="9-digit tax identification number."
                onChange={(e) => {
                  setField('tin', e.target.value);
                }}
              />
              <Select
                label="Business type"
                options={BUSINESS_TYPES}
                value={company.businessType}
                error={companyErrors.businessType}
                onChange={(e) => {
                  setField('businessType', e.target.value);
                }}
              />
              <Select
                label="Sector"
                options={SECTORS}
                value={company.sector}
                error={companyErrors.sector}
                onChange={(e) => {
                  setField('sector', e.target.value);
                }}
              />
            </div>

            <TextArea
              label="Registered address"
              value={company.address}
              rows={3}
              error={companyErrors.address}
              onChange={(e) => {
                setField('address', e.target.value);
              }}
            />

            <div className="reg__grid-2">
              <TextField
                label="Company email"
                type="email"
                value={company.companyEmail}
                error={companyErrors.companyEmail}
                onChange={(e) => {
                  setField('companyEmail', e.target.value);
                }}
              />
              <TextField
                label="Company phone"
                type="tel"
                value={company.companyPhone}
                error={companyErrors.companyPhone}
                onChange={(e) => {
                  setField('companyPhone', e.target.value);
                }}
              />
            </div>

            <TextField
              label="Existing account number"
              value={company.existingAccountNumber}
              inputMode="numeric"
              error={companyErrors.existingAccountNumber}
              hint="The company account this access will be linked to."
              onChange={(e) => {
                setField('existingAccountNumber', e.target.value);
              }}
            />
          </fieldset>

          <fieldset className="reg__fieldset">
            <legend className="reg__legend">Primary contact</legend>
            <p className="reg__step-lead">
              The person the bank contacts about this application. They become the first
              administrator if it is approved.
            </p>

            <div className="reg__grid-2">
              <TextField
                label="Full name"
                value={company.contactFullName}
                autoComplete="name"
                error={companyErrors.contactFullName}
                onChange={(e) => {
                  setField('contactFullName', e.target.value);
                }}
              />
              <TextField
                label="Role in the company"
                value={company.contactRole}
                error={companyErrors.contactRole}
                onChange={(e) => {
                  setField('contactRole', e.target.value);
                }}
              />
              <TextField
                label="Email"
                type="email"
                value={company.contactEmail}
                error={companyErrors.contactEmail}
                onChange={(e) => {
                  setField('contactEmail', e.target.value);
                }}
              />
              <TextField
                label="Mobile number"
                type="tel"
                value={company.contactPhone}
                error={companyErrors.contactPhone}
                onChange={(e) => {
                  setField('contactPhone', e.target.value);
                }}
              />
            </div>
          </fieldset>

          <div className="reg__actions reg__actions--end">
            <Button type="submit">Continue</Button>
          </div>
        </form>
      )}

      {step === 1 && (
        <form onSubmit={onSubmitSignatories} noValidate>
          <h2 className="reg__step-title">Authorised signatories</h2>
          <p className="reg__step-lead">
            Everyone authorised to approve transactions for the company. The bank verifies each one,
            and you can change them later through your administrator.
          </p>

          {signatories.map((signatory, index) => (
            <div key={index} className="reg__signatory">
              <div className="reg__signatory-head">
                <h3 style={{ fontSize: 'var(--text-base)' }}>Signatory {index + 1}</h3>
                {signatories.length > 1 && (
                  <Button
                    variant="tertiary"
                    onClick={() => {
                      setSignatories((c) => c.filter((_, i) => i !== index));
                      setSignatoryErrors((c) => c.filter((_, i) => i !== index));
                    }}
                  >
                    Remove
                    <span className="sr-only"> signatory {index + 1}</span>
                  </Button>
                )}
              </div>

              <div className="reg__grid-2">
                <TextField
                  label="Full name"
                  value={signatory.fullName}
                  error={signatoryErrors[index]?.fullName}
                  onChange={(e) => {
                    setSignatory(index, 'fullName', e.target.value);
                  }}
                />
                <TextField
                  label="National ID number"
                  value={signatory.nationalId}
                  inputMode="numeric"
                  error={signatoryErrors[index]?.nationalId}
                  onChange={(e) => {
                    setSignatory(index, 'nationalId', e.target.value);
                  }}
                />
                <Select
                  label="Role"
                  options={SIGNATORY_ROLES}
                  value={signatory.role}
                  error={signatoryErrors[index]?.role}
                  onChange={(e) => {
                    setSignatory(index, 'role', e.target.value);
                  }}
                />
                <TextField
                  label="Email"
                  type="email"
                  value={signatory.email}
                  error={signatoryErrors[index]?.email}
                  onChange={(e) => {
                    setSignatory(index, 'email', e.target.value);
                  }}
                />
                <TextField
                  label="Mobile number"
                  type="tel"
                  value={signatory.phone}
                  error={signatoryErrors[index]?.phone}
                  onChange={(e) => {
                    setSignatory(index, 'phone', e.target.value);
                  }}
                />
              </div>
            </div>
          ))}

          <Button
            variant="secondary"
            onClick={() => {
              setSignatories((c) => [...c, { ...EMPTY_SIGNATORY }]);
              setSignatoryErrors((c) => [...c, {}]);
            }}
          >
            Add another signatory
          </Button>

          <div className="reg__actions">
            <Button
              variant="secondary"
              onClick={() => {
                setStep(0);
              }}
            >
              Back
            </Button>
            <Button type="submit">Continue</Button>
          </div>
        </form>
      )}

      {step === 2 && (
        <form onSubmit={onSubmitDocuments} noValidate>
          <h2 className="reg__step-title">Supporting documents</h2>
          <p className="reg__step-lead">
            The exact list the bank requires is confirmed during review. Typically this includes the
            certificate of incorporation, the company’s TIN certificate, a board resolution
            authorising internet banking, and ID for each signatory.
          </p>

          <FileUpload
            label="Attach documents"
            files={files}
            onChange={(next) => {
              setFiles(next);
              // Clear the "attach at least one" message the moment they act on it.
              setDocError(undefined);
            }}
            accept={DOC_ACCEPT}
            maxSizeBytes={DOC_MAX_BYTES}
            maxFiles={DOC_MAX_FILES}
            error={docError}
            hint="Scans or clear photographs are fine, as long as all text is readable."
          />

          <div className="reg__actions">
            <Button
              variant="secondary"
              onClick={() => {
                setStep(1);
              }}
            >
              Back
            </Button>
            <Button type="submit">Continue to review</Button>
          </div>
        </form>
      )}

      {step === 3 && (
        <form onSubmit={(event) => void onSubmitApplication(event)} noValidate>
          <h2 className="reg__step-title">Review your application</h2>
          <p className="reg__step-lead">
            Check everything before submitting. You can go back and change any of it.
          </p>

          <ReviewPanel
            caption="Company"
            rows={[
              { label: 'Name', value: company.companyName },
              { label: 'Registration number', value: company.registrationNumber },
              { label: 'TIN', value: company.tin },
              { label: 'Type', value: businessTypeLabel },
              { label: 'Sector', value: sectorLabel },
              { label: 'Address', value: company.address },
              { label: 'Email', value: company.companyEmail },
              { label: 'Phone', value: company.companyPhone },
              { label: 'Account', value: company.existingAccountNumber },
            ]}
          />

          <ReviewPanel
            caption="Primary contact"
            rows={[
              { label: 'Name', value: company.contactFullName },
              { label: 'Role', value: company.contactRole },
              { label: 'Email', value: company.contactEmail },
              { label: 'Phone', value: company.contactPhone },
            ]}
          />

          <ReviewPanel
            caption={`Signatories (${String(signatories.length)})`}
            rows={signatories.map((s, i) => ({
              label: `Signatory ${String(i + 1)}`,
              value: `${s.fullName} — ${
                SIGNATORY_ROLES.find((r) => r.value === s.role)?.label ?? s.role
              }`,
            }))}
          />

          <ReviewPanel
            caption={`Documents (${String(files.length)})`}
            rows={files.map((f) => ({ label: f.file.name, value: 'Attached' }))}
          />

          <Checkbox
            label="I confirm the information above is accurate and that I am authorised to submit this application on behalf of the company."
            checked={declaration}
            error={declarationError}
            disabled={submit.state.pending}
            onChange={(e) => {
              setDeclaration(e.target.checked);
            }}
          />

          <div className="reg__actions">
            <Button
              variant="secondary"
              disabled={submit.state.pending}
              onClick={() => {
                setStep(2);
              }}
            >
              Back
            </Button>
            <Button type="submit" loading={submit.state.pending}>
              Submit application
            </Button>
          </div>
        </form>
      )}

      {step === 4 && challenge !== null && (
        <form onSubmit={(event) => void onSubmitCode(event)} noValidate>
          <h2 className="reg__step-title">Confirm the contact&rsquo;s email address</h2>
          <p className="reg__step-lead">
            We sent a {challenge.otpLength}-digit code to {challenge.deliveryHint}. Enter it to
            confirm the address is reachable &mdash; the bank uses it for everything that follows,
            including the decision on this application.
          </p>

          <OtpInput
            label="Verification code"
            value={code}
            length={challenge.otpLength}
            error={codeError}
            disabled={submit.state.pending}
            onChange={(value) => {
              setCode(value);
              setCodeError(undefined);
            }}
          />

          <ResendTimer
            seconds={challenge.resendAfterSeconds}
            disabled={submit.state.pending}
            onResend={() => void onResend()}
          />

          <p className="reg__note">
            Nobody from the bank will ever ask you for this code. If someone does, hang up and
            report it.
          </p>

          <Alert tone="info" title="Nothing has been submitted yet">
            Your application reaches the bank once this address is confirmed. Until then nothing
            is stored and nobody is reviewing it.
          </Alert>

          <div className="reg__actions">
            <Button
              variant="secondary"
              disabled={submit.state.pending}
              onClick={() => {
                /*
                 * Back to Review, and the code is dropped on the way.
                 *
                 * Editing the company details invalidates the code that was sent — it
                 * guards the details as they were when it was issued, so leaving a
                 * half-typed one in the box would invite the applicant to submit it
                 * against a payload it never covered.
                 */
                setCode('');
                setCodeError(undefined);
                setStep(3);
              }}
            >
              Back
            </Button>
            <Button type="submit" loading={submit.state.pending}>
              Confirm and submit
            </Button>
          </div>
        </form>
      )}

      {step === 5 && result !== null && (
        <div>
          <Alert tone="success" title="Application submitted">
            {result.message}
          </Alert>

          <ReviewPanel
            rows={[
              { label: 'Reference', value: result.reference },
              { label: 'Status', value: 'Submitted' },
              { label: 'Company', value: company.companyName },
              { label: 'We will contact', value: company.contactEmail },
            ]}
          />

          <p className="reg__note">
            Keep this reference. Your corporate profile is created only once the bank approves the
            application — until then no one can transact on it.
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
