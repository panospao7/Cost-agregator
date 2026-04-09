# PLAN A.5 — Time Boundary / Calendar Arithmetic Inconsistencies

## 1. Objective & Blast Radius
- **The Core Issue:** Period math is currently inconsistent across the app: some paths still use locale-sensitive week starts, fixed `+30 days` month math, raw millisecond day indexing, inclusive `23:59:59` endpoints, or time windows captured once and never refreshed after rollover. This causes the same logical day/week/month to bucket data differently, drift across DST, drop boundary transactions, or go stale in long-lived flows.
- **Blast Radius:**
  - **Domain calculators / engines:** `FinancialHealthCalculator.kt`, `BudgetCalculator.kt`, `HistoricalSpendingDistribution.kt`, `BillReminderManager.kt`, `RecurrenceCalculator.kt`, `RecurringExpenseEngine.kt`, `AdvancedAnalyticsDashboard.kt`, `SpendingPaceCalculator.kt`
  - **Shared period utility / reactive clocking:** `TimePeriodUtils.kt`, plus a small rollover-aware ticker utility if no equivalent already exists
  - **Data-layer current-period flows:** `DashboardContractsAdapter.kt`, `BudgetRepository.kt`, and downstream consumers such as `DashboardDataProvider`, dashboard widgets, budget monitor flows, and budget UI collectors
  - **UI explicit date-range construction:** `TransactionFilterSheet.kt`, plus the minimum supporting presentation call site(s) required to pass a reference time or explicit clear state
  - **Audit-only registry-listed file:** `LocationBackfillWorker.kt` (re-read for A.5-specific time-window logic; if none exists, leave it untouched)
- **Assumptions / Unknowns:**
  - No shared rollover-trigger utility was found during the initial audit; a small new helper may be needed rather than duplicating repository-local ticker loops.
  - `TransactionsViewModel` currently uses rolling windows for the `MONTH`/`QUARTER`/`YEAR` tabs. A.5 must not silently relabel or redefine those product semantics unless a listed time-boundary bug requires it.
  - `BudgetRepository`’s hidden 2000-row cap is a separate batch-32 issue. Do not fold pagination changes into A.5 unless boundary correctness is impossible without a tiny shim.
  - `LocationBackfillWorker.kt` does not currently show obvious A.5 period math; treat it as audit-only unless a direct boundary bug is found after re-read.
  - `RecurrenceFrequency` is clearly imported and used, but its source file path was not located in the initial quick glob. Read the actual enum declaration before changing switch coverage or signatures in recurrence files.

## 2. The Single Source of Truth (The Standard)
- **Canonical rule:** All A.5 target files must treat periods as half-open intervals: **`[startInclusive, endExclusive)`**.
- **Canonical owner:** `TimePeriodUtils.kt` must own all shared calendar boundary math for day/week/month/quarter/year calculations.
- **Required contract:**
  1. **Week start:** Monday at `00:00:00.000`, locale-independent.
  2. **Period end:** start of the next day/week/month/year, exclusive. No local `23:59:59` / `23:59:59.999` boundary builders in A.5 target code.
  3. **Calendar advancement:** use calendar-aware `addDays` / `addMonths` / `addYears` (or `java.time` seeded from the explicit timestamp), never `30 * DAY_IN_MILLIS` / `90 * DAY_IN_MILLIS` / similar for logical periods.
  4. **Day indexing / bucketing:** use `TimePeriodUtils.getStartOfDay(...)`, `getStartOfWeek(...)`, `daysBetween(...)`, or `java.time.LocalDate`; never derive logical day/week buckets from raw millisecond division.
  5. **Reactive “current period” flows:** any long-lived flow whose result depends on “now” must be driven by a rollover-aware trigger and `flatMapLatest`, not by capturing a range once at subscription time.
  6. **Compatibility:** keep existing public APIs source-compatible where possible. If clearer explicit helpers are introduced, preserve current entry points as delegating wrappers until callers are migrated.
