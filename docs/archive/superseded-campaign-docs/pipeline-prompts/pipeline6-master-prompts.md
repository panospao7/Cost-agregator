# Pipeline 6 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P6 — Budget / Forecasting / Cashflow**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P6 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_6_CONSOLIDATED_ISSUES.md
- P6 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_6_IMPLEMENTATION_PLAN.md
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Universal tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/UNIVERSAL_ISSUE_TRACKER.md
- Architecture folder: https://github.com/panospao7/Cost-agregator/tree/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture

Important context:
- P6 is **Budget / Forecasting / Cashflow**.
- P6 issue doc says old issues include 9 fixed and 5 TODO-only items, plus new open items. Validate this against code.
- P6 implementation plan says the pipeline was RED after universal fixes, with remaining budget/cashflow/stress-model gaps.
- Docs and trackers may be stale or internally inconsistent. **Code at the target SHA is source of truth.**
- The tracker may refer to `StressForecastEngine.kt`; current source inventory appears to include `FinancialStressForecastEngine.kt`. Search both and report tracker/code drift.

---

## Prompt A — P6 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin architecture, financial-data-integrity, forecasting, budget, cashflow, Room, coroutine, and pipeline-debug agent.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P6 — Budget / Forecasting / Cashflow

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not exactly at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 6 end-to-end:

### Budget scope
- budget CRUD,
- budget period calculation,
- category/month/week/day budget boundaries,
- budget limit storage and conversion,
- period-specific vs latest exchange-rate usage,
- budget monitor checks,
- budget alerts,
- adjusted spend vs gross spend,
- budget rollover,
- budget forecast refresh,
- budget forecast persistence,
- budget forecast conflict handling,
- budget deletion/deactivation/cascade behavior,
- shared budget handling if present,
- diagnostics for budget checks.

### Forecasting scope
- `ForecastInputAssembler`,
- `NormalizedForecastInput`,
- planned expense input,
- recurring occurrence input,
- recurring pattern merge,
- historical spending distribution,
- data quality assessment,
- confidence penalties,
- Monte Carlo simulation,
- stress forecast,
- stress forecast snapshots,
- account-balance provider,
- net-cashflow balance provider,
- data-quality warnings,
- seasonal factor logic,
- stale-pattern handling,
- cancellation propagation.

### Cashflow scope
- cashflow calendar,
- cashflow calculator,
- predicted recurring/planned entries,
- income vs expense direction,
- multi-currency summation,
- deduping predicted entries,
- daily/weekly/monthly bucket boundaries,
- DST and time-zone safety,
- locale-independent week calculation,
- displayed output quality flags.

### Cross-pipeline dependencies
- Expense data from P1/P2/P3/P10/P11/P12 feeds budgets/forecast/cashflow.
- Receipt-created and notification-created expenses must flow through legal transaction lifecycle before affecting P6.
- P4 recurring/planned expenses feed forecast/cashflow.
- P5 currency/money normalization is critical for P6.
- P7 backup/restore must block P6 writes and preserve budget/forecast/cashflow tables.
- P8 privacy/diagnostics must redact user data.
- P12 import/export can create/update data affecting P6.

Read first:
- `docs/analyses and debug master/PIPELINE_6_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_6_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs if referenced by P6.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that methodology:
1. Scout files and flows.
2. Plan review coverage.
3. Inspect code deeply.
4. Inspect tests.
5. Compare with architecture.
6. Debug mismatches.
7. Produce evidence-backed findings.

Shared contracts must be validated before pipeline-local conclusions.

## 3. Architecture docs to read

Always read:
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`

Conditional docs to read:
- UI pipeline:
  - `COMPREHENSIVE_UI_MAP.md`
  - `VIEWMODEL_INJECTION_MAP.md`
  - `route-viewmodel-map.md`
- Privacy/diagnostics:
  - `PRIVACY_UI_ARCHITECTURE.md`
  - `SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/import/export:
  - `DATABASE_BASELINE_POLICY.md`
  - `DB_WRITE_OWNERSHIP.md`
  - `backup-restore-barrier-contract.md`
  - `expense-mutation-inventory.md`

For P6 specifically, pay special attention to:
- Budget / forecasting / cashflow ownership in `CODEBASE_SEGMENTS.md`.
- Money / currency legal paths in `LEGAL_PATHS.md`.
- Dashboard/totals interactions if P6 output feeds dashboard widgets.
- DB write ownership docs for budget, budget forecast, planned expense, stress snapshot, and diagnostics tables.
- Hilt bindings for budget/cashflow/currency/time/diagnostics/database/dispatchers.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, and tests to build the real inventory.

Start with these likely files:

### Budget domain
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastResult.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetHistorySeriesBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationInputs.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt`

