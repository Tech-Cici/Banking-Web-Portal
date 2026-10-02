import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { BRAND } from '@/config/brand';
import { PUBLIC_PATHS } from '@/routes/paths';
import './LandingPage.css';

/**
 * Public landing page.
 *
 * NOT DEFINED IN THE BLUEPRINT — requested directly. Every capability named below is one
 * the blueprint actually specifies (sections 7 to 19), so the page does not promise
 * anything the portal will not do.
 *
 * Two constraints held throughout:
 *
 * 1. No invented social proof. No customer counts, uptime figures, ratings or
 *    testimonials. Fabricating those on a financial site is a misrepresentation, and a
 *    real number can only come from the bank.
 * 2. No invented specifics. Support numbers, fees, legal copy and regulatory disclosures
 *    are marked as pending rather than filled with plausible-looking text — a wrong
 *    support number on a banking page is a fraud vector.
 */

interface Service {
  readonly title: string;
  readonly text: string;
  readonly icon: string;
}

/** Drawn from the blueprint's own feature set, not imagined. */
const SERVICES: readonly Service[] = [
  {
    title: 'Transfers',
    text: 'Move money between your own accounts, to others in the bank, to other domestic banks, or internationally.',
    icon: '⇄',
  },
  {
    title: 'Bill payments',
    text: 'Electricity, water, TV subscriptions and tax payments, with the customer name confirmed before you pay.',
    icon: '⌸',
  },
  {
    title: 'Mobile money and airtime',
    text: 'Send to MTN and Airtel wallets, and top up airtime for your own number or someone else’s.',
    icon: '⌾',
  },
  {
    title: 'Standing orders',
    text: 'Schedule a recurring transfer once and review every execution against its schedule.',
    icon: '↻',
  },
  {
    title: 'Foreign exchange',
    text: 'Request a live rate, see the exact converted amount and fees, then execute before the quote expires.',
    icon: '⇌',
  },
  {
    title: 'Loans',
    text: 'Check balances and repayment schedules, simulate a new loan, and submit an application with documents.',
    icon: '◈',
  },
  {
    title: 'Cards',
    text: 'View your cards, request a new one, and block a card immediately if it goes missing.',
    icon: '▭',
  },
  {
    title: 'Statements and cheque books',
    text: 'Request statements for any period and order cheque books for collection at your branch.',
    icon: '▤',
  },
];

