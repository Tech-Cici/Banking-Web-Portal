import { useState, type SyntheticEvent, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import {
  Alert,
  Button,
  Checkbox,
  OtpInput,
  ResendTimer,
  ReviewPanel,
  TextField,
} from '@/components/ui';
import { PUBLIC_PATHS } from '@/routes/paths';
import { registrationService } from '@/services';
import type { OtpChallenge, RegistrationResult } from '@/types/registration';
import { maskAccountNumber, maskEmail, maskPhoneNumber } from '@/utils/mask';
import {
  adultDateOfBirth,
  collect,
  digits,
  digitsBetween,
  email as validateEmail,
  isValid,
  maxLength,
  normalisePhone,
  required,
  rwandanPhone,
  type FieldErrors,
} from '@/utils/validation';
import { RegistrationShell } from './RegistrationShell';
import { useSubmitState } from './useSubmitState';

/**
 * Personal internet banking registration.
 *
 * This is ACTIVATION of an existing bank account for online use, not opening a new
 * account — which is why step one asks for details the bank already holds rather than
 * collecting a new customer's identity.
 *
 * Flow: identify → verify a one-time code → set a password → done.
 */

const STEPS = ['Your details', 'Verify', 'Confirm', 'Done'] as const;

interface DetailsForm {
  fullName: string;
  accountNumber: string;
  nationalId: string;
  dateOfBirth: string;
  phone: string;
  email: string;
}

const EMPTY_DETAILS: DetailsForm = {
  fullName: '',
  accountNumber: '',
  nationalId: '',
  dateOfBirth: '',
  phone: '',
  email: '',
};

/*
 * Field lengths are PROVISIONAL.
 *
 * The bank's real account-number format and the National ID length are owed (blueprint
 * section 29). Rwandan National IDs are 16 digits; account numbers vary by bank, so a
 * range is used rather than a guessed fixed length. The server is authoritative either
 * way — these only catch obvious typos.
 */
const ACCOUNT_MIN = 10;
const ACCOUNT_MAX = 16;
const NATIONAL_ID_DIGITS = 16;

function validateDetails(form: DetailsForm): FieldErrors<DetailsForm> {
  return collect<DetailsForm>([
    [
      'fullName',
      required(form.fullName, 'Full name') ?? maxLength(form.fullName, 160, 'Full name'),
    ],
    [
      'accountNumber',
      digitsBetween(form.accountNumber, ACCOUNT_MIN, ACCOUNT_MAX, 'Account number'),
    ],
    ['nationalId', digits(form.nationalId, NATIONAL_ID_DIGITS, 'National ID number')],
    ['dateOfBirth', adultDateOfBirth(form.dateOfBirth)],
    ['phone', rwandanPhone(form.phone)],
    ['email', validateEmail(form.email)],
  ]);
}

export function PersonalRegistrationPage(): ReactElement {
  const [step, setStep] = useState(0);
  const [details, setDetails] = useState<DetailsForm>(EMPTY_DETAILS);
  const [errors, setErrors] = useState<FieldErrors<DetailsForm>>({});

  const [challenge, setChallenge] = useState<OtpChallenge | null>(null);
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | undefined>(undefined);

  const [verificationToken, setVerificationToken] = useState('');
  const [terms, setTerms] = useState(false);
  const [pwErrors, setPwErrors] = useState<Record<string, string>>({});

  const [result, setResult] = useState<RegistrationResult | null>(null);

  const submit = useSubmitState();

  const set = <K extends keyof DetailsForm>(key: K, value: DetailsForm[K]): void => {
    setDetails((current) => ({ ...current, [key]: value }));
  };

  /* -------------------------------------------------- step 1: identify */

  const onSubmitDetails = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();
    const found = validateDetails(details);
    setErrors(found);
    if (!isValid(found)) return;

    const issued = await submit.run(() =>
      registrationService.startPersonal({
        fullName: details.fullName.trim(),
        accountNumber: details.accountNumber.replace(/\s/g, ''),
        nationalId: details.nationalId.replace(/\s/g, ''),
        dateOfBirth: details.dateOfBirth,
        phone: normalisePhone(details.phone),
        email: details.email.trim(),
      }),
    );

    if (issued !== undefined) {
      setChallenge(issued);
      setCode('');
      setStep(1);
    }
  };

  /* -------------------------------------------------- step 2: verify */

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

    if (verified !== undefined) {
      setVerificationToken(verified.verificationToken);
      // The code has served its purpose; drop it rather than leave it in memory.
      setCode('');
      setStep(2);
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

  /* -------------------------------------------------- step 3: confirm and submit */

  /*
   * No password is collected here any more.
   *
   * The bank issues a temporary one when an administrator creates the account, and the
   * customer replaces it on first sign-in. An applicant-chosen password cannot coexist
   * with that: the account would have two, and nobody could say which was current.
   */
  const onSubmitPassword = async (event: SyntheticEvent): Promise<void> => {
    event.preventDefault();

    if (!terms) {
      setPwErrors({ terms: 'You must accept the terms to continue.' });
      return;
    }
    setPwErrors({});

    const completed = await submit.run(() =>
      registrationService.completePersonal({ verificationToken }),
    );

    if (completed !== undefined) {
      setResult(completed);
      setStep(3);
    }
  };

  /* -------------------------------------------------- render */

  return (
    <RegistrationShell
      title="Register for personal banking"
      lead="Activate internet banking for an account you already hold with us."
      steps={STEPS}
      current={step}
    >
      {submit.state.message !== undefined && (
        <Alert tone="error" title="We could not continue" reference={submit.state.reference}>
          <p>{submit.state.message}</p>
          {submit.state.nextStep !== undefined && (
            <p className="ui-alert__next">{submit.state.nextStep}</p>
          )}
        </Alert>
      )}

      {step === 0 && (
        <form onSubmit={(event) => void onSubmitDetails(event)} noValidate>
          <h2 className="reg__step-title">Your details</h2>
          <p className="reg__step-lead">
            Enter these exactly as the bank holds them. We use them only to confirm the account is
            yours.
          </p>

          {/*
            The name is asked for again, and it is a CLAIM.

            It was briefly removed on the reasoning that an applicant who can type a name
            can type somebody else's. That reasoning only held while the bank supplied the
            name from its own records. It does not: matching these details against the
            bank's records happens outside this system, and a manager approves once it has
            been done. So the applicant is the only source, staff verify it, and every
            screen that shows it says it was submitted rather than confirmed.
          */}
          <TextField
            label="Full name"
            value={details.fullName}
            autoComplete="name"
            error={errors.fullName ?? submit.state.fieldErrors['fullName']}
            hint="Exactly as the bank holds it, so we can match you to your account."
            onChange={(event) => {
              set('fullName', event.target.value);
            }}
          />

          <TextField
            label="Account number"
            value={details.accountNumber}
            inputMode="numeric"
            autoComplete="off"
            error={errors.accountNumber ?? submit.state.fieldErrors['accountNumber']}
            hint="The account you want to use online."
            onChange={(event) => {
              set('accountNumber', event.target.value);
            }}
          />

          <TextField
            label="National ID number"
            value={details.nationalId}
            inputMode="numeric"
            autoComplete="off"
            error={errors.nationalId ?? submit.state.fieldErrors['nationalId']}
            hint="16 digits, as shown on your ID card."
            onChange={(event) => {
              set('nationalId', event.target.value);
            }}
          />

          <TextField
            label="Date of birth"
            type="date"
            value={details.dateOfBirth}
            autoComplete="bday"
            error={errors.dateOfBirth ?? submit.state.fieldErrors['dateOfBirth']}
            onChange={(event) => {
              set('dateOfBirth', event.target.value);
            }}
          />

          <TextField
            label="Mobile number"
            type="tel"
            value={details.phone}
            autoComplete="tel"
            error={errors.phone ?? submit.state.fieldErrors['phone']}
            hint="Must be the number registered with the bank."
            onChange={(event) => {
              set('phone', event.target.value);
            }}
          />

          <TextField
            label="Email address"
            type="email"
            value={details.email}
            autoComplete="email"
            error={errors.email ?? submit.state.fieldErrors['email']}
            hint="We send your code here, and this is where the bank will contact you."
            onChange={(event) => {
              set('email', event.target.value);
            }}
          />

          <div className="reg__actions reg__actions--end">
            <Button type="submit" loading={submit.state.pending}>
              Email me a code
            </Button>
          </div>
        </form>
      )}

      {step === 1 && challenge !== null && (
        <form onSubmit={(event) => void onSubmitCode(event)} noValidate>
          <h2 className="reg__step-title">Confirm your email address</h2>
          <p className="reg__step-lead">
            We sent a {challenge.otpLength}-digit code to {challenge.deliveryHint}. Enter it below
            to confirm the address is yours — the bank will use it to tell you when your account is
            ready.
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
              Verify
            </Button>
          </div>
        </form>
      )}

      {step === 2 && (
        <form onSubmit={(event) => void onSubmitPassword(event)} noValidate>
          <h2 className="reg__step-title">Confirm your registration</h2>
          <p className="reg__step-lead">
            You do not choose a password here. Once the bank has checked your details it will create
            your account and give you a temporary password, and you will choose your own the first
            time you sign in.
          </p>

          <Alert tone="info" title="What happens next">
            <ol style={{ paddingLeft: 'var(--space-6)', margin: 0 }}>
              <li>The bank checks the details you have given.</li>
              <li>Your account is created and approved by two different members of staff.</li>
              <li>
                We email you when it is ready, and you collect your temporary password from the
                branch.
              </li>
            </ol>
          </Alert>

          <Alert tone="warning" title="Nobody will email you a password">
            Your temporary password is only ever handed over in person or in a letter the bank
            prints. If you receive an email or a call offering you one, it is not from us.
          </Alert>

          <Checkbox
            label={<>I accept the terms and conditions and the privacy notice.</>}
            checked={terms}
            error={pwErrors['terms']}
            disabled={submit.state.pending}
            onChange={(event) => {
              setTerms(event.target.checked);
            }}
          />

          <div className="reg__actions">
            <Button
              variant="secondary"
              disabled={submit.state.pending}
              onClick={() => {
                setStep(1);
              }}
            >
              Back
            </Button>
            <Button type="submit" loading={submit.state.pending}>
              Submit registration
            </Button>
          </div>
        </form>
      )}

      {step === 3 && result !== null && (
        <div>
          <Alert tone="success" title="Registration submitted">
            {result.message}
          </Alert>

          <ReviewPanel
            caption="What we registered"
            rows={[
              { label: 'Name', value: details.fullName },
              { label: 'Account', value: maskAccountNumber(details.accountNumber) },
              { label: 'Mobile', value: maskPhoneNumber(details.phone) },
              { label: 'Email', value: maskEmail(details.email) },
              { label: 'Reference', value: result.reference },
            ]}
          />

          <div className="reg__actions reg__actions--end">
            <Link to={PUBLIC_PATHS.login} className="ui-btn ui-btn--primary">
              Go to sign in
            </Link>
          </div>
        </div>
      )}
    </RegistrationShell>
  );
}
