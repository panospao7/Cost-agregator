# DB Migration / Schema / Constraints Implementation Plan

Last updated: 2026-06-15  
Scope: MIT-010, MIT-011, MIT-033, MIT-078  
Goal: prove old DBs migrate safely, fresh/migrated schemas match, and dedupe/idempotency is enforced at DB level.

---

## 1. Objective

Make the Room/SQLite database release-safe by ensuring:

- every supported historical DB version migrates to latest,
- unsupported DB versions are handled intentionally,
- fresh-install schema equals migrated schema,
- historical migrations do not silently drop user data,
- critical indexes/constraints exist on both fresh and migrated DBs,
- dedupe/idempotency is protected by DB constraints, not only app logic,
- migration failures are caught in CI before release.

This plan owns the database foundation for P2, P4, P7, P10, P11, P12, P13, and P18.

---

## 2. Master Issues Covered

| MIT | Issue | Owned Here |
|---|---|---|
| MIT-010 | Register full DB migration chain or baseline policy | Yes |
| MIT-011 | Prove fresh schema equals migrated schema and declare critical indexes | Yes |
| MIT-033 | Add DB-level uniqueness/idempotency constraints | Yes |
| MIT-078 | Historical migration data-loss hotspots | Yes |

Related but not owned:

| MIT | Relationship |
|---|---|
| MIT-004 | CI migration matrix executes this plan |
| MIT-012 | Backup verifier depends on complete schema/table coverage |
| MIT-030 | Write barrier depends on legal DAO/database ownership |
| MIT-036 | DAO ownership policy depends on schema/constraint clarity |
| MIT-047 | Import coordinator depends on import idempotency tables |
| MIT-080 | Import/export contract depends on schema-supported fields |

---

## 3. Affected Pipelines

| Pipeline | Why Affected |
|---|---|
| P2 | Expense lifecycle, source links, duplicate create behavior |
| P4 | Recurring actual links, reminder/occurrence uniqueness |
| P7 | Backup/restore verifier and restore compatibility |
| P10 | Bank connection/transaction idempotency |
| P11 | Email receipt dedupe/race prevention |
| P12 | Export/import/accounting schema completeness |
| P13 | DB schema, migrations, DAO constraints |
| P18 | Import file/row/provenance/idempotency constraints |

---

## 4. Current Problem Summary

The reports indicate:

- runtime migration registration may only include recent migrations, while older inline migrations may not be registered,
- older installed DBs below v145 may fail to migrate,
- fresh schema may not match migrated schema because some indexes exist only in historical migrations,
- backup verifier table coverage is stale,
- DAO ownership/allowlist and schema constraints disagree,
- dedupe/idempotency constraints are incomplete,
- pending-review migration around `144→145` may be a data-loss hotspot,
- nullable fingerprint/dedupe columns may not actually enforce uniqueness,
- import/bank/email/recurring/group/operation dedupe relies too much on app logic.

---

## 5. Architecture Decision

### Decision 1 — Define supported DB version policy

The project must explicitly choose:

```text
minimumSupportedDbVersion = X
latestDbVersion = current Room version
```

For versions `< X`, choose one of:

1. unsupported with safe user-facing error,
2. destructive migration only after explicit user confirmation,
3. special legacy migration path.

### Recommendation

Use this policy:

- Support all versions that could exist in real user installs.
- For versions impossible in production/demo history, document them as unsupported.
- Never silently destructive-migrate user databases.
- If destructive fallback is necessary, require explicit backup/export/user confirmation.

---

### Decision 2 — Prefer explicit migrations over hidden assumptions

Every supported version jump must be registered in the Room builder:

```text
vX -> vX+1 -> ... -> latest
```

Avoid relying on stale inline migrations that are not included in runtime `DatabaseMigrations.ALL`.

---

### Decision 3 — Constraints must be schema-owned

Critical indexes/constraints must exist in one of:

- Room entity declarations, where Room supports them,
- explicit verified migrations,
- tested schema creation SQL.

They must not exist only accidentally because of old historical migrations.

---

### Decision 4 — DB-level dedupe is mandatory for race-prone paths

Any path where duplicate writes can happen concurrently must have DB support:

- unique index,
- partial unique index,
- claim table,
- idempotency table,
- or transactionally locked lookup/insert pattern.

App-level “check then insert” is insufficient.

---

## 6. Non-Negotiable Invariants

After this plan:

