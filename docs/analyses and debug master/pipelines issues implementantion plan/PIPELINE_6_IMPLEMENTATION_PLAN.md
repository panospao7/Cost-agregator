# Pipeline 6 — Budget / Forecasting / Cashflow: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 6 — Budget / Forecasting / Cashflow  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 6 — Budget / Forecasting / Cashflow
Verdict: RED
Summary:
- 9 old issues FIXED, 5 TODO ONLY (design-level gaps)
- 5 issues FIXED by universal (NEW-P6-001/002/003/005 via U-PR1, NEW-P6-009 via U-PR7)
- 11 pipeline-local issues remain OPEN (1 P1, 5 P2, 5 P3)
- 5 old TODO issues need design decisions (budget limit rate, planned normalization, cashflow currency, stress model)
- Key gaps: unbounded rollover loop, stress pattern double-count, hardcoded thresholds
- Stress forecast model has fundamental design issues (not real balance, counts PAID)
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_6_CONSOLIDATED_ISSUES.md`

**Source files:** `BudgetRepository.kt`, `BudgetMonitor.kt`, `FinancialStressForecastEngine.kt`, `CashFlowCalendarEngine.kt`, `BudgetForecastingEngine.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 6 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | Fixes NEW-P6-001/002/003/005 | No | ✅ Fixed |
| U-PR3 (Money/Currency) | Planned expense normalization in ForecastInputAssembler | No — already applied | ✅ Fixed |
| U-PR7 (TimeProvider) | Fixes NEW-P6-009 — DST-safe day arithmetic | No | ✅ Fixed |
| Others | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P6-P1-01 through P6-P1-05 | ✅ FIXED | None | None |
| P6-P1-06 | 📝 TODO | None | Budget limit period-specific rate |
| P6-P1-07 | ✅ FIXED | None | None |
| P6-P1-08 | 📝 TODO | U-PR3 partial | Remaining: cashflow/stress paths |
| P6-P1-09/10/12/15 | ✅ FIXED | None | None |
| P6-P1-11 | 📝 TODO | None | Cashflow currency normalization |
| P6-P1-13 | 📝 TODO | None | Stress model redesign |
| P6-P1-14 | 📝 TODO | None | Exclude PAID from stress |
| NEW-P6-001/002/003/005 | ✅ FIXED | U-PR1 | None |
| NEW-P6-004 | 🔴 OPEN | None | Bound rollover loop |
| NEW-P6-006 | 🔴 OPEN | None | Fix CE handling in adjustedSpend |
| NEW-P6-007 | 🔴 OPEN | None | Fix interval bounds |
| NEW-P6-008 | 🔴 OPEN | None | Handle stale patterns |
| NEW-P6-009 | ✅ FIXED | U-PR7 | None |
| NEW-P6-010 | 🔴 OPEN | None | Make thresholds configurable |
| NEW-P6-011 through NEW-P6-016 | 🔴 OPEN | None | Various cleanup |

---

## 5. New Issues / Regressions

No regressions from universal fixes. U-PR3 normalized planned expenses in ForecastInputAssembler but cashflow/stress paths still have raw-sum issues (tracked as P6-P1-11, P6-P1-08 remainder).

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P6-004 | P1 | Unbounded rollover loop — O(N) queries | Budget | P6-PR1 |
| P6-P1-14 | P1 | Stress counts PAID occurrences as outflows | Stress | P6-PR1 |
| P6-P1-11 | P1 | Cashflow raw-sums multi-currency | Cashflow | P6-PR1 |
| NEW-P6-006 | P2 | computeAdjustedSpend swallows CE | Budget | P6-PR2 |
| NEW-P6-007 | P2 | expandDetectedPatterns closed interval double-counts | Stress | P6-PR2 |
| NEW-P6-008 | P2 | Stale detected patterns silently skipped | Stress | P6-PR2 |
| NEW-P6-010 | P2 | Hardcoded currency-specific risk thresholds | Stress | P6-PR2 |
| NEW-P6-011 | P2 | calculateSeasonalFactor dead stub | Forecast | P6-PR2 |
| P6-P1-06 | P1 | Budget limit uses latest rate | Budget | P6-PR3 (design) |
| P6-P1-13 | P1 | Stress not real balance forecast | Stress | P6-PR3 (design) |
| NEW-P6-012 | P3 | MIN_HISTORY_MONTHS unused | Cleanup | P6-PR4 |
| NEW-P6-013 | P3 | pacePercentage=0 misleading | UX | P6-PR4 |
| NEW-P6-014 | P3 | estimateIncome divides by hardcoded 3.0 | Correctness | P6-PR4 |
| NEW-P6-015 | P3 | Income recurring treated as expense | Cashflow | P6-PR4 |
| NEW-P6-016 | P3 | WEEK_OF_YEAR locale-dependent | Correctness | P6-PR4 |

