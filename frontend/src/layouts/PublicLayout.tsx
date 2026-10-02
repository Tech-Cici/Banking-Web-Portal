import type { ReactElement } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { BRAND } from '@/config/brand';
import { appConfig } from '@/config/env';
import { PUBLIC_PATHS } from '@/routes/paths';
import './PublicLayout.css';

/**
 * Layout for unauthenticated pages: sign in, the one-time code, the forced password
 * change, password recovery.
 *
 * <p>A GREEN FIELD WITH A WHITE CARD ON IT, to the shape of the design the bank asked
 * for, in the portal's own green rather than the reference's navy. The whole page carries
 * the gradient and the card floats to one side of it; there is no hard split, because a
 * seam down the middle of a sign-in page draws the eye to the seam.
 *
 * <p>It carries no balances, customer details or navigation into the authenticated app — a
 * pre-authentication page must reveal nothing about whether an account exists.
 *
 * <p>THE CARD COMES FIRST IN THE DOM and is placed to the right by the grid. Somebody
 * arriving here came to sign in, so the form is what a screen reader reads first and what
 * the first Tab reaches; the headline beside it is read after. Source order and visual
 * order disagree on purpose, and the grid is what reconciles them — on a phone the single
 * column puts the card first for everybody.
 *
 * <p>It also carries a way out. Until now these screens were a dead end: someone who
 * followed the sign-in link to read about an account, or who simply changed their mind,
 * had only the browser's back button, and on a phone opened from a link there may not be
 * one. A bank's sign-in page that can only be left by signing in is a trap, and people
 * respond to traps by closing the tab.
 */
export function PublicLayout(): ReactElement {
  return (
    <div className="public-layout brand-field">
      {/*
        THE PATTERN IN THE CORNER, drawn in CSS rather than shipped as an image.
        Decorative and nothing else, so it is hidden from assistive technology and takes
        no pointer events — an invisible rectangle over the corner of a sign-in page is
        the kind of thing that swallows a click on the wordmark.
      */}
      <div className="brand-field__decor" aria-hidden="true">
        <span className="brand-field__arc" />
        <span className="brand-field__spot" />
        <span className="brand-field__dots" />
      </div>

      <main className="public-layout__main">
        <div className="public-layout__stage">
          <div className="public-layout__card brand-card">
            <div className="public-layout__brand">
              {/*
                Wordmark only. The bank's logo is still owed (blueprint section 29), and a
                stand-in logo on a sign-in screen teaches customers to trust the wrong
                mark — the one thing a bank's login page must not do. The reference design
                has a logo in exactly this spot, and it stays empty until the real one
                arrives.

                It is a link because everyone already expects the name at the top of a
                page to go home, and an expectation that silently does nothing is its own
                small failure. The visible link below is for the people who do not know
                that.
              */}
              <Link to={PUBLIC_PATHS.landing} className="public-layout__wordmark">
                <span className="public-layout__name">{BRAND.SHORT}</span>
                <span className="public-layout__service">{BRAND.SERVICE}</span>
              </Link>
            </div>

            <Outlet />
          </div>

          <p className="public-layout__back">
            <Link to={PUBLIC_PATHS.landing} className="brand-field__link">
              ← Back to the home page
            </Link>
          </p>
        </div>

        {/*
          THE HEADLINE IS A PARAGRAPH, not a heading, and that is deliberate. The page's
          real heading is the one inside the card ("Sign in"), so marking this up as an
          h2 would put a level-two heading above the level-one and leave anyone
          navigating by headings reading the decoration first.
        */}
        <section className="public-layout__pitch">
          <p className="brand-field__eyebrow">Welcome to {BRAND.FULL}</p>
          <p className="brand-field__headline">Your bank, open whenever you need it</p>
          <Link to={PUBLIC_PATHS.landing} className="brand-field__action">
            Learn more <span aria-hidden="true">→</span>
          </Link>
        </section>
      </main>

      <footer className="public-layout__footer">
        {/*
          THE ANTI-PHISHING LINE LIVES HERE NOW, where every page in this layout gets it.
          It used to be a paragraph inside the sign-in card and nowhere else, which meant
          the one-time code screen — the screen a phishing call is actually about — did
          not carry it. Moving it out of the card also stops it competing with the field
          somebody is trying to type in.
        */}
        <p>
          Never share your password or one-time code — the bank will never ask for them. Always type
          the banking address into your browser yourself.
        </p>
        <p>Version {appConfig.appVersion}</p>
      </footer>
    </div>
  );
}