- **Preferred shared utility split:**
  - Keep pure calendar math in `TimePeriodUtils.kt`.
  - If no existing rollover helper exists, create a tiny adjacent utility (for example `TimeBoundaryTicker.kt`) for boundary-driven invalidation only. Do not mix repository logic into that helper.
- **Out of scope for this epic:**
  - Room/entity/schema changes
  - A full timezone/localization redesign
  - Transfer-income semantics, `effectiveAmount` standardization, merchant normalization, hidden pagination fixes, or broad recurrence-business-rule changes unrelated to calendar arithmetic

## 3. File-by-File Execution Checklist

### Execution order / safe batches
1. **Batch 1 — Canonical time contract + utility coverage**
   - **Scope:** `TimePeriodUtils.kt`, optional `TimeBoundaryTicker.kt`, targeted time-boundary tests
   - **Why first:** every downstream fix must consume one shared period contract rather than re-encode the same logic locally
   - **Validation:** `TimePeriodUtilsTest.kt`, `TimePeriodUtilsStressTest.kt`, `TimePeriodUtilsValidationTest.kt`, `TimePeriodAlignmentTest.kt`
   - **Complete when:** Monday-start, half-open end boundaries, calendar-aware add/day-diff rules are test-backed and documented
2. **Batch 2 — Domain consumers adopt the standard**
   - **Scope:** `FinancialHealthCalculator.kt`, `BudgetCalculator.kt`, `HistoricalSpendingDistribution.kt`, `BillReminderManager.kt`, `RecurrenceCalculator.kt`, `RecurringExpenseEngine.kt`, `AdvancedAnalyticsDashboard.kt`, audit `SpendingPaceCalculator.kt`
   - **Why second:** these are the direct business-logic files called out by the epic and must stop owning local time math before repository/UI paths are updated
   - **Validation:** `BudgetCalculatorBoundaryTest.kt`, `BudgetCalculatorGoldenTest.kt`, `TemporalConsistencyTest.kt`, `AdvancedAnalyticsDashboardTest.kt`, `SpendingPaceCalculatorValidationTest.kt`, `SpendingPaceGoldenTest.kt`, `RecurringExpenseEngineEmptyListTest.kt`
   - **Complete when:** no target domain file still owns local week/month/day boundaries or DST-unsafe day/week bucketing logic
3. **Batch 3 — Data-layer rollover-aware current-period flows**
   - **Scope:** `DashboardContractsAdapter.kt`, `BudgetRepository.kt`, optional shared rollover helper adoption
   - **Why third:** these fixes depend on the canonical math and must be landed together so long-lived collectors do not sit half-migrated
   - **Validation:** `BudgetRolloverTest.kt`, `BudgetRepositoryStressTest.kt`, `CrossGroupIntegrationTest.kt`, `NotificationExpenseDashboardPipelineTest.kt`, plus focused rollover-flow tests if absent
   - **Complete when:** dashboard/budget flows refresh after day/month rollovers without requiring re-subscription
4. **Batch 4 — UI explicit range construction**
   - **Scope:** `TransactionFilterSheet.kt` plus the minimum compile-neighbor presentation file(s) needed to supply a reference time or explicit clear behavior
   - **Why fourth:** the UI should consume the settled contract, not define it
   - **Validation:** relevant transaction filter tests if present; otherwise at minimum `DateBoundaryFlowTest.kt` and existing transaction/viewmodel stress tests that cover filter application
   - **Complete when:** month/year filter ranges use exclusive end boundaries, can be cleared explicitly, and do not depend on local `23:59:59` math
5. **Batch 5 — Documentation / registry / report sync**
   - **Scope:** A.5 registry block, affected final verification files, matching deep-analysis mirrors
   - **Validation:** only A.5-mapped rows/summary sentences receive `[RESOLVED BY A.5]`; unrelated issue families stay untouched
   - **Complete when:** docs reflect the standardized time-boundary contract without over-reporting unrelated fixes

