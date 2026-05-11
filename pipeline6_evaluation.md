# Pipeline 6 Evaluation — Budget / Forecasting / Cashflow

**Date:** 2026-05-11  
**Baseline:** `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
**HEAD:** `0574e404` (post pipelines 1-5)  
**Verdict:** **GREEN — nearly production-clean**

## Executive Summary

Pipeline 6 has been extensively hardened during prior sessions. Of the 15 P1 issues identified in the debug report, **14 are already implemented in code** — many more than the master tracker acknowledges. Only 1 P1 issue and 4 P2 issues remain unimplemented.

The pipeline has solid foundations: `BudgetCalculator` for period logic, `MultiCurrencyRepository` for currency-safe aggregation, write barriers on all CRUD paths, adjusted spend in alerts, PLANNED-only filters in forecasting, normalized multi-currency cashflow, and proper stress-forecast labeling.

## Verified State

### P1 Issues (15 total)

| ID | Description | Status | Evidence |
|----|-------------|--------|----------|
| P6-P1-01 | Forecast unique index conflict | ✅ IN_CODE | `@Insert(onConflict = REPLACE)` in BudgetForecastDao |
| P6-P1-02 | Forecast createdAt=0, wrong currency | ✅ IN_CODE | `createdAt = now`, `currency = homeCurrency` in generateForecast() |
| P6-P1-03 | Write barrier on budget/forecast/planned | ✅ IN_CODE | writeBarrier.checkWritesAllowed in all 3 files |
| P6-P1-04 | Budget alerts adjusted spend | ✅ IN_CODE | Uses `adjustedSpendBreakdown?.effectiveSpend` in BudgetMonitor |
| P6-P1-05 | Rollover partial conversion | ✅ IN_CODE | Propagates `isPartial` + `warningMessage` from prior periods |
| P6-P1-06 | Budget limit period-specific rate | ❌ NOT_IMPLEMENTED | Uses `convert()` (latest) not `convertAsOf()`; TODO at L310-313 |
| P6-P1-07 | SynthesisEngine dataQuality propagation | ✅ IN_CODE | `forecast.confidence - input.dataQuality.confidencePenalty` |
| P6-P1-08 | Planned expense normalization | ✅ IN_CODE | Per-item `currencyConverter.convert()` in ForecastInputAssembler |
| P6-P1-09 | PLANNED-only filter | ✅ IN_CODE | `status == "PLANNED"` in both SynthesisEngine and ForecastInputAssembler |
| P6-P1-10 | Recurring occurrence status | ✅ IN_CODE | Only `"PLANNED"` mapped; status carried in ConfirmedOccurrence |
| P6-P1-11 | Cashflow multi-currency | ✅ IN_CODE | Per-item `currencyConverter.convert()` in calculateDailyCashFlow |
| P6-P1-12 | Cashflow deduped output | ✅ IN_CODE | `predictedRecurring = deduplicatedPredicted` |
| P6-P1-13 | Stress forecast labelled estimate | ✅ IN_CODE | StressForecastMode enum with NET_CASHFLOW_ESTIMATE |
| P6-P1-14 | PAID excluded from stress outflows | ✅ IN_CODE | `ACTIVE_OCCURRENCE_STATUSES = setOf("PLANNED")` |
| P6-P1-15 | Delete budget with forecasts | ✅ IN_CODE | Forecasts deleted first in `database.withTransaction` |

### P2 Issues (5 total)

| ID | Description | Status |
|----|-------------|--------|
| P2-16 | PlannedExpense timestamps/conflict/invariants | ❌ NOT_IMPLEMENTED |
| P2-17 | Budget suggestion hardcoded euro symbol | ✅ IN_CODE (dynamic home currency used) |
| P2-18 | Budget invalidation trigger raw query | ❌ NOT_IMPLEMENTED |
| P2-19 | Autopilot apply-all transaction rollback | ❌ NOT_IMPLEMENTED |
| P2-20 | Budget monitor diagnostic ledger | ❌ NOT_IMPLEMENTED |

## Remaining Work

| Priority | Issue | Severity | Effort |
|----------|-------|----------|--------|
| P1 | P6-P1-06: Budget limit period-specific conversion | High | Small |
| P2 | P2-16: PlannedExpense invariants | Medium | Small |
| P2 | P2-18: Invalidation trigger replacement | Medium | Small |
| P2 | P2-19: Autopilot transaction rollback | Medium | Small |
| P2 | P2-20: Budget monitor diagnostics | Medium | Small |

## Definition of Done

- Budget limit conversion uses period-appropriate historical rates
- PlannedExpenseRepository sets timestamps, checks insert result, sets openSourceOccurrenceKey
- Budget invalidation trigger uses cheap non-aggregate query
- Autopilot apply-all truly rolls back on failure
- BudgetMonitor writes durable diagnostic events
