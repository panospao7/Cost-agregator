# GR-10B Source-Scope Matrix

PR: **PR-GR-10B — Unify production source-scope authority across guards** (Slice 3)
Plan: `docs/guardrails/PR-GR-10B_unified_production_source_scope_plan.md`
("Required source-scope matrix" deliverable + "Required topology contract upgrade")

This matrix classifies every guard in `scripts/ci/guard_registry.py` by
source scope, scan surface, source authority, migration status, and
fail-closed contract. There is no implicit scope anywhere: every registry
entry has exactly one classification, and
`scripts/ci/test_gr10b_source_scope_matrix.py` enforces that this document
and the registry never drift apart (exact guard-id set equality in both
directions, scope equality with the registry `sourceScope` field, closed
vocabularies, no implicit classifications).

## Production source authority

- The only production-Kotlin root authority is the checked-in manifest
  `config/guards/production_source_roots.yml` (module / sourceSet / path).
- Root parsing, strict manifest validation, deterministic production-Kotlin
  enumeration, declared-path membership, safe source-file resolution, and
  scope-evidence hashing live in `scripts/guardrails/production_source_scope.py`
  (PR-GR-10B Slice 1, the ONE live implementation).
- `scripts/db_guard/source_roots.py` is a compatibility seam that re-exports
  the same implementation under the historical `DB_SOURCE_ROOT_*` wire
  names; no second root-parsing implementation exists.
- The topology meta-guard `scripts/ci/verify_production_source_roots.py`
  cross-checks the manifest against the real Gradle layout and fails closed
  (exit 2) on any mismatch or on any layout it cannot model. It is not yet a
  registry entry; registration is plan Step 4 (later slice).

## Topology contract after the Slice 3 upgrade

Supported (modeled) layout, from literal `include(...)` calls in
`settings.gradle.kts` plus the inspected build files:

- conventional `src/main/java` and `src/main/kotlin` under each declared
  module directory;
- a module `build.gradle.kts` `sourceSets { }` block whose ONLY source-dir
  additions for the `main` source set are plain quoted, repository-safe,
  module-relative literal paths via `java.srcDirs("...")` /
  `kotlin.srcDirs("...")` (braced or chained accessor forms);
- non-`main` source-set groups (debug, test, androidTest, release, ...) are
  ignored entirely: they can never define production roots.

Fail closed with `DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED` (controlled reasons):

- settings file missing/unreadable: `settings-file-missing`,
  `settings-file-unreadable`;
- dynamic `include(...)` expression: `dynamic-include-expression`;
- `sourceSets`/`projectDir` markers in `settings.gradle.kts`:
  `custom-source-set-or-projectdir`;
- root `build.gradle.kts` present but unreadable, or touching source layout
  at all (never modeled at root level): `root-build-file-unreadable`,
  `custom-source-set-or-projectdir`;
- declared module build file missing (including an unmappable module
  directory) or unreadable: `module-build-file-missing`,
  `module-build-file-unreadable`;
- dynamic `srcDirs` arguments — variables, concatenation, string templates,
  closures, empty, traversal, absolute, backslash, wildcard:
  `dynamic-source-dir-expression`;
- unmodeled customization — `projectDir =`/`.set(` overrides, `setRoot`,
  assignment/`setSrcDirs` forms, nested accessor blocks, non-literal
  accessor groups, unattributed brace groups, `srcDir` usage outside a
  modeled block, unclosed blocks: `custom-source-set-or-projectdir`.

Cross-check (exit 2 on any mismatch, both directions):

- every declared manifest root must be explainable by the supported
  topology (a conventional candidate or a literal `main` srcDir of a
  declared module);
- every observed Kotlin-containing candidate root must be declared in the
  manifest; test/debug/release/generated segments are pruned during
  observation and are never production candidates.

Line comments are stripped with quote awareness before parsing; block
comments are not handled (a file using them fails closed rather than being
guessed at). Only `.kts` build files are inspected.

## Migration status vocabulary

- `S2-MIGRATED` — the guard enumerates production Kotlin through
  `scripts/guardrails/production_source_scope.py` with the checked-in
  manifest as the only root authority (migrated in PR-GR-10B Slices 1-2).
- `DB-SEAM-DEFERRED` — the guard still resolves roots through the
  `scripts/db_guard/source_roots.py` compatibility seam (same underlying
  implementation, historical wire vocabulary); moving the DB consumers to
  direct neutral-API calls is deferred to a later GR-10B slice behind DB
  parity gates (plan Step 3).
- `N/A` — the guard intentionally does not scan production Kotlin
  (repository-config, test-source, artifact, or external-tool scope), so
  the production manifest does not apply.

## Matrix (24 rows — one per registry guard; no implicit scope)

