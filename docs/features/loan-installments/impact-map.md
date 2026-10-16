# Impact map: loan-installments

Author: impact-mapper (stage 5), split into Part A and Part B after a single run hit its turn limit.
Persisted by feature-orchestrator, 2026-10-16. Status: Part A + Part B DONE.

**Risk tier: T3** (Part A). Planner-first; reviewer-strict; room-migration-, privacy-security- and
architecture-guardian mandatory; migration tests required.

---

## Part A — schema, backup, workers, privacy, guards, tier

Tool budget slightly exceeded (~24 calls). Items marked UNVERIFIED were not confirmed in source.

### A1. Schema
- Current Room version **151**: `APP_DATABASE_SCHEMA_VERSION = 151` at
  `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt:43` (comment at line 26: no
  `@Database(version=N)` annotation for v1–v5). Next version **152**.
- Snapshots: `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/<N>.json`; `151.json` exists.
- Migrations: `app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt`. Latest
  `MIGRATION_150_151` = `SavingsSweepMonthMigration(timeProvider)` wrapping `DatabaseMigrations.MIGRATION_150_151(db,
  timeProvider)` (lines 149–154); migration list ~line 182 — append `MIGRATION_151_152`.
- `DatabaseSchemaPolicy.kt` references the version constant — review for version/table registry.
- Version-constant consumers to review on bump: `DatabaseBackupRepositoryImpl.kt`, `FinancialRescueCoordinator.kt`,
  `DatabaseBackupRepositoryImplTest.kt` (hardcoded `PRAGMA user_version = 86` and 37 are historical fixtures),
  `BackupRestoreMoneyIntegrityScenarioTest.kt`, `ReceiptAssetRepeatRestoreTest.kt`, `SourceScanningArchitectureGuardTest.kt`.
- Migration tests are instrumented: `app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt`
  (>4,400 lines; lists `AppDatabase.MIGRATION_x_y` explicitly, e.g. line 46; version-parsing helper ~line 4456).
  150→151 test by name: UNVERIFIED.
- Spec §12 adds: table `installment_plans`, table `installment_entries`, nullable `expenses.spendingExclusionReason`.
- Required: bump 151→152; `MIGRATION_151_152` (2× CREATE TABLE + indexes, 1× ALTER TABLE ADD COLUMN); register;
  `152.json`; androidTest (expenses seeded at 151 get NULL column, totals identical, new tables empty, backup verify
  passes); entity + DAO additions; `AppDatabase` entity list; update version-constant consumers.
- Additive only; nothing destructive.

### A2. Backup, retention, export
- `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt`: `TABLE_TIERS` at line 45 (business
  tables `TIER_1_EXACT`; lines 49–51: `requiredManifestTables()` filters by tier). Add `installment_plans` and
  `installment_entries` at TIER_1_EXACT. Add the 5 orphan checks from spec §11.3 (existing block ~520–537, fixed
  count-only strings); line 522 check unchanged.
- Manifest counts and backup privacy-mode text stripping: `data/repository/DatabaseBackupRepositoryImpl.kt` + backup
  package. UNVERIFIED which mode strips text — planner must confirm `displayName` / `lenderLabel` stripped like expense
  merchant/notes.
- Retention registry: `app/src/main/java/com/yourname/expensetracker/domain/privacy/RetentionPolicyContract.kt:21`
  (`requiredTargets`). Per spec §11.2 new tables NOT added. No plan event table (OD-13); adding one would need human
  approval of a `RetentionPolicyContract` change.
- Erase-all / factory-reset path: UNVERIFIED (likely `clearAllTables` or `FinancialRescueCoordinator`); planner must
  confirm plan tables are covered.
- Export: no plan-data export in v1; expense CSV/accounting exports may need "not counted" marking and INV-1 totals
  (OD-11). Export code location UNVERIFIED.

### A3. Workers
- Reuse `BillReminderWorker` (`app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt:34`):
  injects `WorkerExecutionGuard`; scheduled via `WorkerSpecScheduler.scheduleFromSpec(context, "bill_reminder_periodic", ...)`
  at `domain/workers/WorkerRegistry.kt:105`; calls `BillReminderManager` (`domain/reminder/BillReminderManager.kt:55`,
  deps `RecurringExpenseRepository`, `TimeProvider`, `MultiCurrencyRepository`, `CurrencySettingsRepository`) — needs
  an installment projection port.
