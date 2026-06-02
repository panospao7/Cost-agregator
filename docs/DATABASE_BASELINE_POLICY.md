# Database Baseline Policy

## Current baseline

The app baseline database version is **v145**.

The app no longer supports automatic migration from schema versions below v145.

## Why

The historical migration chain v6→v145 accumulated schema drift:
- stale indices from old migration paths
- missing columns never added by any migration
- duplicate column/index attempts in gap-jump migrations
- fresh-install callback creating tables/indices outside Room entity declarations
- Room post-migration validation failures on 20+ version jumps

The developer/user data was recovered through the financial rescue/import path.

## Supported upgrade policy

**Supported:**
- v145 → v146 (future incremental migrations)
- v146 → v147, etc.

**Not supported:**
- Automatic Room migration from v6–v144

**Recovery path for old DBs:**
- Use `FinancialRescueCoordinator` / `RescueActivity`
- Import only financial data (expenses, categories, groups, splits)
- Creates fresh latest-schema database

## Rules

1. No `fallbackToDestructiveMigration()` for normal builds.
2. Fresh-install callbacks must NOT create/drop/alter tables or indices (seed data only).
3. Schema must come from Room entities + registered migrations only.
4. Every schema version bump must export schema JSON.
5. Every future migration must have a test.
6. Migrations must use explicit column lists (no `SELECT *`).
7. Do not edit old migrations after release.
8. Disposable tables may be dropped/recreated only in explicit migrations.
