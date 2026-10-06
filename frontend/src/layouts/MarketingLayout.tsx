import { useEffect, useState, type ReactElement } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { BRAND } from '@/config/brand';
import { appConfig } from '@/config/env';
import { PUBLIC_PATHS } from '@/routes/paths';
import './MarketingLayout.css';

/**
 * Public shell for pages a visitor sees before signing in: the landing page and the
 * registration paths.
 *
 * NOT DEFINED IN THE BLUEPRINT. The page inventory (section 4) contains no public
 * marketing surface; this was requested directly. It is kept separate from
 * {@link import('./PublicLayout').PublicLayout} (the centred card used by login, OTP and
 * password recovery) because those screens must stay free of navigation and marketing.
 *
 * Carries no customer data of any kind, and links only to public destinations — every
 * in-page nav target is an anchor on the landing page, so nothing here bounces a visitor
 * into an authenticated route.
 */

interface NavItem {
  readonly label: string;
  /** The id of the section on the landing page, without the `#`. */
  readonly section: string;
}

/**
 * Sections of the landing page.
 *
 * THESE USED TO BE BARE `#fragment` LINKS, AND THAT WAS A BUG THE WHOLE HEADER SHARED.
 *
 * A bare `href="#services"` resolves against whatever page you are standing on. On the
 * landing page it works, because the section is there. On /register/business — where
 * somebody filling in a long form is most likely to want to go and read something — it
 * resolves to `/register/business#services`, which matches nothing: the page does not
 * move, the URL quietly grows a dead fragment, and every item in the top navigation
 * appears to be broken.
 *
 * They are now links to the landing page AND the section, so they work from anywhere,
 * and `ScrollToSection` below does the scrolling React Router does not do for a hash.
 */
const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Personal', section: 'personal' },
  { label: 'Business', section: 'business' },
  { label: 'Services', section: 'services' },
  { label: 'Security', section: 'security' },
  { label: 'Getting started', section: 'getting-started' },
];

/**
 * A link to a section of the landing page, usable from any page in this shell.
 *
 * `to` carries both the path and the hash, so clicking it from a registration form
 * navigates to the landing page first and then lands on the section. On the landing page
 * itself the path does not change and only the hash does, which `ScrollToSection` also
 * handles — otherwise the second click on the same item would do nothing at all, because
 * React Router sees no navigation.
 */
function SectionLink({
  section,
  children,
  className,
  onClick,
}: {
  readonly section: string;
  readonly children: ReactElement | string;
  readonly className?: string;
  readonly onClick?: () => void;
}): ReactElement {
  return (
    <Link
      to={{ pathname: PUBLIC_PATHS.landing, hash: `#${section}` }}
      {...(className === undefined ? {} : { className })}
      {...(onClick === undefined ? {} : { onClick })}
    >
      {children}
    </Link>
  );
}

/**
 * Puts the window where the URL says it should be.
 *
 * REACT ROUTER DOES NOT SCROLL TO A HASH. A full page load does it in the browser, a
 * client-side navigation does not, so `/#services` arrived at the top of the landing page
 * and looked exactly as broken as the dead fragment it replaced.
 *
 * It also resets to the top when the path changes with no hash. Without that, leaving the
 * bottom of the landing page for the registration form drops you halfway down a form you
 * have not started — the browser keeps the old offset because, as far as it is concerned,
 * nothing was navigated.
 *
 * NO SMOOTH SCROLLING. `scroll-padding-top` on `html` (global.css) already keeps the
 * target clear of the sticky header; animating the jump is the kind of motion this
 * portal deliberately does without.
 */
function ScrollToSection(): null {
  /*
   * `key` IS IN THE DEPENDENCIES ON PURPOSE, and leaving it out was a bug this screen
   * had until it was tested. Clicking "Security" while already on the landing page
   * pushes an identical location, so `pathname` and `hash` are unchanged, the effect
   * does not re-run, and the second click does nothing — which is precisely the
   * complaint the bare fragments produced. React Router gives every history entry its
   * own key even when the URL repeats, so that is what makes a repeat click work.
   */
  const { pathname, hash, key } = useLocation();

  useEffect(() => {
    if (hash === '') {
      window.scrollTo(0, 0);
      return;
    }

    /*
     * The element may not be laid out on the frame the effect runs in, when this is the
     * commit that mounted the landing page. One retry on the next frame covers it; a
     * loop or a timeout would be guessing.
     */
    const jump = (): void => {
      document.querySelector(hash)?.scrollIntoView();
    };

    jump();
    const frame = requestAnimationFrame(jump);
    return () => {
      cancelAnimationFrame(frame);
    };
  }, [pathname, hash, key]);

  return null;
}