---

## 7. PR Organization

### P6-PR1 — Critical Correctness (Rollover, Stress, Cashflow)

```
PR name: fix(p6): bound rollover loop, exclude PAID from stress, normalize cashflow
Goal: Fix P1 correctness issues in budget/stress/cashflow
Issues fixed: NEW-P6-004, P6-P1-14, P6-P1-11
Universal dependencies: U-PR3 (already landed — normalization engine available)
Files likely touched:
  - BudgetRepository.kt (rollover)
  - FinancialStressForecastEngine.kt (PAID exclusion)
  - CashFlowCalendarEngine.kt (currency normalization)
Implementation steps:
  1. NEW-P6-004: Add MAX_ROLLOVER_PERIODS constant (e.g. 365 for daily); break loop after limit; batch-query periods instead of per-period query
  2. P6-P1-14: Remove PAID from ACTIVE_OCCURRENCE_STATUSES in stress engine; only include PLANNED/OVERDUE
  3. P6-P1-11: In CashFlowCalendarEngine, normalize amounts via MoneyNormalizationEngine before summing; carry isPartial flag
Tests:
  - rollover_loop_bounded_for_daily_budgets
  - stress_excludes_paid_occurrences
  - cashflow_normalizes_multi_currency_amounts
Risks: Medium — changes stress/cashflow output; verify with golden tests
Acceptance criteria:
  - Rollover never exceeds MAX_ROLLOVER_PERIODS iterations
  - PAID occurrences don't inflate stress forecast
  - Cashflow calendar shows normalized amounts with quality warnings
```

### P6-PR2 — Stress Engine & Budget Hardening

```
PR name: fix(p6): stress interval bounds, stale patterns, configurable thresholds, dead code
Goal: Fix stress engine correctness and budget edge cases
Issues fixed: NEW-P6-006, NEW-P6-007, NEW-P6-008, NEW-P6-010, NEW-P6-011
Universal dependencies: None
Files likely touched:
  - BudgetMonitor.kt (CE handling)
  - FinancialStressForecastEngine.kt (intervals, patterns, thresholds)
  - BudgetForecastingEngine.kt (seasonal stub)
Implementation steps:
  1. NEW-P6-006: Add CancellationException rethrow in computeAdjustedSpend catch block
  2. NEW-P6-007: Fix expandDetectedPatterns to use half-open interval [start, end) — no double-count at boundaries
  3. NEW-P6-008: When detected pattern is stale (older than threshold), log warning and exclude from forecast (don't silently include)
  4. NEW-P6-010: Extract hardcoded risk thresholds to AppConfig; allow per-currency override via settings
  5. NEW-P6-011: Either implement calculateSeasonalFactor or remove dead stub; if removing, ensure no callers
Tests:
  - adjusted_spend_rethrows_cancellation
  - pattern_expansion_no_double_count_at_boundary
  - stale_patterns_excluded_with_warning
  - risk_thresholds_configurable
Risks: Low — targeted fixes
Acceptance criteria:
  - No CE swallowing in budget monitor
  - Pattern expansion produces correct day count
  - Stale patterns don't corrupt forecast
  - Thresholds overridable without code change
```

### P6-PR3 — Design-Level Issues (Budget Rate, Stress Model)