### Forecasting domain
- `app/src/main/java/com/yourname/expensetracker/domain/forecast/NormalizedForecastInput.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/AccountBalanceProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastDataQuality.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MergedRecurringPatternsProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloResult.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/NetCashflowBalanceProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/NormalizedForecastInput.kt`

If tracker docs mention `StressForecastEngine.kt`, search:
- `rg -n "class .*Stress|computeStressForecast|ACTIVE_OCCURRENCE_STATUSES|expandDetectedPatterns|estimateIncome|calculateSeasonalFactor"`

If the exact tracker file does not exist, map each issue to the current implementation and report tracker/code drift.

### Cashflow domain
- `app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt`
- any file discovered by:
  - `rg -n "CashFlow|cashflow|calendar|projected|predicted|netCashflow"`

### Money / currency dependencies
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateResult.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyBucket.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyBucketInput.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyNormalizationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/RateBasis.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/BucketDatePolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/StaleRatePolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/TransactionTypeFilter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/HomeCurrencyForMoneyMath.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyRatesRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencySettingsRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/HomeCurrencyResolution.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/UserCurrencyProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/ExchangeRateContracts.kt`

### Repositories
- `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/PlannedExpenseRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/CurrencyDataRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/CurrencyRatesRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
- any repository discovered by P6 call graph.

### DAOs
- `BudgetAdjustmentDao.kt`
- `BudgetDao.kt`
- `BudgetForecastDao.kt`
- `CategoryDao.kt`
- `ExpenseDao.kt`
- `ExchangeRateDao.kt`
- `PlannedExpenseDao.kt`
- `ManualRecurringExpenseDao.kt`
- `RecurringExpenseDao.kt`
- `RecurringOccurrenceDao.kt`
- `StressForecastSnapshotDao.kt`
- `PipelineDiagnosticEventDao.kt`
- `BackgroundJobRunDao.kt` if worker/background execution is involved.

### Room entities / schema touchpoints
- `Budget.kt`
- `BudgetAdjustmentRecommendation.kt`
- `BudgetForecast.kt`
- `Category.kt`
- `Expense.kt`
- `ExchangeRate.kt`
- `PlannedExpense.kt`
- `ManualRecurringExpense.kt`
- `RecurringOccurrence.kt`
- `StressForecastSnapshot.kt`
- `PipelineDiagnosticEvent.kt`
- `AppDatabase.kt`
- `DatabaseMigrations.kt`
- exported Room schemas if present.

### Worker/background infrastructure
If any P6 work is scheduled in background, include:
- `WorkerExecutionGuard.kt`
- `WorkerGuardRequest.kt`
- `WorkerRunLogger.kt`
- `WorkerRunContext.kt`
- `WorkerSpec.kt`
- `WorkerSpecScheduler.kt`
- `WorkerRegistry.kt`
- `RetryableWorkerException.kt`
- relevant WorkManager workers and DI bindings.

If P6 has no workers, explicitly say so with search evidence.

### Hilt modules
Review all modules that provide:
- budget repository/engines,
- forecasting/stress engines,
- cashflow calculator,
- currency/money services,
- DAOs/database,
- diagnostics,
- time providers,
- dispatchers,
- worker infrastructure.

Likely seeds:
- `CashFlowModule.kt`
- `CurrencyModule.kt`
- `DaoModule.kt`
- `DashboardContractsModule.kt`
- `DatabaseModule.kt`
- `DiagnosticsModule.kt`
- `DispatchersModule.kt`
- `TimeModule.kt`
- `WorkerModule.kt`

### UI
If budget/forecast/cashflow UI exists, include:
- screens,
- ViewModels,
- route/navigation entries,
- state models,
- mappers,
- components,
- instrumentation/UI tests.

Search:
- `rg -n "Budget|Forecast|CashFlow|Stress|Runway|Pace|Rollover" app/src/main/java/com/yourname/expensetracker/ui`
- `rg -n "Budget|Forecast|CashFlow|Stress" app/src/main/java/com/yourname/expensetracker`

If no UI is reached, explicitly say “UI not reached” with evidence.

