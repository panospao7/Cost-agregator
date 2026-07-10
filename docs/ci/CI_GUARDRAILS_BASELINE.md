# CI Guardrails Baseline Inventory

> **Created**: PR 1 — CI Baseline and Workflow Validation
> **Purpose**: Comprehensive inventory of all existing CI guardrails, verification tasks, scripts, tests, and gaps. Serves as the foundation for the multi-PR CI static guardrails hardening plan.
> **Status**: Baseline snapshot as of PR 1 completion.

> **PR 2 update**: `:app:check` now runs as a blocking CI step in the `lint-and-check` job. `verifyNoIgnoredGrowth` is now wired to the `:app:check` Gradle lifecycle (was standalone only).

> **PR 3 update**: Dedicated `static-guards` CI job added — runs all 5 Python guard scripts + pytest script tests as a blocking PR check. `verify_source_provenance_boundaries.py` and `pytest scripts/test_*.py` now run in CI (were missing).

> **PR 4 update**: Guard framework standardized — `guard_template.py` created as reference template, `guard-framework.md` documents the standard contract, `verify_allowlist_compliance.py` added as meta-guard to validate allowlist entries across all guard scripts. Allowlist compliance check runs in the `static-guards` CI job.

> **PR 5 update**: Migration matrix MVP — `verify_migration_matrix.py` validates Room migration coverage from baseline (v145) to latest (v148). Detects missing migration steps, cross-validates `val MIGRATION_N_M` definitions against the `ALL` array, and reports known intentional gaps (pre-baseline versions 33–144). Runs as a blocking CI step in the `static-guards` job.

> **PR 6 update**: New Guards Batch A — 5 high-risk architecture guard scripts wired into CI: `verify_cancellation_boundaries.py` (G-CANCEL-01, warning mode — 248 pre-existing violations), `verify_ui_dao_boundaries.py` (G-UI-DAO-01), `verify_worker_boundaries.py` (G-WORKER-01), `verify_receipt_link_boundaries.py` (G-RCPT-LINK-01), `verify_import_lifecycle_boundaries.py` (G-IMPORT-01). All 5 guards run as blocking steps in the `static-guards` job (cancellation guard in warning mode only pending backlog migration).

> **PR 7 update**: New Guards Batch B — 3 privacy/money/release guard scripts wired into CI: `verify_cloud_payload_boundaries.py` (G-CLOUD-01, blocking with `--fail-on-violation`), `verify_pii_logging_boundaries.py` (G-PII-01, warning mode — 52 pre-existing violations), `verify_di_release_boundaries.py` (G-DI-01, warning mode). Cloud payload guard is production-clean; PII logging and DI/release run in warning mode initially pending backlog migration.

> **PR 8 update**: Ignored Test Budget — `verify_ignored_test_budget.py` (G-IGNORE-01) validates `@Ignore` annotations have non-empty reasons, categorizes them (stress, jvm_incompatible, removed_api, vat_logic, truth_boxing, negative_id, rewrite_needed, other), and checks against the `release_block_denylist.yml`. Runs in warning mode initially (31 pre-existing).

> **PR 9 update**: Branch protection documentation and CODEOWNERS — `CODEOWNERS` file created assigning review ownership for CI/guard, database/migrations, lifecycle/architecture, workers, and privacy/security paths. Developer quickstart guide created at `docs/ci/developer-quickstart.md` with pre-push checklist, guard script commands, allowlist format, and CI pipeline overview. Branch protection recommendations (required checks, branch rules) documented below.

---

## CI Workflow Overview

**File**: `.github/workflows/ci.yml`

**Trigger**:
- `push` to `main` or `master`
- `pull_request` to `main` or `master`
- `workflow_dispatch` (manual)

**Concurrency**: `cancel-in-progress: true` — cancels in-progress runs on the same ref when a new run starts.

**JDK**: 17 (Eclipse Temurin via `actions/setup-java@v4`).

**Gradle caching**: `gradle/actions/setup-gradle@v4`.

### Job: `validate-workflow` (Validate Workflow)
- **Runner**: `ubuntu-latest`
- **Timeout**: 5 minutes
- **Non-optional**: All downstream jobs (`unit-tests`, `lint-and-check`, `instrumented-tests`) depend on it via `needs: validate-workflow`
- **Steps**:
  1. Checkout (`actions/checkout@v4`)
  2. Download actionlint via `curl -fsSL` (official rhysd/actionlint script)
  3. Lint all workflow YAML files: `./actionlint -color`
