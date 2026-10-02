import { useCallback, useEffect, useState, type ReactElement } from 'react';
import { LoadingState } from '@/components/feedback/LoadingState';
import { appConfig } from '@/config/env';
import { ApiError, healthService } from '@/services';
import type { HealthResponse } from '@/types/api';

/**
 * PHASE 1 DIAGNOSTIC PAGE.
 *
 * Exercises the whole client stack against the real backend: validated config, the
 * centralised API client, correlation-id propagation, CORS, and the normalised error model
 * with all four required UI states (blueprint section 47 — loading, empty, success, error).
 *
 * It is the browser-visible proof that the Phase 1 architecture works. Fold it into /help
 * as "service status" (blueprint 19.4) or delete it once real features exist.
 */

type Status =
  | { readonly state: 'loading' }
  | { readonly state: 'success'; readonly data: HealthResponse }
  | { readonly state: 'error'; readonly error: ApiError };

export function SystemStatusPage(): ReactElement {
  const [status, setStatus] = useState<Status>({ state: 'loading' });

  // Performs the request and reports the outcome. Deliberately does NOT set the loading
  // state: doing that synchronously inside an effect causes cascading renders
  // (react-hooks/set-state-in-effect). The initial state is already 'loading', and the
  // retry handler below resets it from an event, where a synchronous update is correct.
  const runCheck = useCallback((signal?: AbortSignal): void => {
    healthService
      .check(signal)
      .then((data) => {
        setStatus({ state: 'success', data });
      })
      .catch((cause: unknown) => {
        if (signal?.aborted === true) return;

        setStatus({
          state: 'error',
          error:
            cause instanceof ApiError
              ? cause
              : new ApiError({ kind: 'unknown', message: 'An unexpected error occurred.' }),
        });
      });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    runCheck(controller.signal);
    return () => {
      controller.abort();
    };
  }, [runCheck]);

  const retry = useCallback((): void => {
    setStatus({ state: 'loading' });
    runCheck();
  }, [runCheck]);

  return (
    <article>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-2)' }}>System status</h1>
      <p style={{ color: 'var(--color-text-muted)', marginBottom: 'var(--space-6)' }}>
        Phase 1 diagnostic: confirms this browser can reach the banking API.
      </p>

      <section
        aria-label="API connectivity"
        style={{
          padding: 'var(--space-6)',
          backgroundColor: 'var(--color-surface)',
          border: '1px solid var(--color-border)',
          borderRadius: 'var(--radius-lg)',
          maxWidth: '42rem',
        }}
      >
        {status.state === 'loading' && <LoadingState label="Checking connection to the API…" />}

        {status.state === 'success' && (
          <div>
            {/* Icon + text, never colour alone (blueprint section 23). */}
            <p style={{ color: 'var(--color-success)', fontWeight: 'var(--weight-semibold)' }}>
              <span aria-hidden="true">✓ </span>
              Connected
            </p>
            <dl style={{ marginTop: 'var(--space-4)', fontSize: 'var(--text-sm)' }}>
              <dt style={{ fontWeight: 'var(--weight-medium)' }}>Service</dt>
              <dd style={{ marginBottom: 'var(--space-2)' }}>{status.data.service}</dd>
              <dt style={{ fontWeight: 'var(--weight-medium)' }}>Reported status</dt>
              <dd style={{ marginBottom: 'var(--space-2)' }}>{status.data.status}</dd>
              <dt style={{ fontWeight: 'var(--weight-medium)' }}>Server time</dt>
              <dd>{new Date(status.data.time).toLocaleString()}</dd>
            </dl>
          </div>
        )}

        {status.state === 'error' && (
          <div role="alert">
            <p style={{ color: 'var(--color-error)', fontWeight: 'var(--weight-semibold)' }}>
              <span aria-hidden="true">⚠ </span>
              Not connected
            </p>
            <p style={{ marginTop: 'var(--space-2)' }}>{status.error.message}</p>

            {/* The reference a customer quotes to support (blueprint section 3). */}
            {status.error.correlationId !== undefined && (
              <p
                className="numeric"
                style={{
                  marginTop: 'var(--space-4)',
                  fontSize: 'var(--text-sm)',
                  color: 'var(--color-text-muted)',
                }}
              >
                Reference: {status.error.correlationId}
              </p>
            )}

            {status.error.isSafeToRetry && (
              <button
                type="button"
                onClick={retry}
                style={{
                  marginTop: 'var(--space-4)',
                  padding: 'var(--space-2) var(--space-4)',
                  color: 'var(--color-text-inverse)',
                  backgroundColor: 'var(--color-primary)',
                  border: 'none',
                  borderRadius: 'var(--radius-sm)',
                }}
              >
                Try again
              </button>
            )}
          </div>
        )}
      </section>

      <dl style={{ marginTop: 'var(--space-6)', fontSize: 'var(--text-sm)' }}>
        <dt style={{ fontWeight: 'var(--weight-medium)' }}>API base URL</dt>
        <dd style={{ marginBottom: 'var(--space-2)' }}>{appConfig.apiBaseUrl}</dd>
        <dt style={{ fontWeight: 'var(--weight-medium)' }}>Mock API</dt>
        <dd style={{ marginBottom: 'var(--space-2)' }}>
          {appConfig.enableMockApi ? 'Enabled' : 'Disabled'}
        </dd>
        <dt style={{ fontWeight: 'var(--weight-medium)' }}>Build</dt>
        <dd>
          {appConfig.appVersion} ({appConfig.mode})
        </dd>
      </dl>
    </article>
  );
}
