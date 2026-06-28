# Pipeline 6 — Budget/Forecasting/Cashflow: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 9 FIXED, 5 TODO ONLY, 16 NEW open issues  
> **Total open items:** 21 (5 TODO + 16 NEW)

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P6-P1-01 | P1 | Budget forecast refresh fails on unique index conflict | ✅ FIXED | ✅ **FIXED** | ABORT + `insertWithDeactivation` |
| P6-P1-02 | P1 | Forecast rows persisted with `createdAt=0` and wrong currency | ✅ FIXED | ✅ **FIXED** | `createdAt=now`, `currency=homeCurrency` |
| P6-P1-03 | P1 | Budget/forecast/planned writes lack restore guard | ✅ FIXED | ✅ **FIXED** | Write barrier across all budget/forecast/planned writes |
| P6-P1-04 | P1 | Budget alerts use gross `percentUsed` | ✅ FIXED | ✅ **FIXED** | `BudgetMonitor` reads `adjustedSpendBreakdown` |
| P6-P1-05 | P1 | Rollover ignores partial conversion state | ✅ FIXED | ✅ **FIXED** | Rollover ORs `isPartial`, merges warnings |
| P6-P1-06 | P1 | Budget limit conversion uses current rate | 📝 TODO ONLY | 📝 **TODO ONLY** | Budget limit uses latest rate, not period-specific |
| P6-P1-07 | P1 | Forecast data quality ignored by `SynthesisEngine` | ✅ FIXED | ✅ **FIXED** | `confidencePenalty` applied |
| P6-P1-08 | P1 | Planned expenses not normalized before forecast | 📝 TODO ONLY | 📝 **TODO ONLY** | Groups by currency and sums raw amounts |
| P6-P1-09 | P1 | Cancelled/skipped planned expenses still enter forecast | ✅ FIXED | ✅ **FIXED** | PLANNED-only filter |
| P6-P1-10 | P1 | Recurring occurrence status lost before forecast | ✅ FIXED | ✅ **FIXED** | Occurrences filtered to PLANNED, normalized |
| P6-P1-11 | P1 | Cash-flow calendar raw-sums multi-currency amounts | 📝 TODO ONLY | 📝 **TODO ONLY** | Sums `effectiveAmount` across currencies |
| P6-P1-12 | P1 | Cash-flow output displays pre-dedup recurring predictions | ✅ FIXED | ✅ **FIXED** | Deduplicated predicted |
| P6-P1-13 | P1 | Stress forecast is not a real account-balance forecast | 📝 TODO ONLY | 📝 **TODO ONLY** | Computes net-cashflow, not account balance |
| P6-P1-14 | P1 | Stress forecast counts PAID occurrences as active outflows | 📝 TODO ONLY | 📝 **TODO ONLY** | ACTIVE_OCCURRENCE_STATUSES includes PAID |
| P6-P1-15 | P1 | Deleting budget can fail after forecasts exist | ✅ FIXED | ✅ **FIXED** | CASCADE + explicit delete |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P6-001 | P1 | `computeStressForecast` swallows CancellationException | StressForecastEngine.kt | ✅ FIXED (U-PR1) |
| NEW-P6-002 | P1 | `BudgetMonitor` writeAlertDiagnostic swallows CE | BudgetMonitor.kt | ✅ FIXED (pre-existing) |
| NEW-P6-003 | P1 | `BudgetMonitor` CHECK_FAILED diagnostic swallows CE | BudgetMonitor.kt | ✅ FIXED (pre-existing) |
| NEW-P6-004 | P1 | Unbounded rollover loop — O(N) queries for daily budgets | BudgetRepository.kt | ✅ FIXED (P6-PR1) |
| NEW-P6-005 | P2 | `BudgetRepository` CRUD swallows CancellationException | BudgetRepository.kt | ✅ FIXED (U-PR1) |
| NEW-P6-006 | P2 | `computeAdjustedSpend` swallows CE | BudgetMonitor.kt | 🔴 OPEN |
| NEW-P6-007 | P2 | Stress `expandDetectedPatterns` closed interval double-counts | StressForecastEngine.kt | ✅ FIXED (P6-PR2) |
| NEW-P6-008 | P2 | Stale detected patterns silently skipped | StressForecastEngine.kt | 🔴 OPEN |
| NEW-P6-009 | P2 | DST-unsafe day arithmetic in stress horizon | StressForecastEngine.kt | ✅ FIXED (U-PR7) |
| NEW-P6-010 | P2 | Hardcoded currency-specific risk thresholds | StressForecastEngine.kt | 🔴 OPEN |
| NEW-P6-011 | P2 | `calculateSeasonalFactor` dead stub | BudgetForecastingEngine.kt | 🔴 OPEN |
| NEW-P6-012 | P3 | `MIN_HISTORY_MONTHS` unused | BudgetForecastingEngine.kt | 🔴 OPEN |
| NEW-P6-013 | P3 | `pacePercentage=0` misleading when no baseline | BudgetMonitor.kt | 🔴 OPEN |
| NEW-P6-014 | P3 | `estimateIncome` divides by hardcoded 3.0 | StressForecastEngine.kt | 🔴 OPEN |
| NEW-P6-015 | P3 | Income recurring treated as expense in cashflow | CashFlowCalendarEngine.kt | 🔴 OPEN |
| NEW-P6-016 | P3 | Weekly period uses `WEEK_OF_YEAR` (locale-dependent) | BudgetRepository.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 9 |
| 📝 TODO ONLY (old issues) | 5 |
| 🔴 OPEN (new issues) | 16 |
| **Total open work** | **21** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **NEW-P6-001** — computeStressForecast swallows CancellationException
2. **NEW-P6-002** — BudgetMonitor writeAlertDiagnostic swallows CE
3. **NEW-P6-003** — BudgetMonitor CHECK_FAILED diagnostic swallows CE
4. **NEW-P6-004** — Unbounded rollover loop — O(N) queries for daily budgets
5. **P6-P1-06** — Budget limit uses latest rate (TODO)
6. **P6-P1-08** — Planned expenses not normalized (TODO)
7. **P6-P1-11** — Cash-flow raw-sums multi-currency (TODO)
8. **P6-P1-13** — Stress not real balance (TODO)
9. **P6-P1-14** — Stress counts PAID (TODO)

### P2 (should fix)
10. **NEW-P6-005** — BudgetRepository CRUD swallows CE
11. **NEW-P6-006** — computeAdjustedSpend swallows CE
12. **NEW-P6-007** — Stress expandDetectedPatterns double-counts
13. **NEW-P6-008** — Stale detected patterns silently skipped
14. **NEW-P6-009** — DST-unsafe day arithmetic in stress horizon
15. **NEW-P6-010** — Hardcoded currency-specific risk thresholds
16. **NEW-P6-011** — calculateSeasonalFactor dead stub

### P3 (cleanup)
17. **NEW-P6-012** — MIN_HISTORY_MONTHS unused
18. **NEW-P6-013** — pacePercentage=0 misleading when no baseline
19. **NEW-P6-014** — estimateIncome divides by hardcoded 3.0
20. **NEW-P6-015** — Income recurring treated as expense in cashflow
21. **NEW-P6-016** — Weekly period uses WEEK_OF_YEAR (locale-dependent)

---

## Do-Not-Fix-Locally (wait for universal PR)

| Issue | Wait for |
|-------|----------|
| NEW-P6-001/002/003/005/006 (CancellationException) | U-PR1 — shared detekt rule + helper |
| P6-P1-06 (budget limit rate) | P6-CURRENT-001 — budget normalization alignment |