- AUTO_RECORD (`recordDueEntries`) host open (spec §5.1 "existing or new worker"). Best fit: recurring-generation
  worker (name UNVERIFIED). A new worker needs `WorkerRegistry` entry + spec registration (T3 guard surface, FG-11).
- `WorkerExecutionGuard.kt:85`, `WorkerRegistry.kt:46` (under `domain/workers/`). Write barriers
  (`checkWritesAllowed`, `runWrite`) inside the coordinator. Never swallow `CancellationException`.

### A4. Privacy, permissions, cloud
- No network, no new permissions, no notification/OCR/receipt reads (spec §11.5); manifest not verified.
- `CloudPayloadPurpose` enum at `domain/privacy/CloudPayloadRedactor.kt:25`.
- Dashboard briefing: `GenerateDashboardBriefingUseCase` (`domain/ai/usecase/`) ← `DashboardBriefingInputBuilder`
  (`DashboardBriefingInputBuilder.kt:24`, depends only on `TimeProvider`, maps `ProcessedDashboardData`; has
  `DashboardUpcomingItemInput`, imported line 4).
- **Risk:** if upstream `ProcessedDashboardData` reads `CashFlowCalculator` / forecast / `RecurringPlanProjectionService`
  with installment projection wired in, plan amounts/dates leak into the briefing. Planner must trace upstream assembly
  and add an explicit exclusion. Upstream UNVERIFIED. `GenerateTransactionInsightUseCase` also uses `DASHBOARD_BRIEFING`.
- Diagnostics: bounded codes and counts only.

### A5. FG guards (`FINAL_CI_GUARD_ACCEPTANCE_GATE.md`)
- FG-10 (cancellation), FG-11 (worker guard) — if a worker is added/changed.
- FG-12 (UI/DAO ownership) — plan DAO single writer; no `ExpenseDao` use from plan code.
- FG-13 (DB access / write barrier).
- FG-14 (event writer / transaction ownership) — new TLC op `setSpendingExclusionDbOnly`, new
  `TransactionUpdateKind.SPENDING_INCLUSION`.
- FG-16 (privacy/PII/cloud boundaries) — plan repositories unreachable from cloud payload assembly.
- FG-17 (money/time correctness) — `check_raw_money_aggregates` / `verify_raw_money_aggregates` (registered in
  `scripts/ci/guard_registry.py`, `scripts/ci/run_static_guard_suite.py`; tests `scripts/test_verify_raw_money_aggregates.py`).
  FG mapping probable, UNVERIFIED. Canonical predicate rollout will likely touch its allowlist/ratchet.
- FG-21 (blocking migration proof).
- FG-02 (guard registry), FG-23 (guard self-protection), FG-06 (allowlist/suppression), FG-07 (no-growth ratchets).
- FG-26 (documentation traceability) — `LEGAL_PATHS.md`, `ENGINE_INTERACTION_MAP.md`, `CODEBASE_SEGMENTS.md`.
- DB ownership policy: `config/guards/db_ownership_policy.yml` + `db_ownership_policy.signatures.accounting.json`,
  enforced by `scripts/verify_db_access_boundaries.py`, `scripts/db_guard/`. Plan DAO writes need a policy entry.
- New guards proposed by spec (canonical-predicate guard, plan single-writer test, cloud-reachability guard) need owner
  approval (strengthening, but touches registry/self-protection).

### A6. Risk tier: **T3**
New Room tables + column + migration; money/currency/rounding; new lifecycle owner and legal path; workers
(AUTO_RECORD); privacy/cloud boundary (briefing); guard additions and `db_ownership_policy` changes; cross-segment
contracts (TLC, cash flow, forecast, recurring projection, reminders, budgets); change to every spending aggregate.
Gates: planner-first; reviewer-strict; guardians room-migration, privacy-security, architecture; migration tests
(androidTest `DatabaseMigrationTest`); validation: targeted-unit-test → static-guards → app-check / full unit-tests.