### Tests
Search the whole repo:
- `rg -n "Budget|Forecast|CashFlow|Stress|Rollover|MoneyNormalization|Currency|PlannedExpense|RecurringOccurrence" app/src/test app/src/androidTest`
- tests matching:
  - `*Budget*`
  - `*Forecast*`
  - `*CashFlow*`
  - `*Stress*`
  - `*Synthesis*`
  - `*Currency*`
  - `*Money*`
  - `*Planned*`
  - `*Recurring*`
  - `*Restore*`
  - `*Barrier*`

Also inventory:
- diagnostics/event writers,
- migrations/schema touchpoints,
- import/export/backup serializers if budgets/forecasts/stress snapshots are included,
- dashboard contracts if P6 output reaches P5/dashboard.

## 5. Code-reading rules

Mandatory:
- Do not trust docs over code.
- If docs and code disagree, report the mismatch.
- Do not review only filenames; open implementation and tests.
- Trace actual runtime flow, not package structure.
- Search direct and indirect callers.
- Include cross-pipeline dependencies.
- Mark uncertainty clearly.
- Verify Hilt-injected runtime path, not just constructor signatures.
- Check whether public methods bypass legal write paths.
- Check whether tests actually assert invariants.
- If tracker says fixed/open/TODO, validate against code at this SHA.

Use searches like:
- `rg -n "Budget|budget|Forecast|forecast|CashFlow|cashflow|Stress|stress|Rollover|rollover"`
- `rg -n "computeStressForecast|expandDetectedPatterns|ACTIVE_OCCURRENCE_STATUSES|estimateIncome|calculateSeasonalFactor|MIN_HISTORY_MONTHS"`
- `rg -n "percentUsed|adjustedSpend|pacePercentage|BudgetMonitor|BudgetForecastingEngine"`
- `rg -n "MoneyNormalizationEngine|MoneyAggregate|RateBasis|homeCurrency|ExchangeRate|currency"`
- `rg -n "PAID|PLANNED|SKIPPED|CANCELLED|OVERDUE|fulfilled|planned"`
- `rg -n "WEEK_OF_YEAR|Calendar|LocalDate|ZoneId|plusDays|TimeProvider|System.currentTimeMillis"`
- `rg -n "CancellationException|catch \\(e: Exception\\)|catch \\(t: Throwable\\)"`
- `rg -n "DatabaseWriteBarrier|writeBarrier|restore|maintenance|WorkerExecutionGuard"`
- `rg -n "insert\\(|update\\(|delete\\(" app/src/main/java/com/yourname/expensetracker/data/repository app/src/main/java/com/yourname/expensetracker/data/database/dao`

## 6. Universal contracts to verify

Audit these for P6:

1. Restore/write barrier:
   - every budget/forecast/planned/stress snapshot write checks `DatabaseWriteBarrier`,
   - no writes during restore/backup/maintenance modes,
   - background jobs are blocked/drained correctly.

2. Worker guard and run logging:
   - if any P6 worker exists, it uses `WorkerExecutionGuard`,
   - run start/success/skip/retry/failure is logged,
   - retry classification is correct,
   - cancellation is not swallowed.

3. Privacy/redaction/raw-storage policy:
   - diagnostics/events do not store raw PII,
   - category/merchant/user strings are redacted or minimal,
   - forecast/budget warnings do not leak sensitive transaction details.

4. Money/currency normalization:
   - all budget, planned, recurring, cashflow, forecast, and stress sums are normalized before aggregation,
   - rate basis is explicit,
   - partial conversion state and warnings propagate,
   - missing/stale rates do not silently produce false totals,
   - hardcoded currency defaults are justified or removed.

5. Transaction lifecycle ownership:
   - expense CUD flows update P6 reads through legal lifecycle/DAO read paths,
   - no direct expense writes create inconsistent budget totals,
   - not-mine/shared transaction filtering is respected if applicable.

6. Receipt lifecycle/link ownership:
   - receipt-created expenses reach P6 through the same transaction path,
   - no duplicate receipt/expense amount inflates budget/cashflow.

7. Recurring planned/actual reconciliation:
   - P4 planned/recurring data used by P6 excludes cancelled/skipped/paid/fulfilled as appropriate,
   - PAID occurrences are not active future outflows,
   - planned expenses are normalized and status-filtered.

8. Diagnostics/drop reasons/events:
   - budget monitor failures and forecast degradation are visible,
   - diagnostic writes are best-effort but cancellation-safe,
   - data-quality warnings are propagated to UI/dashboard if relevant.