```
PR name: design(p6): budget limit period-specific rate + stress balance model
Goal: Address fundamental design gaps (may be partial/incremental)
Issues fixed: P6-P1-06, P6-P1-13
Universal dependencies: None
Files likely touched:
  - BudgetRepository.kt / BudgetMonitor.kt (limit rate)
  - FinancialStressForecastEngine.kt (balance model)
Implementation steps:
  1. P6-P1-06: Convert budget limit using period-end rate (not latest); store converted limit alongside raw limit
  2. P6-P1-13: Add initial balance input to stress forecast; compute running balance = initial + income - expenses over horizon
  NOTE: These are design-level changes that may need incremental delivery
Tests:
  - budget_limit_uses_period_end_rate
  - stress_forecast_tracks_running_balance
Risks: High — changes budget/stress semantics; needs product decision
Acceptance criteria:
  - Budget limit comparison uses consistent rate basis
  - Stress forecast reflects actual projected balance
```

### P6-PR4 — Cleanup & Minor Fixes

```
PR name: chore(p6): remove dead code, fix income direction, locale-safe week
Goal: Code hygiene and minor correctness
Issues fixed: NEW-P6-012, NEW-P6-013, NEW-P6-014, NEW-P6-015, NEW-P6-016
Universal dependencies: None
Files likely touched:
  - BudgetForecastingEngine.kt, BudgetMonitor.kt, StressForecastEngine.kt, CashFlowCalendarEngine.kt, BudgetRepository.kt
Implementation steps:
  1. NEW-P6-012: Remove unused MIN_HISTORY_MONTHS constant
  2. NEW-P6-013: Return null or "N/A" for pacePercentage when no baseline exists (not 0)
  3. NEW-P6-014: Replace hardcoded 3.0 divisor with actual month count from income history
  4. NEW-P6-015: In cashflow, classify income recurring rules as INCOME direction (positive), not expense
  5. NEW-P6-016: Replace WEEK_OF_YEAR with ISO week (WeekFields.ISO) for locale-independent behavior
Tests:
  - income_recurring_shows_as_positive_in_cashflow
  - week_number_consistent_across_locales
Risks: Very low
Acceptance criteria:
  - No dead constants; income direction correct; week numbers ISO-compliant
```

---

## 8. Detailed Implementation Plan

### P6-PR1 Step-by-Step

1. **Open** `BudgetRepository.kt` — find rollover loop; add `var iterations = 0` counter; break at MAX_ROLLOVER_PERIODS; consider batch query for all prior periods
2. **Open** `FinancialStressForecastEngine.kt` — find `ACTIVE_OCCURRENCE_STATUSES`; remove `PAID`; keep only `PLANNED`, `OVERDUE`, `DUE`
3. **Open** `CashFlowCalendarEngine.kt` — find amount summation; inject `MoneyNormalizationEngine`; normalize each entry before summing

### P6-PR2 Step-by-Step

1. **Open** `BudgetMonitor.kt` — find `computeAdjustedSpend` catch block; add `if (e is CancellationException) throw e`
2. **Open** `FinancialStressForecastEngine.kt` — find `expandDetectedPatterns`; change end bound from inclusive to exclusive
3. **Find** stale pattern handling; add age check and exclusion with log
4. **Extract** hardcoded thresholds to companion object constants → then to AppConfig

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 6 Adapter/Follow-up |
|---|---|
| U-PR3 (Money/Currency) | ✅ ForecastInputAssembler normalization landed; cashflow/stress still need local normalization (P6-PR1) |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 6 targeted tests
./gradlew :app:testDebugUnitTest --tests "*Budget*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Forecast*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CashFlow*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Stress*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Synthesis*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P6-PR1: Rollover bounded; PAID excluded from stress; cashflow normalized
- [ ] P6-PR2: CE rethrown; interval bounds correct; stale patterns excluded; thresholds configurable
- [ ] P6-PR3: Budget limit uses period rate; stress tracks balance (design decision required)
- [ ] P6-PR4: Dead code removed; income direction correct; locale-safe weeks
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Pipeline 6 status upgraded to YELLOW→GREEN in master tracker
