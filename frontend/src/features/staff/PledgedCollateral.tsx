import { useState, type ReactElement } from 'react';
import { Alert, Button, Skeleton, StatusBadge } from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { adminService } from '@/services';
import type { PledgedField, PledgedItem } from '@/types/admin';
import { saveFile } from '@/utils/download';
import './staff.css';

interface PledgedCollateralProps {
  /** The loan application being reviewed. */
  readonly requestId: string;
}

/**
 * What is securing this loan, shown to the person about to approve it.
 *
 * <p>WHY THIS SCREEN HAD TO EXIST. Everything below was already being captured — the
 * document, the text read off it, the customer's typed declaration and the comparison
 * between the two — and none of it could be looked at. A manager opened an application,
 * saw an amount and a product, and approved. The pledge was a row in a table nobody could
 * read, which is the same as no pledge at all: the bank held a scan of a title deed and
 * made a credit decision without it.
 *
 * <p>DECLARED AND READ ARE NEVER MERGED. The declared value is the customer's statement and
 * is what the bank holds them to. The read value is what a machine thought a photograph of
 * a page said, and is wrong often enough that showing it alone would be worse than showing
 * nothing. The reviewer sees both and the verdict recorded between them.
 *
 * <p>A DISAGREEMENT IS NOT AN ACCUSATION. The customer is shown what was read and corrects
 * it before submitting, so DIFFERENT usually means the reader misread a character on a
 * photographed page. It is flagged because it is the one thing a reviewer should put their
 * own eyes on, not because it is evidence of anything.
 *
 * <p>NOTHING HERE IS VERIFIED, and the panel says so in those words. This portal cannot ask
 * the land authority whether a parcel number exists or who holds it. A field marked "agrees"
 * means the customer typed what the page says; whether the page is genuine, and whether the
 * person who uploaded it owns what it describes, are questions for the reviewer.
 */
export function PledgedCollateral({ requestId }: PledgedCollateralProps): ReactElement {
  const { state, reload } = useAsync(`collateral:${requestId}`, (signal) =>
    adminService.collateralFor(requestId, signal),
  );

  if (state.status === 'loading') {
    return <Skeleton rows={3} label="Loading what was pledged" />;
  }

  if (state.status === 'error') {
    return (
      <Alert tone="error" title="We could not load what was pledged" reference={state.reference}>
        <p>{state.message}</p>
        {/*
          NO SILENT APPROVAL. The grant form sits below this panel and stays usable, because
          a reader that is down is not a reason to hold up every loan in the queue. But the
          reviewer is told in plain words that they are now looking at an application whose
          security they cannot see, rather than at one that has none.
        */}
        <p className="staff__note">
          Until this loads you are looking at an application without its security. Retry before
          approving, or decline and ask the customer to resubmit.
        </p>
        <Button variant="secondary" onClick={reload}>
          Try again
        </Button>
      </Alert>
    );
  }

  const items = state.data;

  if (items.length === 0) {
    return (
      <div className="staff__pledge-none">
        <p>
          <strong>Nothing was pledged against this application.</strong>
        </p>
        {/*
          WHY THIS IS NOT PHRASED AS "no security was required". This endpoint reports what
          was pledged, not what the rule demanded, and the rule is applied to the amount the
          customer asked for. Approving MORE than was asked is an ordinary decision on this
          very form, and the amount that needed no security is not necessarily the amount
          being granted.
        */}
        <p className="staff__note">
          The bank accepted it without security for the amount that was asked for. If you are about
          to lend more than that, check the product's threshold yourself — nothing here re-applies
          it to your figure.
        </p>
      </div>
    );
  }

  return (
    <div className="staff__pledges">
      <h3 className="staff__pledge-heading">
        Securing this loan{items.length > 1 && <> — {items.length} items</>}
      </h3>
      <p className="staff__note">
        None of this is verified. The bank cannot ask a registry whether these documents are genuine
        or who owns what they describe; what follows is the document the customer uploaded and what
        could be read off it.
      </p>

      {items.map((item) => (
        <PledgedOne key={item.id} item={item} />
      ))}
    </div>
  );
}

/** How many of a pledge's declared fields disagreed with the page. */
function disagreements(item: PledgedItem): number {
  return item.fields.filter((field) => field.agreement === 'DIFFERENT').length;
}