function BrandMark(): ReactElement {
  // Placeholder mark, matching public/favicon.svg. Pending the bank's logo
  // (blueprint section 29).
  return (
    <svg
      className="marketing__brand-mark"
      width="26"
      height="26"
      viewBox="0 0 32 32"
      aria-hidden="true"
      focusable="false"
    >
      <rect width="32" height="32" rx="7" fill="currentColor" />
      <path
        d="M11 14v-2.5a5 5 0 0 1 10 0V14"
        fill="none"
        stroke="#fff"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
      <rect x="8.5" y="14" width="15" height="11" rx="2.5" fill="#fff" />
      <circle cx="16" cy="19" r="1.9" fill="currentColor" />
    </svg>
  );
}

export function MarketingLayout(): ReactElement {
  const [isNavOpen, setNavOpen] = useState(false);

  const closeNav = (): void => {
    setNavOpen(false);
  };

  return (
    <div className="marketing">
      <ScrollToSection />

      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>

      <header className="marketing__header">
        <div className="marketing__header-inner">
          <Link to={PUBLIC_PATHS.landing} className="marketing__brand" onClick={closeNav}>
            <BrandMark />
            <span>{BRAND.SHORT}</span>
          </Link>

          <button
            type="button"
            className="marketing__nav-toggle"
            aria-expanded={isNavOpen}
            aria-controls="marketing-navigation"
            onClick={() => {
              setNavOpen((open) => !open);
            }}
          >
            {isNavOpen ? 'Close' : 'Menu'}
          </button>

          <nav
            id="marketing-navigation"
            aria-label="Primary"
            className={isNavOpen ? 'marketing__nav marketing__nav--open' : 'marketing__nav'}
          >
            <ul className="marketing__nav-list">
              {NAV_ITEMS.map((item) => (
                <li key={item.section}>
                  <SectionLink
                    section={item.section}
                    className="marketing__nav-link"
                    onClick={closeNav}
                  >
                    {item.label}
                  </SectionLink>
                </li>
              ))}
            </ul>

            <div className="marketing__actions">
              <Link
                to={PUBLIC_PATHS.login}
                className="mkt-btn mkt-btn--secondary"
                onClick={closeNav}
              >
                Sign in
              </Link>
              <Link
                to={PUBLIC_PATHS.register}
                className="mkt-btn mkt-btn--primary"
                onClick={closeNav}
              >
                Get started
              </Link>
            </div>
          </nav>
        </div>
      </header>

      <main id="main-content" className="marketing__main">
        <Outlet />
      </main>

      <footer className="marketing__footer">
        <div className="marketing__footer-inner">
          <div>
            <h2 className="marketing__footer-heading">Personal banking</h2>
            <ul className="marketing__footer-list">
              <li>
                <SectionLink section="services">Transfers and payments</SectionLink>
              </li>
              <li>
                <SectionLink section="services">Loans and cards</SectionLink>
              </li>
              <li>
                <SectionLink section="services">Statements</SectionLink>
              </li>
            </ul>
          </div>

          <div>
            <h2 className="marketing__footer-heading">Business banking</h2>
            <ul className="marketing__footer-list">
              <li>
                <SectionLink section="business">Bulk and salary payments</SectionLink>
              </li>
              <li>
                <SectionLink section="business">Approval workflows</SectionLink>
              </li>
              <li>
                <SectionLink section="business">Multi-company access</SectionLink>
              </li>
            </ul>
          </div>

          <div>
            <h2 className="marketing__footer-heading">Support</h2>
            <ul className="marketing__footer-list">
              {/*
                The real contact details are owed by the bank (docs/OPEN-ITEMS.md) and are
                deliberately not invented — a wrong support number on a banking site is a
                fraud vector. "Contact details to be supplied" was a note to ourselves
                printed in a public footer, so it now points at the channel that is always
                correct and cannot be spoofed from this page.
              */}
              <li>Call the number on the back of your card</li>
              <li>Report suspicious activity</li>
              <li>Service status</li>
            </ul>
          </div>

          <div>
            <h2 className="marketing__footer-heading">Legal</h2>
            <ul className="marketing__footer-list">
              <li>Terms and conditions</li>
              <li>Privacy notice</li>
              <li>Fees and charges</li>
            </ul>
          </div>
        </div>

        <p className="marketing__security-note">
          <strong>Security reminder.</strong> The bank will never ask for your password, PIN or
          one-time code — not by phone, SMS or email. Always type the banking address into your
          browser yourself rather than following a link.
        </p>

        <div className="marketing__legal">
          {/*
            This said "Placeholder content for development. Regulatory disclosures,
            licensing details and legal copy are pending from the bank." Printing a
            bank's own unfinished status in its footer invites doubt about everything
            above it, and a customer can do nothing with the information. The items are
            tracked in docs/OPEN-ITEMS.md.
          */}
          <p>
            &copy; {String(new Date().getFullYear())} Ciara&rsquo;s demo. A demonstration
            project, not a real bank.
          </p>
          <p>Version {appConfig.appVersion}</p>
        </div>
      </footer>
    </div>
  );
}
