import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { StatusBadge } from '@/components/ui';
import { RETAIL_PATHS } from '@/routes/paths';
import type { SessionUser } from '@/types/banking';
import { formatDateTime } from '@/utils/datetime';

interface DashboardHeaderProps {
  readonly user: SessionUser;
  /** What this person's role lets them do, in one plain sentence. */
  readonly roleLabel: string;
  readonly roleNote: string;
}

/**
 * Page heading, role, and the last-sign-in line.
 *
 * The last sign-in is not decoration. It is the cheapest fraud control a bank has: the
 * one person who can recognise a session they did not start is the customer, and they
 * can only do that if they are told. It carries a direct route to the security screen so
 * noticing and acting are one step apart.
 *
 * The role is stated out loud because on a corporate account it governs what the rest of
 * the page will and will not offer, and a colleague wondering why their screen differs
 * should be able to read the answer rather than guess at it.
 */
export function DashboardHeader({ user, roleLabel, roleNote }: DashboardHeaderProps): ReactElement {
  return (
    <header className="dash__head">
      <h1 className="dash__title">Hello, {user.preferredName}</h1>

      <p className="dash__role">
        <StatusBadge tone="info" label={roleLabel} />
        <span className="dash__role-note">{roleNote}</span>
      </p>

      <p className="dash__signin">
        {user.lastLoginAt === undefined ? (
          /*
           * A first sign-in has no previous one to report. Saying so plainly is the
           * honest version of this control, and it is still useful: a customer who sees
           * it on a later visit knows something is wrong with their account's history.
           */
          <>This is your first sign-in. </>
        ) : (
          /*
           * ON WHAT, NOT WHERE. This line used to read "from Kigali, Rwanda" for every
           * customer in the world — a string constant at four call sites in the service,
           * with no geo-IP lookup behind it. The device comes from the User-Agent of the
           * sign-in being reported and is omitted entirely when there was none, because
           * the whole value of this sentence is that the customer can check it.
           */
          <>
            Last sign-in {formatDateTime(user.lastLoginAt)}
            {user.lastLoginDevice !== undefined && ` on ${user.lastLoginDevice}`}.{' '}
          </>
        )}
        Not you? <Link to={RETAIL_PATHS.security}>Secure your account</Link>.
      </p>
    </header>
  );
}
