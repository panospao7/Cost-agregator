# Remaining Issues — Execution Plan

> Generated: 2026-05-03 | Re-verified against codebase: 2026-05-03
> Scope: All items still marked **STILL PRESENT** in the issue registry

---

## Phase 1 Code Verification Results

The 12 CRITICAL Phase 1 issues were re-verified against the actual codebase on 2026‑05‑03.
Below is the per‑issue status with corrected severities.

### Forecast subsystem (originally 5 CRITICAL)

| # | Issue | Plan Severity | Verified Status | Corrected Severity | Evidence |
|---|-------|---------------|-----------------|---------------------|----------|
| 1 | FCST‑1: Month forecast treats weekly bills as monthly | CRITICAL | **ALREADY FIXED** | — (remove from plan) | `SynthesisEngine.kt:147‑192` multiplies by `weeksRemaining` for WEEKLY/BIWEEKLY |
| 2 | FCST‑2: Double‑counting in Monte Carlo | CRITICAL | **ALREADY FIXED** | — (remove from plan) | `MonteCarloSpendingSimulator.kt:21‑30` docs + `FinancialStressForecastEngine.kt:400‑413` filters recurring merchant keys |
| 3 | FCST‑3: Cashflow only sees next occurrence | CRITICAL | **CONFIRMED** | CRITICAL | `CashFlowCalculator.kt:137‑142` uses `nextExpectedDate` per‑day loop; `getUpcomingBills()` is correct but `calculateDailyCashFlow()` doesn’t use it |
| 4 | FCST‑4: No currency on forecast money | CRITICAL | **ALREADY FIXED** | — (remove from plan) | `FinancialForecast.kt:24`, `ForecastComponents.kt:92`, `MonteCarloResult.kt:39`, `StressHorizon.kt:681` all carry `displayCurrency` |
| 5 | FCST‑5: Stress forecast balance starts at 0 | CRITICAL | **CONFIRMED** | **MAJOR** | `FinancialStressForecastEngine.kt:591‑593` hardcoded 0.0; documented as intentional (no account‑balance source) |

**Remaining Forecast CRITICAL: 1 (FCST‑3)  ┃  New MAJOR: 1 (FCST‑5 downgrade)**

### Shared subsystem (originally 4 CRITICAL)

| # | Issue | Plan Severity | Verified Status | Corrected Severity | Evidence |
|---|-------|---------------|-----------------|---------------------|----------|
| 6 | SHR‑6: `addExpenseToGroup` TOCTOU races | CRITICAL | **ALREADY FIXED** | — (remove from plan) | `GroupTransactionCoordinator.kt:171‑248` wrapped in `database.withTransaction` |
| 7 | SHR‑7: Hard delete orphans expenses | CRITICAL | **CONFIRMED** | **MAJOR** | `deleteGroupAtomic` (line 623) does not unlink system‑expense flags; KDoc warns to prefer soft‑archive |
| 8 | SHR‑8: Cross‑group `paidById` enforcement | CRITICAL | **CONFIRMED** | CRITICAL | `GroupExpense.kt:20‑38` — FK only checks member existence, not same‑group; no DB trigger |
| 9 | SHR‑9: Custom split validation | CRITICAL | **ALREADY FIXED** | — (remove from plan) | `validateCustomSplitPayloadFormat()` (line 731) validates JSON + member‑count match |

**Remaining Shared CRITICAL: 1 (SHR‑8)  ┃  New MAJOR: 1 (SHR‑7 downgrade)**

### Currency subsystem (originally 3 CRITICAL)

| # | Issue | Plan Severity | Verified Status | Corrected Severity | Evidence |
|---|-------|---------------|-----------------|---------------------|----------|
| 10 | CURR‑1: Exchange rate unique index too narrow | CRITICAL | **ALREADY FIXED** | — (remove from plan) | `ExchangeRate.kt:25` — 3‑column unique index `(fromCurrency, toCurrency, validDate)` |
| 11 | CURR‑2: `convert()` lacks date param | CRITICAL | **PARTIALLY FIXED** | CRITICAL | Data model supports historical dates, but `convert()` (line 85) has no date param; `getRate()` uses `LIMIT 1` without date ordering |
| 12 | CURR‑3: Home currency change no re‑normalization | CRITICAL | **CONFIRMED** | **MAJOR** | `CurrencySettingsRepositoryImpl.kt:53‑62` — only logs warning, no trigger; documented limitation |

**Remaining Currency CRITICAL: 1 (CURR‑2)  ┃  New MAJOR: 1 (CURR‑3 downgrade)**

---

## Corrected Summary Table

