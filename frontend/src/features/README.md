# Features

One directory per banking capability. A feature owns its own components, hooks, validation
and types, and exposes a small public surface through an `index.ts`.

```
features/
  transfers/
    components/      # TransferForm, BeneficiaryPicker — used only by this feature
    hooks/           # useOwnTransfer
    validation.ts    # client-side rules, never a substitute for server validation
    types.ts
    index.ts         # the only file other features may import from
```

## Boundaries

- A feature may import from `components/`, `services/`, `hooks/`, `utils/`, `types/`.
- A feature must **not** import from another feature's internals. Cross-feature reuse means
  the shared part belongs in `components/` or `utils/`.
- Pages compose features; features do not own routes.
- Anything money-moving reuses the shared transaction engine (Phase 9) rather than
  implementing its own entry → review → verify → process → result flow. The blueprint is
  emphatic about this (sections 9 and 17 of the brief); four independent transfer flows is
  the failure mode to avoid.

Empty until Phase 5. Phase 1 deliberately ships no feature code.
