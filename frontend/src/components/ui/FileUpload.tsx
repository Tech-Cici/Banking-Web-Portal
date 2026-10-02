import { useRef, useState, type DragEvent, type ReactElement } from 'react';
import { useFieldIds } from './useFieldIds';
import './ui.css';

export interface UploadedFile {
  readonly id: string;
  readonly file: File;
}

interface FileUploadProps {
  readonly label: string;
  readonly files: readonly UploadedFile[];
  readonly onChange: (files: readonly UploadedFile[]) => void;
  /** Accepted extensions, e.g. ['.pdf', '.jpg']. Server rules are authoritative. */
  readonly accept: readonly string[];
  readonly maxSizeBytes: number;
  readonly maxFiles: number;
  readonly hint?: string | undefined;
  readonly error?: string | undefined;
  readonly disabled?: boolean;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Drag-and-drop or browse file upload.
 *
 * Client-side checks cover extension, size and count ONLY — exactly what the blueprint
 * permits (section 18.2). The server re-validates everything; a client check is a
 * courtesy that saves a round trip, never a gate.
 *
 * Files are held in memory and never inspected, parsed or logged. For registration these
 * are identity and company documents, so the less this code touches them the better.
 */
export function FileUpload({
  label,
  files,
  onChange,
  accept,
  maxSizeBytes,
  maxFiles,
  hint,
  error,
  disabled = false,
}: FileUploadProps): ReactElement {
  const [dragging, setDragging] = useState(false);
  const [localError, setLocalError] = useState<string | undefined>(undefined);
  const inputRef = useRef<HTMLInputElement>(null);
  const ids = useFieldIds(hint, error ?? localError);

  const accepted = accept.join(', ');

  const addFiles = (incoming: FileList | null): void => {
    if (incoming === null) return;
    setLocalError(undefined);

    const next: UploadedFile[] = [...files];

    for (const file of Array.from(incoming)) {
      if (next.length >= maxFiles) {
        setLocalError(`You can attach at most ${maxFiles} files.`);
        break;
      }

      const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
      if (!accept.includes(extension)) {
        setLocalError(`${file.name} is not an accepted file type. Accepted: ${accepted}.`);
        continue;
      }

      if (file.size > maxSizeBytes) {
        setLocalError(
          `${file.name} is ${formatBytes(file.size)}, larger than the ${formatBytes(maxSizeBytes)} limit.`,
        );
        continue;
      }

      next.push({ id: `${file.name}-${String(file.lastModified)}-${String(file.size)}`, file });
    }

    onChange(next);
  };

  const onDrop = (event: DragEvent<HTMLDivElement>): void => {
    event.preventDefault();
    setDragging(false);
    if (!disabled) addFiles(event.dataTransfer.files);
  };

  /*
   * localError first. It describes the file the user just tried to add, whereas the
   * `error` prop is usually a form-level message from an earlier submit attempt. With the
   * prop winning, "attach at least one document" masked "that file type is not accepted",
   * leaving the user staring at a rejected file with no explanation.
   */
  const shownError = localError ?? error;

  return (
    <div className="ui-field">
      <span className="ui-field__label" id={`${ids.inputId}-label`}>
        {label}
      </span>

      <div
        className={[
          'ui-upload',
          dragging ? 'ui-upload--dragging' : '',
          shownError === undefined ? '' : 'ui-upload--invalid',
        ]
          .filter(Boolean)
          .join(' ')}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => {
          setDragging(false);
        }}
        onDrop={onDrop}
      >
        <p style={{ marginBottom: 'var(--space-3)', fontSize: 'var(--text-sm)' }}>
          Drag files here, or
        </p>

        {/* The real control is the button; the input stays visually hidden but focusable
            through the button, so keyboard users are never stranded. */}
        <input
          ref={inputRef}
          id={ids.inputId}
          type="file"
          className="sr-only"
          multiple
          accept={accepted}
          disabled={disabled}
          aria-describedby={ids.describedBy}
          aria-labelledby={`${ids.inputId}-label`}
          onChange={(event) => {
            addFiles(event.target.files);
            event.target.value = '';
          }}
        />
        <button
          type="button"
          className="ui-btn ui-btn--secondary"
          disabled={disabled}
          onClick={() => {
            inputRef.current?.click();
          }}
        >
          Choose files
        </button>

        <p className="ui-upload__meta" style={{ marginTop: 'var(--space-3)' }}>
          {accepted} · up to {formatBytes(maxSizeBytes)} each · maximum {maxFiles} files
        </p>
      </div>

      {files.length > 0 && (
        <ul className="ui-upload__list">
          {files.map((entry) => (
            <li key={entry.id} className="ui-upload__item">
              <span>
                {entry.file.name}
                <span className="ui-upload__meta"> · {formatBytes(entry.file.size)}</span>
              </span>
              <button
                type="button"
                className="ui-btn ui-btn--tertiary"
                disabled={disabled}
                onClick={() => {
                  onChange(files.filter((candidate) => candidate.id !== entry.id));
                }}
              >
                Remove
                <span className="sr-only"> {entry.file.name}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {hint !== undefined && (
        <p className="ui-field__hint" id={ids.hintId}>
          {hint}
        </p>
      )}

      {shownError !== undefined && (
        <p className="ui-field__error" id={ids.errorId} role="alert">
          <span aria-hidden="true">⚠</span>
          <span>{shownError}</span>
        </p>
      )}
    </div>
  );
}