function PledgedOne({ item }: { readonly item: PledgedItem }): ReactElement {
  const [showText, setShowText] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const differing = disagreements(item);

  const download = (): void => {
    setSaving(true);
    setFailure(null);

    void adminService
      .collateralDocument(item.documentId)
      .then(saveFile)
      .catch(() => {
        setFailure('We could not fetch that document. Try again in a moment.');
      })
      .finally(() => {
        setSaving(false);
      });
  };

  return (
    <section className="staff__pledge">
      <header className="staff__pledge-head">
        <div>
          <p className="staff__pledge-kind">{item.kindLabel}</p>
          <p className="staff__pledge-desc">{item.description}</p>
        </div>
        {differing > 0 ? (
          <StatusBadge
            tone="warning"
            label={differing === 1 ? '1 field differs' : `${String(differing)} fields differ`}
          />
        ) : item.ocrStatus === 'READ' ? (
          <StatusBadge tone="success" label="Matches the page" />
        ) : (
          <StatusBadge tone="neutral" label="Not read" />
        )}
      </header>

      {/*
        THE DOCUMENT COMES FIRST, above the extracted detail. The reading is a convenience;
        the page is the evidence, and a reviewer who wants to look at it should not have to
        scroll past a machine's opinion of it to find the link.
      */}
      <p className="staff__pledge-file">
        <Button variant="secondary" loading={saving} onClick={download}>
          Open the document
        </Button>
        <span className="staff__pledge-filename">
          {item.filename} · {Math.max(1, Math.round(item.sizeBytes / 1024))} KB · expected:{' '}
          {item.expectedDocument}
        </span>
      </p>

      {failure !== null && <p className="staff__pledge-failure">{failure}</p>}

      {item.heading !== undefined && (
        <p className="staff__pledge-title">
          The page is headed <q>{item.heading}</q>
        </p>
      )}

      {item.ocrStatus !== 'READ' && (
        <Alert
          tone="warning"
          title={
            item.ocrStatus === 'UNREADABLE'
              ? 'Nothing legible could be read off this document'
              : 'The reader failed on this document'
          }
        >
          <p>
            The figures below are the customer&rsquo;s own, unchecked against the page. Open the
            document and read it yourself before approving.
          </p>
        </Alert>
      )}

      <FieldRows title="What the customer declared" rows={item.fields} />

      {item.alsoRead.length > 0 && (
        <FieldRows
          title="Also on the page"
          note="Read off the document and never typed by the customer, so there is nothing to compare it with. It is here so the document can be recognised as the one it claims to be."
          rows={item.alsoRead}
        />
      )}

      {item.text !== undefined && (
        <div className="staff__pledge-raw">
          <Button
            variant="tertiary"
            onClick={() => {
              setShowText((open) => !open);
            }}
          >
            {showText ? 'Hide everything that was read' : 'Show everything that was read'}
          </Button>
          {/*
            PLAIN TEXT IN A <pre>, never rendered as anything. These bytes came from outside
            the bank by way of a reader; the one safe thing to do with them is show them as
            the characters they are.
          */}
          {showText && <pre className="staff__pledge-text">{item.text}</pre>}
        </div>
      )}
    </section>
  );
}

const VERDICTS: Readonly<
  Record<string, { tone: 'success' | 'warning' | 'neutral'; label: string }>
> = {
  SAME: { tone: 'success', label: 'agrees' },
  DIFFERENT: { tone: 'warning', label: 'differs' },
  NOT_READ: { tone: 'neutral', label: 'not read' },
};

function FieldRows({
  title,
  note,
  rows,
}: {
  readonly title: string;
  readonly note?: string;
  readonly rows: readonly PledgedField[];
}): ReactElement {
  return (
    <div className="staff__pledge-fields">
      <p className="staff__pledge-fields-title">{title}</p>
      {note !== undefined && <p className="staff__note">{note}</p>}

      <table className="staff__pledge-table">
        <thead>
          <tr>
            <th scope="col">Field</th>
            <th scope="col">Declared</th>
            <th scope="col">Read off the page</th>
            <th scope="col">&nbsp;</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((field) => {
            const verdict = VERDICTS[field.agreement];

            return (
              <tr
                key={field.key}
                className={
                  field.agreement === 'DIFFERENT' ? 'staff__pledge-row--differs' : undefined
                }
              >
                <th scope="row">{field.label}</th>
                <td className="numeric">{field.declared ?? '—'}</td>
                <td className="numeric">{field.read ?? '—'}</td>
                <td>
                  {verdict !== undefined && (
                    <StatusBadge tone={verdict.tone} label={verdict.label} />
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