### A7. Gate-2 human decisions beyond OD-1..OD-15
1. Schema impact: DB 151→152, additive, 2 tables + 1 column.
2. Tier T3 and mandatory reviewer/guardian set.
3. New legal path "Installment Plan Mutations" in `LEGAL_PATHS.md` (overlaps OD-1; record explicitly).
4. New guards: canonical-spending-predicate, plan single-writer, cloud-reachability.
5. `config/guards/db_ownership_policy.yml` + signature-accounting changes.
6. Any `check_raw_money_aggregates` allowlist/ratchet change.
7. AUTO_RECORD worker host: reuse existing vs new `WorkerRegistry` entry.
8. New enum values `ExpenseSource.INSTALLMENT_PLAN`, `TransactionUpdateKind.SPENDING_INCLUSION` (persisted/exported;
   backup compatibility).
9. Data-wipe coverage of plan tables; backup privacy-mode behaviour for `displayName` / `lenderLabel`.
10. Out-of-scope triage: `BackupVerifier.kt:522` sourceType observation; whether upcoming-payment event metadata
    reaches export.

### Part A unverified items
Recurring-generation worker name; wipe path; export code location; upstream dashboard assembly; FG-ID for
raw_money_aggregates; 150→151 migration test; backup privacy-mode set; manifest permissions.

---

## Part B — segments, legal paths, aggregate blast radius, engines, worktree overlap

Read-only, ~17–25 tool calls. Items marked UNVERIFIED were not confirmed in source.

### B1. Segments touched (`docs/architecture/CODEBASE_SEGMENTS.md`)
- **Seg 9 Core Expense Management** (line 223) — primary: TLC, `ExpenseDao`, Expense entity, `effectiveAmount`; new
  `setSpendingExclusionDbOnly`, `spendingExclusionReason`, `TransactionUpdateKind.SPENDING_INCLUSION`, canonical predicate.
- Seg 7 Recurring Expenses (172) — plan coordinator mirrors recurring pattern; `RecurringPlanProjectionService` possible
  sibling projection (spec 5.3); installment schedules forbidden in recurring tables.
- Seg 36 Bill Reminders (698) — reused for installment due reminders.
- Seg 13 Cash Flow Planning (318); Seg 1 Forecasting & Runway (61); Seg 2 Budget Management (82);
  Seg 8 Analytics & Insights (205); Seg 10 Dashboard Totals & Widgets (257).
- Seg 18 Export & Backup (413); Seg 12 Startup & Background Runtime (283); Seg 16 Currency & Exchange (353).
- Seg 35 Savings Optimization & Health (683), Seg 23 Savings Prompts (497) — indirect.
- Seg 25 Shared Expense Budget Offset (526), Seg 24 Shared Expense Groups (506) — shared-expense edge case.
- Seg 30 Dependency Injection (597), Seg 39 Static Guardrails (727).
- Owning segment for Add-expense / Transactions UI: UNVERIFIED.

### B2. LEGAL_PATHS.md updates (`docs/architecture/LEGAL_PATHS.md`)
- **ADD** "Installment Plan Mutations" (after "Recurring Rule Mutations" line 292 or near "Recurring Plan Projection"
  line 1270). Coordinator ops: `declareOnExpense`, `declareWithNewExpense`, `createStandalone`, `confirmEntry`,
  `linkExpense`, `unlinkExpense`, skip, adjust, edit, extend, shorten, stop, refund, payoff, delete, `recordDueEntries`,
  `onExpenseChanged`, `onExpenseDeleted`, `reconcile`. Forbidden (spec 5.2): plan DAO writes outside coordinator; any
  `ExpenseDao` call from plan code; plan code calling `deleteExpense`; schedules in `recurring_occurrences` /
  `manual_recurring_expenses` / `planned_expenses`; setting `spendingExclusionReason` outside TLC; predicate in dedupe
  BLOCKING/RESOLVER families; plan repositories reachable from cloud payload assembly.
- **UPDATE** "Expense Mutations" (line 21; TLC refs 26, 53, 65): add `setSpendingExclusionDbOnly` (UPDATED event,
  `SPENDING_INCLUSION`), callable only by plan coordinator inside its transaction; `insertAtomic()` remains sole insert.
  None of `setSpendingExclusion*`, `spendingExclusionReason`, `SPENDING_INCLUSION`, `InstallmentPlanLifecycleCoordinator`
  exist in `app/src/main` today — all new.