- [ ] Runtime Room builder registers every supported migration.
- [ ] Minimum supported DB version is documented.
- [ ] Unsupported versions fail safely and intentionally.
- [ ] Fresh install schema and migrated schema are equivalent.
- [ ] Critical indexes exist in both fresh and migrated DBs.
- [ ] No supported migration drops user data silently.
- [ ] Migration tests use non-empty representative data.
- [ ] Unique/idempotency constraints prevent duplicate races.
- [ ] Nullable dedupe columns are either populated before constraint use or not relied on.
- [ ] DB-level constraints match repository/import/bank/email/recurring behavior.
- [ ] CI fails on missing migration, schema drift, or data-loss hotspot.

---

# 7. Implementation Phases

---

## Phase 0 — Database Baseline Inventory

### Goal

Know the current database reality before changing migrations.

### Tasks

- [ ] Record latest Room DB version.
- [ ] Find all `@Database(version = ...)` declarations.
- [ ] Find Room builder setup.
- [ ] Find `DatabaseMigrations.ALL` or equivalent.
- [ ] Inventory all migration classes/objects.
- [ ] Inventory historical inline migrations.
- [ ] Inventory `app/schemas/**`.
- [ ] Inventory all entities.
- [ ] Inventory all DAO mutators.
- [ ] Inventory all unique/index annotations.
- [ ] Inventory raw SQL indexes created in migrations.
- [ ] Inventory dropped/recreated table migrations.
- [ ] Inventory nullable fingerprint/dedupe columns.
- [ ] Create `docs/db/DB_BASELINE_INVENTORY.md`.

### Commands to run locally

```bash
rg "@Database" app/src
rg "Migration\\(" app/src
rg "AutoMigration" app/src
rg "DatabaseMigrations" app/src
rg "fallbackToDestructiveMigration|addMigrations|Room.databaseBuilder" app/src
rg "CREATE INDEX|CREATE UNIQUE INDEX|DROP TABLE|ALTER TABLE|CREATE TABLE" app/src
rg "dedupe|fingerprint|external|sourceLink|pending_review|PendingReview" app/src
```

### Deliverables

- `docs/db/DB_BASELINE_INVENTORY.md`
- Table of every migration.
- Table of every schema version file.
- Table of every critical index/constraint.
- Table of every possible data-loss migration.

### Acceptance Criteria

- [ ] You can answer: “which versions are supported?”
- [ ] You can answer: “which migrations are registered at runtime?”
- [ ] You can answer: “which indexes exist only in migrations?”

---

## Phase 1 — Define Supported Version and Migration Policy

### Goal

Make old-version behavior explicit.

### Tasks

- [ ] Choose `minimumSupportedDbVersion`.
- [ ] List versions that real users may have.
- [ ] List versions that only existed in local/dev builds.
- [ ] Decide policy for versions below minimum.
- [ ] Remove accidental destructive fallback if present.
- [ ] If destructive fallback remains, require explicit reason and tests.
- [ ] Document policy in `docs/db/MIGRATION_SUPPORT_POLICY.md`.

### Required policy fields

```md
# Migration Support Policy

Latest DB version:
Minimum supported DB version:

Supported:
- vX -> latest
- vY -> latest

Unsupported:
- versions below X

Unsupported behavior:
- safe error / backup prompt / explicit destructive reset

Destructive migration:
- allowed? yes/no
- if yes, under what user-visible conditions?

Test matrix:
- PR matrix
- nightly/release matrix
```

### Acceptance Criteria

- [ ] No ambiguity around old installs.
- [ ] CI and code agree on supported minimum version.
- [ ] Users are not silently data-wiped.

---

## Phase 2 — Register Complete Runtime Migration Chain

### Goal

Make Room runtime migration registration match support policy.

### Tasks

- [ ] Ensure every supported migration is present in code.
- [ ] Register every supported migration in `DatabaseMigrations.ALL`.
- [ ] Confirm Room builder uses `DatabaseMigrations.ALL`.
- [ ] Remove stale migrations not used by supported policy or mark dev-only.
- [ ] Avoid duplicate/conflicting migrations.
- [ ] Add test that enumerates expected migration versions.
- [ ] Add test that Room builder receives all expected migrations.

### Specific check

If reports are correct and `DatabaseMigrations.ALL` only contains `145→146` and `146→147`, then:

- [ ] recover/register historical `6→145` migration chain, or
- [ ] define baseline version `145`, and
- [ ] intentionally reject versions below `145`.

### Acceptance Criteria

- [ ] A supported DB below latest migrates successfully.
- [ ] A missing supported migration fails tests.
- [ ] Unsupported versions follow documented policy.

---

## Phase 3 — Build Real Migration Test Harness

### Goal

Prove migrations execute with real data.

### Tools

For Android Room, prefer:

