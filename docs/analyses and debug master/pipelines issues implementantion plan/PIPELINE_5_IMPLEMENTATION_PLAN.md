# Pipeline 5 — Currency / Dashboard / Analytics: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 5 — Currency / Dashboard / Analytics  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 5 — Currency / Dashboard / Analytics
Verdict: RED
Summary:
- 11 old issues fully FIXED, 1 PARTIAL (budget limit basis — tracked in P6)
- 1 issue FIXED by universal (NEW-P5-005 via U-PR3)
- 14 pipeline-local issues remain (1 P0, 4 P1, 5 P2, 3 P3)
- P0: previousMonthAggregate always null — user-visible dead comparison feature
- P1: FinancialRunway always 0, division by zero, wrong average denominator
- Dashboard synthesis engine has multiple dead/broken features
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_5_CONSOLIDATED_ISSUES.md`

**Source files:** `DashboardSynthesisEngine.kt`, `TotalsAggregationEngine.kt`, `MultiCurrencyRepository.kt`, `AnalyticsComputeEngine.kt`, `MoneyAggregateBuilder.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 5 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | No direct impact | No | N/A |
| U-PR2 (TOCTOU) | No direct impact | No | N/A |
| U-PR3 (Money/Currency) | **Fixes** NEW-P5-005 — SynthesisEngine planned expense normalization | No | ✅ Fixed |
| U-PR4 (Barrier) | No direct impact | No | N/A |
| U-PR5 (Privacy) | No direct impact | No | N/A |
| U-PR6 (Worker Guard) | No direct impact | No | N/A |
| U-PR7 (TimeProvider) | No direct impact | No | N/A |
| U-PR8 (Side Effects) | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P5-P1-01 through P5-P1-07 | ✅ FIXED | None | None |
| P5-P1-08 | ⚠ PARTIAL | None | Budget limit basis — tracked in P6 |
| P5-NEW-01/06/07/09 | ✅ FIXED | None | None |
| NEW-P5-001 | ✅ FIXED | None | observeDashboardExpenses loads previous month (P5-PR1 landed) |
| NEW-P5-002 | ✅ FIXED | None | projectedTotal guards daysElapsed > 0 (P5-PR1 landed) |
| NEW-P5-003 | 🔴 OPEN | None | Fix deposit filter |
| NEW-P5-004 | 🔴 OPEN | None | Fix day average denominator |
| NEW-P5-005 | ✅ FIXED | U-PR3 | None |
| NEW-P5-006 | 🔴 OPEN | None | Cache homeCurrency |
| NEW-P5-007 | 🔴 OPEN | None | Use actual home currency |
| NEW-P5-008 | 🔴 OPEN | None | Align category type filter |
| NEW-P5-009 | 🔴 OPEN | None | Handle size mismatch |
| NEW-P5-010 | 🔴 OPEN | None | Fix average computation |
| NEW-P5-011 | ✅ FIXED | None | totalRemaining computed from budget/income (P5-PR1 landed) |
| NEW-P5-012 | 🔴 OPEN | None | Make threshold configurable |
| NEW-P5-013 | 🔴 OPEN | None | Handle unknown type |
| NEW-P5-014 | 🔴 OPEN | None | Fix timezone edge case |

---

## 5. New Issues / Regressions

No regressions from universal fixes. U-PR3 (Money/Currency) correctly normalized planned expenses in SynthesisEngine.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P5-001 | P0 | previousMonthAggregate always null | Dashboard | P5-PR1 |
| NEW-P5-011 | P1 | FinancialRunway always 0 days | Dashboard | P5-PR1 |
| NEW-P5-002 | P1 | Division by zero in projectedTotal | Dashboard | P5-PR1 |
| NEW-P5-004 | P1 | getAverageForPeriodType(DAY) wrong denominator | Analytics | P5-PR1 |
| NEW-P5-003 | P1 | Deposit filter includes not-mine items | Repository | P5-PR2 |
| NEW-P5-008 | P2 | Category ALL_TYPES vs PURCHASE-only mismatch | Analytics | P5-PR2 |
| NEW-P5-010 | P2 | computeFromNormalized per-expense not per-day average | Analytics | P5-PR2 |
| NEW-P5-007 | P2 | NormalizedAnalyticsInput defaults to EUR | Analytics | P5-PR2 |
| NEW-P5-006 | P2 | homeCurrency().first() cold Flow on every call | Performance | P5-PR3 |
| NEW-P5-009 | P2 | MoneyAggregateBuilder drops counts on size mismatch | Correctness | P5-PR3 |
| NEW-P5-012 | P3 | Stale-rate 7-day threshold hardcoded | Config | P5-PR3 |
| NEW-P5-013 | P3 | aggregateCurrencyTotals empty on unknown type | Robustness | P5-PR3 |
| NEW-P5-014 | P3 | Trend builder timezone edge case | Correctness | P5-PR3 |

---

## 7. PR Organization

### P5-PR1 — Dashboard Dead Features & Critical Bugs

```
PR name: fix(p5): revive previousMonth comparison, fix runway, guard division by zero, fix day average
Goal: Fix user-visible broken dashboard features
Issues fixed: NEW-P5-001, NEW-P5-011, NEW-P5-002, NEW-P5-004
Universal dependencies: U-PR3 (already landed — normalization available)
Files likely touched:
  - DashboardSynthesisEngine.kt
  - TotalsAggregationEngine.kt
Implementation steps:
  1. NEW-P5-001: Populate previousMonthAggregate by querying prior month's normalized totals; use same MoneyNormalizationEngine path as current month
  2. NEW-P5-011: Fix FinancialRunway computation — likely needs: (a) actual average daily spend > 0, (b) available balance / daily spend = runway days
  3. NEW-P5-002: Guard projectedTotal division: if daysElapsed == 0, return currentTotal (not divide)
  4. NEW-P5-004: Fix getAverageForPeriodType(DAY) — use actual days in period as denominator, not transaction count
Tests:
  - previousMonth_aggregate_populated_for_valid_prior_month
  - runway_returns_positive_days_when_balance_and_spend_exist
  - projectedTotal_safe_on_first_day_of_period
  - day_average_uses_calendar_days_as_denominator
Risks: Medium — dashboard changes are user-visible; verify with golden tests
Acceptance criteria:
  - Month-over-month comparison shows real data (not null/0)
  - Runway shows meaningful days remaining
  - No ArithmeticException on day 1 of period
  - Daily average = total / calendar_days_elapsed
```

