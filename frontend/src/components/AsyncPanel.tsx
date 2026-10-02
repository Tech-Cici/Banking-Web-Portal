import type { ReactElement, ReactNode } from 'react';
import { Alert, ErrorNotice, Panel, Skeleton } from '@/components/ui';
import type { AsyncState } from '@/hooks/useAsync';

interface AsyncPanelProps<T> {
  readonly title: string;
  readonly subtitle?: string | undefined;
  readonly action?: ReactNode;
  readonly state: AsyncState<T>;
  readonly reload: () => void;
  /** Rows of skeleton to draw while loading — roughly the panel's settled height. */
  readonly skeletonRows?: number;
  /**
   * What this panel was loading, finishing "We could not …" — for example
   * `"load your accounts"`. Defaults to the title lower-cased, which reads acceptably
   * for most panels but is worth overriding where it does not.
   */
  readonly loadingWhat?: string;
  readonly children: (data: T) => ReactNode;
}

/**
 * A {@link Panel} that shows loading, failure and content for one piece of data.
 *
 * The three states are kept distinct on purpose. "Nothing to show" and "we could not
 * find out" look identical if a failure silently renders an empty list, and on an
 * approvals queue that difference is the difference between going home and staying to
 * release a payroll.
 *
 * A 403 is phrased as a limit on the account rather than as a fault. Someone without a
 * permission has not hit an error; they simply have a different job.
 */
export function AsyncPanel<T>({
  title,
  subtitle,
  action,
  state,
  reload,
  skeletonRows = 3,
  loadingWhat,
  children,
}: AsyncPanelProps<T>): ReactElement {
  return (
    <Panel title={title} subtitle={subtitle} action={action}>
      {state.status === 'loading' && <Skeleton rows={skeletonRows} label={`Loading ${title}`} />}

      {state.status === 'error' &&
        (state.kind === 'forbidden' ? (
          <Alert tone="info">
            Your access does not include this. If you need it, speak to whoever manages
            your access.
          </Alert>
        ) : (
          <ErrorNotice
            error={state.cause}
            action={loadingWhat ?? `load ${title.toLowerCase()}`}
            onRetry={reload}
          />
        ))}

      {state.status === 'ready' && children(state.data)}
    </Panel>
  );
}
