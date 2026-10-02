# Database migrations

Flyway owns the schema. Hibernate's `ddl-auto` is pinned to `none` in every profile,
including tests, so the schema a test runs against is the schema production runs against.

Nothing lives here yet — Phase 1 introduces no entities, and `spring.flyway.enabled` is
`false` until the first file below appears.

## Rules

- Name files `V<n>__<snake_case_description>.sql`, numbered sequentially: `V1__create_app_user.sql`.
- A migration that has been merged is immutable. Correct it with a new migration; never
  edit one in place, because Flyway validates checksums and a changed file fails startup
  on every environment that already applied it.
- Write migrations against PostgreSQL. The `dev` profile's H2 is a convenience, not the
  target; verify every migration with `-Dspring-boot.run.profiles=postgres`.
- Additive first: add a nullable column, backfill, then enforce `NOT NULL` in a later
  migration. A single blocking `ALTER` on a large table is an outage.
- No `DROP` of a column or table that a running version still reads. Deploys are not atomic.
- Money columns are `NUMERIC(19,4)` — never `float`/`double`. Store the currency alongside
  every amount, and never mix currencies in one column without it.
- Every table carries `created_at`/`updated_at` as `TIMESTAMPTZ`, stored in UTC.

## Enabling Flyway

When the first migration lands, set `spring.flyway.enabled: true` in `application.yml`
and delete this section.