9. Import/export schema/roundtrip:
   - budget, forecast, planned, exchange-rate, and stress snapshot data survives export/import/restore if in scope,
   - restore does not leave stale generated forecast rows or invalid FKs.

10. DAO conflict handling and timestamps:
   - budget forecast refresh handles unique constraints safely,
   - `IGNORE` insert results are checked,
   - `createdAt`/`updatedAt` are valid,
   - deletes/deactivations cascade intentionally,
   - period uniqueness and category constraints are correct.

## 7. P6-specific invariants to audit

### Budget CRUD / refresh
Check:
- all budget writes are barrier-guarded,
- budget create/update/delete/deactivate updates timestamps,
- budget forecast refresh is atomic,
- forecast rows use real `createdAt`, `updatedAt`, and home currency,
- unique index conflicts do not abort refresh incorrectly,
- deleting/deactivating a budget cannot fail due to dependent forecast rows,
- rollover loop is bounded and does not run O(N) queries forever,
- rollover respects partial currency conversion state and warnings,
- alerts use adjusted/net spend rather than gross percent if shared/not-mine offsets apply.

### Budget limits / currency
Check:
- budget limit comparison uses a coherent rate basis,
- issue `P6-P1-06`: budget limit uses latest rate vs period-specific rate,
- compare budget limit and spending in the same currency and date basis,
- partial/missing/stale conversions produce warning/partial result, not fake precision,
- finite amount checks exist.

### Forecast input assembly
Check:
- planned expenses are normalized before forecast,
- cancelled/skipped/fulfilled planned expenses are excluded,
- recurring occurrences included in forecast are only active future obligations,
- PAID occurrences are not active outflows,
- historical expenses use legal normalized read path,
- confidence penalty uses data quality,
- hidden writes do not occur in query-like methods.

### Cashflow calendar / calculator
Check:
- no raw summing of `effectiveAmount` across currencies,
- income recurring rules are positive/inflow, not expense/outflow,
- predicted recurring entries are deduped,
- planned and recurring duplicates do not double count,
- date buckets are DST-safe,
- weekly periods use ISO or explicit deterministic week logic, not locale-dependent `WEEK_OF_YEAR`,
- partial conversion warnings are surfaced.

### Stress forecast
Check:
- stress forecast is a true running account-balance forecast if claimed,
- otherwise report design gap `P6-P1-13`,
- initial balance/account balance provider is used if available,
- running balance = initial + income - expenses over horizon,
- PAID occurrences are excluded,
- stale detected patterns are excluded or degraded with warnings,
- detected pattern expansion uses half-open intervals `[start, end)` to avoid double-counting boundaries,
- risk thresholds are configurable or documented; hardcoded currency-specific thresholds are flagged,
- income estimate uses actual count/window, not hardcoded `3.0`,
- cancellation exceptions propagate.

### Budget monitor / diagnostics
Check:
- `computeAdjustedSpend` and diagnostic helpers rethrow `CancellationException`,
- failed diagnostics do not fail core budget check unless designed,
- `pacePercentage=0` is not shown when no baseline exists; use null/N/A/quality warning,
- diagnostics are redacted.

### UI / dashboard
If P6 output reaches UI:
- partial currency state is visible,
- warnings are not discarded,
- “no baseline” is not shown as 0% pace,
- loading/error states are consistent,
- budget/cashflow cards do not claim precision with missing rates.

## 8. Known P6 issue set to validate

Read P6 consolidated issue doc and implementation plan, then validate each against code.

Old issues to validate:
- `P6-P1-01`: budget forecast refresh unique index conflict.
- `P6-P1-02`: forecast rows persisted with `createdAt=0` and wrong currency.
- `P6-P1-03`: budget/forecast/planned writes lack restore guard.
- `P6-P1-04`: budget alerts use gross `percentUsed`.
- `P6-P1-05`: rollover ignores partial conversion state.
- `P6-P1-06`: budget limit conversion uses current/latest rate, not period-specific rate.
- `P6-P1-07`: forecast data quality ignored by synthesis.
- `P6-P1-08`: planned expenses not normalized before forecast.
- `P6-P1-09`: cancelled/skipped planned expenses enter forecast.
- `P6-P1-10`: recurring occurrence status lost before forecast.
- `P6-P1-11`: cash-flow calendar raw-sums multi-currency amounts.
- `P6-P1-12`: cash-flow output displays pre-dedup recurring predictions.
- `P6-P1-13`: stress forecast is not real account-balance forecast.
- `P6-P1-14`: stress counts PAID occurrences as active outflows.
- `P6-P1-15`: deleting budget can fail after forecasts exist.