- `MigrationTestHelper`,
- exported Room schemas,
- generated historical DB fixtures,
- JVM/Robolectric if viable,
- instrumentation if Room test helper requires it.

### Required test classes

Create or update:

```text
DatabaseMigrationMatrixTest
FreshVsMigratedSchemaParityTest
HistoricalDataPreservationMigrationTest
MigrationConstraintParityTest
UnsupportedVersionPolicyTest
```

### PR migration matrix

Run fast representative set:

```text
minimumSupported -> latest
last_pre_145_supported -> latest
144 -> latest, if supported
145 -> latest
146 -> latest
latest - 1 -> latest
fresh latest schema
```

### Release/nightly migration matrix

Run every supported version:

```text
for version in minimumSupported..latest-1:
    version -> latest
```

### Acceptance Criteria

- [ ] Tests fail if migration is missing.
- [ ] Tests fail if schema validation fails.
- [ ] Tests fail if representative data disappears.
- [ ] Tests run in CI through MIT-004.

---

## Phase 4 — Create Representative Historical Data Fixtures

### Goal

Migration tests must detect real data loss, not only schema validity.

### Required representative data

Each historical fixture should include rows for tables that existed at that version.

At latest, coverage should include:

#### Core finance

- [ ] expenses,
- [ ] categories,
- [ ] payment methods/accounts if present,
- [ ] expense source links,
- [ ] transaction lifecycle/audit events.

#### Currency/money

- [ ] currencies,
- [ ] exchange rates,
- [ ] conversion status/stale-rate fields if present.

#### Receipts/review

- [ ] scanned receipts,
- [ ] receipt links,
- [ ] pending review rows,
- [ ] OCR/raw-text privacy fields,
- [ ] parsed items JSON if present.

#### Notifications

- [ ] raw notifications,
- [ ] dedupe fingerprints,
- [ ] notification intake state/diagnostics if present.

#### Recurring/reminders

- [ ] recurring rules,
- [ ] recurring occurrences,
- [ ] reminders,
- [ ] planned/manual recurring rows,
- [ ] linked actual expense IDs.

#### Bank/email/import

- [ ] bank connections,
- [ ] bank transactions,
- [ ] bank import/review rows,
- [ ] email receipt sources,
- [ ] import runs/batches/rows if present.

#### Groups/shared/business

- [ ] group expenses,
- [ ] split/member/payment rows,
- [ ] shared/not-mine flags,
- [ ] business/project fields.

#### Operations

- [ ] operation runs,
- [ ] operation events,
- [ ] restore/import/export ledgers if present.

### Fixture rules

- [ ] Include at least one row per critical table.
- [ ] Include nullable and non-null variants.
- [ ] Include duplicate-edge rows where old schema allowed duplicates.
- [ ] Include privacy-sensitive fields to ensure sanitization columns survive.
- [ ] Include source/provenance fields.

### Acceptance Criteria

- [ ] Migration tests would fail if any critical table is dropped without copy.
- [ ] Migration tests would fail if dedupe/provenance fields are lost.
- [ ] Migration tests would fail if privacy fields are reset incorrectly.

---

## Phase 5 — Fresh vs Migrated Schema Parity

### Goal

A fresh install and a migrated old install must produce equivalent latest schemas.

### What to compare

- [ ] table names,
- [ ] column names,
- [ ] column types,
- [ ] nullability,
- [ ] default values,
- [ ] primary keys,
- [ ] foreign keys,
- [ ] indexes,
- [ ] unique indexes,
- [ ] partial indexes,
- [ ] triggers,
- [ ] views,
- [ ] check constraints, if any.

### Implementation approach

Create two DBs:

```text
A = fresh latest DB
B = old fixture migrated to latest
```

Query SQLite metadata:

```sql
PRAGMA table_info(table_name);
PRAGMA foreign_key_list(table_name);
PRAGMA index_list(table_name);
PRAGMA index_info(index_name);
PRAGMA index_xinfo(index_name);
SELECT sql FROM sqlite_master WHERE type IN ('table','index','trigger','view');
```

Normalize SQL before comparing:

- ignore whitespace,
- ignore harmless ordering if needed,
- preserve uniqueness/partial `WHERE` clauses,
- preserve FK actions.

### Acceptance Criteria

- [ ] Fresh and migrated DB schemas are equivalent.
- [ ] Indexes that existed only in migrations are either declared or recreated for fresh installs.
- [ ] Any intentional difference is documented and tested.

---

## Phase 6 — Historical Data-Loss Hotspot Review

### Goal

Find and fix migrations that silently drop real data.

