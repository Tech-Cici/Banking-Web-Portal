import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { Alert, PageHeader } from '@/components/ui';
import { RETAIL_PATHS } from '@/routes/paths';
import './money.css';

interface OutboundNotAvailablePageProps {
  readonly kind: 'EXTERNAL' | 'INTERNATIONAL';
}

const COPY = {
  EXTERNAL: {
    title: 'Send to another bank in Rwanda',
    where: 'another bank in Rwanda',
  },
  INTERNATIONAL: {
    title: 'Send money abroad',
    where: 'a bank outside Rwanda',
  },
} as const;

/**
 * SENDING MONEY OUT OF ZIGAMA IS NOT BUILT, and this screen says so.
 *
 * <p>WHAT IT REPLACED, because it is the reason this file exists rather than a form. Both
 * outbound rails were answered end to end by the front end's own mocks. The customer
 * filled in a beneficiary and an amount, pressed Send, and reached a green panel reading
 * "Done — the money is on its way", with a reference number. Nothing had been sent
 * anywhere and nothing could be: this service has no connection to any other bank.
 *
 * <p>A false receipt is worse than an error. An error sends somebody to a branch; a
 * receipt sends them home to wait for money that is never going to arrive, and the person
 * expecting it is told it was sent.
 *
 * <p>So the form is gone rather than disabled. A disabled form still says this is the
 * screen where it happens, and somebody would reasonably go looking for what to fill in
 * first.
 */
export function OutboundNotAvailablePage({
  kind,
}: OutboundNotAvailablePageProps): ReactElement {
  const copy = COPY[kind];

  return (
    <article className="money__narrow">
      <PageHeader
        title={copy.title}
        crumbs={[{ label: 'Transfers', to: RETAIL_PATHS.transfers }]}
      />

      <Alert tone="info" title="This is not available in the portal yet">
        <p>
          Money cannot be sent to {copy.where} from here. The portal has no connection to
          other banks, so there is nothing that could carry it &mdash; and a screen that
          accepted the instruction anyway would be telling you the money had gone when it
          had not.
        </p>
        <p style={{ marginTop: 'var(--space-3)' }}>
          Arrange it at a branch, and quote the account you want it to come from.
        </p>
      </Alert>

      <Alert tone="info" title="What does work">
        <p>
          You can move money between your own accounts, and to anyone else who banks at
          Zigama CSS, by their account number.{' '}
          <Link to={RETAIL_PATHS.transferInternal}>Send money at Zigama</Link>.
        </p>
      </Alert>
    </article>
  );
}
