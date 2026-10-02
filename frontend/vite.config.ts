/// <reference types="vitest/config" />
import { rm } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';

/**
 * Removes the MSW service worker from a production bundle.
 *
 * `public/mockServiceWorker.js` is committed so a fresh clone can run with mocks, but
 * anything copied out of `public/` is emitted unconditionally. A registerable request
 * interceptor served from the production origin is a liability, so it is deleted after the
 * bundle is written.
 */
function stripMockServiceWorker(): Plugin {
  let isProduction = false;
  let outDir = 'dist';

  return {
    name: 'strip-mock-service-worker',
    apply: 'build',
    configResolved(config) {
      // Taken from Vite's resolved config rather than process.env, so `vite build
      // --mode development` is correctly treated as a non-production build.
      isProduction = config.isProduction;
      outDir = config.build.outDir;
    },
    async closeBundle() {
      if (!isProduction) return;
      await rm(join(outDir, 'mockServiceWorker.js'), { force: true });
    },
  };
}

export default defineConfig({
  plugins: [react(), stripMockServiceWorker()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    port: 5173,
    // Fail loudly rather than silently moving to another port: the backend CORS
    // allow-list names 5173 explicitly, so a silent move breaks API calls in a way
    // that looks like a CORS bug.
    strictPort: true,
  },

  preview: {
    port: 4173,
    strictPort: true,
  },

  build: {
    // Keep sourcemaps out of production assets. They reproduce the original source,
    // which for a banking client hands an attacker a readable copy of the app.
    // Build them separately for the error tracker if one is adopted.
    sourcemap: false,
    target: 'es2022',
    reportCompressedSize: true,
  },

  test: {
    environment: 'jsdom',
    // `.env.development` is not loaded in test mode, and src/config/env.ts fails fast on
    // missing variables — by design. Supplied here so it is visible rather than hidden in
    // a dotfile. The MSW server intercepts regardless of VITE_ENABLE_MOCK_API, which only
    // controls the browser worker.
    env: {
      VITE_API_BASE_URL: 'http://localhost:8080/api/v1',
      VITE_API_TIMEOUT_MS: '30000',
      VITE_ENABLE_MOCK_API: 'false',
      VITE_APP_VERSION: '0.0.0-test',
    },
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    restoreMocks: true,
    clearMocks: true,
    coverage: {
      provider: 'v8',
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.{test,spec}.{ts,tsx}',
        'src/test/**',
        'src/mocks/**',
        'src/main.tsx',
        'src/vite-env.d.ts',
      ],
    },
  },
});