export function LandingPage(): ReactElement {
  return (
    <>
      {/* ---------------- hero ---------------- */}
      {/*
        THE HERO IS THE GREEN FIELD, the same one the sign-in screen stands on, with the
        illustration as a white card floating on it. The band is full-bleed and the
        content inside it keeps the page's own width cap, which is how
        `landing__section--tint` already works — a gradient that stops at the content
        column would read as a stray box rather than as the top of the page.
      */}
      <section className="landing__hero-band brand-field" aria-labelledby="hero-title">
        <div className="brand-field__decor" aria-hidden="true">
          <span className="brand-field__arc" />
          <span className="brand-field__spot" />
          <span className="brand-field__dots" />
        </div>

        <div className="landing__section landing__hero">
          <div>
            <p className="brand-field__eyebrow">{BRAND.FULL}</p>
            <h1 id="hero-title" className="landing__hero-title">
              Your bank, open whenever you need it
            </h1>
            <p className="landing__hero-lead">
              Check balances, move money, pay bills and manage your cards and loans — from a
              browser, on any device. For businesses, the same portal adds bulk payments, salary
              runs and maker–checker approvals.
            </p>

            <div className="landing__hero-actions">
              {/*
              "Get started" is the filled one. Registering is what somebody who cannot
              yet sign in has to do, and it is the action this page exists to offer; an
              existing customer looking for "Sign in" knows what they came for and finds
              an outlined button next to it perfectly well.
            */}
              <Link
                to={PUBLIC_PATHS.register}
                className="brand-field__action brand-field__action--solid"
              >
                Get started <span aria-hidden="true">→</span>
              </Link>
              <Link to={PUBLIC_PATHS.login} className="brand-field__action">
                Sign in
              </Link>
            </div>

            <p className="landing__hero-note">
              Already bank with us? Registering takes a few minutes and needs your existing account
              details.
            </p>
          </div>

          <div className="landing__hero-card brand-card" aria-hidden="true">
            <p className="landing__hero-card-label">Illustration</p>
            <div className="landing__hero-card-row">
              <div>
                <div style={{ fontWeight: 'var(--weight-medium)' }}>Current account</div>
                <div style={{ fontSize: 'var(--text-sm)', color: 'var(--color-text-muted)' }}>
                  **** 4582
                </div>
              </div>
              <div className="landing__hero-card-amount numeric">RWF ••••••</div>
            </div>
            <div className="landing__hero-card-row">
              <div>
                <div style={{ fontWeight: 'var(--weight-medium)' }}>Savings account</div>
                <div style={{ fontSize: 'var(--text-sm)', color: 'var(--color-text-muted)' }}>
                  **** 7310
                </div>
              </div>
              <div className="landing__hero-card-amount numeric">RWF ••••••</div>
            </div>
            <p className="landing__hero-card-caption">
              Example layout only. Account numbers are masked everywhere in the portal.
            </p>
          </div>
        </div>
      </section>

      {/* ---------------- services ---------------- */}
      <section
        id="services"
        className="landing__section landing__section--tint"
        aria-labelledby="services-title"
      >
        <div className="landing__inner">
          <p className="landing__eyebrow">What you can do</p>
          <h2 id="services-title" className="landing__section-title">
            Everyday banking, handled online
          </h2>
          <p className="landing__section-lead">
            Every transfer and payment follows the same path — enter the details, review exactly
            what will leave your account including fees, confirm, then get a reference number you
            can quote.
          </p>

          <div className="landing__grid landing__grid--services">
            {SERVICES.map((service) => (
              <article key={service.title} className="landing__card">
                <div className="landing__card-icon" aria-hidden="true">
                  {service.icon}
                </div>
                <h3 className="landing__card-title">{service.title}</h3>
                <p className="landing__card-text">{service.text}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- personal vs business ---------------- */}
      <section className="landing__section" aria-labelledby="audiences-title">
        <p className="landing__eyebrow">Two ways to bank</p>
        <h2 id="audiences-title" className="landing__section-title">
          Personal and business, one portal
        </h2>
        <p className="landing__section-lead">
          Sign in once. What you see depends on the permissions your bank or your company
          administrator has given you.
        </p>

        <div className="landing__split">
          <div className="landing__panel" id="personal">
            <h3 className="landing__panel-title">Personal banking</h3>
            <p className="landing__panel-lead">
              For individual customers managing their own accounts.
            </p>
            <ul className="landing__list">
              <li>Accounts, balances and full transaction history</li>
              <li>Transfers within the bank, to other banks and internationally</li>
              <li>Bills, airtime, mobile wallets and tax payments</li>
              <li>Saved beneficiaries and recurring standing orders</li>
              <li>Loans, cards, cheque books and statements</li>
            </ul>
            <Link to={PUBLIC_PATHS.register} className="mkt-btn mkt-btn--secondary">
              Register as a customer
            </Link>
          </div>

          <div className="landing__panel" id="business">
            <h3 className="landing__panel-title">Business banking</h3>
            <p className="landing__panel-lead">For companies, with control over who can do what.</p>
            <ul className="landing__list">
              <li>Switch between every company you are authorised to access</li>
              <li>Bulk payments and salary runs from an uploaded file</li>
              <li>Maker–checker approvals with a visible approval trail</li>
              <li>Server-side validation of every row before anything is submitted</li>
              <li>Salary detail hidden from staff without permission to see it</li>
            </ul>
            <Link to={PUBLIC_PATHS.register} className="mkt-btn mkt-btn--secondary">
              Register a business
            </Link>
          </div>
        </div>
      </section>

      {/* ---------------- security ---------------- */}
      <section
        id="security"
        className="landing__section landing__section--tint"
        aria-labelledby="security-title"
      >
        <div className="landing__inner">
          <p className="landing__eyebrow">Security</p>
          <h2 id="security-title" className="landing__section-title">
            Built so that mistakes are hard to make
          </h2>
          <p className="landing__section-lead">
            These are properties of how the portal is built, not promises about outcomes.
          </p>

          <div className="landing__grid landing__grid--security">
            <article className="landing__card">
              <h3 className="landing__card-title">Step-up verification</h3>
              <p className="landing__card-text">
                Signing in uses a one-time code, and higher-risk actions ask you to verify again
                before anything happens.
              </p>
            </article>
            <article className="landing__card">
              <h3 className="landing__card-title">Nothing hidden before you confirm</h3>
              <p className="landing__card-text">
                Every payment shows the exact debit account, the fee and the total leaving your
                account before you approve it.
              </p>
            </article>
            <article className="landing__card">
              <h3 className="landing__card-title">No accidental double payments</h3>
              <p className="landing__card-text">
                Each submission carries a unique key. If a connection drops, the portal checks what
                happened instead of sending the money twice.
              </p>
            </article>
            <article className="landing__card">
              <h3 className="landing__card-title">Details stay masked</h3>
              <p className="landing__card-text">
                Account and card numbers are shown masked, and card PINs and security codes are
                never displayed or stored.
              </p>
            </article>
            <article className="landing__card">
              <h3 className="landing__card-title">Two sets of eyes for business</h3>
              <p className="landing__card-text">
                Corporate payments can require a second person to approve, and nobody can approve
                their own transaction.
              </p>
            </article>
            <article className="landing__card">
              <h3 className="landing__card-title">A reference for every problem</h3>
              <p className="landing__card-text">
                When something fails you get a reference number that support can trace directly to
                what happened.
              </p>
            </article>
          </div>
        </div>
      </section>

      {/* ---------------- getting started ---------------- */}
      <section id="getting-started" className="landing__section" aria-labelledby="start-title">
        <p className="landing__eyebrow">Getting started</p>
        <h2 id="start-title" className="landing__section-title">
          Three steps to access
        </h2>
        <p className="landing__section-lead">
          The exact documents and checks are set by the bank and confirmed during registration.
        </p>

        <ol className="landing__steps">
          <li className="landing__step">
            <h3 className="landing__card-title">Choose how you are registering</h3>
            <p className="landing__card-text">
              As a personal customer, as a business, or as someone joining a business that already
              banks here.
            </p>
          </li>
          <li className="landing__step">
            <h3 className="landing__card-title">Submit your details</h3>
            <p className="landing__card-text">
              Business registrations are reviewed by the bank. People joining a business are
              approved by that company’s administrator.
            </p>
          </li>
          <li className="landing__step">
            <h3 className="landing__card-title">Verify and sign in</h3>
            <p className="landing__card-text">
              Confirm your contact details with a one-time code, set your password, and you’re in.
            </p>
          </li>
        </ol>
      </section>

      {/* ---------------- closing CTA ---------------- */}
      {/*
        THE SAME FIELD AT THE FOOT OF THE PAGE, so the long white middle is bookended
        rather than simply stopping. It used to be a flat block of the brand colour; the
        gradient is the same shape with the same measured contrast, and two different
        greens at the two ends of one page read as a mistake.
      */}
      <section className="landing__cta brand-field" aria-labelledby="cta-title">
        <div className="landing__cta-inner">
          <h2 id="cta-title" className="landing__cta-title">
            Ready to get started?
          </h2>
          <p className="landing__cta-lead">
            Register as a personal customer, register your business, or join a business that already
            banks with us.
          </p>
          <div className="landing__cta-actions">
            <Link
              to={PUBLIC_PATHS.register}
              className="brand-field__action brand-field__action--solid"
            >
              Get started <span aria-hidden="true">→</span>
            </Link>
            <Link to={PUBLIC_PATHS.login} className="brand-field__action">
              Sign in
            </Link>
          </div>
        </div>
      </section>
    </>
  );
}
