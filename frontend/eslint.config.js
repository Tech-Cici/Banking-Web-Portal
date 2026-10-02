import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * Flat ESLint config.
 *
 * Pinned to ESLint 9 because eslint-plugin-jsx-a11y@6.10.2 declares a peer range of
 * `^3 || ... || ^9`, so ESLint 10 is an unresolvable conflict. Accessibility linting is a
 * blueprint requirement (WCAG 2.2 AA, section 23), so the plugin wins and ESLint stays on
 * 9 until jsx-a11y ships ESLint 10 support. Revisit then.
 */
export default tseslint.config(
  {
    ignores: ['dist', 'coverage', 'node_modules', 'public/mockServiceWorker.js'],
  },

  // Type-aware linting for application source.
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.strictTypeChecked,
      ...tseslint.configs.stylisticTypeChecked,
      jsxA11y.flatConfigs.recommended,
      // v7 ships both eslintrc-style and flat variants; only `configs.flat.*` is flat.
      reactHooks.configs.flat['recommended-latest'],
    ],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-refresh': reactRefresh,
    },
    rules: {
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      /* --- Rules that exist for this being a banking client --- */

      // console.* is how OTPs, PANs and account numbers end up in a browser log or a
      // shipped bundle. Errors only, and even those go through a logger later.
      'no-console': 'error',

      // Either handle the promise or mark it deliberately ignored with void. An unhandled
      // rejection in a submit handler silently loses a transaction result.
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',

      // XSS. There is no legitimate use in this app.
      'react-hooks/react-compiler': 'off',
      'no-restricted-syntax': [
        'error',
        {
          selector: 'JSXAttribute[name.name="dangerouslySetInnerHTML"]',
          message:
            'dangerouslySetInnerHTML is banned (blueprint section 24). Render text, or sanitise server-side.',
        },
        {
          selector:
            'CallExpression[callee.object.name=/^(localStorage|sessionStorage)$/][callee.property.name=/^(setItem|getItem)$/]',
          message:
            'Do not use web storage directly. Long-lived tokens and sensitive drafts must not be persisted (blueprint section 24); use the session/draft services instead.',
        },
        {
          selector: 'MemberExpression[object.name="Math"][property.name="random"]',
          message:
            'Math.random is not cryptographically secure. Use crypto.randomUUID / crypto.getRandomValues for idempotency keys and identifiers.',
        },
      ],

      // Money must never be a float. Enforced at the type level too (see utils/money.ts).
      '@typescript-eslint/no-unsafe-assignment': 'error',
      '@typescript-eslint/no-explicit-any': 'error',

      // Bracket access on a Record<string, T> is the clearer spelling for a lookup map,
      // and with noUncheckedIndexedAccess it is already type-safe.
      '@typescript-eslint/dot-notation': ['error', { allowIndexSignaturePropertyAccess: true }],
      '@typescript-eslint/consistent-type-imports': [
        'error',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
      ],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/restrict-template-expressions': [
        'error',
        { allowNumber: true, allowBoolean: false, allowNullish: false },
      ],
    },
  },

  // Tests may lean on non-null assertions and unbound expectations.
  {
    files: ['**/*.{test,spec}.{ts,tsx}', 'src/test/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/unbound-method': 'off',
      'no-console': 'off',
    },
  },

  /*
   * Mock handlers deliberately shape loose fixture data.
   *
   * Web storage is also permitted HERE AND NOWHERE ELSE. The mock layer stands in for a
   * database, and an onboarding queue that vanished on every hot reload would make the
   * register -> create -> approve flow untestable. The ban exists to stop APPLICATION
   * code persisting tokens and sensitive drafts (blueprint section 24); it still applies
   * everywhere outside this directory, and none of this ships.
   */
  {
    files: ['src/mocks/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-unsafe-assignment': 'off',
      'no-restricted-syntax': [
        'error',
        {
          selector: 'JSXAttribute[name.name="dangerouslySetInnerHTML"]',
          message:
            'dangerouslySetInnerHTML is banned (blueprint section 24). Render text, or sanitise server-side.',
        },
        {
          selector: 'MemberExpression[object.name="Math"][property.name="random"]',
          message:
            'Math.random is not cryptographically secure. Use crypto.randomUUID / crypto.getRandomValues for idempotency keys and identifiers.',
        },
      ],
    },
  },

  // Node-side config files.
  {
    files: ['vite.config.ts', 'eslint.config.js'],
    languageOptions: {
      globals: globals.node,
    },
    rules: {
      'no-console': 'off',
    },
  },

  // Must stay last so formatting rules defer to Prettier.
  prettier,
);
