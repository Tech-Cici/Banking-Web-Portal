import { useState, type ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, PageHeader, Panel } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { DIAGNOSTIC_PATHS, RETAIL_PATHS } from '@/routes/paths';
import { healthService } from '@/services';
import { formatDateTime } from '@/utils/datetime';
import './account.css';

interface Question {
  readonly q: string;
  readonly a: ReactElement;
}

const QUESTIONS: readonly Question[] = [
  {
    q: 'I sent money to the wrong person. Can the bank get it back?',
    a: (
      <p>
        Not automatically. Once a payment has been made, the money belongs to the person who
        received it, and the bank can only ask them to return it. Call the bank straight away
        — the sooner it is reported, the better the chance. This is why a new payee waits
        before it can be paid, and why you are asked to type the account number twice.
      </p>
    ),
  },
  {
    q: 'A payment says "pending confirmation". What does that mean?',
    a: (
      <p>
        It means the bank did not tell us whether it went through. It may have. Do not send
        it again — check your transactions in a few minutes, and call the bank quoting the
        reference if it has not appeared.
      </p>
    ),
  },
  {
    q: 'Why can I see less than my colleague?',
    a: (
      <p>
        Company access is granted per person. Your <Link to={RETAIL_PATHS.profile}>profile</Link>{' '}
        lists exactly what yours allows. A maker prepares payments and an approver releases
        them, and deliberately no one does both.
      </p>
    ),
  },
  {
    q: 'My available balance is lower than my current balance.',
    a: (
      <p>
        The difference has not cleared yet — usually a recent deposit, or a card payment that
        has been authorised but not settled. You can spend the available balance.
      </p>
    ),
  },
  {
    q: 'I have lost my card.',
    a: (
      <p>
        Block it now from <Link to={RETAIL_PATHS.cards}>Cards</Link>. It takes effect
        immediately. Unblocking has to be done by the bank, so blocking is always the safe
        thing to do first.
      </p>
    ),
  },
  {
    q: 'Someone is asking me for my one-time code.',
    a: (
      <p>
        Stop. Nobody from the bank will ever ask for your password or a one-time code, by any
        means. Hang up and call the number printed on the back of your card.
      </p>
    ),
  },
];

/**
 * Help, and a way to see whether the problem is the bank's.
 *
 * The questions are the ones that actually generate calls, answered plainly. The service
 * check is here rather than buried in diagnostics because "is it me or is it you?" is
 * the first thing a customer wants to know when something fails.
 */
export function HelpPage(): ReactElement {
  const [open, setOpen] = useState<string | null>(null);
  const health = useAsync('help:health', (signal) => healthService.check(signal));

  return (
    <article className="account__page">
      <PageHeader title="Help" lead="Answers to the things people ask most." />

      <Alert tone="warning" title="If you think someone has access to your account">
        Change your password and remove any device you do not recognise from{' '}
        <Link to={RETAIL_PATHS.security}>Security</Link>, then call the bank on the number
        printed on the back of your card. Do not use a number someone has given you.
      </Alert>

      <div className="account__grid">
        <Panel title="Common questions">
          <ul className="account__faq">
            {QUESTIONS.map((item) => (
              <li key={item.q}>
                <button
                  type="button"
                  className="account__faq-q"
                  aria-expanded={open === item.q}
                  onClick={() => {
                    setOpen(open === item.q ? null : item.q);
                  }}
                >
                  {item.q}
                </button>
                {open === item.q && <div className="account__faq-a">{item.a}</div>}
              </li>
            ))}
          </ul>
        </Panel>

        <Panel title="Is the service working?">
          {health.state.status === 'loading' && <p className="dash__note">Checking…</p>}

          {health.state.status === 'ready' && (
            <Alert tone="success" title="The banking service is responding">
              Checked at {formatDateTime(health.state.data.time)}.
            </Alert>
          )}

          {health.state.status === 'error' && (
            <Alert tone="warning" title="We could not reach the banking service">
              <p>{health.state.message}</p>
              <p className="money__note">
                If this persists, the problem is at the bank rather than with your
                connection.
              </p>
            </Alert>
          )}

          <div className="money__actions">
            <Button variant="secondary" onClick={health.reload}>
              Check again
            </Button>
            <Link to={DIAGNOSTIC_PATHS.systemStatus} className="money__link">
              Detailed status
            </Link>
          </div>
        </Panel>

        {/*
          The real contact details are owed by the bank (docs/OPEN-ITEMS.md) and are
          deliberately not invented — a wrong support number on a banking page is a fraud
          vector. Until they arrive this points at the channel that does exist: the number
          on the back of the customer's own card, which is always correct and cannot be
          spoofed by anything on this page.
        */}
        <Panel title="Contact the bank">
          <p className="dash__note">
            Call the number printed on the back of your card, or visit any branch. The bank
            will never ask for your password, PIN or one-time code.
          </p>
        </Panel>
      </div>
    </article>
  );
}