### Domain Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`
  - Keep this as the canonical owner of shared day/week/month/quarter/year period math.
  - Add any missing pure helpers needed by A.5 (for example explicit year/month range builders, boundary-delay helpers, or clearly named half-open range helpers) instead of recreating ad-hoc `Calendar` code elsewhere.
  - Ensure the KDoc states the half-open contract clearly: `date >= start && date < end`.
  - Preserve system-default-zone behavior; do **not** turn this into a UTC/timezone-normalization epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/util/TimeBoundaryTicker.kt` **(create if no equivalent helper already exists)**
  - Provide a small rollover-aware trigger flow for “current period” collectors.
  - Prefer boundary-aware re-emission (immediate emit, then next rollover) over blind fixed polling.
  - Keep this utility clock-driven via the existing `TimeProvider`; do **not** create a second clock abstraction.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
  - Delete/retire the local `getStartOfDay/getEndOfDay/getStartOfWeek/getEndOfWeek/getStartOfMonth/getEndOfMonth` helpers.
  - Replace inclusive `in start..end` checks with explicit half-open predicates using `TimePeriodUtils` ranges.
  - Standardize the week window to Monday-start via `TimePeriodUtils.getWeekRange(...)`.
  - Preserve current score formulas, weights, and bonuses unless an A.5 regression test proves that a boundary change requires a tiny safe adjustment.
  - **Do not change** transaction-type filtering, budget-normalization semantics, or legacy score thresholds in this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt`
  - Make rolling windows resolve the active anchored cycle containing `now`, instead of pinning `start = budget.startDate` forever.
  - Replace rolling monthly `TimePeriodUtils.addDays(start, 30)` with calendar month math.
  - Route `CALENDAR` + `YEARLY` budgets to Jan 1 → Jan 1 windows via `TimePeriodUtils.getYearRange(now)` rather than anniversary logic.
  - If the current helper API is too implicit, add explicit “window containing evaluation time” / “next window from current window” helpers and keep existing entry points as delegating wrappers for compatibility.
  - Preserve existing month-end anchor coercion behavior (31st/29th) unless regression tests show a real boundary bug.
  - **Do not change** `Budget` entity fields, `BudgetPeriod` enums, rollover business semantics, or forecasting behavior here.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt`
  - Replace locale-sensitive `Calendar.set(DAY_OF_WEEK, MONDAY)` setup with `TimePeriodUtils.getStartOfWeek(...)`.
  - Replace raw `msPerDay/msPerWeek` bucket math with calendar-safe grouping: week buckets by week start, distinct day counting by start-of-day.
  - Preserve lookback length, qualifying-week threshold, trimming rules, and log-normal fitting logic.
  - **Do not change** purchase/withdrawal filtering, distribution family, or output DTO shape in A.5.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`
  - Replace `cutoff = now + daysAhead * DAY_IN_MILLIS` with calendar-aware day addition.
  - Route next-date advancement through the shared recurrence/time utility rather than a local string-based `Calendar` switch where practical.
  - Preserve reminder urgency thresholds and reminder-day business rules.
  - **Do not change** monthly-cost conversion/product semantics unless the time-math refactor absolutely requires a compile-safe enum path.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt`
  - Make due/upcoming window math calendar-safe (`addDays` / shared utilities) instead of raw millisecond arithmetic.
  - Keep `calculateNextDate` / `calculatePreviousDate` as the canonical recurrence advancement path for day/month/year hopping.
  - Prefer explicit reference timestamps from callers over implicit wall-clock defaults if that is required for boundary correctness.
  - **Do not change** frequency labels or broaden into a full recurrence-policy redesign beyond time arithmetic standardization.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
  - Keep interval classification on calendar-day differences (`TimePeriodUtils.daysBetween(...)`) and audit any remaining fixed-duration assumptions.
  - When rolling detected patterns forward, use calendar-aware additions through `TimePeriodUtils` / `RecurrenceCalculator` rather than flat 30/90/365-day approximations.
  - Preserve merchant grouping, confidence thresholds, and pattern-detection heuristics unless a direct A.5 regression forces a minimal change.
  - **Do not change** merchant-identity normalization or manual-override policy here unless directly required by an A.5 regression.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt`
  - Replace month-end construction using `23:59:59` with `TimePeriodUtils.getMonthRange(...)` / `getEndOfMonth(...)` exclusive boundaries.
  - Prefer deriving month windows from one canonical month iterator or one preloaded range query if that is the safest way to remove boundary drift.
  - Preserve current amount/effectiveAmount behavior, transfer handling, insight text, and category-label behavior.
  - **Do not change** raw-vs-effective amount semantics or transfer-income business rules in A.5.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt` **(audit-first / minimal change expected)**
  - Re-read the current day/month math after Batch 1 settles the shared utilities and touch the file only if a direct A.5 regression remains.
  - If touched, preserve the current partial-day pacing model and existing pace thresholds.
  - **Do not change** projection heuristics or pace-product semantics just to “normalize” this file.

> [!WARNING]
> Do **not** reintroduce local `Calendar.firstDayOfWeek`, `set(DAY_OF_WEEK, MONDAY)` without a shared helper, raw `timestamp / DAY_IN_MILLIS` day indexing, or `+30 days` month math in any A.5 target file.

> [!WARNING]
> Do **not** “fix” boundary bugs by switching back to inclusive `23:59:59` endpoints. The canonical A.5 contract is start-of-next-period exclusive.

> [!WARNING]
> Do **not** change the `TimeProvider` interface or create a second clock abstraction. Use the existing `TimeProvider` plus a tiny rollover-aware helper if needed.

### Data Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
  - Stop capturing the current month once with `System.currentTimeMillis()` in `observeDashboardExpenses()`.
  - Inject/use the app’s existing `TimeProvider` and rebuild the month-scoped expense flow from a rollover-aware trigger using `flatMapLatest`.
  - Derive the active month with `TimePeriodUtils.getMonthRange(now)` on each trigger.
  - Preserve entity → `DashboardExpense` mapping and all non-expense dashboard contracts.
  - **Do not change** dashboard DTO shape or downstream mapping semantics in this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
  - Replace one-time capture of `twentyFiveMonthsAgo` / `endExclusive` with a rollover-aware flow that recalculates the window after day/month rollover.
  - Reuse `TimePeriodUtils.addMonths(now, -25)` and `TimePeriodUtils.getEndOfDay(now)` (exclusive next-day start) per trigger rather than once per subscription.
  - Keep spend selection, health thresholds, and rollover accumulation semantically stable while routing period windows through the fixed `BudgetCalculator`.
  - Preserve the public `Flow<List<BudgetStatus>>` contract.
  - **Do not change** shared-expense offset semantics, hidden pagination behavior, or budget notification-reset logic under A.5 unless a boundary fix cannot compile without a tiny shim.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt` **(audit only)**
  - Re-check for any A.5-specific time-window capture or calendar arithmetic after the shared utilities are in place.
  - If no direct period/window bug exists, leave the file untouched and document it as an audit-only no-op.
  - **Do not fix** privacy/logging, retry-budget, or location-bias issues here under A.5.