- **Purpose**: Catches workflow syntax errors, invalid action references, and common YAML mistakes before expensive Gradle jobs run. Added in PR 1.

### Job: `static-guards` (Static Guards)
- **Runner**: `ubuntu-latest`
- **Timeout**: 10 minutes
- **Depends on**: `validate-workflow`
- **Steps**:
  1. Checkout (`actions/checkout@v4`)
  2. Set up Python 3.11 (`actions/setup-python@v5`)
  3. Install test dependencies: `pip install pytest`
  4. Verify privacy boundaries: `python3 scripts/verify_privacy_boundaries.py --root .`
  5. Verify DB access boundaries: `python3 scripts/verify_db_access_boundaries.py --fail-on-violation`
  6. Verify event writer boundaries: `python3 scripts/verify_event_writers.py --fail-on-violation`
  7. Verify money boundaries: `python3 scripts/verify_money_boundaries.py --root .`
  8. Verify source provenance boundaries: `python3 scripts/verify_source_provenance_boundaries.py --root .`
  9. Verify cancellation boundaries (warning mode — 248 pre-existing violations): `python3 scripts/verify_cancellation_boundaries.py`
  10. Verify UI/ViewModel DAO boundaries: `python3 scripts/verify_ui_dao_boundaries.py --fail-on-violation`
  11. Verify worker boundaries: `python3 scripts/verify_worker_boundaries.py --fail-on-violation`
  12. Verify receipt link boundaries: `python3 scripts/verify_receipt_link_boundaries.py --fail-on-violation`
  13. Verify import lifecycle boundaries: `python3 scripts/verify_import_lifecycle_boundaries.py --fail-on-violation`
  14. Verify cloud payload boundaries: `python3 scripts/verify_cloud_payload_boundaries.py --fail-on-violation`
  15. Verify PII logging boundaries (warning mode — 52 pre-existing violations): `python3 scripts/verify_pii_logging_boundaries.py`
  16. Verify DI/release boundaries: `python3 scripts/verify_di_release_boundaries.py`
  17. Verify allowlist compliance: `python3 scripts/verify_allowlist_compliance.py --fail-on-violation`
  18. Verify migration matrix: `python3 scripts/verify_migration_matrix.py --fail-on-violation`
  19. Verify ignored test budget (warning mode — 31 pre-existing): `python3 scripts/verify_ignored_test_budget.py`
  20. Run guard script tests: `python -m pytest scripts/test_*.py -v`
  21. Upload guard output artifact on failure (7-day retention)
 - **Purpose**: Runs all Python-based architecture guard scripts and their pytest test suites as blocking PR checks. Enforces project-specific rules that Gradle-based checks cannot easily express. Added in PR 3; allowlist compliance check added in PR 4; migration matrix check added in PR 5; 5 new guard scripts (cancellation, UI/DAO, worker, receipt link, import lifecycle) added in PR 6; 3 new guard scripts (cloud payload, PII logging, DI/release) added in PR 7; ignored test budget guard added in PR 8.

### Job: `unit-tests` (Unit Tests)
- **Runner**: `ubuntu-latest`
- **Timeout**: 30 minutes
- **Steps**:
  1. Checkout (`actions/checkout@v4`)
  2. Set up JDK 17
  3. Set up Gradle cache
  4. Grant Gradle wrapper execute permission
  5. Run unit tests: `./gradlew :app:testDebugUnitTest --no-daemon --stacktrace`
  6. Verify Room schema snapshots: `./gradlew :app:verifyRoomSchemaSnapshots -PstrictRoomSchemas=true --no-daemon --stacktrace`
  7. Verify no ignored-test growth: `./gradlew :app:verifyNoIgnoredGrowth -PmaxIgnoredTests=29 --no-daemon --stacktrace`
  8. Run currency guardrails (PowerShell): `pwsh scripts/currency_guardrails.ps1 -SourceDir app/src/main/java -ProjectRoot ${{ github.workspace }}`
  9. Upload unit test results artifact (7-day retention)
  10. Upload Room schema verification results artifact (7-day retention)

