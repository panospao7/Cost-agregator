# Pipeline 5–9: NEW Issues Deep Audit Report

> **Generated:** 2026-05-31  
> **Method:** Critical source code audit beyond existing pipeline reports  
> **Total NEW issues found:** 59 (1 P0, 16 P1/HIGH, 26 P2/MEDIUM, 16 P3/LOW)  
> **Combined with existing report issues:** 121 documented + 59 new = 180 total across P5-9

---

## Executive Summary

| Pipeline | P0 | P1/HIGH | P2/MED | P3/LOW | Total NEW | Existing Report Issues |
|----------|:--:|:-------:|:------:|:------:|:---------:|:---------------------:|
| 5 — Currency/Dashboard | 1 | 4 | 5 | 4 | 14 | 23 |
| 6 — Budget/Forecast/Cashflow | 0 | 4 | 7 | 5 | 16 | 30 |
| 7 — Backup/Restore | 1 | 1 | 3 | 1 | 6 | 24 |
| 8 — Privacy/AI/Redaction | 0 | 2 | 5 | 1 | 8 | 18 |
| 9 — Workers/Background | 0 | 5 | 7 | 3 | 15 | 26 |
| **TOTAL** | **2** | **16** | **27** | **14** | **59** | **121** |

### Cross-cutting patterns found:
1. **CancellationException swallowing** — 10+ locations across P5, P6, P7
2. **Maintenance mode leaks** — encrypted export and privacy gate denial leave app write-locked (P7)
3. **Dead/non-functional features** — previousMonth always null, FinancialRunway always 0 (P5)
4. **Mixed-currency arithmetic** — SynthesisEngine, stress forecast, cashflow recurring (P5, P6)
5. **Worker guard gaps** — BillReminder bypasses guard, NotificationIntake not registered, WarrantyWorker uses System.currentTimeMillis (P9)

---

## P0 — Critical

| ID | Pipeline | Title | Impact |
|----|----------|-------|--------|
| NEW-P5-001 | 5 | `previousMonthAggregate` always null — dead feature | Month-over-month comparison dead, forecast confidence reduced |
| NEW-P7-001 | 7 | Encrypted export never exits maintenance mode on success | App permanently write-locked after encrypted debug export |

---

## P1 / HIGH — Must Fix

| ID | Pipeline | Title |
|----|----------|-------|
| NEW-P5-002 | 5 | Division by zero risk in projectedTotal |
| NEW-P5-003 | 5 | Deposit filter includes "not mine" items |
| NEW-P5-004 | 5 | getAverageForPeriodType(DAY) wrong denominator |
| NEW-P5-005 | 5 | SynthesisEngine sums planned expenses across currencies |
| NEW-P5-011 | 5 | FinancialRunway always shows 0 days |
| NEW-P6-001 | 6 | computeStressForecast swallows CancellationException |
| NEW-P6-002 | 6 | BudgetMonitor writeAlertDiagnostic swallows CancellationException |
| NEW-P6-003 | 6 | BudgetMonitor CHECK_FAILED diagnostic swallows CancellationException |
| NEW-P6-004 | 6 | Unbounded rollover loop — O(N) queries for daily budgets |
| NEW-P7-002 | 7 | Privacy gate denial / WAL failure leak maintenance mode |
| NEW-P8-001 | 8 | updateSettings() TOCTOU race — stale `old` in applyPrivacyChange |
| NEW-P8-002 | 8 | DataRetentionWorker loop has no checkpoint for 5 targets |
| NEW-P9-001 | 9 | TimeoutCancellationException misclassified as system cancellation |
| NEW-P9-002 | 9 | BillReminderWorker bypasses guard for settings/quiet-hours |
| NEW-P9-003 | 9 | WorkerRunContext counters not thread-safe |
| NEW-P9-004 | 9 | WarrantyExpirationWorker uses runGuarded (no context/counts) |
| NEW-P9-005 | 9 | WarrantyExpirationWorker uses System.currentTimeMillis |

---

## P2 / MEDIUM

