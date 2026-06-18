# Cost-agregator Updated Master Testing Implementation Plan v2

Review target: `31d9e1bbb10976b648788b91fd1922aa3564759a`  
Review type: static GitHub review, not local test execution.

## 1. Biggest updated findings

### Finding 1 — Room schema verification is still unsafe

The architecture and `AppDatabase.kt` say the real DB version is `113`.

But `app/build.gradle.kts` currently has:

```kotlin
val maxVersion = (findProperty("roomSchemaMaxVersion")?.toString()?.toIntOrNull()) ?: 92
```

So the schema verifier is no longer stuck at `35`, but it is still behind the actual app DB version.

This is now the top release-risk issue.

Required fix:

- derive expected schema version from `APP_DATABASE_SCHEMA_VERSION`
- or require `roomSchemaMaxVersion=113` in CI
- make strict mode default in CI
- fail if schemas `93..113` are missing
- fail if `AppDatabase.kt` version and Gradle verifier version disagree

Current plan update:

> Replace “fix stale maxVersion 35” with “make schema verification source-of-truth driven and strict for v113.”

---

### Finding 2 — `testOptions` exists now, but is incomplete

The previous plan said there was no `testOptions` block. That is outdated.

Current `app/build.gradle.kts` includes:

- `unitTests.isReturnDefaultValues = true`
- `unitTests.isIncludeAndroidResources = true`
- Android Test Orchestrator execution
- `maxParallelForks`
- verbose test logging

Good improvement.

Still missing:

- `forkEvery`
- max heap config
- CI task split: fast/integration/nightly
- include/exclude filters by category
- slow-test reporting
- flaky retry policy
- ignored-test-count guardrail
- schema check wired into `check`
- test report artifact standardization

Important warning:

`unitTests.isReturnDefaultValues = true` can hide broken Android-framework calls by returning null/defaults. Keep it only if necessary, but add tests or lint rules that catch accidental framework calls in JVM tests.

---

### Finding 3 — `AppDatabase.kt` is too large and too important to rely on shallow tests

`AppDatabase.kt` is around 7,433 lines and 436 KB. It defines the DB version, entity list, DAOs, and many migrations.

It also contains a KDoc warning about fragile `INSERT INTO ... SELECT *` migration patterns. The file itself lists affected migration areas such as:

- `MIGRATION_49_50`
- `MIGRATION_68_69`
- `MIGRATION_106_107`
- `MIGRATION_107_108`

This needs dedicated migration regression tests, not only schema snapshot presence.

Updated requirement:

Add a migration test matrix:

1. old DB snapshot → v113 migration succeeds
2. migrated schema equals fresh v113 schema
3. fragile table rebuild migrations preserve column values
4. restored backup DB equals expected contract
5. no migration test uses silent `assume` skipping in release CI

---

### Finding 4 — docs are drifting from code

Examples:

- Architecture doc says DB `v113`.
- README still mentions old DB versions such as `46` and `92`.
- README says compile/target SDK 34, but Gradle uses compile/target SDK 35.
- README claims feature/test completeness that is not fully supported by the quality audit.

This matters because AI agents and humans will follow the wrong docs.

Add a doc-drift guard:

- check README DB version against `APP_DATABASE_SCHEMA_VERSION`
- check architecture DB version against `APP_DATABASE_SCHEMA_VERSION`
- check README SDK versions against Gradle
- fail CI or at least warn when they diverge

---

### Finding 5 — current e2e tests are good but still too narrow

The e2e folder currently includes useful flows:

- `AnalyticsPipelineTest.kt`
- `BudgetAlertPipelineTest.kt`
- `CategoryBreakdownFlowTest.kt`
- `DailyAverageFlowTest.kt`
- `DateBoundaryFlowTest.kt`
- `EmptyDataFlowTest.kt`
- `GroupSettlementPipelineTest.kt`
- `MonthlyTotalFlowTest.kt`
- `NotificationExpenseDashboardPipelineTest.kt`
- `ReceiptProcessingPipelineTest.kt`
- `SharedExpenseFlowTest.kt`

This is a good base.

But most are still individual flow tests. The missing layer is still canonical seeded multi-pipeline scenarios that combine:

- notification
- transaction lifecycle
- receipt lifecycle
- recurring
- group/shared expense
- budget
- analytics
- currency
- dashboard
- workers/restore/privacy