### High-risk patterns

Search for:

```sql
DROP TABLE
CREATE TABLE new_
INSERT INTO new_ SELECT
ALTER TABLE RENAME
DELETE FROM
UPDATE ... SET column = NULL
```

### Tasks

- [ ] Review every drop/recreate migration.
- [ ] Confirm all columns are copied or intentionally transformed.
- [ ] Confirm FKs/indexes are recreated.
- [ ] Confirm timestamps/provenance/privacy fields survive.
- [ ] Confirm nullable fingerprint fields are backfilled if needed.
- [ ] Add tests with non-empty data for each drop/recreate table.
- [ ] Specifically review pending-review migration around `144→145`.
- [ ] If user data was previously dropped, add repair/backfill or documented unsupported policy.

### Pending-review hotspot checklist

- [ ] Did old `pending_review` data exist before migration?
- [ ] Was the table dropped/recreated?
- [ ] Were existing rows copied?
- [ ] Are source references preserved?
- [ ] Are privacy-safe payload fields preserved?
- [ ] Are timestamps/status fields preserved?
- [ ] Do new constraints reject migrated rows?
- [ ] Is there a fallback if rows cannot be migrated?

### Acceptance Criteria

- [ ] No supported migration silently drops non-empty user tables.
- [ ] Pending-review migration behavior is proven by test.
- [ ] Every destructive transformation has explicit product policy.

---

# 8. DB-Level Dedupe and Idempotency Constraints

---

## Phase 7 — Define Dedupe Constraint Matrix

### Goal

Create one source of truth for dedupe/idempotency constraints.

Create:

```text
docs/db/DB_DEDUPE_CONSTRAINT_MATRIX.md
```

### Required columns

```md
| Domain | Table | Natural Key | Nullable? | Constraint Type | Conflict Behavior | Owner | Tests |
```

### Domains to include

- expenses,
- expense source links,
- raw notifications,
- receipts,
- email receipts,
- recurring actual links,
- bank connections,
- bank transactions,
- import runs,
- import rows,
- operation events,
- group expenses,
- categories/import-created categories.

---

## Phase 8 — Add/Verify Critical Constraints

### 8.1 Email receipt dedupe

Problem:

Email message hash/fingerprint may be indexed but not unique.

Tasks:

- [ ] Identify privacy-safe message hash column.
- [ ] Ensure it is always populated for legal paths.
- [ ] Add unique index or claim table.
- [ ] Use partial unique index if nullable legacy rows exist.
- [ ] Add concurrent ingestion test.

Possible constraint shape:

```text
UNIQUE(provider, accountHash, messageHash)
or
UNIQUE(privacySafeFingerprint)
WHERE privacySafeFingerprint IS NOT NULL
```

Acceptance:

- [ ] Two workers cannot create duplicate receipt/expense for same email.

---

### 8.2 Recurring linked actual expense uniqueness

Problem:

Same actual expense can fulfill multiple recurring rules.

Tasks:

- [ ] Identify table/column storing `linkedExpenseId`.
- [ ] Add unique partial index on linked actual expense where non-null.
- [ ] Decide if one actual may intentionally link to multiple rules; if yes, define join table semantics.
- [ ] Backfill or resolve existing duplicates before adding unique index.
- [ ] Add duplicate-link migration test.

Acceptance:

- [ ] Same actual expense cannot accidentally fulfill multiple recurring obligations.

---

### 8.3 Bank transaction idempotency

Problem:

Provider transaction ID may not be scoped by provider/account/connection.

Tasks:

- [ ] Define canonical bank transaction identity:
  - provider ID,
  - connection/account ID,
  - provider transaction ID,
  - optionally booking date/amount/currency if provider ID absent.
- [ ] Add unique index for strict external IDs.
- [ ] Add fallback fingerprint unique/claim table for statement imports.
- [ ] Add API-vs-statement dedupe tests.

Acceptance:

- [ ] Same provider transaction ID on different accounts does not conflict.
- [ ] Same transaction from API and statement import does not duplicate.

---

### 8.4 Bank connection uniqueness

Problem:

Connection uniqueness may be too weak, e.g. only `bankId`.

Tasks:

- [ ] Define connection natural key:
  - provider,
  - institution/bank,
  - account ID hash,
  - user/account scope if applicable.
- [ ] Add unique index.
- [ ] Handle legacy duplicates.
- [ ] Add migration test.

Acceptance:

- [ ] Multiple accounts at same bank are allowed if intended.
- [ ] Duplicate same account connection is rejected/idempotent.

---

### 8.5 Import run/file/row idempotency

Problem:

CSV/JSON import lacks stable row identity.

Tasks:

- [ ] Add/import fields:
  - `fileImportRunId`,
  - `fileHash`,
  - `csvImportBatchId`,
  - `csvRowNumber`,
  - row fingerprint,
  - source link.
- [ ] Add unique file/run constraint as appropriate.
- [ ] Add unique row constraint:
  - `(fileHash, rowNumber)` for stable file imports,
  - or `(batchId, rowNumber)`,
  - or privacy-safe row fingerprint.
- [ ] Ensure valid CSV rows populate provenance before validation.
- [ ] Add duplicate import tests.

Acceptance:

- [ ] Re-importing same file/row does not create duplicate expenses unless user chooses duplicate mode.

---

### 8.6 Operation event idempotency

Problem:

Operation events may duplicate under retry.

Tasks:

- [ ] Define operation event idempotency key:
  - operationRunId,
  - phase,
  - eventType,
  - entityId,
  - correlationId.
- [ ] Add unique index if appropriate.
- [ ] Or allow duplicates only with sequence number.
- [ ] Add retry tests.

Acceptance:

- [ ] Retried event writes do not create misleading duplicate lifecycle events.

---

### 8.7 Group/shared expense constraints

Tasks:

- [ ] Identify group expense natural keys.
- [ ] Add unique constraints for member rows.
- [ ] Add unique constraints for split/payment rows.
- [ ] Ensure shared/not-mine flags survive migrations.
- [ ] Add duplicate member/payment tests.

Acceptance:

- [ ] Concurrent group/split writes do not duplicate members or payments.

---

### 8.8 Raw notification dedupe fingerprints

Tasks:

- [ ] Identify `raw_notifications.dedupeFingerprint`.
- [ ] Ensure legal paths populate it.
- [ ] Decide constraint:
  - unique fingerprint,
  - unique package/key/content hash,
  - or claim table.
- [ ] Handle nullable legacy rows.
- [ ] Add same-key/different-content test.

Acceptance:

- [ ] Duplicate notification rows are prevented without collapsing different content incorrectly.

---

### 8.9 Receipt fingerprints/source links

Tasks:

- [ ] Identify receipt privacy-safe fingerprint.
- [ ] Ensure redacted modes still provide safe dedupe key or explicit no-dedupe behavior.
- [ ] Add unique partial index where possible.
- [ ] Ensure receipt-expense link consistency is backed by FK/unique constraints where appropriate.

Acceptance:

- [ ] Duplicate receipt ingestion races are DB-prevented where supported.

---

### 8.10 Category/import category safety

Tasks:

- [ ] Define category natural key:
  - normalized name,
  - parent/category type,
  - user scope if any.
- [ ] Decide whether import-created categories are allowed to auto-create.
- [ ] If allowed, enforce uniqueness on normalized key.
- [ ] Ensure failed import row cannot leave stray category behind via transaction.

Acceptance:

- [ ] Concurrent import/category creation cannot create duplicates.

---

## Phase 9 — Existing Duplicate Cleanup / Backfill

### Goal

You cannot add unique constraints if existing data violates them.

### Tasks

For each new unique constraint:

- [ ] Query for duplicates.
- [ ] Define conflict resolution policy.
- [ ] Create migration/backfill.
- [ ] Preserve source links/audit evidence.
- [ ] Mark unresolved conflicts for `PendingReview` if needed.
- [ ] Add migration tests with duplicate legacy rows.

### Conflict policies

Choose per domain:

#### Merge

Use when rows are semantically identical.

#### Keep earliest, link later

Use when duplicate source should point to existing entity.

#### Keep both with disambiguated key

Use when old data cannot be safely merged.

#### Move to review

Use when automated merge is unsafe.

### Acceptance Criteria

- [ ] New constraints can be applied to real legacy data.
- [ ] Duplicate cleanup is deterministic.
- [ ] User-visible data is not lost silently.

---

# 9. Fresh Schema Declaration Rules

---

## Phase 10 — Move Critical Indexes Into Entity or Verified Create Path

### Goal

Fresh installs should not miss indexes that migrated installs have.

### Tasks

- [ ] For every critical migration-created index, check entity declaration.
- [ ] If Room supports it, add `@Index`.
- [ ] If partial/complex index not supported by Room annotation, add verified DB creation hook/migration strategy and parity test.
- [ ] Update exported schema.
- [ ] Confirm fresh DB contains same index.

### Critical indexes include