| ID | Pipeline | Title |
|----|----------|-------|
| NEW-P5-006 | 5 | homeCurrency().first() cold Flow on every call |
| NEW-P5-007 | 5 | NormalizedAnalyticsInput.homeCurrency defaults to "EUR" |
| NEW-P5-008 | 5 | Category aggregates ALL_TYPES vs PURCHASE-only mismatch |
| NEW-P5-009 | 5 | MoneyAggregateBuilder silently drops counts on size mismatch |
| NEW-P5-010 | 5 | computeFromNormalized per-expense average not per-day |
| NEW-P6-005 | 6 | BudgetRepository CRUD swallows CancellationException |
| NEW-P6-006 | 6 | computeAdjustedSpend swallows CancellationException |
| NEW-P6-007 | 6 | Stress expandDetectedPatterns closed interval double-counts boundary |
| NEW-P6-008 | 6 | Stale detected patterns silently skipped |
| NEW-P6-009 | 6 | DST-unsafe day arithmetic in stress horizon |
| NEW-P6-010 | 6 | Hardcoded currency-specific risk thresholds (500/100) |
| NEW-P6-011 | 6 | calculateSeasonalFactor dead stub always returns 1.0 |
| NEW-P7-003 | 7 | enterCriticalRecoveryRequired non-atomic two-commit |
| NEW-P7-004 | 7 | RestoreJournal appendEvent read-modify-write race |
| NEW-P7-005 | 7 | CostbackupBundle.extract() leaks FileInputStream |
| NEW-P8-003 | 8 | MERCHANT_LINE_REGEX over-matches names, under-matches non-Latin |
| NEW-P8-004 | 8 | CloudPiiSanitizer missing address/national-ID/DOB patterns |
| NEW-P8-005 | 8 | requireAllowed() ignores capability parameter |
| NEW-P8-006 | 8 | DataRetentionWorker silently swallows purge failures |
| NEW-P8-007 | 8 | sanitizeRawOcr conflates null with empty string |
| NEW-P9-006 | 9 | WorkerSpecScheduler uses deprecated REPLACE policy |
| NEW-P9-007 | 9 | SharedPreferences version write not atomic with enqueue |
| NEW-P9-008 | 9 | NotificationIntakeWorker not in guard/registry |
| NEW-P9-009 | 9 | LocationBackfillWorker isStopped exits as SUCCESS |
| NEW-P9-010 | 9 | MerchantKeyBackfillWorker same isStopped issue |
| NEW-P9-011 | 9 | scheduleAtMidnight can produce near-zero delay |
| NEW-P9-012 | 9 | DailyBriefing reschedule failure silently swallowed |

---

## Recommended Fix Priority

### Immediate (P0 + critical P1)
1. **NEW-P7-001** — Add `restoreMaintenanceMode.exit()` to encrypted export success path
2. **NEW-P7-002** — Add exit() to privacy gate denial and WAL checkpoint failure paths
3. **NEW-P5-001** — Expand DashboardDataProvider query to include previous month
4. **NEW-P5-005** — Normalize planned expense currencies before SynthesisEngine sums
5. **NEW-P5-011** — Wire FinancialRunway to actual budget remaining
6. **NEW-P6-004** — Add iteration cap to rollover loop (prevent ANR)

### Next sprint
7. **NEW-P9-001** — Catch TimeoutCancellationException before guard sees it
8. **NEW-P8-001** — Capture `old` inside DataStore edit block
9. **NEW-P8-002** — Add checkpoint() before each retention target purge
10. **NEW-P6-001/002/003** — Add CancellationException rethrow in stress/monitor catches
11. **NEW-P9-002** — Move settings/quiet-hours check inside guard block
12. **NEW-P9-005** — Inject TimeProvider into WarrantyExpirationWorker

---

## Cross-cutting Fix Strategies

### Strategy 1: CancellationException audit
Add detekt custom rule flagging `catch (e: Exception)` or `catch (_: Exception)` in suspend functions without CancellationException check. Affects P5, P6, P7.

### Strategy 2: Maintenance mode exit guarantee
Wrap all `exportDatabase`/`createCostBackup` paths in try/finally that always exits maintenance mode. Affects P7.

### Strategy 3: Worker guard completeness
- All workers must use `runGuardedWithContext` (not `runGuarded`)
- All workers must be in WorkerRegistry
- `isStopped` should throw CancellationException, not break
Affects P9.

### Strategy 4: Mixed-currency arithmetic guard
CI guardrail flagging `.values.sum()` on currency-keyed maps. Affects P5, P6.
