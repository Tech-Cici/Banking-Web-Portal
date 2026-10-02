import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { PUBLIC_PATHS } from '@/routes/paths';
import './RegisterChoicePage.css';

/**
 * Where "Get started" lands: a choice of registration path.
 *
 * NOT DEFINED IN THE BLUEPRINT. Section 5.1 mentions only "Optional: Register/Activate
 * Internet Banking if onboarding is enabled" with no fields, flow or states.
 *
 * Deliberately contains NO form fields. The forms behind these three routes are deferred
 * until the Phase 2 design system exists, so they are built once on real components rather
 * than written twice. This page is navigation only.
 */

interface RegistrationPath {
  readonly title: string;
  readonly text: string;
  readonly meta: string;
  readonly to: string;
}

const PATHS: readonly RegistrationPath[] = [
  {
    title: 'Personal customer',
    text: 'You already hold an account with the bank and want to use it online. You will need your account details and a phone number registered with the bank.',
    meta: 'Verified against your existing account',
    to: PUBLIC_PATHS.registerPersonal,
  },
  {
    title: 'Register a business',
    text: 'Apply for corporate internet banking for your company. You will submit company details, directors and signatories, and supporting documents.',
    meta: 'Reviewed and approved by the bank',
    to: PUBLIC_PATHS.registerBusiness,
  },
  {
    title: 'Join a business',
    text: 'Your company already banks here and you need access. You will need the company code from your administrator, who then approves your request and sets your role.',
    meta: 'Approved by your company administrator',
    to: PUBLIC_PATHS.registerJoin,
  },
];

export function RegisterChoicePage(): ReactElement {
  return (
    <div className="register-choice brand-field">
      <div className="brand-field__decor" aria-hidden="true">
        <span className="brand-field__arc" />
        <span className="brand-field__spot" />
        <span className="brand-field__dots" />
      </div>

      <div className="register-choice__inner">
        <h1 className="register-choice__title">Get started</h1>
        <p className="register-choice__lead">
          Choose the option that describes you. If you are not sure, your branch or your company
          administrator can tell you which applies.
        </p>

        <div className="register-choice__grid">
          {PATHS.map((path) => (
            <Link key={path.to} to={path.to} className="register-choice__card brand-card">
              <h2 className="register-choice__card-title">{path.title}</h2>
              <p className="register-choice__card-text">{path.text}</p>
              <p className="register-choice__meta">{path.meta}</p>
              <p className="register-choice__cue" aria-hidden="true">
                Continue →
              </p>
            </Link>
          ))}
        </div>

        <p className="register-choice__help">
          <strong>Already registered?</strong> <Link to={PUBLIC_PATHS.login}>Sign in instead</Link>.
          Never enter your banking details on a page you reached from a link in an email or SMS.
        </p>
      </div>
    </div>
  );
}