New issues to validate:
- `NEW-P6-001`: `computeStressForecast` swallows `CancellationException`.
- `NEW-P6-002`: `BudgetMonitor` `writeAlertDiagnostic` swallows CE.
- `NEW-P6-003`: `BudgetMonitor` CHECK_FAILED diagnostic swallows CE.
- `NEW-P6-004`: unbounded rollover loop / O(N) queries for daily budgets.
- `NEW-P6-005`: `BudgetRepository` CRUD swallows CE.
- `NEW-P6-006`: `computeAdjustedSpend` swallows CE.
- `NEW-P6-007`: stress `expandDetectedPatterns` closed interval double-counts.
- `NEW-P6-008`: stale detected patterns silently skipped.
- `NEW-P6-009`: DST-unsafe day arithmetic in stress horizon.
- `NEW-P6-010`: hardcoded currency-specific risk thresholds.
- `NEW-P6-011`: `calculateSeasonalFactor` dead stub.
- `NEW-P6-012`: `MIN_HISTORY_MONTHS` unused.
- `NEW-P6-013`: `pacePercentage=0` misleading when no baseline.
- `NEW-P6-014`: `estimateIncome` divides by hardcoded `3.0`.
- `NEW-P6-015`: income recurring treated as expense in cashflow.
- `NEW-P6-016`: weekly period uses `WEEK_OF_YEAR` / locale-dependent logic.

Important:
- If docs say fixed but code still violates the invariant, mark as bug.
- If code is fixed but tracker says open, mark tracker drift.
- If issue is truly design/TODO, classify as design/TODO with required decision.

## 9. Review dimensions

Check:
- correctness,
- financial data integrity,
- currency normalization,
- data-quality propagation,
- atomicity/transactions,
- lifecycle bypasses,
- direct DAO writes,
- restore/export safety,
- privacy fail-closed behavior,
- raw PII storage/logging,
- cancellation handling,
- coroutine races,
- worker retry/idempotency if background work exists,
- dedupe/conflict behavior,
- state-machine transitions,
- timestamp/currency defaults,
- schema/migration compatibility,
- Hilt binding correctness,
- UI state consistency if relevant,
- diagnostics coverage,
- test coverage,
- performance risks,
- security/privacy risks.

## 10. Required output format

Produce this exact structure:

# Pipeline 6 Review — Budget / Forecasting / Cashflow

## 1. Pipeline summary
- What P6 does.
- Main data flow.
- Entry points and exits.
- Mermaid or text data-flow diagram.

## 2. File inventory
Create a table:
| Category | Files reviewed | Why relevant | Notes |

Include:
- entry points,
- services/engines/coordinators,
- repositories,
- DAOs,
- Room entities,
- workers if any,
- parsers/importers/exporters if relevant,
- Hilt modules,
- ViewModels/UI if reached,
- tests,
- diagnostics/event writers,
- migrations/schema touchpoints.

Also list:
- files intentionally skipped and why,
- files discovered but not fully reviewed and why.

## 3. Architecture comparison
- Does code follow `LEGAL_PATHS.md`?
- Does code follow budget/forecast/cashflow ownership docs?
- Any doc/code drift?
- Any tracker/code drift?
- Any stale TODO or misleading comment?

## 4. Runtime flow / call graph
Include:
- budget create/update/delete,
- budget monitor check/alert,
- forecast refresh,
- forecasting input assembly,
- cashflow calculation,
- stress forecast,
- recurring/planned input path,
- restore/write gating,
- diagnostics/events,
- UI/dashboard output if relevant.

## 5. Issue table
Use columns:
| ID | Severity P0/P1/P2/P3 | Status bug/partial/TODO/fixed/design | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |

Every finding must have concrete evidence:
- file path,
- method name,
- relevant condition,
- why it violates contract.

## 6. Universal contract audit
Subsections:
- restore barrier,
- privacy/redaction,
- lifecycle ownership,
- worker guard/run logging,
- money/currency normalization,
- diagnostics/events,
- import/export/backup if relevant,
- DAO conflict/timestamps.

For each, verdict:
- PASS,
- FAIL,
- PARTIAL,
- NOT APPLICABLE,
with evidence.

## 7. P6 issue reconciliation
Create table:
| Tracker issue | Tracker status | Code status at target SHA | Evidence | Final status | Notes |