### Job: `lint-and-check` (Lint & Check)
- **Runner**: `ubuntu-latest`
- **Timeout**: 30 minutes
- **Steps**:
  1. Checkout
  2. Set up JDK 17
  3. Set up Gradle cache
  4. Grant Gradle wrapper execute permission
  5. Run lint: `./gradlew :app:lintDebug --no-daemon --stacktrace`
  6. Assemble debug build: `./gradlew :app:assembleDebug --stacktrace`
  7. Run Gradle check (all wired verification tasks, including `verifyDbAccessBoundaries`): `./gradlew :app:check --stacktrace`
  8. Upload lint results artifact (7-day retention)

### Job: `instrumented-tests` (Instrumented Tests)
- **Runner**: `ubuntu-latest`
- **Timeout**: 60 minutes
- **Non-blocking**: `continue-on-error: true`
- **Condition**: Runs only on push to `main`/`master` OR `workflow_dispatch`
- **Strategy matrix**: `api-level: [34]`, `target: [google_apis]`
- **Steps**:
  1. Checkout
  2. Set up JDK 17
  3. Enable KVM
  4. Set up Gradle cache
  5. Grant Gradle wrapper execute permission
  6. Run instrumented tests via Android Emulator Runner (`reactivecircus/android-emulator-runner@v2`)
  7. Upload instrumented test results artifact (7-day retention)

---

## Gradle Verification Tasks

All verification tasks defined in `app/build.gradle.kts`. Tasks wired to `:app:check` lifecycle:

| Task | Type | Description | Wired to `check`? |
|------|------|-------------|-------------------|
| `verifyRoomSchemaSnapshots` | Inline Kotlin | Reports schema snapshot coverage; fails on missing via `-PstrictRoomSchemas=true` | ✅ Yes |
| `checkLifecycleBypasses` | Inline Kotlin | Scans `app/src/main/java` for direct `expenseDao.update*(...)` calls bypassing `TransactionLifecycleCoordinator`; 7 allowlisted files | ✅ Yes |
| `checkRawMoneyAggregates` | Inline Kotlin | Scans for raw `Double` financial aggregates like `sumOf { it.amount }` outside `fromBuckets` blocks; 7 allowlisted files | ✅ Yes |
| `checkDirectTimeCalls` | Inline Kotlin | Scans for `System.currentTimeMillis()` / `Date()` / `Instant.now()` outside `TimeProvider`; 21 allowlisted files, skips legacy/deprecated/backfill paths | ✅ Yes |
| `checkLifecycleBypass` | Inline Kotlin | Scans `src/main/java` for `expenseDao.insert/update/delete` outside the allowlist; 10 allowlisted classes | ✅ Yes |
| `verifyDbAccessBoundaries` | External `.py` | Runs `scripts/verify_db_access_boundaries.py --fail-on-violation` | ✅ Yes |
| `verifyNoIgnoredGrowth` | Inline Kotlin | Counts `@Ignore` annotations; fails if count exceeds `-PmaxIgnoredTests=N` (default: 29) | ✅ Yes (wired in PR 2) |

### `checkLifecycleBypass` Allowlist (10 classes)

The inline Kotlin guard in `app/build.gradle.kts` allows direct `ExpenseDao.insert/update/delete` calls from:
1. `TransactionLifecycleCoordinator` — canonical mutation entry point
2. `LocationBackfillWorker` — background column backfill
3. `MerchantKeyBackfillWorker` — background column backfill
4. `GroupTransactionCoordinator` — atomic group-expense creation
5. `DebugExpenseRepository` — `BuildConfig.DEBUG` guarded
6. `AppDatabase` — Room infrastructure
7. `ReceiptLinkService` — circular dependency constraint (RCP-30)
8. `ExpenseRepository` — delegated to coordinator
9. `MultiCurrencyRepository` — analytics-only read path with conversion inserts
10. `NotificationRepository` — notification capture, not expense mutation

---

## Existing Python Guard Scripts

All located in `scripts/`:

| Script | Purpose | Fail Flag | Has Tests | CI Job |
|--------|---------|-----------|-----------|--------|
| `verify_privacy_boundaries.py` | 14 privacy rules (G1-G14); always fails on violation (no flag needed) | N/A (always fails) | No dedicated test file | `static-guards` |
| `verify_db_access_boundaries.py` | DAO mutation boundaries against allowlist | `--fail-on-violation` | ✅ `scripts/test_verify_db_access_boundaries.py` | `static-guards` |
| `verify_event_writers.py` | Lifecycle event construction boundaries | `--fail-on-violation` | No dedicated test file | `static-guards` |
| `verify_money_boundaries.py` | Money boundary rules (G-MONEY); always fails on violation | N/A (always fails) | No dedicated test file | `static-guards` |
| `verify_source_provenance_boundaries.py` | Source provenance rules (G-PROV); always fails on violation | N/A (always fails) | No dedicated test file | `static-guards` |
| `verify_allowlist_compliance.py` | Meta-guard: validates allowlist entries (reason, owner, expiry) across all guard scripts | `--fail-on-violation` | No dedicated test file | `static-guards` |
| `verify_migration_matrix.py` | Room migration coverage: validates every version from baseline (v145) to latest has a registered migration; cross-validates `ALL` array | `--fail-on-violation` | ✅ `scripts/test_verify_migration_matrix.py` | `static-guards` |
| `verify_cancellation_boundaries.py` | G-CANCEL-01: detects unsafe CancellationException handling (broad catch, runCatching, .onFailure in suspend/worker contexts) | `--fail-on-violation` (CI runs in warning mode — 248 pre-existing violations) | ✅ `scripts/test_verify_cancellation_boundaries.py` | `static-guards` |
| `verify_ui_dao_boundaries.py` | G-UI-DAO-01: ViewModels/UI code directly injecting or calling mutating DAOs | `--fail-on-violation` | ✅ `scripts/test_verify_ui_dao_boundaries.py` | `static-guards` |
| `verify_worker_boundaries.py` | G-WORKER-01: workers bypassing WorkerExecutionGuard, direct DAO mutations, missing Result.success/failure paths | `--fail-on-violation` | ✅ `scripts/test_verify_worker_boundaries.py` | `static-guards` |
| `verify_receipt_link_boundaries.py` | G-RCPT-LINK-01: ScannedReceipt.expenseId mutations and scannedReceiptDao calls outside approved paths | `--fail-on-violation` | ✅ `scripts/test_verify_receipt_link_boundaries.py` | `static-guards` |
| `verify_import_lifecycle_boundaries.py` | G-IMPORT-01: import paths (CSV, JSON, backup/restore) bypassing lifecycle coordinators | `--fail-on-violation` | ✅ `scripts/test_verify_import_lifecycle_boundaries.py` | `static-guards` |
| `verify_cloud_payload_boundaries.py` | G-CLOUD-01: cloud payload boundaries (AiSettings.redactBeforeCloud, CloudPayloadPolicy enforcement) | `--fail-on-violation` | ✅ `scripts/test_verify_cloud_payload_boundaries.py` | `static-guards` |
| `verify_pii_logging_boundaries.py` | G-PII-01: PII/sensitive data in log statements, error messages, or exception fields (warning mode — 52 pre-existing violations) | `--fail-on-violation` (CI runs in warning mode) | ✅ `scripts/test_verify_pii_logging_boundaries.py` | `static-guards` |
| `verify_di_release_boundaries.py` | G-DI-01: DI/release binding boundaries (full-codebase detection) | `--fail-on-violation` | ✅ `scripts/test_verify_di_release_boundaries.py` | `static-guards` |
| `verify_ignored_test_budget.py` | G-IGNORE-01: validates @Ignore annotations have reasons, categorizes them, checks release-block denylist (warning mode — 31 pre-existing) | `--fail-on-violation` (CI runs in warning mode) | ✅ `scripts/test_verify_ignored_test_budget.py` | `static-guards` |
| `test_verify_db_access_boundaries.py` | pytest tests for DB access boundary guard | N/A | N/A | `static-guards` (via pytest) |

### PowerShell Guardrails

| Script | Purpose | CI Job |
|--------|---------|--------|
| `scripts/currency_guardrails.ps1` | Checks raw `effectiveAmount` sums, deprecated `CurrencyFormatter.format()`, `"EUR"` hardcodes | `unit-tests` (pwsh shell) |

---

## Kotlin Architecture Guard Tests

Located in `app/src/test/java/com/yourname/expensetracker/architecture/` and related packages:

| Test Class | Purpose |
|-----------|---------|
| `CancellationSafetyArchitectureGuardTest` | Broad `catch` blocks in suspend functions must rethrow `CancellationException`; ~100+ known violations |
| `CancellationSafeTest` | Unit tests for the `CancellationSafe` helper (`domain/util/CancellationSafe.kt`) |
| `CancellationPropagationContractTest` | Cancellation propagation contract tests (`contracts/CancellationPropagationContractTest.kt`) |
| `DirectEventDaoInsertGuardTest` | Direct event DAO insert only from approved files; 32 approved entries |
| `TransactionContextProvenanceGuardTest` | `TransactionContext` constructor provenance; 6 allowlist entries |
| `WriteBarrierArchitectureGuardTest` | DAO write callers must inject `DatabaseWriteBarrier` |
| `WorkerGuardArchitectureGuardTest` | Every `CoroutineWorker` subclass must use `WorkerExecutionGuard` |
| `WorkerGuardStaticVerificationTest` | Worker FQN cross-check against registry |
| `SourceScanningArchitectureGuardTest` | Worker guard calls, receiver DAO injection, privacy capabilities, etc. |
| `RawDaoArchitectureGuardTest` | No raw DAO mutator outside repository layer |
| `RecurringArchitectureGuardTest` | Recurring lifecycle rules; no direct DAO mutation |
| `DeprecatedApiArchitectureGuardTest` | No production calls to deprecated raw-`Double` APIs |
| `BankPrivacyModeArchitectureGuardTest` | Bank API integration privacy mode enforcement |
| `BackupRestoreArchitectureGuardTest` | `resetDatabase` maintenance mode enforcement |
| `Engine5PrimitiveGuardTest` | No `System.currentTimeMillis()` / raw `CurrencyCode` usage in domain |
| `ExpenseDaoMutationAccessTest` | Validates that expense DAO mutations are accessed only from approved classes |
| `GuardSeededViolationTest` | Verifies guard scripts detect seeded violations (`guards/GuardSeededViolationTest.kt`) |
| `MoneyBoundaryGuardTest` | Verifies money boundary guard catches regressions (`guard/MoneyBoundaryGuardTest.kt`) |

**Total**: 18 architecture guard test classes across `architecture/`, `guards/`, `guard/`, and `contracts/` packages.

---

## Room Schema Coverage

### Schema Files
- **Count**: 103 JSON schema files
- **Location**: `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/`
- **Version range**: 33 to 148 (with intentional gaps — versions removed when migrations are consolidated or schema snapshots are trimmed)

### Migration Classes
- Defined in: `DatabaseMigrations.kt`

### Migration Tests
| Test Class | Type | Location |
|-----------|------|----------|
| `MigrationRegistrationTest` | JVM unit test | `app/src/test/.../data/database/` |
| `DatabaseMigrationTest` | Instrumented (Android) | `app/src/androidTest/.../data/database/` |
| `MigrationContractTest` | Instrumented (Android) | `app/src/androidTest/.../data/database/` |

---

## Migration Matrix

### Guard: `verify_migration_matrix.py`

- **Rule ID**: `G-MIG-01`
- **Location**: `scripts/verify_migration_matrix.py`
- **Tests**: `scripts/test_verify_migration_matrix.py` (10 test cases)
- **CI job**: `static-guards` (blocking)

#### What it checks

1. **Complete coverage**: Every version from the migration baseline (v145) to the latest schema version (v148, per `APP_DATABASE_SCHEMA_VERSION`) must have a registered `MIGRATION_N_N+1` object.
2. **ALL array consistency**: Every `val MIGRATION_N_M` definition must appear in the `ALL` array, and every entry in `ALL` must have a corresponding `val` definition.
3. **Known gaps**: Versions below the baseline are intentionally excluded from failure. The guard reports them as informational gaps.
4. **Schema coverage**: Reports which schema JSON versions exist, highlighting gaps for diagnostic purposes (informational only).

#### Baseline

The migration baseline is read from the `DatabaseMigrations.kt` comment: *"v145 is the baseline. There are intentionally no historical migrations below v145."*

If no baseline comment is found, the script falls back to the lowest start version among registered `MIGRATION_N_M` objects.

#### Excluded range

Versions 33–144 are below the v145 baseline. These pre-baseline versions are explicitly unsupported — users must use destructive migration or legacy import paths. The guard does **not** fail on gaps in this range.

#### Exit codes

| Code | Meaning |
|------|---------|
| 0 | All migrations present (or violations in warning mode) |
| 1 | Missing migrations found and `--fail-on-violation` set |
| 2 | Script error (missing source files, unparseable config) |