| Guard ID | Scope classification | Scan surface | Source authority | Migration status | Fail-closed contract |
|---|---|---|---|---|---|
| source_provenance | production-kotlin-filtered | All declared production Kotlin enumerated, then provenance package filter plus exact named coordinator and provenance targets | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2 before any scan conclusion. Violations exit 1. |
| ui_dao | production-kotlin-filtered | All declared production Kotlin enumerated, then UI-layer and ViewModel relevance filter for direct DAO access | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1 against the allowlist. |
| worker | production-kotlin-filtered | All declared production Kotlin enumerated, then Worker relevance filter (WorkerExecutionGuard usage, write barrier, cancellation) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1 against the allowlist. |
| receipt_link | production-kotlin-filtered | All declared production Kotlin enumerated, then receipt-expense link relevance filter | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1 against the allowlist. |
| import_lifecycle | production-kotlin-filtered | All declared production Kotlin enumerated, then import-lifecycle relevance filter (canonical ImportCoordinator) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1 against the allowlist. |
| cloud_payload | production-kotlin-filtered | All declared production Kotlin enumerated, then PreparedCloudPayload relevance filter | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1 against the allowlist. |
| pii_logging | production-kotlin-filtered | All declared production Kotlin enumerated, then logging and diagnostics relevance filter (strict-zero PII) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1 against the allowlist. |
| di_release | production-kotlin-filtered | All declared production Kotlin enumerated, then Hilt module relevance filter (release vs debug bindings) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1 against the allowlist. |
| allowlist_compliance | repository-config | Allowlist YAML metadata only (required reason, owner, expiry fields) | Explicit scripts/allowlists paths declared in the registry | N/A | Violations exit 1. Malformed or unreadable allowlist input exits 2. |
| migration_matrix | production-kotlin-targeted | Room migration and schema configuration plus exact declared production targets for migration code | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Ratchet baseline v1 blocks new gaps (exit 1). Scope diagnostic exits 2. |
| ignored_test_budget | test-source | src/test and src/androidTest trees only (at-Ignore annotation census) | Test tree layout. Never uses the production manifest | N/A | Over-budget exits 1. Unreadable test trees exit 2. |
| lint_baseline_policy | repository-config | lint-baseline.xml issue types only | Explicit baseline path from the registry | N/A | Disallowed issue types exit 1. Unreadable baseline exits 2. |
| time_boundaries | production-kotlin-all | Every Kotlin file under every declared production root for direct wall-clock API calls | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope failure exits 2. Violations exit 1 (strict zero, no baseline). |
| deprecation_escalations | production-kotlin-all | Every declared production Kotlin file for DeprecationLevel.ERROR sites against the tracked changelog | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope failure exits 2. Missing or stale changelog rows exit 1. |
| db_artifact_sync | repository-config | Regenerates tracked DB policy signature artifacts in memory and byte-compares them (never scans Kotlin) | Explicit tracked artifact paths from the registry | N/A | Drift exits 1. Missing or unreadable inputs exit 2. Never writes tracked artifacts. |
| known_good_state | repository-config | Executes the documented known-good checklist sections against the live repository (delegates to registered guards) | docs/ci checklist plus the underlying registered guards | N/A | Drift exits 1. Infrastructure failures exit 2. |
| cancellation | production-kotlin-filtered | All declared production Kotlin enumerated, then suspend and worker cancellation relevance filter | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Ratchet baseline v1 blocks growth (exit 1). |
| privacy | production-kotlin-filtered | All declared production Kotlin enumerated, then privacy-relevance filter (cloud redaction, privacy gate, pseudonym, export) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Ratchet baseline v1 blocks growth (exit 1). |
| db_access | production-kotlin-all | Room mutator inventory over every declared production Kotlin file (write, read, restore barrier, ownership policy) | scripts.db_guard.source_roots compatibility seam re-exporting guardrails.production_source_scope | DB-SEAM-DEFERRED | Any root or scope diagnostic exits 2 with no partial scan. Protocol v2 findings plus baseline v2 ratchet (exit 1). |
| event_writers | production-kotlin-filtered | All declared production Kotlin enumerated, then event-construction relevance filter (canonical writers) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Ratchet baseline v1 blocks growth (exit 1). |
| money | production-kotlin-filtered | All declared production Kotlin enumerated, then money-relevance filter (conversion, aggregation, trend rules) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Ratchet baseline v1 blocks growth (exit 1). |
| raw_money_aggregates | production-kotlin-filtered | All declared production Kotlin enumerated, then raw-aggregate relevance filter (G-MONEY-RAW rules) | guardrails.production_source_scope (checked-in manifest) | S2-MIGRATED | Scope diagnostic exits 2. Violations exit 1. |
| currency_guardrails_ps | external-tool | PowerShell CI unit-tests job: blocking raw-aggregate check plus advisory report | Registry-declared external engine contract. The declared -SourceDir app/src/main/java argument is a tracked hard-coded scope input pending the GR-10B plan Step 9 disposition | N/A | Registered external engine. Never executed by the Python runner bridge. The CI job fails on a nonzero guard exit. |
| release_artifact | artifact | Assembled release APK only (signing, package, version) in the separate release-check CI job | Build output artifact. Explicitly outside the static guard suite | N/A | Violations exit 1. Missing or unreadable artifact exits 2. |

## Notes

- `db_access` is the only production-Kotlin guard still on the
  `scripts/db_guard/source_roots.py` seam. The seam re-exports the neutral
  implementation (one live implementation, historical wire names), so scope
  authority is already unified; the deferral is about the DB parity gates
  required before changing the DB consumers' import surface (plan Step 3).
- `currency_guardrails_ps` retains a hard-coded declared scope argument in
  its registry execution entry. GR-10B plan Step 9 requires it to consume a
  manifest-backed file list before any promotion; until then it stays a
  registered external engine that the Python runner bridge never executes,
  and its scope argument is documented here rather than left silent.
- The topology meta-guard (`scripts/ci/verify_production_source_roots.py`)
  itself consumes the manifest plus the settings/build-file topology
  contract above; it exits 2 on any root uncertainty and is registered as a
  blocking policy guard by plan Step 4 (later slice).