- **UPDATE** "Recurring Plan Projection" (1270) if a sibling projection is chosen; "Workers / Background Jobs" (483);
  "Backup / Restore" (417); "Privacy / Cloud AI" (375); "Diagnostics" (556); "Accounting Export" (459, only if spec 11.4
  touches it — UNVERIFIED); "Lifecycle Events" (584) for `TransactionSideEffectPlanner.planUpdated/planDeleted` →
  `onExpenseChanged/onExpenseDeleted`.

### B3. Spending-aggregate blast radius (`app/src/main`)
- `SUM(` in `data/database/dao/*.kt`: **63 in 10 files** — `ExpenseDao.kt` 44, `GroupExpenseDao.kt` 2,
  `BudgetAdjustmentDao.kt` 3, `InvestmentDao.kt` 3, `MileageTrackingDao.kt` 3, `ReceiptItemCategorizationDao.kt` 1,
  `SavingsSweepPlanDao.kt` 1, `SplitItemAssignmentDao.kt` 2, `UserCorrectionDao.kt` 2, `WarrantyDao.kt` 2.
- Real blast surface: `ExpenseDao` (sums ~276, 1266–1293, 1492–2521). `isNotMine|myShareAmount` appears 97× in
  `ExpenseDao` (4× in `WarrantyDao`) — SQL duplicates effective-amount logic inline. Canonical predicate must reach all or
  most of the 44 `ExpenseDao` sums, with a SQL-vs-Kotlin parity test.
- Probably unchanged: Investment, Mileage, UserCorrection, Warranty, SavingsSweepPlan, ReceiptItemCategorization.
  Per-query check needed: GroupExpense, BudgetAdjustment, SplitItemAssignment (UNVERIFIED).
- `effectiveAmount` in Kotlin: **229 occurrences in 70 files**. Defined `data/database/entity/Expense.kt:163`
  (`isNotMine -> 0.0`); `normalizedEffectiveAmount` at :176 also needs the exclusion. `domain/model/ExpenseSnapshot.kt`
  own field: UNVERIFIED.
- Heaviest: AnalyticsViewModel 18, ExecuteFinancialQueryUseCase 16, AnomalyDetector 11, SpendingMapViewModel 9,
  AnalyticsCurrencyNormalizer 9, AdvancedAnalyticsDashboard 9, ForecastInputAssembler 8, BusinessExpenseReportGenerator 7,
  TravelDetectionEngine 6, MoneyNormalizationEngine 6.
- Others: MultiCurrencyRepository 5, ExpenseRepository 5, BudgetRepository 1, AnalyticsRepository 1;
  FinancialHealthScoreV2 5, SmartSavingsEngine 1, AutomatedSavingsRuleEngine 4, MonthlySavingsSweepUseCase 1;
  BudgetForecastingEngine 1, FinancialStressForecastEngine 2, SynthesisEngine 2, RecurringExpenseEngine 2;
  BackupVerifier 2, ExportOptionsViewModel 4, ExpenseExportMapper 2, ExportTransaction 1, AccountantReportPdfExporter 4,
  JsonExpenseImporter 3, CsvExpenseImporter 2; DashboardWidgetUiMapper 1, ComputeDashboardWidgetsUseCase 3,
  DashboardContractsAdapter 1, DashboardNormalizedInput 1, DashboardPrimitives 2, DashboardExpenseMapper 2;
  TransactionsViewModel 1, TransactionsScreen 2, ReceiptScanViewModel 4, ReceiptMatchingViewModel 1;
  NaturalLanguageSearchEngine 3, NaturalLanguageExpenseQueryRepositoryImpl 1, NaturalLanguageSearchViewModel 1,
  NaturalLanguageExpenseQueryRepository 1; DomainTransactionFilter 2, TaxEstimator 1, LifestyleInflationDetector 3,
  RecurringIncomeTracker 3, AnomalyAlertOrchestrator 4; AppDatabase 1, ExpenseDao 1; TransactionLifecycleCoordinator
  (3 `SUM(` hits, separate count). `ImportContractErrorCodes.kt` 1 (likely string/comment).