Updated plan:

Do not add dozens more isolated e2e files first. Build a scenario framework and convert/merge existing e2e flows into fewer stronger acceptance tests.

---

## 2. Updated priority order

## P0 — Fix source-of-truth and release safety first

### P0.1 Make DB schema verification strict and v113-aware

Implement:

```kotlin
tasks.register("verifyRoomSchemaSnapshots") {
    val appDatabaseFile = file("src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt")
    val versionRegex = Regex("""const val APP_DATABASE_SCHEMA_VERSION\s*=\s*(\d+)""")
    val expectedVersion = versionRegex.find(appDatabaseFile.readText())
        ?.groupValues?.get(1)
        ?.toInt()
        ?: error("Cannot find APP_DATABASE_SCHEMA_VERSION")

    val schemaDir = file("$projectDir/schemas/com.yourname.expensetracker.data.database.AppDatabase")
    val existing = schemaDir.listFiles()
        ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
        ?.toSet()
        ?: emptySet()

    val expected = (1..expectedVersion).toSet()
    val missing = expected - existing

    if (missing.isNotEmpty()) {
        throw GradleException("Missing Room schema snapshots: ${missing.sorted()}")
    }
}
```

If you intentionally only keep schemas from 33 onward, encode that explicitly:

```kotlin
val minSupportedSchemaVersion = 33
val expected = (minSupportedSchemaVersion..expectedVersion).toSet()
```

But do not silently compare only to 92.

Acceptance:

- `verifyRoomSchemaSnapshots` fails if v113 schema is missing
- task is wired into `check`
- release CI runs strict mode
- architecture/README version drift is detected

---

### P0.2 Add migration/fresh-install parity for v113

Create:

```text
app/src/androidTest/java/.../data/database/MigrationFreshInstallParityV113Test.kt
```

Test contracts:

1. create old DB from snapshot
2. run migrations to v113
3. create fresh v113 DB
4. compare:
   - table names
   - columns
   - nullability
   - default values
   - indices
   - foreign keys
5. run representative DAO queries

Also add focused tests for fragile migration areas:

- table rebuilds preserve columns
- explicit column order is used
- indexes exist after migration
- unique constraints exist after migration
- transaction event tables exist
- receipt event/link tables exist
- recurring occurrence tables exist
- privacy audit table exists
- background job table exists

---

### P0.3 Stop direct write bypasses around lifecycle coordinators

Architecture says:

- `TransactionLifecycleCoordinator` is the single entry point for expense create/update/delete.
- `ReceiptLifecycleCoordinator` is the single entry point for receipt processing.
- `RecurringLifecycleCoordinator` owns recurring occurrence generation/materialization.

Add static/code-review guardrails:

- production code should not call `expenseDao.insert*` directly except:
  - repositories
  - migrations
  - test seeders
  - explicitly approved import adapters
- production code should not create receipt links outside `ReceiptLinkService`
- production code should not materialize planned recurring data outside recurring lifecycle services

Create script:

```text
scripts/check-lifecycle-bypasses.sh
```

Fail CI on suspicious calls unless allowlisted.

---

## 3. Updated test infrastructure plan

## 3.1 Create one official scenario fixture package

Create:

```text
app/src/test/java/com/yourname/expensetracker/testfixtures/
  clock/
  coroutine/
  database/
  scenario/
  golden/
  assertions/
```

Move or wrap existing helpers:

- `FakeTimeProvider`
- `TestUtils`
- `FlowTestUtils`
- `ViewModelTestUtils`
- `HiltTestUtils`
- `GoldenAnalyticsDataset`
- `ExpectedResults`
- `GoldenDataSets`
- `FlowPipelineTestHarness`

Goal:

No more scattered one-off test builders.

---

## 3.2 Add `ScenarioSeeder`

The seeder should support both styles:

### A. Seed database state

Used for repository/analytics/dashboard tests.

```kotlin
ScenarioSeeder(db).seed(
    ScenarioSeed(
        settings = ...,
        categories = ...,
        exchangeRates = ...,
        expenses = ...,
        budgets = ...,
        groups = ...,
        receipts = ...,
        recurringRules = ...
    )
)
```

### B. Feed real pipeline inputs

Used for multi-pipeline acceptance tests.

```kotlin
ScenarioRunner(appHarness).feed(
    NotificationInput(...),
    ReceiptInput(...),
    BankStatementInput(...),
    RecurringRuleInput(...)
)
```

