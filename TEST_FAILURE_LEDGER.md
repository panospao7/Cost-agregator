# Test Failure Ledger — Validation/Stabilization Run (Pipelines 5–12)

> HEAD: `43ca2228` (Pipeline 11 + accumulated 5–12)
> Method: real `:app:testDebugUnitTest` runs, batched by package. Numbers below are MEASURED, not estimated.

## Gate status
- `:app:assembleDebug` — **GREEN** (prod compile + KSP/Room + Hilt graph OK; warnings only)
- `:app:compileDebugUnitTestKotlin` — **GREEN** (after FIX-COMPILE-1/2 below)
- `architecture.*` — **GREEN** (all pass, incl. BackupRestoreArchitectureGuardTest after comment fix)

## Operational note
- A JVM instrumentation-agent crash (`java.lang.instrument ASSERTION FAILED: "!errorOutstanding"`)
  occurred during the `data.*` batch and suppressed per-class result XMLs. This fork instability is the
  likely cause of the original full-suite hang. Console logs in `batches/*.txt` are authoritative for that batch.
  Mitigation under consideration: run in smaller package filters; do NOT mask via timeout/threshold changes.

## Measured failure census
- `domain.*` batch: 2633 run, **121 FAILED**, 5 skipped.
- `data.*` batch: 947 run, **53 FAILED**, 154 skipped.
- (ui/scenarios/golden/e2e/integration/etc. NOT yet run.)

---

## Fixes applied

### FIX-COMPILE-1 — invalid MockK import (test compile)
- Command: `:app:compileDebugUnitTestKotlin`
- Failure type: test compile (`Unresolved reference 'capture'`)
- Root cause: `import io.mockk.capture` is not a top-level symbol; `capture()` is a `MockKMatcherScope` member resolved inside `every{}`/`coEvery{}`.
- Fix type: stale test (dead import). Deleted the line in 4 files: BudgetForecastingEngineDiagnosticsTest, PlannedExpenseRepositoryDiagnosticsTest, BudgetRepositoryDiagnosticsTest, BudgetMonitorTest.
- Status: DONE, verified green.

### FIX-COMPILE-2 — nested block comment (test compile)
- Failure type: test compile (`Unclosed comment` at EOF)
- First failing file: architecture/BackupRestoreArchitectureGuardTest.kt
- Root cause: KDoc line 23 text `ui/screens/debug/**` contains `/*`, opening a NESTED block comment; the `*/` on line 24 closed only the nested level, leaving the KDoc open to EOF.
- Fix type: stale test (comment text). Reworded to `ui/screens/debug/`.
- Status: DONE, verified green (guard tests pass).

---

## Open failure families (triage)

| ID | Family / class(es) | Count | Signature | Provisional verdict |
|----|--------------------|------:|-----------|---------------------|
| F-01 | domain.receipt.BankStatementParserTest | 19 | `NoSuchElementException` in `@Before` line 34 | STALE TEST — `mockk<Flow>()`+`coEvery{first()}` antipattern; use `flowOf("EUR")` |
| F-02 | domain.*.lifecycle: Receipt(8)+Transaction(8) | 16 | `UncompletedCoroutinesError` after 1m | NEEDS INVESTIGATION (HIGH RISK shared lifecycle infra) |
| F-03 | domain.budget.BudgetMonitor(3)+Stress(5) | 8 | `updateXNotification` not called; prod uses cached `getBudgetStatuses()` | NEEDS INVESTIGATION |
| F-04 | domain.intelligence.ml.ExpenseCategoryClassifierTest | 7 | DataStore file collision + JSONException | NEEDS INVESTIGATION |
| F-05 | domain.tax.TaxEstimatorTest | 6 | assertion drift | NEEDS INVESTIGATION (PURCHASE-only filter? P5) |
| F-06 | domain.reminder.BillReminderManagerTest | 3 | `IllegalStateException: Legacy markBillPaid removed` | STALE TEST — intentional prod change (P4); update to new contract |
| F-07 | domain.forecasting.* + analytics.* delta drift | ~20 | `Expected x ± d, but was y` | NEEDS INVESTIGATION (likely P5 normalization) |
| F-08 | data.repository.ReviewQueueRepositoryTest | 8 | (tbd) | NEEDS INVESTIGATION |
| F-09 | data.store.ExpenseStoreTest | 5 | (tbd) | NEEDS INVESTIGATION |
| F-10 | data.database.MigrationRegistrationTest | 4 | (tbd) | NEEDS INVESTIGATION (MIGRATION — careful) |
| F-11 | data.ai CloudDashboardBriefingServiceTest | 4 | (tbd) | NEEDS INVESTIGATION |
| F-12 | misc single/low-count domain+data classes | ~50 | mixed | per-class triage |

## Data-batch root-cause clusters (from saved console log; XMLs lost to instrument crash)

| ID | Family / class(es) | Count | Signature | Provisional verdict |
|----|--------------------|------:|-----------|---------------------|
| F-13 | ReviewQueueRepositoryTest(8) + RecurringExpenseRepositoryTest(3) | 11 | `ClassCastException: java.lang.Object cannot be cast to CreateExpenseResult / RecurringRuleLifecycleCoordinator` | NEEDS INVESTIGATION — relaxed MockK on a `Lazy<>`/sealed-return; likely stub gap from a recent Lazy-wrap refactor |
| F-14 | ExpenseStoreTest(5)+DatabaseBarrierTest(1)+ExportReadBarrierTest(1) | 7 | expected `DatabaseAccessBlockedException` but was `IllegalStateException` | NEEDS INVESTIGATION (shared DatabaseWriteBarrier contract; commit-aware) |
| F-15 | data.database.MigrationRegistrationTest | 4 | `FileNotFoundException: AppDatabase/{119,120,141,142}.json` missing in test assets | MIGRATION — verify exported schemas + test asset wiring (careful) |
| F-16 | ExportReadBarrierTest(2) | 2 | `UncompletedCoroutinesError` 1m | part of F-02 coroutine-harness family |
| F-17 | AutomatedSavingsRuleStateRepositoryTest(1)+ml.ExpenseCategoryClassifierTest(7) | 8 | `multiple DataStores active for the same file` | STALE TEST — isolation; unique temp file / cancel scope |
| F-18 | MaintenanceOperationRunnerTest | 3 | drain timeout / exit not called | NEEDS INVESTIGATION |
| F-19 | CloudDashboardBriefingServiceTest | 4 | retry count `expected 2/3 but was 0` | NEEDS INVESTIGATION (retry refactor?) |
| F-20 | MerchantKeyBackfillWorkerTest(2) | 2 | merchant key `still_broken` vs `stillbroken` | likely intentional normalization change |
| F-21 | misc single (ExchangeRateStoreAdapter validDate, EmailReceiptParser date/tz, RecommendationDao, RecurringOccurrenceDao, AiChat payloadJson, Geocoding, RawNotificationDao, DeterministicExpenseExportPager, AccountingExport, OnDeviceReceiptAssist, ReceiptRepoStatementDup) | ~14 | mixed | per-class triage |

## Wave 1 dispatch (parallel, independent file sets)
- coder    → F-01 BankStatementParserTest fixture (19)
- debugger → F-14 DatabaseWriteBarrier typed-exception contract (7)
- debugger → F-13 ClassCastException relaxed-mock/Lazy stub gap (11)