| Subsystem | CRITICAL (was) | CRITICAL (now) | MAJOR (new from downgrade) | Key Remaining Files |
|-----------|---------------|----------------|---------------------------|---------------------|
| Forecast | 5 | **1** | 1 (FCST‑5) | CashFlowCalculator |
| Shared | 4 | **1** | 1 (SHR‑7) | GroupExpense, GroupTransactionCoordinator |
| Currency | 3 | **1** | 1 (CURR‑3) | CurrencyConverter, CurrencySettingsRepositoryImpl |
| **Phase 1 Total** | **12** | **3** | **3** | |

Net change: **9 CRITICAL issues removed from Phase 1** (6 fully fixed + 3 downgraded to MAJOR).

---

## Corrected Execution Order (Phase 1 only)

With only 3 CRITICAL remaining in Phase 1 (down from 12), the priorities within Phase 1 are:

| Priority | Issue | CRITICAL/Major | Summary |
|----------|-------|----------------|---------|
| **1** | FCST‑3 | CRITICAL | `calculateDailyCashFlow()` only sees `nextExpectedDate` — wire in occurrence‑based expansion |
| **2** | SHR‑8 | CRITICAL | No DB‑level trigger enforcing `paidById` belongs to same group as `groupId` |
| **3** | CURR‑2 | CRITICAL | `convert()` lacks date param — historical rates stored but unusable through API |
| 4 | FCST‑5 | MAJOR (was CRITICAL) | Stress forecast balance hardcoded 0.0 |
| 5 | SHR‑7 | MAJOR (was CRITICAL) | Hard‑delete orphans system‑expense flags; soft‑archive preferred |
| 6 | CURR‑3 | MAJOR (was CRITICAL) | Home currency change triggers no re‑normalization |

---

## Key Fixes (updated for remaining issues)

- **`CashFlowCalculator.kt`**: Replace `nextExpectedDate` per‑day loop with occurrence‑driven expansion (use `getUpcomingBills()` or `RecurringOccurrenceDao.getByDateRange()`)
- **`GroupExpense.kt`**: Add DB trigger or composite FK to enforce `paidById` ∈ same group as `groupId`
- **`CurrencyConverter.kt`**: Add `convertAsOf(amount, from, to, date)` overload; update `ExchangeRateStore.getRate()` to accept optional `validDate`; add `ORDER BY validDate DESC` to DAO query
- **`GroupTransactionCoordinator.kt`**: Add `unlinkSystemExpensesForGroup()` before hard‑delete to clear `isSharedExpense`/`myShareAmount` on orphaned Expense rows
- **`FinancialStressForecastEngine.kt`**: (MAJOR) Explore account‑balance integration or document that 0.0 is the app’s assumption
- **`CurrencySettingsRepositoryImpl.kt`**: (MAJOR) Add re‑normalization trigger or hook for `setHomeCurrency()`

---

## Cross‑Cutting Batches (from Registry Suggestions)

These cross‑subsystem work items remain relevant:

| Batch | Focus | Involved Subsystems | Issues Fixed |
|-------|-------|---------------------|--------------|
| S1 | Wire `RecurringOccurrenceExpander` | Forecast, Recurring | FCST‑3, FCST‑11, FCST‑N1, FCST‑7 |
| S4 | MultiCurrencyRepository adoption | Dashboard, Currency, Location, Budget | (unchanged) |
| S8 | DB invariant enforcement | DB/Migration | SHR‑8, DB‑2, DB‑6, BUD‑25 |

---

## Phases 2‑5 (unchanged from original plan)

The remaining phases (AI Safety, Budget, Dashboard, etc.) were not re‑verified in this pass.
Their CRITICAL counts may also be overstated pending code verification.

| Phase | Subsystems | CRITICAL (claimed) |
|-------|-----------|-------------------|
| 2 | AI/ML, AI Integration, Privacy | 9 (unverified) |
| 3 | Budget, Receipt, Recurring | 6 (unverified) |
| 4 | Dashboard, Location | 4 (unverified) |
| 5 | Backup, DB/Migration, Migration Policy, Warranty, Search, Workers, Transaction | 4 (unverified) |

**Recommendation:** Re‑verify Phase 2‑5 issues against actual code before starting implementation, as the Phase 1 re‑verification found 75% of claimed CRITICAL items (9/12) were already fixed or misclassified.

---

## Notes

- **Total items still marked STILL PRESENT in registry:** 282 (re‑verified subset: 12 Phase 1 CRITICAL)
- **This re‑verification found:** 6 fully fixed, 3 confirmed with original severity, 3 downgraded (CRITICAL → MAJOR)
- **Items Verified RESOLVED at reconciliation (line 199) partially contradicted by code:** FCST‑3 was marked resolved but `calculateDailyCashFlow()` still uses `nextExpectedDate`‑only loop; FCST‑2 was marked PARTIALLY but code shows complete double‑count prevention
- **Execution plan Phase 1 workload reduced by ~75%** — focus on the 3 true CRITICAL items first

(End of file — total lines ~145)