Include all old and new P6 issues from `PIPELINE_6_CONSOLIDATED_ISSUES.md`.

## 8. Test coverage review
- Existing tests found.
- What each test proves.
- Missing tests.
- Weak tests that do not assert the important invariant.

## 9. Test plan
Include:
- unit tests,
- integration tests,
- regression tests,
- instrumentation/UI tests if needed,
- manual validation scenarios.

## 10. Optional deliverables
Include at least one:
- Mermaid/text data-flow diagram,
- call graph,
- dependency map,
- legal write path table,
- before/after fix plan,
- commit plan split by safe PRs.

## 11. Final verdict
- GREEN / YELLOW / RED.
- Highest-risk remaining issue.
- Whether P6 is production-safe.
- What must be fixed before GREEN.

## 11. Severity rubric

Use:
- P0: data loss, corruption, privacy leak, broken restore, duplicate money records, irreversible wrong write.
- P1: major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow.
- P2: edge-case bug, poor diagnostics, partial inconsistency, retry/idempotency weakness.
- P3: cleanup, docs drift, TODO, non-critical maintainability.

## 12. Completion criteria

The review is not complete until:
- P6 issue doc was read,
- master/universal trackers were read,
- architecture docs were checked,
- all relevant source files were inventoried,
- key callers/callees were traced,
- tests were found or missing tests were listed,
- cross-pipeline impacts were identified,
- every finding has evidence and a fix strategy,
- final verdict is justified.
```

---

## Prompt B — P6 Fix Implementation + Tests Prompt

Use this after Prompt A produces confirmed findings.

```text
You are a senior Android/Kotlin implementation agent specializing in financial correctness, Room transactions, currency normalization, coroutine safety, and test-driven fixes.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Commit baseline:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P6 — Budget / Forecasting / Cashflow

Mode:
Fix implementation + test writing + validation.
Only fix confirmed P6 issues.
Do not perform broad refactors.
Preserve architecture contracts and public behavior unless a bug requires change.

## 2. Required reading before editing

Read:
- `docs/analyses and debug master/PIPELINE_6_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_6_IMPLEMENTATION_PLAN.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`
- DB/restore docs if touching schema/restore/import/export.

Do not trust docs over code.
If tracker status differs from code, fix code only if code is actually wrong.
If only docs are stale, report docs drift instead of changing code.

## 3. Implementation constraints

Follow P6 legal paths:
- budget writes must go through repository/service path and be restore-barrier guarded,
- forecast rows must be written atomically and conflict-safe,
- cashflow and forecast aggregations must use normalized money where cross-currency input is possible,
- planned/recurring data must respect status filters,
- diagnostics must be redacted and cancellation-safe,
- background work, if any, must use worker guard/run logging,
- UI must surface partial/quality warnings if it consumes partial results.

General rules:
- Keep changes minimal and targeted.
- Add/update tests for every fixed issue.
- Do not introduce schema migration unless explicitly required and approved.
- Do not mask `CancellationException`.
- Do not use `System.currentTimeMillis()` where `TimeProvider` exists.
- Do not raw-sum amounts across currencies.
- Do not silently ignore missing/stale exchange rates.
- Do not silently convert design gaps into magic behavior; mark design decision needed.
- Do not change user-visible financial semantics without tests and notes.

## 4. Candidate P6 fix areas

Validate first, then fix if still broken.

### P6-PR1 — Critical correctness
Issues:
- `NEW-P6-004`: unbounded rollover loop / O(N) daily budget queries.
- `P6-P1-14`: stress counts PAID occurrences as active outflows.
- `P6-P1-11`: cashflow calendar raw-sums multi-currency amounts.
- relevant part of `P6-P1-08`: planned/recurring inputs not normalized on all paths.

Implementation intent:
1. Bound rollover loop:
   - add maximum iteration/window,
   - prefer batch query over per-period query,
   - log diagnostic when cap reached,
   - avoid infinite or user-data-dependent unbounded loops.
2. Exclude PAID from active stress outflows:
   - active statuses should be future obligations only, e.g. PLANNED/OVERDUE/DUE as code model supports,
   - ensure fulfilled/paid/skipped/cancelled statuses do not inflate outflows.
3. Normalize cashflow:
   - inject/use `MoneyNormalizationEngine` or existing money aggregate abstraction,
   - propagate `isPartial` and warnings,
   - keep bucket currency explicit.

