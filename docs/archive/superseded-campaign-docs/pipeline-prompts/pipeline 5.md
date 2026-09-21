Created the **Pipeline 5 master prompt pack** using the same directive structure.

<pipeline5-master-prompts.md>
# Pipeline 5 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P5 — Currency / Dashboard / Analytics**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P5 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_5_CONSOLIDATED_ISSUES.md
- P5 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_5_IMPLEMENTATION_PLAN.md
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Universal tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/UNIVERSAL_ISSUE_TRACKER.md
- Architecture folder: https://github.com/panospao7/Cost-agregator/tree/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture
- Codebase segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/CODEBASE_SEGMENTS.md
- Legal paths: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/LEGAL_PATHS.md

Important context:
- P5 is **Currency / Dashboard / Analytics**.
- Core architecture segments involved:
  - Segment 1 — Forecasting & Runway
  - Segment 2 — Budget Management
  - Segment 8 — Analytics & Insights
  - Segment 9 — Core Expense Management
  - Segment 10 — Dashboard Totals & Widgets
  - Segment 13 — Cash Flow Planning
  - Segment 16 — Currency & Exchange
  - Segment 18 — Export & Backup
  - Segment 29 — Debug & Diagnostics
  - Segment 30 — Dependency Injection
  - Segment 33 — Configuration / Performance / Accessibility
- The P5 issue docs and implementation plan may be stale or internally inconsistent. Treat them as context, but **code at the target SHA is the source of truth**.
- Some P5 docs mention files like `DashboardSynthesisEngine.kt`, `AnalyticsComputeEngine.kt`, or `TrendBuilder.kt`. At this target SHA, verify actual paths with `rg`; if the named file does not exist, map the issue to the current implementation or report tracker/code drift.

---

## Prompt A — P5 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin architecture, financial-data-integrity, currency-normalization, and pipeline-debug agent.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P5 — Currency / Dashboard / Analytics

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 5 end-to-end:

### Currency / exchange-rate scope
- home currency resolution,
- exchange-rate lookup,
- latest-rate vs transaction-date rate basis,
- stale-rate detection,
- missing-rate behavior,
- conversion failure propagation,
- MoneyAggregate / MoneyAggregateResult usage,
- partial result warnings,
- hardcoded currency defaults,
- currency settings and exchange-rate persistence if touched.

### Dashboard scope
- dashboard totals,
- current-month and previous-month comparisons,
- daily/weekly/monthly drilldowns,
- dashboard widgets,
- runway / financial runway cards,
- spending pace / projections,
- category and merchant totals shown on dashboard,
- budget dashboard adapter warnings,
- dashboard UI quality indicators,
- dashboard data provider / contracts adapter / normalized input path.

### Analytics scope
- AnalyticsInputAssembler,
- NormalizedAnalyticsInput,
- AdvancedAnalyticsEngine,
- TotalsAggregationEngine,
- category analytics,
- merchant analytics,
- spending pattern analytics,
- statistical insights,
- average calculations,
- trend / bucket generation,
- date window boundaries,
- time-zone / DST behavior,
- transaction type filtering,
- not-mine / shared-expense filtering,
- data quality warnings,
- deprecated self-fetching overloads.

### Cross-pipeline dependencies
- Expense data from P1/P2/P3/P10/P11/P12 eventually enters dashboard/analytics.
- Receipt-created and notification-created expenses must be visible through the same normalized read path.
- Recurring/planned expenses from P4 can affect dashboard/forecast/runway.
- Budget limit/spend comparisons overlap with P6.
- Export/import/restore can affect currency/rate tables and dashboard totals.

Read first:
- `docs/analyses and debug master/PIPELINE_5_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_5_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs if referenced by P5.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that spirit:
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

For P5 specifically, pay special attention to:
- `LEGAL_PATHS.md` Money / Currency section.
- `LEGAL_PATHS.md` Analytics section.
- Dashboard/totals ownership in `CODEBASE_SEGMENTS.md`.
- Currency / exchange segment ownership.
- DAO map for `ExpenseDao`, `ExchangeRateDao`, `BudgetDao`, `CategoryDao`.
- Hilt map for currency, dashboard, analytics, time, dispatchers, database, diagnostics.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, and tests to build the real inventory.

Start with these likely files:

### Analytics domain
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DataQualityReport.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/BudgetVsActualEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceProjection.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TransferDirectionAnalytics.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculator.kt`

If the tracker mentions `AnalyticsComputeEngine.kt` or `TrendBuilder.kt`, search for the current equivalent:
- `rg -n "computeFromNormalized|buildTrend|TrendBuilder|AnalyticsComputeEngine|averagePerDay|averagePerTransaction"`

If no such file exists, report tracker/code drift and identify the real implementation.

### Money / currency domain
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
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/CurrencyCode.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyRatesRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencySettingsRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/HomeCurrencyResolution.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/UserCurrencyProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/currency/ExchangeRateContracts.kt`

### Dashboard / forecasting / runway
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardNormalizedInput.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardRepositoryContracts.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardWidgetRegistry.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecast/NormalizedForecastInput.kt`
- any files containing:
  - `FinancialRunway`
  - `previousMonthAggregate`
  - `projectedTotal`
  - `DashboardSynthesisEngine`
  - `BudgetStatusSnapshot`
  - `CurrencyQualityUi`
  - `isPartial`
  - `warningMessage`

If `DashboardSynthesisEngine.kt` does not exist, map those issues to the actual class, likely dashboard use cases and/or `SynthesisEngine.kt`, then report the doc/code drift.

### Repositories
- `app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt`
-

:warning: The provider stream ended early, so this response may be incomplete.