- [ ] expense source links,
- [ ] email receipt dedupe,
- [ ] bank transaction identity,
- [ ] bank connection identity,
- [ ] recurring linked actual expense,
- [ ] import file/row identity,
- [ ] operation event idempotency,
- [ ] group/shared/member/payment keys,
- [ ] raw notification fingerprint,
- [ ] receipt fingerprint.

### Acceptance Criteria

- [ ] Fresh install gets the same critical uniqueness/dedupe protection as migrated install.

---

# 10. Testing Strategy

---

## 10.1 Migration registration tests

Tests:

- [ ] `DatabaseMigrations.ALL` contains all expected version pairs.
- [ ] No duplicate version pairs.
- [ ] No gaps in supported chain.
- [ ] Room builder uses expected migration set.

---

## 10.2 Migration execution tests

Tests:

- [ ] supported minimum -> latest,
- [ ] every supported version -> latest in release/nightly,
- [ ] representative versions -> latest in PR,
- [ ] unsupported version behavior.

---

## 10.3 Data preservation tests

For each hotspot:

- [ ] insert old-version representative data,
- [ ] migrate,
- [ ] assert row exists,
- [ ] assert important fields preserved,
- [ ] assert FK/source/provenance preserved,
- [ ] assert privacy fields preserved,
- [ ] assert dedupe fields backfilled or intentionally null.

---

## 10.4 Schema parity tests

Tests:

- [ ] fresh latest vs migrated latest table parity,
- [ ] index parity,
- [ ] FK parity,
- [ ] defaults/nullability parity,
- [ ] trigger/view parity if present.

---

## 10.5 Constraint tests

For each new unique/idempotency constraint:

- [ ] duplicate insert fails or returns expected conflict code,
- [ ] valid distinct rows succeed,
- [ ] null/legacy behavior is intentional,
- [ ] concurrent insertion race is prevented.

Domains:

- [ ] email receipt,
- [ ] recurring linked actual,
- [ ] bank transaction,
- [ ] bank connection,
- [ ] import row,
- [ ] operation event,
- [ ] group/member/payment,
- [ ] raw notification,
- [ ] receipt fingerprint,
- [ ] category normalized key.

---

## 10.6 Repository behavior tests

After DB constraints:

- [ ] repository catches constraint conflicts and maps to typed duplicate/idempotent result,
- [ ] no crash leaks raw SQL exception to UI,
- [ ] duplicate source-link policy links to existing entity,
- [ ] import duplicate row returns row-level duplicate result,
- [ ] bank duplicate transaction maps to idempotent sync result.

---

# 11. Static / CI Guard Requirements

This plan depends on MIT-004 and MIT-003 guard work.

### Required CI checks

- [ ] migration execution matrix,
- [ ] fresh/migrated schema parity,
- [ ] exported schema freshness,
- [ ] no unregistered migration,
- [ ] no destructive fallback unless allowlisted,
- [ ] no DAO/entity schema drift,
- [ ] no missing critical index for dedupe matrix entries.

### Suggested new script

```text
scripts/verify_db_migration_boundaries.py
```

It should check:

- [ ] `DatabaseMigrations.ALL` has no gaps for supported range,
- [ ] latest Room version has exported schema file,
- [ ] no `fallbackToDestructiveMigration` in release builder unless allowlisted,
- [ ] all dedupe matrix constraints are present in schema,
- [ ] every allowlist has owner/reason/expiry.

### Acceptance Criteria

- [ ] CI fails if developer adds DB version without migration/test.
- [ ] CI fails if critical index is removed.
- [ ] CI fails if destructive migration is introduced accidentally.

---

# 12. Rollout / PR Plan

---

## PR 1 — DB Baseline Inventory and Policy

### Includes

- `docs/db/DB_BASELINE_INVENTORY.md`
- `docs/db/MIGRATION_SUPPORT_POLICY.md`
- current latest/minimum version decision
- migration chain inventory
- schema/index inventory

### Acceptance

- [ ] Supported DB version policy is explicit.
- [ ] Migration gaps are known.
- [ ] No runtime behavior changed yet unless obvious safe fix.

---

## PR 2 — Runtime Migration Chain Fix

### Includes

- register full supported migration chain,
- remove/mark stale migrations,
- assert expected migration pairs,
- ensure Room builder uses correct migration set.

### Acceptance

- [ ] Supported versions migrate at runtime.
- [ ] Missing migration test fails before fix and passes after.

---

## PR 3 — Migration Test Harness MVP

### Includes

- `DatabaseMigrationMatrixTest`,
- representative old fixtures,
- PR-level matrix,
- unsupported version policy test.

### Acceptance

- [ ] Representative old DB migrates to latest.
- [ ] Unsupported version behavior is tested.
- [ ] CI can run tests.