#### Reproduction

```bash
# Warning mode (reports gaps but always exits 0)
python3 scripts/verify_migration_matrix.py --root .

# CI mode (fails on missing migrations)
python3 scripts/verify_migration_matrix.py --fail-on-violation

# Tests
python -m pytest scripts/test_verify_migration_matrix.py -v
```

---

## Ignored Test Budget

### Current State
- **Current `@Ignore` count**: ~31 annotations
- **Maximum threshold**: 29 (set via `-PmaxIgnoredTests=29` in CI)
- **Guard**: `verifyNoIgnoredGrowth` Gradle task (✅ wired to `:app:check` in PR 2)

### Ignored Test Categories

| Category | Count | Description |
|----------|-------|-------------|
| Stress tests | 20 | `@Ignore("Stress test: may hang in CI, run manually")` — heavy tests across repositories, ViewModels, and services |
| JVM incompatibility | 4 | Tests requiring Android runtime features not available on desktop JVM (e.g., `AndroidKeyStore`) |
| Removed APIs | 3 | Tests referencing deleted receipt/recurring lifecycle APIs that need rewrite when domain model stabilizes |
| VAT logic | 1 | VAT calculation logic differs from test expectation |
| Negative ID | 1 | Negative IDs unsupported for receipt notification mapping |
| Truth boxing | 3 | Truth `assertThat` incompatible with Kotlin value class boxing in `MoneyTest` |

**Total**: 32

### Guard Task: `verifyNoIgnoredGrowth`
- Defined in `app/build.gradle.kts` (lines 356–387)
- Counts `@Ignore` lines in `.kt` and `.java` files under `src/test/` and `src/androidTest/`
- Skips commented-out `@Ignore` (lines starting with `//`)
- Default threshold: 29 (`-PmaxIgnoredTests=29`)
- **Wired to `:app:check` in PR 2** — also runs as a separate step in the `unit-tests` CI job for early failure feedback

### Guard: `verify_ignored_test_budget.py`

- **Rule ID**: `G-IGNORE-01`
- **Location**: `scripts/verify_ignored_test_budget.py`
- **Tests**: `scripts/test_verify_ignored_test_budget.py` (8 test cases)
- **CI job**: `static-guards` (warning mode — 31 pre-existing)
- **Denylist**: `config/release_block_denylist.yml`

#### What it checks

1. **Validates reasons**: Every `@Ignore` annotation must have a non-empty reason string argument.
2. **Categorizes**: Classifies each `@Ignore` into one of 8 categories (stress, jvm_incompatible, removed_api, vat_logic, truth_boxing, negative_id, rewrite_needed, other) based on keyword matching against the reason string.
3. **Counts**: Reports total count and per-category breakdown.
4. **Denylist enforcement**: Cross-references ignored tests against the release-block denylist (`config/release_block_denylist.yml`). Any `@Ignore` in a denylisted class triggers a RELEASE-BLOCK violation.

#### Denylist classes

7 test classes are on the release-block denylist (must NOT be `@Ignore`d before release):

| Class | Reason |
|-------|--------|
| `TransactionLifecycleCoordinatorTest` | Critical: transaction lifecycle correctness |
| `MoneyTest` | Critical: money/currency math correctness |
| `DatabaseMigrationTest` | Critical: database migration integrity |
| `WorkerExecutionGuardTest` | Critical: worker guard enforcement |
| `ExportImportRoundtripTest` | Critical: backup/restore roundtrip integrity |
| `ReceiptMatchingWorkerTest` | Critical: receipt matching pipeline |
| `RecurringExpenseEngineTest` | Critical: recurring expense engine |

#### Category definitions

| Category | Keyword pattern | Example reason |
|----------|----------------|---------------|
| `stress` | `Stress test` | `"Stress test: may hang in CI, run manually"` |
| `jvm_incompatible` | `not available on desktop JVM` / `AndroidKeyStore` | `"AndroidKeyStore not available on desktop JVM"` |
| `removed_api` | `Tests reference removed` | `"Tests reference removed APIs"` |
| `vat_logic` | `VAT calculation` | `"VAT calculation logic differs from test expectation"` |
| `truth_boxing` | `Truth assertThat incompatible` | `"Truth assertThat incompatible with Kotlin value class boxing"` |
| `negative_id` | `Negative IDs are unsupported` | `"Negative IDs are unsupported for receipt notification mapping"` |
| `rewrite_needed` | `Needs rewrite` | `"Needs rewrite when domain model stabilizes"` |
| `other` | _(fallback)_ | `"Performance test: runs too slow for PR CI"` |