### P5-PR2 — Analytics Correctness

```
PR name: fix(p5): deposit filter, category type alignment, average computation, EUR default
Goal: Fix analytics computation errors
Issues fixed: NEW-P5-003, NEW-P5-008, NEW-P5-010, NEW-P5-007
Universal dependencies: None
Files likely touched:
  - MultiCurrencyRepository.kt
  - TotalsAggregationEngine.kt
  - AnalyticsComputeEngine.kt
  - NormalizedAnalyticsInput.kt
Implementation steps:
  1. NEW-P5-003: Fix deposit filter to exclude items where isSharedExpense=true AND paidByOther=true (not-mine items)
  2. NEW-P5-008: Align category aggregation to use PURCHASE-only filter (same as dashboard totals)
  3. NEW-P5-010: Change computeFromNormalized average to divide by calendar days, not expense count
  4. NEW-P5-007: Replace hardcoded EUR default with actual homeCurrency from UserCurrencyProvider
Tests:
  - deposit_filter_excludes_not_mine_shared_expenses
  - category_totals_match_dashboard_totals_filter
  - average_is_per_day_not_per_expense
  - analytics_input_uses_actual_home_currency
Risks: Low — computation fixes with clear expected behavior
Acceptance criteria:
  - Deposit totals exclude other-paid shared expenses
  - Category breakdown sums to dashboard total
  - Average spending is meaningful daily rate
  - No hardcoded EUR anywhere in analytics
```

### P5-PR3 — Performance & Robustness

```
PR name: fix(p5): cache homeCurrency, handle builder mismatch, configurable thresholds
Goal: Fix performance and edge-case robustness issues
Issues fixed: NEW-P5-006, NEW-P5-009, NEW-P5-012, NEW-P5-013, NEW-P5-014
Universal dependencies: None
Files likely touched:
  - MultiCurrencyRepository.kt
  - MoneyAggregateBuilder.kt
  - AnalyticsCurrencyNormalizer.kt
  - TrendBuilder.kt
Implementation steps:
  1. NEW-P5-006: Cache homeCurrency in a StateFlow or lazy val; refresh on settings change instead of cold Flow.first() per call
  2. NEW-P5-009: On size mismatch in MoneyAggregateBuilder, log warning and use available counts (don't silently drop)
  3. NEW-P5-012: Extract 7-day stale threshold to AppConfig constant; allow override
  4. NEW-P5-013: Return empty aggregate with warning (not empty list) for unknown transaction type
  5. NEW-P5-014: Use ZonedDateTime for trend date bucketing to handle DST transitions
Tests:
  - homeCurrency_cached_not_cold_flow_per_call
  - builder_handles_size_mismatch_gracefully
  - trend_builder_handles_DST_transition
Risks: Low — defensive improvements
Acceptance criteria:
  - homeCurrency() does not create new Flow subscription per call
  - No silent data loss in aggregate builder
  - Trend dates correct across DST boundaries
```

---

## 8. Detailed Implementation Plan

### P5-PR1 Step-by-Step

1. **Open** `DashboardSynthesisEngine.kt`
   - Find `previousMonthAggregate` — it's likely declared but never assigned
   - Add query: load prior month expenses, normalize via MoneyNormalizationEngine, assign to previousMonthAggregate
   - Find `FinancialRunway` computation — likely `availableBalance / averageDailySpend`
   - Fix: ensure averageDailySpend > 0 before division; use actual normalized daily spend

2. **Find** `projectedTotal` computation
   - Add guard: `if (daysElapsed <= 0) return currentMonthTotal`

3. **Open** `TotalsAggregationEngine.kt`
   - Find `getAverageForPeriodType(DAY)` — fix denominator to use calendar days in the period

### P5-PR2 Step-by-Step

1. **Open** `MultiCurrencyRepository.kt` — find deposit aggregation query; add filter excluding `isSharedExpense && paidByOther`
2. **Open** `TotalsAggregationEngine.kt` — find category aggregation; ensure it uses same PURCHASE-only filter as dashboard
3. **Open** `AnalyticsComputeEngine.kt` — find average computation; change to total/calendarDays
4. **Open** `NormalizedAnalyticsInput.kt` — replace `"EUR"` default with injected homeCurrency

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 5 Adapter/Follow-up |
|---|---|
| U-PR3 (Money/Currency) | ✅ Already landed — SynthesisEngine normalization working |
| P5-P1-08 (budget basis) | Tracked in Pipeline 6 — P6-CURRENT-001 |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 5 targeted tests
./gradlew :app:testDebugUnitTest --tests "*Dashboard*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Synthesis*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Analytics*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*MultiCurrency*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*MoneyAggregate*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TotalsAggregation*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P5-PR1: previousMonth populated; runway > 0; no division by zero; day average correct
- [ ] P5-PR2: Deposit filter correct; category alignment; per-day average; no EUR default
- [ ] P5-PR3: homeCurrency cached; builder robust; thresholds configurable; timezone-safe trends
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Pipeline 5 status upgraded to GREEN in master tracker