---

## PR 4 — Fresh vs Migrated Schema Parity

### Includes

- schema parity helper,
- fresh latest DB creation,
- migrated latest DB creation,
- metadata comparison.

### Acceptance

- [ ] Index/constraint drift is detected.
- [ ] Existing drift is fixed or documented.

---

## PR 5 — Data-Loss Hotspot Tests

### Includes

- pending-review `144→145` review/test,
- drop/recreate migration tests,
- representative non-empty data fixtures.

### Acceptance

- [ ] No known hotspot can silently drop supported user data.
- [ ] If a version is unsupported, policy is explicit.

---

## PR 6 — Dedupe Constraint Matrix

### Includes

- `docs/db/DB_DEDUPE_CONSTRAINT_MATRIX.md`
- list of natural keys,
- conflict behavior,
- owner,
- required constraints.

### Acceptance

- [ ] Every MIT-033 domain has defined DB-level strategy.
- [ ] Unknowns are marked blocking, not ignored.

---

## PR 7 — Add High-Priority Constraints Batch A

### Includes

Highest-risk constraints:

- email receipt dedupe,
- recurring linked actual uniqueness,
- bank transaction identity,
- bank connection identity.

### Acceptance

- [ ] Concurrent duplicate tests pass.
- [ ] Legacy duplicate backfill is handled.

---

## PR 8 — Add Constraints Batch B

### Includes

- import file/row identity,
- operation event idempotency,
- raw notification fingerprint,
- receipt fingerprint/source-link constraints,
- group/member/payment constraints,
- category normalized key if needed.

### Acceptance

- [ ] Duplicate races are DB-prevented.
- [ ] Repository/import/bank code maps conflicts to typed results.

---

## PR 9 — CI Integration

### Includes

- migration matrix in CI,
- schema parity in CI,
- exported schema freshness check,
- DB migration boundary script.

### Acceptance

- [ ] DB migration/schema checks are required PR status.
- [ ] New DB version without migration fails CI.

---

## PR 10 — Cleanup and Tracker Closure

### Includes

- remove temporary allowlists,
- update docs,
- update master tracker with closing SHAs,
- add release note for supported DB version policy.

### Acceptance

- [ ] MIT-010, MIT-011, MIT-033, MIT-078 closure criteria met.

---

# 13. Handling Existing Data Conflicts

When adding unique constraints, follow this process:

1. Query for duplicates.
2. Decide domain-specific policy.
3. Write migration/backfill.
4. Add test with duplicate legacy data.
5. Add repository-level typed duplicate handling.
6. Add user-visible review item only where automatic merge is unsafe.

## Example duplicate resolution policies

### Email receipt duplicate

- keep earliest receipt,
- link later source rows to earliest if possible,
- mark duplicates as ignored/import duplicate.

### Bank transaction duplicate

- if same provider/account/transaction ID: keep one,
- if same provider transaction ID but different account: keep both,
- if API and statement import duplicate: preserve both sources but one expense/review.

### Recurring linked actual duplicate

- keep strongest/highest-confidence rule link,
- unlink others and create review/diagnostic,
- never let one actual silently satisfy multiple rules.

### Import row duplicate

- keep first successful row,
- later same file/row becomes idempotent duplicate result,
- different batch same fingerprint depends on user-selected dedupe mode.

---

# 14. Constraints and Nullability Rules

## General rules

- Do not rely on unique indexes over nullable columns unless behavior is understood.
- In SQLite, multiple `NULL` values are allowed in unique indexes.
- If uniqueness matters only when value exists, use partial unique indexes:
  - `WHERE fingerprint IS NOT NULL`
- If every legal path must populate fingerprint, add lifecycle validation and migration backfill.
- If legacy rows cannot be backfilled, treat them as legacy/no-dedupe and document.

## Required checks

- [ ] Every fingerprint column has population contract.
- [ ] Every unique nullable column has explicit partial-index decision.
- [ ] Every backfilled column has migration test.
- [ ] Every legal writer sets required dedupe fields before insert.

---

# 15. Repository / DAO Behavior After Constraints

Adding DB constraints is not enough. Code must handle them cleanly.

### Required behavior

- [ ] DAO insert conflict should return typed conflict/idempotent result where possible.
- [ ] Repository should not expose raw SQLite exception to UI.
- [ ] Duplicate create should link to existing entity if policy says so.
- [ ] Import duplicate row should become row-level duplicate result.
- [ ] Bank duplicate sync should be idempotent.
- [ ] Email duplicate should not create second receipt/expense.
- [ ] Recurring duplicate actual link should produce review/diagnostic.