#### Exit codes

| Code | Meaning |
|------|---------|
| 0 | Pass (no violations, or violations in warning mode) |
| 1 | Violations found and `--fail-on-violation` set |
| 2 | Script error (missing source directories) |

#### Reproduction

```bash
# Warning mode (reports violations but always exits 0)
python3 scripts/verify_ignored_test_budget.py --root .

# CI mode (fails on violations)
python3 scripts/verify_ignored_test_budget.py --fail-on-violation

# Tests
python -m pytest scripts/test_verify_ignored_test_budget.py -v
```

---

## Gaps Identified

The following gaps were identified during baseline inventory. PRs 2–9 of the CI Static Guardrails plan address these:

1. **Missing workflow validation** — No `actionlint` or `yamllint` in CI to validate workflow YAML files before jobs run. (Addressed in PR 1: this PR adds a `validate-workflow` job with `actionlint`. Yamllint is deferred per the plan's optional designation — "if project is willing to maintain YAML style config".)

2. **Missing Python guard scripts from plan** — 15+ planned guard scripts not yet implemented:
   - `verify_source_scan_patterns.py` (double-injected @Singleton DAOs, mismatched imports)
   - `verify_worker_registration.py` (FQN cross-check)
   - `verify_hilt_bindings.py` (abstract Void bindings, Map<K,V> injections)
   - `verify_field_toggles.py` (static time/money compat shims)
   - `verify_timeprovider_bindings.py` (dev-time injection leak)
   - `verify_timeprovider_source.py` (TimeProvider source file set)
   - `verify_currency_code_source.py` (raw CurrencyCode source file set)
   - `verify_currency_usages.py` (CurrencyFormatter.format(), extension duplications)
   - `verify_deprecated_surface.py` (deprecated API surface boundaries)
   - `verify_panic_guards.py` (runCatching, `?:` gated by owner flag)
   - `verify_export_api_compliance.py` (ExportAPIContract V1/V2 shape check)
   - `verify_it_annotations.py` (instrumented tests bound to `@RequiresDevice` / `@SdkSuppress`)
   - `verify_executor_pools.py` (unmanaged dispatcher creation)
   - `verify_coverage_domination.py` (no single test > 70% of total assertions)
   - `verify_test_stability.py` (no flaky `@Ignore("may hang")` in critical surface)
   - `verify_source_provenance_db.py` (provenance column always present on write)
   - Additionally: the existing `verify_source_provenance_boundaries.py` guard was not running in CI — ✅ FIXED in PR 3 (now runs in `static-guards` job)

3. **Missing `docs/ci/` documentation** — No CI guardrails baseline or local reproduction guide existed prior to PR 1. (Addressed in PR 1: this PR creates `CI_GUARDRAILS_BASELINE.md` and `local-ci.md`.)

4. **CODEOWNERS file** — ✅ ADDED in PR 9. GitHub CODEOWNERS now covers CI/guard infrastructure, database/migrations, lifecycle/architecture boundaries, workers, and privacy/security paths. All assigned to `@panospao7`.

5. **Missing release security scripts** — No automated pre-release hardening checks.

6. **`verifyNoIgnoredGrowth` not wired to `:app:check`** — ✅ FIXED in PR 2. The ignored-test growth guard is now wired to `:app:check` and also runs as part of the blocking `:app:check` step in the `lint-and-check` CI job.

7. **No ignored test categorization** — Ignored tests lack formal categorization (obsolete / flaky / waiting-for-fix / release-blocking / unknown). The `@Ignore` reason string convention exists but is not enforced by an automated guard.

---

## Branch Protection Recommendations

The following branch protection rules are recommended for the `main` (and `master`) branch in the GitHub repository settings:

### Required Status Checks (blocking)

| Check | Job | Rationale |
|-------|-----|-----------|
| `validate-workflow` | Validate Workflow | Prevents broken CI YAML from blocking downstream jobs |
| `static-guards` | Static Guards | Enforces architecture guardrails on every PR |
| `unit-tests` | Unit Tests | Ensures tests, schema snapshots, and ignored-test budget pass |
| `lint-and-check` | Lint & Check | Ensures lint, compilation, and all wired Gradle checks pass |

### Branch Protection Rules

- **Require a pull request before merging**: Enabled (at least 1 approving review)
- **Require status checks to pass before merging**: Enabled (all 4 blocking checks above)
- **Require conversation resolution before merging**: Recommended
- **Require signed commits**: Optional (recommended for production repos)
- **Require linear history**: Recommended (prevents merge commits cluttering history)
- **Do not allow bypassing the above settings**: Enabled (including administrators)
- **Restrict who can push to matching branches**: Limit to repository administrators

### CODEOWNERS

The `CODEOWNERS` file (repo root) assigns automatic review requests for architecture-sensitive paths:
- `.github/workflows/` — CI workflow files
- `app/src/main/java/**/database/` and `app/schemas/` — Room database and migrations
- `app/src/main/java/**/lifecycle/` and `app/src/main/java/**/coordinator/` — Lifecycle/architecture boundaries
- `app/src/main/java/**/workers/` — WorkManager workers
- `app/src/main/java/**/privacy/` and `app/src/main/java/**/security/` — Privacy/security paths
- Python guard scripts, allowlists, and guard template
- `config/release_block_denylist.yml`

All paths are owned by `@panospao7`.

### How to Enable Branch Protection

1. Go to repository **Settings → Branches**
2. Under "Branch protection rules", click **Add rule** (or **Add classic branch protection rule**)
3. Set **"Branch name pattern"** to `main`
4. Also add a second rule with pattern `master` (if used)
5. Check **"Require a pull request before merging"** (requires at least 1 approving review)
6. Check **"Require status checks to pass before merging"**
7. Search and select these required checks:
   - `validate-workflow`
   - `static-guards`
   - `unit-tests`
   - `lint-and-check`
8. Check **"Require branches to be up to date before merging"**
9. Check **"Do not allow bypassing the above settings"** (includes administrators)
10. Click **Save changes**

These settings are enforced by GitHub and cannot be modified via the repository's CI workflow file — they must be configured manually by a repository administrator.

---

## Recommended Next Steps

The following is the planned sequencing for the remaining PRs in the CI Static Guardrails plan (per `CI_STATIC_GUARDRAILS_IMPLEMENTATION_PLAN.md` §15):

| PR | Content | Description |
|----|---------|-------------|
| 2 | Full Gradle Verification | `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `:app:check` as blocking PR checks; artifact upload on failure | ✅ **COMPLETED** — `:app:check` added as blocking CI step; `verifyNoIgnoredGrowth` wired to Gradle `check` lifecycle; `lint-and-check` job renamed to "Lint & Check" |
| 3 | Existing Static Guards in CI | Wire all existing Python guard scripts (privacy, DB access, event writer, money, source provenance) as blocking CI steps; script tests blocking | ✅ **COMPLETED** |
| 4 | Guard Framework Standardization | Rule ID format, allowlist owner/reason/expiry validation, guard fixture pattern, guard docs | 🔄 **IN REVIEW** — tests pending |
| 5 | Migration Matrix MVP | Minimum supported version decision, representative migration fixtures, fresh-vs-migrated schema parity | 🔄 **IN REVIEW** — `verify_migration_matrix.py` created; test suite added; CI integration complete |
| 6 | New Guards Batch A: High-Risk Architecture | Cancellation guard, UI/ViewModel DAO guard, worker full guard, receipt link ownership guard, import lifecycle guard | 🔄 **IN REVIEW** — 5 guard scripts created; CI steps wired; documentation updated |
| 7 | New Guards Batch B: Privacy/Money/Release | Cloud payload guard, PII logging/error guard, raw money sum guard improvements, DI/release binding guard (expanded to full-codebase in PR14) | 🔄 **IN REVIEW** |
| 8 | Ignored Test Budget | Ignored-test scanner, current baseline, release-critical denylist, fail-on-increase policy | 🔄 **IN REVIEW** — `verify_ignored_test_budget.py` created; denylist created; test suite added; CI integration complete |
| 9 | Branch Protection and Documentation | Required check configuration, CODEOWNERS updates, guard ownership docs, developer quickstart | 🔄 **IN REVIEW** — `CODEOWNERS` created; developer quickstart written; docs cross-linked |
