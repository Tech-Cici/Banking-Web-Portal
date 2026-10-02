import { setupWorker } from 'msw/browser';
import { appConfig } from '@/config/env';
import { handlersExcept } from './handlers';

/**
 * Browser-side mock worker, started from `main.tsx` when VITE_ENABLE_MOCK_API is true.
 *
 * The service worker file is generated into `public/` by `npx msw init public`. It is
 * committed so a fresh clone works, and excluded from lint and Prettier because it is
 * vendored output.
 */
/*
 * The live features are left out, so MSW passes them through to the real service. See
 * handlersExcept — this is where VITE_LIVE_API takes effect.
 */
export const worker = setupWorker(...handlersExcept(appConfig.liveApiPaths));