### Tests

- [ ] DAO conflict test.
- [ ] Repository conflict mapping test.
- [ ] UI-safe error mapping test if surfaced.

---

# 16. Documentation Requirements

Add/update:

```text
docs/db/MIGRATION_SUPPORT_POLICY.md
docs/db/DB_BASELINE_INVENTORY.md
docs/db/DB_DEDUPE_CONSTRAINT_MATRIX.md
docs/db/SCHEMA_PARITY_TESTING.md
docs/db/HISTORICAL_DATA_MIGRATION_TESTS.md
```

Update:

```text
docs/MASTER_ISSUE_TRACKER.md
docs/MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md
```

Each closed MIT should include:

- closing commit SHA,
- test class names,
- migration versions covered,
- remaining known limitations.

---

# 17. Risks and Mitigations

## Risk: old migrations are missing or unrecoverable

Mitigation:

- define supported baseline,
- reject unsupported versions safely,
- provide export/backup guidance if possible.

## Risk: adding unique constraints fails on existing duplicate data

Mitigation:

- add duplicate cleanup/backfill migration,
- test legacy duplicates,
- use review queue for ambiguous merges.

## Risk: schema parity tests are flaky due SQL formatting/order

Mitigation:

- compare normalized SQLite metadata, not raw SQL only,
- sort tables/indexes/columns,
- preserve semantic fields.

## Risk: migration matrix slows PR CI

Mitigation:

- representative matrix in PR,
- full matrix nightly/release,
- cache Gradle,
- keep fixtures small.

## Risk: app code expects duplicate rows

Mitigation:

- add repository behavior tests,
- migrate app logic to typed idempotency outcomes,
- stage constraints behind code fixes.

---

# 18. Completion Checklist

This implementation plan is complete when:

- [ ] DB baseline inventory exists.
- [ ] Migration support policy exists.
- [ ] Runtime migration chain is complete for supported versions.
- [ ] Unsupported versions are handled intentionally.
- [ ] Migration matrix runs in CI.
- [ ] Fresh-vs-migrated schema parity is tested.
- [ ] Historical data-loss hotspots are tested.
- [ ] Pending-review migration is proven safe or policy-handled.
- [ ] Dedupe constraint matrix exists.
- [ ] Email receipt dedupe is DB-enforced.
- [ ] Recurring linked actual uniqueness is DB-enforced.
- [ ] Bank transaction identity is DB-enforced.
- [ ] Bank connection identity is DB-enforced.
- [ ] Import row/file identity is DB-enforced.
- [ ] Operation event idempotency is DB-enforced or intentionally sequenced.
- [ ] Raw notification/receipt fingerprint constraints are defined.
- [ ] Group/member/payment constraints are defined.
- [ ] Existing duplicates are backfilled/resolved.
- [ ] Repository code maps DB conflicts to typed outcomes.
- [ ] No release path uses silent destructive migration.
- [ ] Master tracker is updated with closing SHAs.

---

# 19. Definition of Done by MIT

## MIT-010 can close when

- [ ] Minimum supported DB version is documented.
- [ ] Runtime migration chain covers every supported version.
- [ ] Room builder registers the chain.
- [ ] Unsupported versions follow documented policy.
- [ ] Tests prove supported old DBs migrate.

## MIT-011 can close when

- [ ] Fresh latest schema equals migrated latest schema.
- [ ] Critical indexes/constraints exist for fresh and migrated installs.
- [ ] Schema parity is in CI.
- [ ] Intentional differences are documented.

## MIT-033 can close when

- [ ] Dedupe constraint matrix exists.
- [ ] Race-prone dedupe/idempotency domains have DB-level protection.
- [ ] Existing duplicates are migrated/resolved.
- [ ] App code maps constraint conflicts to typed results.
- [ ] Concurrent duplicate tests pass.

## MIT-078 can close when

- [ ] Drop/recreate migrations are audited.
- [ ] Pending-review hotspot is tested.
- [ ] Non-empty historical data survives supported migrations.
- [ ] Unsupported data-loss cases are explicitly policy-handled.
- [ ] CI catches future data-loss migration patterns.

---

# 20. Recommended First Action

Start with:

```text
PR 1 — DB Baseline Inventory and Migration Support Policy
```

Do not begin adding constraints before this is complete.  
You need to know the supported version range and existing duplicate data risks first.

Then do:

```text
PR 2 — Runtime Migration Chain Fix
PR 3 — Migration Test Harness MVP
PR 4 — Fresh vs Migrated Schema Parity
```

Only after those should you add new uniqueness constraints.