Required tests:
- `rollover_loop_bounded_for_daily_budgets`
- `rollover_uses_batch_or_capped_query_count`
- `stress_excludes_paid_occurrences`
- `cashflow_normalizes_multi_currency_amounts`
- `cashflow_propagates_partial_conversion_warning`
- `planned_inputs_are_status_filtered_and_normalized`

### P6-PR2 — Stress engine and budget hardening
Issues:
- `NEW-P6-006`: `computeAdjustedSpend` swallows CE.
- `NEW-P6-007`: `expandDetectedPatterns` closed interval double-counts.
- `NEW-P6-008`: stale detected patterns silently skipped.
- `NEW-P6-010`: hardcoded currency-specific risk thresholds.
- `NEW-P6-011`: `calculateSeasonalFactor` dead stub.

Implementation intent:
1. Rethrow `CancellationException` in every catch block.
2. Use half-open intervals `[start, end)` for pattern expansion.
3. Handle stale patterns explicitly:
   - exclude or degrade confidence,
   - produce warning/diagnostic,
   - do not silently include/exclude without signal.
4. Extract risk thresholds:
   - constants/config object at minimum,
   - settings/AppConfig provider if architecture already supports it,
   - avoid currency magic hidden in code.
5. Implement or remove seasonal factor stub:
   - if no callers, remove dead code/test no use,
   - if called, implement real logic and tests.

Required tests:
- `adjusted_spend_rethrows_cancellation`
- `pattern_expansion_no_double_count_at_boundary`
- `stale_patterns_excluded_or_warned`
- `risk_thresholds_are_configurable_or_explicit`
- `seasonal_factor_not_dead_stub_or_no_callers`

### P6-PR3 — Design-level issues
Issues:
- `P6-P1-06`: budget limit uses latest/current rate instead of period-specific rate.
- `P6-P1-13`: stress forecast is not real account-balance forecast.
- possible remainder of `P6-P1-08`: all planned/forecast paths need consistent normalization.

Implementation intent:
1. Budget limit rate basis:
   - decide period-start, period-end, transaction-date, or budget-created-date rate basis,
   - document the basis,
   - compare spend and limit in same home currency/date basis,
   - propagate partial/missing-rate state.
2. Stress balance model:
   - use `AccountBalanceProvider` if available,
   - forecast running balance = initial balance + income - expenses,
   - preserve net-cashflow model only if clearly named as net cashflow, not account balance.
3. If schema changes are required, stop and produce migration plan before implementation.

Required tests:
- `budget_limit_uses_period_specific_rate_basis`
- `budget_limit_partial_conversion_propagates_warning`
- `stress_forecast_tracks_running_balance`
- `stress_forecast_uses_initial_balance_provider`
- `stress_forecast_not_labeled_balance_if_only_net_cashflow`

### P6-PR4 — Cleanup and minor correctness
Issues:
- `NEW-P6-012`: unused `MIN_HISTORY_MONTHS`.
- `NEW-P6-013`: `pacePercentage=0` misleading when no baseline.
- `NEW-P6-014`: `estimateIncome` divides by hardcoded `3.0`.
- `NEW-P6-015`: income recurring treated as expense in cashflow.
- `NEW-P6-016`: weekly period uses locale-dependent `WEEK_OF_YEAR`.

Implementation intent:
1. Remove unused constants or wire them into validation.
2. Represent no-baseline pace as null/N/A/quality warning, not 0%.
3. Replace hardcoded income divisor with actual observed month/window count.
4. Treat income recurring rules as inflows.
5. Use ISO or explicit deterministic week logic.

Required tests:
- `pace_percentage_null_when_no_baseline`
- `income_estimate_uses_actual_history_month_count`
- `income_recurring_is_positive_in_cashflow`
- `week_number_consistent_across_locales`
- `unused_history_constant_removed_or_used`

## 5. Universal checks before/after every fix

Verify:
- restore/write barrier on all writes,
- no direct DAO bypass,
- no CE swallowing,
- money/currency normalization on aggregates,
- partial conversion warnings not dropped,
- diagnostics are redacted,
- timestamps are set,
- DAO conflict results checked,
- no new schema drift,
- UI state remains consistent if touched.

## 6. Required validation commands