- [ ] Supporting compile-neighbor tests / constructor wiring
  - Update direct-instantiation tests once `DashboardContractsAdapter` gains `TimeProvider` or a ticker dependency.
  - If a shared rollover helper is introduced, keep its surface small/private to avoid unnecessary DI churn.

> [!WARNING]
> Do **not** change Room entity definitions, `@Entity` annotations, DAO schemas, or migrations to land A.5.

> [!WARNING]
> Do **not** duplicate repository-local ticker loops if one shared rollover helper can own the trigger. Duplicated clocking logic will drift again.

### UI Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilterSheet.kt`
  - Initialize year/month chip state from `currentFilter?.dateRange` so an existing explicit range is visible/editable.
  - Represent “date filter cleared” explicitly; applying after reset must write `dateRange = null`, not fall back to the previous filter range.
  - Build year/month ranges through `TimePeriodUtils` and the half-open contract (month start → next month start, year start → next year start), not `23:59:59` timestamps.
  - Source the reference “current year” from an injected/passed reference time path rather than `System.currentTimeMillis()` if a minimal compile-neighbor path exists.
  - Preserve category/type/ownership UI behavior and chip labels.
  - **Do not change** Transactions tab semantics (`MONTH` remaining rolling 30 days in tab logic) as part of this sheet-only A.5 fix.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt` **(supporting compile-neighbor, only if needed)**
  - Pass a reference timestamp/time-backed value into `TransactionFilterSheet` if that is the minimum safe way to remove direct wall-clock calls from the sheet.
  - Keep existing apply/clear wiring and ownership handling stable aside from explicit date-range clear behavior.
  - **Do not change** dialog workflows, filter-banner rules, pagination, or edit behavior under A.5.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt` **(supporting compile-neighbor, only if needed)**
  - Touch only if the filter sheet needs a presentation-owned reference time or a tiny helper exposure for exclusive-end filter construction.
  - Preserve current external filter/tab-intersection semantics unless a direct A.5 regression proves a change is required.
  - **Do not change** the rolling last-30-days `MONTH` tab semantics in this epic.

