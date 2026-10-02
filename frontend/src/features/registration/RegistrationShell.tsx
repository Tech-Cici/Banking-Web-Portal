import type { ReactElement, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Stepper } from '@/components/ui';
import { PUBLIC_PATHS } from '@/routes/paths';
import './RegistrationShell.css';

interface RegistrationShellProps {
  readonly title: string;
  readonly lead: string;
  readonly steps: readonly string[];
  readonly current: number;
  readonly children: ReactNode;
}

/**
 * Common frame for the three registration flows: back link, title, stepper, card.
 *
 * Shared so the three flows cannot drift apart visually or in their step semantics.
 */
export function RegistrationShell({
  title,
  lead,
  steps,
  current,
  children,
}: RegistrationShellProps): ReactElement {
  return (
    <div className="reg brand-field">
      <div className="brand-field__decor" aria-hidden="true">
        <span className="brand-field__arc" />
        <span className="brand-field__spot" />
        <span className="brand-field__dots" />
      </div>

      <div className="reg__inner">
        <Link className="reg__back brand-field__link" to={PUBLIC_PATHS.register}>
          ← All registration options
        </Link>

        <h1 className="reg__title">{title}</h1>
        <p className="reg__lead">{lead}</p>

        {/*
          THE STEPPER IS ON THE GREEN, not inside the card. It says where somebody is in
          a four-step flow, so it has to stay put while the card beneath it changes
          completely from one step to the next — inside the card it reads as part of the
          form it is describing.
        */}
        <Stepper steps={steps} current={current} label={`${title} progress`} />

        <div className="reg__card brand-card">{children}</div>

        {/*
          THE WAY OUT FOR SOMEBODY WHO IS NOT NEW, on all three registration flows at
          once because it is here rather than in each of them.
          A customer who already banks online and lands on a registration form — from the
          header's "Get started", or a bookmark, or a search result — otherwise has to
          work out that none of this applies to them. Filling in a registration form for
          an account that already exists is a submission staff then have to unpick.
        */}
        <p className="reg__signin">
          Already have an account?{' '}
          <Link className="brand-field__link" to={PUBLIC_PATHS.login}>
            Sign in instead
          </Link>
        </p>
      </div>
    </div>
  );
}

/*
 * A table of the mock's test inputs used to sit at the bottom of every registration
 * screen — account numbers that force a rate limit or a server error, codes that force
 * an expiry. Useful, and in the wrong place: it printed a list of magic inputs
 * underneath a form a member of the public is filling in, and the guard protecting it
 * was the mock flag rather than the build.
 *
 * The table is now in docs/OPEN-ITEMS.md. The triggers still work.
 */