Run at minimum:
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Budget*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Forecast*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CashFlow*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Stress*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Synthesis*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Currency*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Money*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Planned*" --stacktrace
./gradlew :app:check --stacktrace
```

If a command cannot run, report:
- exact command,
- failure reason,
- whether failure is related to P6,
- what still needs manual validation.

## 7. Required output

Produce:

## Summary
- Issues fixed.
- Issues confirmed already fixed.
- Issues deferred/design-only.
- Issues not touched and why.

## Changed files
| File | Change | Issue IDs | Tests |

## Issue reconciliation
| ID | Before | After | Evidence | Tests |

## Test results
- Commands run.
- Pass/fail.
- Relevant logs.

## Remaining risks
- Highest risk.
- Cross-pipeline impacts.
- Any migration/design follow-up.

## Commit plan
Split into safe PRs:
1. critical budget/cashflow/stress correctness,
2. stress/budget hardening,
3. design-level budget-rate/stress-balance model,
4. cleanup/UI/docs/tracker sync.
```

---

## Prompt C — P6 Final Validation / Fixed-Claims Audit Prompt

Use this after fixes land.

```text
You are a senior validation/debugger agent.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Target:
Use the current working branch/commit provided by the user.
Baseline context commit:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P6 — Budget / Forecasting / Cashflow

Mode:
Validation of already-fixed claims.
Do not implement new fixes.
Verify whether P6 can be marked GREEN/YELLOW/RED.

## 2. Required reading

Read:
- P6 consolidated issue doc,
- P6 implementation plan,
- master tracker,
- universal tracker,
- all architecture docs listed in Prompt A,
- changed source files,
- changed tests,
- migration/schema files if touched,
- changed Hilt modules,
- changed UI files if touched.

Do not trust PR descriptions or comments.
Validate against code and tests.

## 3. Claims to validate

Validate:
- all P6 old issues marked fixed,
- all P6 new issues marked fixed,
- all universal fixes that affect P6,
- all newly added tests,
- no new bypasses introduced,
- no new schema/restore risks introduced.

Specific P6 claims:
- budget CRUD and forecast writes are restore-barrier guarded,
- budget forecast refresh is unique-conflict safe,
- budget forecast rows have valid timestamps and currency,
- budget delete/deactivate cannot fail due to forecast rows,
- budget alerts use adjusted spend where required,
- rollover is bounded and efficient,
- budget limit conversion uses intended rate basis,
- rollover partial currency state propagates,
- planned expenses are normalized and status-filtered,
- cancelled/skipped/fulfilled planned items do not enter forecast,
- recurring status is preserved before forecast,
- PAID occurrences are excluded from active stress outflows,
- cashflow does not raw-sum cross-currency amounts,
- cashflow dedupes predicted recurring/planned entries,
- income recurring is positive/inflow,
- stress forecast is correctly named/modelled,
- stress forecast uses initial account balance if claiming balance forecast,
- stale patterns produce warnings/degraded quality,
- pattern expansion does not double-count boundaries,
- hardcoded risk thresholds are removed/configurable/explicit,
- no `CancellationException` is swallowed,
- day/week arithmetic is DST/locale safe,
- UI shows partial/no-baseline quality correctly if touched.

## 4. Required validation steps

1. Build exact file inventory.
2. Trace runtime flows.
3. Compare code to `LEGAL_PATHS.md`.
4. Run targeted tests.
5. Review test assertions for real coverage.
6. Check direct DAO writes.
7. Check restore/write barrier.
8. Check money/currency normalization.
9. Check diagnostics/privacy.
10. Check migrations/schema if touched.
11. Check UI/dashboard output if touched.
12. Check cross-pipeline impacts with P4 recurring/planned and P5 currency/dashboard.

## 5. Required output

Produce:

# P6 Fixed-Claims Validation

## 1. Verdict
GREEN / YELLOW / RED

## 2. Claims table
| Claim | Source doc/PR | Validated? | Evidence | Remaining risk |

## 3. Regression search
| Area | Search/check performed | Result |

Include at least:
- direct DAO writes,
- CE swallowing,
- raw cross-currency sums,
- PAID occurrence use,
- `WEEK_OF_YEAR`,
- stale pattern handling,
- barrier checks,
- Hilt bindings.

## 4. Test validation
| Test | What it proves | Weakness/gap |

## 5. Contract audit
- restore barrier,
- worker guard if applicable,
- lifecycle ownership,
- money/currency,
- diagnostics/privacy,
- DAO conflicts/timestamps,
- UI quality state if applicable.

## 6. Remaining issues
| ID | Severity | Status | Evidence | Required next action |

## 7. Production safety
- Is P6 production-safe?
- Highest-risk issue.
- Required fix before GREEN.
```