### B4. Engines and readers
`ENGINE_INTERACTION_MAP.md` rows: CashFlowCalculator (71), FinancialStressForecastEngine (72), ForecastInputAssembler (69),
BillReminderWorker (35, 123–128, 211), SynthesisEngine (47, 219). No rows for RecurringPlanProjectionService,
SmartSavingsEngine, AutomatedSavingsRuleEngine, FinancialHealthScoreV2. Wiring below is per spec 5.3.

| Engine | v1 status per spec |
|---|---|
| `CashFlowCalculator` (:210, :521) | WIRED — future SCHEDULED entries as dated outflows via projection port |
| `ForecastInputAssembler` (:483) | WIRED — projected entries; installment-linked history excluded from pattern baselines (INV-29) |
| `FinancialStressForecastEngine` (:327) | WIRED — known bounded outflows |
| `RecurringPlanProjectionService` (:69) | WIRED as distinct item type or sibling projection (planner decides); RECURRING_RULE contract unchanged |
| `BillReminderWorker` / `BillReminderManager` | WIRED — existing settings, per-entry dedupe key `planUuid:seq:recordGeneration` |
| Budgets (BudgetRepository, BudgetVsActualEngine, BudgetForecastingEngine, BudgetAutopilotEngine) | WIRED implicitly via predicate; not-yet-due never inflate spent; Budget screen info line; "committed" hint out of v1 |
| Analytics (AnalyticsInputAssembler, AdvancedAnalyticsEngine, InsightsEngine, SpendingPaceCalculator) | WIRED implicitly via `effectiveAmount` + predicate (not enumerated in spec; UNVERIFIED) |
| Savings engines (SmartSavings, AutomatedSavingsRule, MonthlySavingsSweep) | Implicitly affected via `effectiveAmount`; spec Q13 (UNVERIFIED by mapper) |
| `SynthesisEngine` (:873) | NOT wired in v1 unless planner proves no cloud-payload path; implicitly affected by predicate |
| `FinancialHealthScoreV2` Bill Reliability | EXPLICITLY UNWIRED; query contract kept; `effectiveAmount` hits still change |

Other engine-map rows touched: `TransactionSideEffectDispatcher` (HIGH, new hook); `WorkerExecutionGuard` (HIGH),
`WorkerRegistry`; `MoneyAggregate/Builder`, `TotalsAggregationEngine`, `MoneyNormalizationEngine` (HIGH);
`PrivacyGate` / `CloudPayloadPolicy` (HIGH); `CurrencyConverter` (CRITICAL); `TimeProvider` (CRITICAL).

### B5. Overlap with the Release-1.0 worktree (`Cost-agregator-release`, branch `release-1.0-wt`)
- **Certain / very likely conflicts:** `TransactionsScreen.kt` (row overflow "Pay in installments…", chips, filter, declare
  sheet), `TransactionsViewModel.kt` (filter, chip data, link lookup), `AddExpenseViewModel.kt` (installments switch;
  `AddExpenseSheet.kt` not in overlap list), `BudgetScreen.kt` ("Includes €X in installments"; also BudgetViewModel /
  BudgetRepository, not in overlap list).
- **Probable / conditional:** `ManualRecurringExpenseScreen.kt` (pattern reference only; header chip likely in
  `RecurringExpensesScreen`) — low; `ReceiptScanViewModel.kt` (4 `effectiveAmount` hits; consumer risk only).
- **Must not change (spec line 647):** `AmountUtils.kt`, `AmountInputSanitizer.kt`.
- **No evidence of touch:** `BankStatementParser.kt`, `SharedExpenseGroupsScreen.kt`, `ReviewScreen.kt`,
  `SavingsGoalsScreen.kt`; `OwnershipValidator.kt` possibly if ownership preconditions gate declaration (spec 7.0,
  UNVERIFIED).
- **Recommendation:** sequence feature implementation after the Release-1.0 work lands, or keep feature edits in those
  files as small additive hooks.

### Part B other facts
Verified existing: `Expense.effectiveAmount` / `normalizedEffectiveAmount` (`Expense.kt:163/176`),
`TransactionLifecycleCoordinator.createExpenseDbOnlyV2` (:890), `updateOwnershipDbOnlyV2` (:2099), `TransactionUpdateKind`
enum (`TransactionUpdateKind.kt:3`). Unverified: insertAtomic unique/dedupe interactions, `STRICT_EXTERNAL_ID` name,
spec line refs such as `CashFlowCalculator:210`.
