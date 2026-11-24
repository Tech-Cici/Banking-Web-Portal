import { useEffect, useState, type ReactElement } from 'react';
import {
  Alert,
  Button,
  ErrorNotice,
  FileUpload,
  Select,
  TextField,
  type UploadedFile,
} from '@/components/ui';
import { useAsync } from '@/hooks/useAsync';
import { collateralService } from '@/services';
import type { CollateralDocument, CollateralEntry } from '@/types/banking';
import './products.css';

/** The server refuses anything else by inspecting the bytes; this only shapes the picker. */
const ACCEPT = ['.jpg', '.jpeg', '.png', '.pdf'];
const MAX_BYTES = 8 * 1024 * 1024;

interface CollateralSectionProps {
  /** The rule for the chosen product, as a sentence the server composed. */
  readonly note: string | undefined;
  /** Called with the pledge whenever it changes, or null while it is incomplete. */
  readonly onChange: (entry: CollateralEntry | null) => void;
}

/**
 * Attaching something as security, and correcting what the bank read off the document.
 *
 * WHAT THE CUSTOMER SUBMITS IS WHAT THEY TYPED. The document is read on the server and the
 * boxes are filled in with whatever it found, which saves retyping a seventeen-character
 * chassis number off a photograph. Every box stays editable, and the values that leave this
 * screen are the ones left in them. The reading is a typing aid with the standing of a guess.
 *
 * NOTHING IS BLOCKED BY A FAILED READING. A blurred photograph, a reader that is not
 * installed, a PDF with no pages: the file is still stored, the boxes are simply empty, and
 * the customer types them. A convenience that becomes a gate is worse than no convenience.
 */
export function CollateralSection({ note, onChange }: CollateralSectionProps): ReactElement {
  const kinds = useAsync('collateral:kinds', (signal) => collateralService.kinds(signal));

  const [kind, setKind] = useState('');
  const [files, setFiles] = useState<readonly UploadedFile[]>([]);
  const [document, setDocument] = useState<CollateralDocument | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [description, setDescription] = useState('');
  const [uploading, setUploading] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);

  const available = kinds.state.status === 'ready' ? kinds.state.data : [];
  const chosen = available.find((option) => option.kind === kind);

  /*
   * THE PARENT IS TOLD ON EVERY CHANGE, from an effect rather than from each handler. A
   * pledge is complete only when the document is uploaded, every field it asks for is filled
   * and it has a description; working that out in six places is how one of them comes to
   * disagree with the others and the form enables a button it should not.
   */
  useEffect(() => {
    if (document === null || description.trim() === '') {
      onChange(null);
      return;
    }

    const missing = document.fields.some((field) => (values[field.key] ?? '').trim() === '');
    if (missing) {
      onChange(null);
      return;
    }

    onChange({
      documentId: document.id,
      description: description.trim(),
      values: Object.fromEntries(
        document.fields.map((field) => [field.key, (values[field.key] ?? '').trim()]),
      ),
    });
  }, [document, values, description, onChange]);

  const clearDocument = (): void => {
    setDocument(null);
    setValues({});
    setFailure(null);
  };

  const upload = (chosenFiles: readonly UploadedFile[]): void => {
    setFiles(chosenFiles);
    clearDocument();
    setFailure(null);

    const first = chosenFiles[0];
    if (first === undefined || kind === '') return;

    setUploading(true);
    void collateralService
      .upload(kind, first.file)
      .then((read) => {
        setDocument(read);
        /* The boxes start as whatever was read; blanks where nothing was. */
        setValues(
          Object.fromEntries(read.fields.map((field) => [field.key, field.value ?? ''])),
        );
      })
      .catch((cause: unknown) => {
        /*
         * THE FILE IS DROPPED FROM THE LIST, not left sitting there under an error. The
         * server refuses a readable page carrying none of the document's own identifiers,
         * which means the customer attached the wrong thing and the next step is to attach
         * something else. Leaving the rejected name in the box with a Remove link beside it
         * reads as "we have your file, and also an error", which is neither true nor a
         * prompt to do the one thing that helps.
         */
        setFiles([]);
        setFailure(cause);
      })
      .finally(() => {
        setUploading(false);
      });
  };

  return (
    <section className="products__collateral">
      <h2 className="products__collateral-title">Security</h2>

      {note !== undefined && <p className="dash__note">{note}</p>}

      {failure !== null && <ErrorNotice error={failure} action="read that document" />}

      <Select
        label="What are you putting up?"
        value={kind}
        options={available.map((option) => ({ value: option.kind, label: option.label }))}
        onChange={(event) => {
          setKind(event.target.value);
          setFiles([]);
          clearDocument();
        }}
      />

      {chosen !== undefined && (
        <>
          <FileUpload
            label={`Attach the ${chosen.document.toLowerCase()}`}
            files={files}
            onChange={upload}
            accept={ACCEPT}
            maxSizeBytes={MAX_BYTES}
            maxFiles={1}
            hint="A clear photograph of the page is fine. JPEG, PNG or PDF, up to 8 MB."
          />

          {uploading && <p className="dash__note">Reading the document…</p>}

          {document !== null && document.ocrStatus === 'FAILED' && (
            <Alert tone="warning" title="We could not read it automatically">
              {document.ocrFailure ?? 'The document is saved. Please type the details below.'}
            </Alert>
          )}

          {document !== null && document.ocrStatus === 'UNREADABLE' && (
            <Alert tone="info" title="Nothing legible on that page">
              The file is saved and your application can go ahead. Type the details below, or
              attach a sharper photograph if you have one.
            </Alert>
          )}

          {document?.heading != null && (
            <Alert tone="info" title="We read this as">
              {document.heading}
            </Alert>
          )}

          {document !== null && (
            <>
              {/*
                EVERY BOX IS EDITABLE, INCLUDING THE ONES THAT CAME BACK FILLED. What the
                reader found is a guess; what the customer leaves here is the declaration.
              */}
              {document.fields.map((field) => (
                <TextField
                  key={field.key}
                  label={field.label}
                  hint={
                    field.value === null
                      ? `${field.hint} We could not read this one.`
                      : `${field.hint} Read from your document — check it.`
                  }
                  value={values[field.key] ?? ''}
                  onChange={(event) => {
                    setValues((current) => ({ ...current, [field.key]: event.target.value }));
                  }}
                />
              ))}

              <TextField
                label="What is it?"
                hint="Your own words, so you recognise it later. For example: plot at Kabeza."
                value={description}
                onChange={(event) => {
                  setDescription(event.target.value);
                }}
              />

              {document.alsoRead.length > 0 && (
                <div className="products__alsoread">
                  <p className="products__alsoread-title">What else the page says</p>
                  {/*
                    NOT INPUTS. These are not being asked for, so they are not boxes: a field
                    the customer can edit but that never leaves the screen is an invitation to
                    waste their time. They are here so somebody can see the bank read the
                    right document.
                  */}
                  <dl className="products__alsoread-list">
                    {document.alsoRead.map((field) => (
                      <div key={field.key} className="products__alsoread-row">
                        <dt>{field.label}</dt>
                        <dd className="numeric">{field.value}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              )}

              {document.textPreview !== null && (
                <details className="products__reading">
                  <summary>What the bank read off your document</summary>
                  <pre className="products__reading-text">{document.textPreview}</pre>
                </details>
              )}

              <div className="money__actions">
                <Button
                  variant="secondary"
                  onClick={() => {
                    setFiles([]);
                    clearDocument();
                    setDescription('');
                  }}
                >
                  Attach a different document
                </Button>
              </div>
            </>
          )}
        </>
      )}
    </section>
  );
}