> [!WARNING]
> Do **not** use A.5 to relabel or redefine the Transactions screen tab meanings (“Month”, “Quarter”, “Year”). That product-definition issue belongs to a separate consistency pass unless a listed bug explicitly forces it.

> [!WARNING]
> Do **not** keep a hidden fallback like `currentFilter?.dateRange` after reset/apply. A cleared UI filter must serialize to `null` explicitly.

### Failure / rollback containment notes
- If a new rollover helper causes too much constructor churn, keep it small and internal to the affected repositories/adapters; do not half-migrate one flow and leave the other on frozen ranges.
- If `BudgetCalculator` API cleanup ripples too broadly, add new explicit helpers and keep old entry points as delegating wrappers rather than breaking callers mid-batch.
- If removing wall-clock access from `TransactionFilterSheet` becomes too invasive, pass a simple `referenceNowMs` from the minimum parent call site rather than injecting clocks directly into Compose.
- If the `LocationBackfillWorker` audit shows no concrete A.5 issue, leave the file untouched and do not force a no-op edit just to match the registry list.

## 4. Verification Plan
- **Unit Tests:** update and/or run the following as the minimum A.5 verification set.
  - `app/src/test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsTest.kt`
    - Keep exclusive-end expectations explicit.
  - `app/src/test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsStressTest.kt`
    - Extend DST, Monday-start, and rollover coverage.
  - `app/src/test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsValidationTest.kt`
    - Update any stale inclusive-end assumptions; add half-open day/month/year regressions.
  - `app/src/test/java/com/yourname/expensetracker/metrics/TimePeriodAlignmentTest.kt`
    - Update stale expectations that still treat month/year ends as inclusive last-day timestamps.
  - `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetCalculatorBoundaryTest.kt`
    - Replace the current bug-documenting rolling-month expectation with active-window/calendar-year regressions.
  - `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetCalculatorGoldenTest.kt`
    - Update golden expectations for rolling monthly and calendar yearly windows.
  - `app/src/test/java/com/yourname/expensetracker/data/repository/BudgetRolloverTest.kt`
    - Ensure rollover windows are computed from completed active cycles, not the original creation window.
  - `app/src/test/java/com/yourname/expensetracker/consistency/TemporalConsistencyTest.kt`
    - Add/assert cross-DST alignment between `BudgetCalculator`, `SpendingPaceCalculator`, and `TimePeriodUtils`.
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculatorValidationTest.kt`
    - Run as regression; adjust only if shared helper expectations change.
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceGoldenTest.kt`
    - Run as regression to prove pace output is not unintentionally changed by utility refactors.
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboardTest.kt`
    - Add a regression for month-trend boundary construction using exclusive next-month start.
  - `app/src/test/java/com/yourname/expensetracker/e2e/DateBoundaryFlowTest.kt`
    - Keep half-open boundary behavior locked end-to-end.
  - `app/src/test/java/com/yourname/expensetracker/verification/CrossGroupIntegrationTest.kt`
    - Re-run because it hardcodes daily/weekly/monthly helper usage and average-day computations.
  - `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`
    - Re-run after dashboard/budget flow rollover changes.
  - `app/src/test/java/com/yourname/expensetracker/data/repository/BudgetRepositoryStressTest.kt`
    - Re-run after reactive-window refactor.
  - `app/src/test/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngineEmptyListTest.kt`
    - Re-run after recurrence/date-advancement changes.
  - `app/src/test/java/com/yourname/expensetracker/data/location/LocationBackfillWorkerTest.kt`
    - Run as audit-only regression if the file stays untouched.
  - `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt`
    - Re-run DAO boundary coverage after any range-construction change.
  - **Create if no focused test already exists:**
    - `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorBoundaryTest.kt`
      - Monday-start week + exclusive-end regressions.
    - `app/src/test/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt`
      - DST-safe week/day grouping.
    - `app/src/test/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapterRolloverTest.kt`
      - Long-lived collector refreshes after month rollover.
    - `app/src/test/java/com/yourname/expensetracker/data/repository/BudgetRepositoryRolloverFlowTest.kt`
      - Day rollover refreshes budget-status flow without re-subscription.
    - `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTimeBoundaryTest.kt`
      - DST-safe reminder cutoff and next-date handling.
    - `app/src/test/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilterSheetDateRangeTest.kt`
      - Explicit clear behavior and exclusive-end range construction.
- **Syntax/Lint:**
  - Ensure no targeted A.5 file still contains local `23:59:59`, `23:59:59.999`, `Calendar.firstDayOfWeek`, `set(DAY_OF_WEEK, MONDAY)` without shared-helper semantics, or raw calendar bucket math like `timestamp / DAY_IN_MILLIS`.
  - Ensure no long-lived “current period” flow still captures `now` once outside a rollover-aware trigger when it is meant to stay current.
  - Ensure no imports were broken when routing code to `TimePeriodUtils` or any new rollover helper.
  - Rebuild after each micro-batch; minimum bar is a clean `:app:compileDebugKotlin`.
  - Run `:app:testDebugUnitTest` after all A.5 edits land.
  - Re-run relevant instrumentation/DAO tests after range/boundary changes.
  - If a new rollover helper is introduced, verify it does not leak coroutines or spin aggressively between boundaries.

## 5. Documentation & Registry Updates (CRITICAL)
- **Registry Update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, append `[RESOLVED BY A.5]` to **only** the exact six-line A.5 block supplied for this task:
    1. `### A.5: Time Boundary / Calendar Arithmetic Inconsistencies`
    2. `**Batches affected:** 01, 02, 03, 04, 10, 16, 17, 30, 32, 36, 37, 41, 43`
    3. `**Severity:** HIGH`
    4. `**Description:** Week boundaries use locale-dependent \`Calendar.firstDayOfWeek\` instead of standardized Monday-start. Month boundaries use \`+30 days\` instead of calendar month math. Day indexing uses millisecond division causing DST errors. End boundaries use \`23:59:59\` instead of start-of-next-day exclusive. Reactive flows capture time windows once and never refresh on rollover.`
    5. `**Affected files:** \`FinancialHealthCalculator.kt\`, \`BudgetCalculator.kt\`, \`HistoricalSpendingDistribution.kt\`, \`TransactionFilterSheet.kt\`, \`DashboardContractsAdapter.kt\`, \`BudgetRepository.kt\`, \`LocationBackfillWorker.kt\`, \`BillReminderManager.kt\`, \`RecurrenceCalculator.kt\`, \`RecurringExpenseEngine.kt\`, \`TimePeriodUtils.kt\`, \`AdvancedAnalyticsDashboard.kt\`, \`SpendingPaceCalculator.kt\``
    6. `**Suggested fix:** Centralize all period math through \`TimePeriodUtils\`. Use calendar-aware day/month addition. Use exclusive end boundaries consistently. Drive long-lived reactive flows from a rollover-aware clock/ticker.`
  - Do **not** mark adjacent A.x epics as resolved.
