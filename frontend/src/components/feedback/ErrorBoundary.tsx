import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  readonly children: ReactNode;
  readonly fallback?: ReactNode;
}

interface ErrorBoundaryState {
  readonly hasError: boolean;
}

/**
 * Catches render-time errors so a single broken widget does not blank the whole portal.
 *
 * The blueprint requires partial widget failure to be a designed state (section 6.1), which
 * means boundaries around dashboard widgets rather than one at the root.
 *
 * Deliberately shows no error detail. A React error message can contain props — which on a
 * banking screen means balances and account numbers.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  override componentDidCatch(_error: Error, _errorInfo: ErrorInfo): void {
    // PHASE 1: no error-reporting service is configured yet, so this is intentionally a
    // no-op. It is NOT console.error, which would write component props — balances,
    // account numbers — into the browser log.
    //
    // When a reporting service is adopted, report here and configure it to strip request
    // bodies, form values and parameterised URLs (blueprint section 24).
  }

  override render(): ReactNode {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          <div role="alert" style={{ padding: 'var(--space-6)' }}>
            <h2>This part of the page did not load</h2>
            <p>
              Something went wrong on our side, and it is not something you did. Please reload
              the page. If it keeps happening, call the number on the back of your card.
            </p>
          </div>
        )
      );
    }

    return this.props.children;
  }
}
