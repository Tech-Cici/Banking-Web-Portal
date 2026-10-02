import type { ReactElement } from 'react';
import { RouterProvider } from 'react-router-dom';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
import { SessionProvider } from '@/contexts/SessionProvider';
import { router } from '@/routes/router';

/*
 * The development session switcher is GONE.
 *
 * It let you jump between three seeded users. There are no seeded users any more —
 * every customer is registered, created by an admin and approved by a manager — so
 * there is nothing to switch to, and a control that adopts another customer's session
 * has no place in a banking app even in development.
 */

/**
 * Application root.
 *
 * Kept deliberately thin. Cross-cutting providers — session, permissions, corporate
 * context, toasts, query cache — are added here as their phases land, each in its own
 * module under `src/app/providers/` rather than inline, so this file never becomes the
 * place where everything is wired.
 */
export function App(): ReactElement {
  return (
    <ErrorBoundary>
      <SessionProvider>
        <RouterProvider router={router} />
      </SessionProvider>
    </ErrorBoundary>
  );
}