The highest-value scenario tests should use **feed mode**, not only direct DB insertion.

---

## 3.3 Add assertion DSLs

Required assertion helpers:

```kotlin
assertMoney(...)
assertMoneyBucket(...)
assertPartialConversion(...)
assertDashboardTotal(...)
assertCategoryTotal(...)
assertLifecycleEvents(...)
assertReceiptEvents(...)
assertReceiptLinkedToExpense(...)
assertRecurringOccurrenceStatus(...)
assertNoDoubleCount(...)
assertGroupBalance(...)
assertBudgetStatus(...)
assertPrivacyAuditEvent(...)
assertWorkerPausedDuringRestore(...)
```

This keeps scenario tests readable and prevents duplicate expected-value math inside each test.

---

## 4. Updated canonical scenario list

The previous scenario list was good. I would update the priority and add two new scenarios.

## Scenario 1 — `schema_v113_migration_fresh_install_parity`

Priority: highest.

Covers:

- database
- migrations
- backup/restore foundation
- schema snapshots
- all persisted segments indirectly

Expected:

- schema snapshots include v113
- old snapshots migrate to v113
- migrated schema equals fresh schema
- critical DAO queries work
- no silent assume skips

---

## Scenario 2 — `transaction_lifecycle_db_contract`

Priority: highest.

Use in-memory Room if possible.

Input:

- create manual expense
- duplicate create attempt
- update amount/category
- delete expense

Expected:

- `Created`
- `DuplicateSkipped`
- event log sequence:
  - `CREATED`
  - `DUPLICATE_SKIPPED`
  - `UPDATED`
  - `DELETED`
- dashboard total changes correctly
- budget recalculates
- analytics recalculates
- direct DAO bypass does not happen in production path

---

## Scenario 3 — `notification_review_dashboard_budget`

Priority: highest.

Input:

- Greek bank notification
- Revolut notification
- duplicate notification
- category rules
- monthly budget

Expected:

- parser extracts amount/currency/merchant/time
- confidence route correct
- review item created if needed
- approval creates expense via lifecycle coordinator
- duplicate skipped
- transaction event logged
- dashboard total correct
- budget status correct
- analytics category total matches dashboard

---

## Scenario 4 — `receipt_matching_analytics_no_double_count`

Priority: highest.

Input:

- receipt OCR text or fixture image
- existing bank transaction
- fake item categorization result

Expected:

- receipt status progression correct
- asset/hash stored
- duplicate detector checked
- receipt event log written
- receipt-expense link created
- no duplicate expense created
- analytics counts expense once
- warranty/price-protection effects gated by document type

---

## Scenario 5 — `multicurrency_partial_rate_dashboard_analytics`

Priority: highest.

Input:

- home currency EUR
- EUR, USD, GBP expenses
- one missing/stale exchange rate
- category budget

Expected:

- source buckets preserved
- converted total excludes or marks missing-rate data according to contract
- partial flag true
- warning visible to dashboard/analytics
- budget uses normalized home currency only where safe
- forecast confidence reduced or partial state exposed

---

## Scenario 6 — `recurring_planned_actual_no_double_count`

Priority: high.

Input:

- monthly recurring rule
- planned occurrence
- actual payment notification/manual expense

Expected:

- occurrence generated
- reminder delivery created once
- actual expense linked
- planned + actual not double-counted
- future forecast includes future planned cost
- current dashboard uses actual once

---

## Scenario 7 — `shared_expense_reimbursement_budget_offset`

Priority: high.

Input:

- group dinner
- payer/member split
- reimbursement
- dining budget

Expected:

- group balances correct
- settlement suggestion correct
- gross spending contract explicit
- budget-offset net amount correct
- analytics not corrupted by reimbursements

---

## Scenario 8 — `restore_maintenance_workers_safe_startup`

Priority: high.

Input:

- restore journal active/incomplete
- pending workers
- notification capture enabled before restore

Expected:

- workers paused/cancelled
- notification capture disabled
- journal recovery happens before scheduling
- no worker writes DB during restore
- workers rescheduled only after safe completion
- critical recovery state blocks unsafe startup

---

## Scenario 9 — `privacy_ai_redaction_gate`

Priority: high.

Input:

- cloud AI disabled
- sensitive receipt/notification/query text
- local AI available/unavailable variants

Expected:

- cloud provider not called when denied
- privacy audit event written
- sensitive fields redacted before allowed calls
- fallback behavior deterministic
- user-facing denial state exists

---

## Scenario 10 — `bank_statement_import_review_lifecycle`

Priority: medium-high.

Input:

- statement OCR/PDF text
- existing duplicate
- new transactions
- review decisions

Expected:

- transactions extracted
- review candidates created
- duplicate candidate skipped
- approved candidate creates expense via lifecycle
- source tracking retained
- analytics includes only approved non-duplicates

---

## Scenario 11 — `investment_tracking_smoke_contract`

New scenario.

Priority: medium.

Because investment tracking exists in README/architecture, add a minimal contract test.

Input:

- portfolio with stock/crypto/bond/ETF style holdings
- historical values
- one sync/import failure

Expected:

- portfolio current value correct
- gain/loss correct
- missing price source is surfaced
- dashboard/investment UI state handles empty/failure states
- no currency mixing if holdings are multi-currency

---

## Scenario 12 — `bill_negotiation_recommendation_contract`

New scenario.

Priority: medium.

Input:

- recurring bill/subscription
- price increase
- provider negotiation unavailable
- privacy/AI denied variant

Expected:

- negotiation recommendation generated only when eligible
- no recommendation when data insufficient
- provider failure handled
- cloud AI/privacy gate respected
- user-facing state clear

---

## 5. Dead/marginal test handling update

Keep the previous classification, but change the action slightly.

### Delete or archive immediately

- source-string assertion tests
- tests that only reimplement math inside the test
- ignored stress tests with no unique business contract
- duplicate stress tests that mirror regular tests

### Rewrite into scenario/contract tests

Do not blindly delete these if the feature is live:

- `CsvEscapingTest.kt`
- `CustomSplitParserTest.kt`
- `SplitCalculationPrecisionTest.kt`
- `TaxCalculationTest.kt`
- `MoneyTest.kt`

These protect financial/export correctness. If currently ignored, rewrite them as focused deterministic tests.

### Move to nightly

Only meaningful deterministic stress tests should move to nightly:

- transaction rollback
- notification pipeline concurrency
- repository load tests
- receipt matching load tests
- geocoding load tests
- worker idempotency tests

If a stress test has arbitrary `delay`, race-sensitive assertions, or relaxed mocks only, rewrite first or delete.

---

## 6. Gradle/CI updates

Current Gradle is improved, but add:

```kotlin
unitTests.all {
    it.forkEvery = 100
    it.maxHeapSize = "2g"

    if (project.hasProperty("fastTests")) {
        it.exclude("**/*StressTest*")
        it.exclude("**/*PerformanceTest*")
        it.exclude("**/*Migration*")
    }

    if (!project.hasProperty("includeNightly")) {
        it.exclude("**/*Nightly*")
    }
}
```

Add tasks:

```text
checkFast
checkIntegration
checkMigration
checkNightly
verifyNoIgnoredGrowth
verifyLifecycleBypasses
verifyDocsVersionDrift
verifyRoomSchemaSnapshots
```

CI gates:

### PR gate

- compile
- fast JVM tests
- schema verifier strict
- no ignored-test increase
- no lifecycle bypass
- no docs version drift
- canonical scenario tests touched by changed area

### Release gate

- full JVM suite
- instrumented migration suite
- all canonical scenarios
- backup/restore tests
- privacy gate tests
- restore/worker tests
- large DB smoke test

### Nightly gate

- stress
- concurrency
- performance
- large backup/restore corpus
- long-running workers

---

## 7. Codebase-level improvement plan

Testing alone will not solve everything. Add these codebase hardening items.

### 7.1 Split `AppDatabase.kt`

Long-term target:

```text
data/database/
  AppDatabase.kt
  AppDatabaseSchema.kt
  AppDatabaseMigrationsCore.kt
  AppDatabaseMigrationsFeature.kt
  AppDatabaseMigrationsV100Plus.kt
  DatabaseCallbacks.kt
```

Keep public API stable, but reduce the risk of editing one 7k-line file.

### 7.2 Create database contract docs

Add:

```text
docs/database/SCHEMA_CONTRACT.md
```

Include:

- current schema version
- supported migration start versions
- critical invariants
- lifecycle event tables
- receipt link rules
- recurring occurrence rules
- privacy audit rules
- backup/restore expectations

### 7.3 Update README to match code

Fix:

- DB version
- SDK versions
- testing claims
- feature completeness claims
- Gradle commands
- CI status if not real

### 7.4 Dependency alignment review

Current Gradle mixes several library generations. Add a maintenance task:

- align `kotlinx-coroutines-android` and `kotlinx-coroutines-test`
- review Robolectric compatibility with compile SDK 35
- review AndroidX test runner/orchestrator versions
- remove Mockito if only one test still needs it
- decide whether `unitTests.isReturnDefaultValues` is still needed

---

## 8. Concrete revised 6-week plan

## Week 1 — Release safety

1. Make Room schema verifier v113-aware.
2. Wire schema verifier into `check`.
3. Add docs version drift check.
4. Add lifecycle bypass scanner.
5. Update README/architecture version drift.
6. Add `MigrationFreshInstallParityV113Test` skeleton.
7. Confirm schemas 93–113 exist or generate them.

Deliverable:

- CI can no longer pass with stale schema coverage.

---

## Week 2 — Fixture foundation

1. Create `testfixtures` package.
2. Standardize fake clock.
3. Standardize coroutine rule.
4. Standardize in-memory Room DB factory.
5. Create first `ScenarioSeeder`.
6. Create money/dashboard/event assertion helpers.
7. Convert one existing e2e test to scenario fixture style.

Deliverable:

- one scenario test using the new harness.

---

## Week 3 — Core lifecycle scenarios

1. `transaction_lifecycle_db_contract`
2. `notification_review_dashboard_budget`
3. lifecycle bypass scanner allowlist finalized
4. event log assertion DSL

Deliverable:

- core expense flow tested from input to DB/event/dashboard.

---

## Week 4 — Receipt/currency/recurring

1. `receipt_matching_analytics_no_double_count`
2. `multicurrency_partial_rate_dashboard_analytics`
3. `recurring_planned_actual_no_double_count`

Deliverable:

- three main multi-pipeline correctness risks covered.

---

## Week 5 — Groups/runtime/privacy

1. `shared_expense_reimbursement_budget_offset`
2. `restore_maintenance_workers_safe_startup`
3. `privacy_ai_redaction_gate`
4. WorkManager test harness if needed

Deliverable:

- background safety and privacy now tested.

---

## Week 6 — Under-tested feature smoke tests + cleanup

1. investment tracking smoke contract
2. bill negotiation smoke contract
3. bank statement import review lifecycle
4. delete/rewrite dead ignored tests
5. move valid stress tests to nightly
6. add slow-test report

Deliverable:

- cleaner suite with stronger coverage and less noise.

---

## 9. Final updated definition of done

The improved plan is complete when:

1. Schema verification uses DB v113 as source of truth.
2. Missing schema snapshots fail CI.
3. Migration/fresh-install parity passes for v113.
4. README and architecture no longer drift from code.
5. No production code bypasses transaction/receipt/recurring lifecycle entry points without allowlist.
6. Scenario fixture system exists.
7. At least 10 canonical multi-pipeline scenarios pass.
8. Existing e2e tests are either converted or mapped into scenario catalog.
9. Dead ignored tests are deleted or rewritten.
10. Stress tests are tagged nightly, not permanently ignored.
11. Currency partial-rate behavior is tested end-to-end.
12. Receipt matching no-double-count behavior is tested.
13. Recurring planned/actual no-double-count behavior is tested.
14. Restore maintenance worker safety is tested.
15. Privacy/AI redaction gates are tested.
16. Investment and bill negotiation have at least smoke/contract coverage.
17. CI has fast/integration/migration/nightly separation.
18. Test documentation explains how future agents should add scenario tests.

---

## Sources reviewed

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/31d9e1bbb10976b648788b91fd1922aa3564759a

- Architecture doc:  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/ARCHITECTURE.md

- Segment map:  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docs/architecture/CODEBASE_SEGMENTS.md

- App Gradle config:  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/app/build.gradle.kts

- AppDatabase:  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- Test scout report:  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-scout-report.md

- Test quality audit:  
  https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-quality-audit.md

- e2e tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker/e2e

- verification tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/31d9e1bbb10976b648788b91fd1922aa3564759a/app/src/test/java/com/yourname/expensetracker/verification