- **Batch Reports:**
  - Update only the A.5-mapped rows/summary sentences in these final verification files, and only if the implemented code actually resolves that specific note:
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-01.md`
      - Mark only the `AdvancedAnalyticsDashboard.kt:160-206` month-end exclusive-boundary issue as `[RESOLVED BY A.5]`.
      - Do **not** mark `AdvancedAnalyticsEngine` day-index/DST rows unless that file was actually fixed too.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
      - Mark only the `BudgetCalculator.kt` rolling-window / `+30 days` / explicit-window-contract rows that were directly fixed by A.5.
      - Do **not** mark BudgetForecastingEngine or shared-budget rows.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-03.md`
      - Mark only the `FinancialHealthCalculator` week-boundary row.
      - Leave non-boundary health-score issues untouched.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-04.md`
      - Mark only the `HistoricalSpendingDistribution` locale/DST week/day bucketing rows.
      - Do **not** mark `FinancialStressForecastEngine` or `SynthesisEngine` unless they were actually touched.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
      - Re-read for an explicit A.5 note; if none exists, leave the file untouched.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-16.md`
      - Mark only the `TransactionFilterSheet.kt:251-284` time/boundary row if the sheet now uses exclusive-end ranges and explicit clear behavior.
      - Do **not** mark `correlationId` or unrelated filter-state issues under A.5.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-17.md`
      - Re-read for explicit A.5 rows; do **not** mark the broader “Month means different things across screens” semantics row unless that product-definition issue was intentionally fixed.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-30.md`
      - Re-read for explicit A.5 rows; if none exist, leave untouched.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-32.md`
      - Mark only the `DashboardContractsAdapter` stale-month row, the `BudgetRepository` stale-reactive-window row, and the matching cross-component stale-flow summary if both long-lived flows are actually rollover-aware after the fix.
      - Do **not** mark hidden truncation or shared-expense contract rows.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
      - Mark only the `AdvancedAnalyticsDashboard` month-end boundary row.
      - Mark the monthly-trend N+1 row only if the implementation genuinely removes it as part of the same fix.
      - Do **not** mark raw-vs-effective amount or transfer-semantics rows.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-37.md`
      - Mark only the `BudgetCalculator` rolling-window and calendar-year rows.
      - Do **not** mark forecasting/autopilot/shared-budget rows.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
      - Mark only the `FinancialHealthCalculator` week-boundary row and the `Legacy health score ↔ TimePeriodUtils/dashboard time contracts` cross-component summary if local helpers are removed and the calculator now delegates to `TimePeriodUtils`.
      - Do **not** mark false-positive day-end rows, score-model rows, or investment issues.
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-43.md`
      - Re-read carefully; if no row explicitly maps to A.5 time-boundary arithmetic, leave the file untouched.
      - Do **not** manufacture a resolution tag for broader recurrence semantic-drift issues unless that exact row was intentionally fixed inside A.5.
  - Update matching deep-analysis mirrors only where the same A.5 issue family is explicitly described:
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-01.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-01-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-02.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-02-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-03.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-03-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-04.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-04-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-10.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-10-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-16.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-16-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-17.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-17-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-30.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-30-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-32.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-32-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-37.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-37-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-43.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-43-DEBUGGER.md`
  - In every report file, append `[RESOLVED BY A.5]` only to the exact row/summary sentence that maps to the implemented A.5 fix. Do **not** bulk-edit unrelated findings.
