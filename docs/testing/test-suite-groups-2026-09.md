# Test Suite Groups — Sweep 2026-09-18

## Purpose

The JVM suite (~663 test files under `app/src/test`, plus 28 device test files under `app/src/androidTest`) is too large to validate or triage as one unit. These groups partition the suite so each slice can be run in a bounded serial shard via `validation-runner`, triaged independently, and reported per-group during the full-suite sweep.

Groups map 1:1 to the named shards in `config/validation/test-shards.json` where possible. Two deliberate divergences exist and are documented below: `metrics/` + `diagnostics/` are reported under GROUP-A for thematic reasons (GROUP-E note), and GROUP-G collects files no shard filter matches. `test-shards.json` remains authoritative for runner behavior; this doc is descriptive.

## Group definitions

Package filters are abbreviated; every filter is `com.yourname.expensetracker.<pkg>.*` exactly as listed in `test-shards.json`.

| Group ID | Runner profile/shard | Package filters | Contents summary | File count | Notes |
|---|---|---|---|---|---|
| GROUP-A | `architecture-contracts` | architecture, consistency, contracts, guard, guards, verification (+ metrics, diagnostics — sweep reporting only) | architecture guards, consistency cross-checks, contract tests, guard suites, verification, metrics consistency, durable diagnostics | ~53 | Runner shard covers only the 6 listed packages; `metrics/` and `diagnostics/` are added to this group for sweep reporting (see GROUP-E note). Verify. |
| GROUP-B | `data` | data | data layer: ai, backup, currency, database (incl. dao), email, location, privacy, repository, security, service, speech, store | 139 | Largest shard. Contains HANG-003 (`data.backup.ExportReadBarrierTest`). |
| GROUP-C | `domain-a-m` | domain.{ai, alerts, analytics, bank, budget, business, carbon, cashflow, categorization, challenge, common, consistency, core, currency, dashboard, debug, dto, engine, export, forecasting, groups, health, income, intelligence, investment, location, logic, model} | domain logic a–m (28 packages per shard filters) | ~163 | Verify. |
| GROUP-D | `domain-n-z` | domain.{naturallanguage, negotiation, notification, parser, price, privacy, provenance, receipt, receiptmatching, recurring, reminder, savings, sideeffect, split, subscription, tax, transaction, usecase, util, widget, workers} | domain logic n–z (21 packages per shard filters) | ~136 | Contains HANG-001 (`domain.receipt.lifecycle.*`) and HANG-002 (`domain.transaction.lifecycle.*`). Verify. |
| GROUP-E | `runtime-ui` | diagnostics, metrics, receiver, service, startup, ui, util, worker, workers | runtime, services, receivers, startup, UI/ViewModel, workers, utils | ~79 | Count derived as residual of ~663 total minus other groups; verify. See discrepancy note below. |
| GROUP-F | `integration-golden` | e2e, golden, integration, scenarios | end-to-end flows, golden-master invariants, integration pipelines, scenario tests | 81 | |
| GROUP-G | none | not matched by any shard filter | root-package fixtures + orphaned fixture-test + testfixtures | ~12 | Coverage gap — see note below. Verify. |
| GROUP-H | `connected-tests` | app/src/androidTest/** | instrumented tests: migrations (MigrationContractTest, DatabaseMigrationTest, DatabaseMigrationMatrixTest), dao/ suite, location worker test | 28 | Requires emulator/device. CI is `continue-on-error` → effectively manual-only for MigrationContractTest and dao/ tests. |

### GROUP-E / GROUP-A diagnostics+metrics discrepancy — resolution

The `runtime-ui` shard filter includes `diagnostics.*` and `metrics.*`, so **in the RUNNER those packages execute in the runtime-ui shard**. In **this sweep doc they are reported under GROUP-A** (thematic fit with consistency/verification reporting). Both statements are true; do **not** double-count the 10 files (5 `diagnostics/`, 5 `metrics/`) when summing groups.

### GROUP-G — orphaned files and fixtures (coverage gap)

Not matched by any shard filter:

- Root package (`app/src/test/java/com/yourname/expensetracker/`): `AnalyticsEngineTestBase.kt`, `AnalyticsTestCompat.kt`, ` TestUtils.kt` — fixtures, 0 `@Test`.
- `currency/CanonicalMultiCurrencyFixture.kt` — **contains `CanonicalMultiCurrencyFixtureTest` with 7 `@Test` methods: runnable but orphaned from all shards.** It only executes under the unfiltered `unit-tests` profile. **Flagged as a coverage gap** — shard-based sweeps never run it.
- `testfixtures/**` (6 files: TestFixtures, scenario/ScenarioSeeder, scenario/ScenarioSeed, scenario/ScenarioAssertions, golden/GoldenScenarioVerifier, database/AppDatabaseTestFactory) — fixtures, 0 `@Test`.
- Empty directories `concurrency/` and `di/` (no files).

## Cross-cutting ledgers

### Known-hanging tests (`config/validation/known-hanging-tests.json`)

| ID | Test filter | Status | Routing | Reason code | Review by |
|---|---|---|---|---|---|
| HANG-001 | `domain.receipt.lifecycle.*` | suspected | legacy | HISTORICAL_COROUTINE_TIMEOUT | 2026-10-31 |
| HANG-002 | `domain.transaction.lifecycle.*` | suspected | legacy | HISTORICAL_COROUTINE_TIMEOUT | 2026-10-31 |
| HANG-003 | `data.backup.ExportReadBarrierTest` | suspected | legacy | HISTORICAL_COROUTINE_TIMEOUT | 2026-10-31 |

`routing=legacy` means: run via the `legacy-tests` profile (isolated from other execution). Per the ledger's own description, entries add no `@Ignore` and never remove tests from `unit-tests` or normal shards — HANG-001/002 still sit inside GROUP-D shard scope, HANG-003 inside GROUP-B.

### @Ignore census (verified 2026-09-18 by grep over `app/src/test`)

33 grep matches for `@Ignore`, of which 6 are comment-only mentions (e.g. "no @Ignore" notes in TimePeriodUtils tests, a removed-annotation note in BudgetRepositoryStressTest) → **27 real `@Ignore` annotation sites in 25 files**:

- **20 × "Stress test: may hang in CI, run manually"** — 17 class-level: 6 in `data/repository/*StressTest` (ReviewQueue, Receipt, Notification, NotificationProcessingPipeline, Expense, Category), `ExpenseEntityStressTest`, `CompositeGeocodingServiceStressTest`, and 9 UI/ViewModel stress files (Budget, AddExpense, Main, Analytics, Debug, Transactions, Home, Review, ReceiptScan); plus 3 method-level in `SpendingMapViewModelStressTest`.
- **3 whole-file husks** referencing removed APIs (class-level): `ReceiptLifecycleBugFixesTest`, `ReceiptLifecycleHardeningTest`, `RecurringLifecycleFixesTest`.
- `SecureKeyStorageTest` — AndroidKeyStore unavailable on desktop JVM (class-level).
- `NotificationIntakeEnqueueFailureTest` — documented 10+ min hang; real-Room + runTest + MockK suspend-extension interplay (class-level).
- `TaxCalculationTest` — 1 method (VAT logic differs from expectation).
- `NotificationIdGeneratorTest` — 1 method (negative IDs unsupported for receipt notification mapping).

Sum check: 20 + 3 + 1 + 1 + 1 + 1 = 27.

### Special profiles

- **`trusted-tests`** — fast structural gate; filters architecture/contracts/guard/guards/verification (per the `trusted` block in `test-shards.json`; note it does **not** include `consistency`, so it is narrower than GROUP-A). Not a replacement for the full suite.
- **`legacy-tests`** — isolates ledgered hang suspects (HANG-001/002/003); never skips them from `unit-tests` or normal shards.
- **`migration-tests`** — `*Migration*` filter over JVM tests; does **NOT** catch the androidTest `MigrationContractTest` (device-only, GROUP-H).

## Execution mapping

| Group | validation-runner profile(s) used in this sweep |
|---|---|
| GROUP-A…F | `unit-test-shard -Shard <shard-name>` (one run per shard) |
| GROUP-G | only covered by the unfiltered `unit-tests` profile |
| GROUP-H | `connected-tests` (requires emulator/device) |
| Whole suite | `unit-tests` |

## Maintenance rules

- When adding a test file, place it in a package a shard filter already matches; if a new top-level package is added, update `config/validation/test-shards.json` **and** this doc (date-stamped).
- This doc is descriptive, not authoritative for runner behavior; `config/validation/test-shards.json` is authoritative.
- Date-stamp any re-grouping. Last updated: 2026-09-18.
