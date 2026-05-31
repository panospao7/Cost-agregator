# Pipeline 5 — Currency/Dashboard/Analytics: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 11 FIXED, 1 PARTIAL, 14 NEW open issues  
> **Total open items:** 15 (1 PARTIAL + 14 NEW)

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P5-P1-01 | P1 | Historical totals use latest-rate aggregate conversion | ✅ FIXED | ✅ **FIXED** | Per-tx TRANSACTION_DATE via `MoneyNormalizationEngine` |
| P5-P1-02 | P1 | `ExchangeRateDao.getRate()` ambiguous with historical rows | ✅ FIXED | ✅ **FIXED** | `getLatestRateForPair` uses `validDate DESC` |
| P5-P1-03 | P1 | Dashboard adapter drops `MoneyAggregate` and partial warnings | ✅ FIXED | ✅ **FIXED** | Dashboard carries `isPartial`/`warning`/`CurrencyQualityUi` |
| P5-P1-04 | P1 | Weekly/daily totals drilldown functionally broken | ✅ FIXED | ✅ **FIXED** | Historical aggregation APIs working |
| P5-P1-05 | P1 | Dashboard widgets raw-sum `effectiveAmount` | ✅ FIXED | ✅ **FIXED** | `DashboardNormalizedInput` used |
| P5-P1-06 | P1 | Stale-rate state not propagated to analytics quality | ✅ FIXED | ✅ **FIXED** | `staleRateCount` populated |
| P5-P1-07 | P1 | `MultiCurrencyRepository` inconsistent builder use | ✅ FIXED | ✅ **FIXED** | MCR uses `MoneyNormalizationEngine` |
| P5-P1-08 | P1 | Budget-vs-actual comparisons not fully normalized | ⚠ PARTIAL | ⚠ **PARTIAL** | Budget limit PERIOD_END vs spend latest — tracked as P6-CURRENT-001 |
| P5-NEW-01 | P1 | Weekly/daily drilldown included non-spending types | ✅ FIXED | ✅ **FIXED** | Routed to PURCHASE-only historical APIs |
| P5-NEW-06 | P1 | Budget dashboard dropped partial/conversion warning | ✅ FIXED | ✅ **FIXED** | `BudgetStatusSnapshot` gained fields |
| P5-NEW-07 | P2 | Analytics stale detection used `lastUpdated` not `validDate` | ✅ FIXED | ✅ **FIXED** | Uses `convertOutcome(TRANSACTION_DATE)` |
| P5-NEW-09 | P2 | Monthly/yearly `PeriodTotal` dropped partial warnings | ✅ FIXED | ✅ **FIXED** | Propagates `isPartial`/`warningMessage` |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P5-001 | P0 | `previousMonthAggregate` always null — dead feature | DashboardSynthesisEngine.kt | 🔴 OPEN |
| NEW-P5-002 | P1 | Division by zero risk in `projectedTotal` | DashboardSynthesisEngine.kt | 🔴 OPEN |
| NEW-P5-003 | P1 | Deposit filter includes not-mine items | MultiCurrencyRepository.kt | 🔴 OPEN |
| NEW-P5-004 | P1 | `getAverageForPeriodType(DAY)` wrong denominator | TotalsAggregationEngine.kt | 🔴 OPEN |
| NEW-P5-005 | P1 | `SynthesisEngine` sums planned expenses across currencies | DashboardSynthesisEngine.kt | 🔴 OPEN |
| NEW-P5-006 | P2 | `homeCurrency().first()` cold Flow on every call | MultiCurrencyRepository.kt | 🔴 OPEN |
| NEW-P5-007 | P2 | `NormalizedAnalyticsInput.homeCurrency` defaults to EUR | NormalizedAnalyticsInput.kt | 🔴 OPEN |
| NEW-P5-008 | P2 | Category aggregates ALL_TYPES vs PURCHASE-only mismatch | TotalsAggregationEngine.kt | 🔴 OPEN |
| NEW-P5-009 | P2 | `MoneyAggregateBuilder` silently drops counts on size mismatch | MoneyAggregateBuilder.kt | 🔴 OPEN |
| NEW-P5-010 | P2 | `computeFromNormalized` per-expense average not per-day | AnalyticsComputeEngine.kt | 🔴 OPEN |
| NEW-P5-011 | P1 | `FinancialRunway` always shows 0 days | DashboardSynthesisEngine.kt | 🔴 OPEN |
| NEW-P5-012 | P3 | Stale-rate detection fixed 7-day threshold | AnalyticsCurrencyNormalizer.kt | 🔴 OPEN |
| NEW-P5-013 | P3 | `aggregateCurrencyTotals` returns empty on unknown type | MultiCurrencyRepository.kt | 🔴 OPEN |
| NEW-P5-014 | P3 | `buildTrendFromNormalizedInput` timezone edge case | TrendBuilder.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 11 |
| ⚠ PARTIAL (old issues) | 1 |
| 🔴 OPEN (new issues) | 14 |
| **Total open work** | **15** |

---

## Priority Order for Remaining Work

### P0 (critical)
1. **NEW-P5-001** — previousMonthAggregate always null (dead feature, user-visible)

### P1 (must fix)
2. **NEW-P5-011** — FinancialRunway always shows 0 days
3. **NEW-P5-002** — Division by zero risk in projectedTotal
4. **NEW-P5-003** — Deposit filter includes not-mine items
5. **NEW-P5-004** — getAverageForPeriodType(DAY) wrong denominator
6. **NEW-P5-005** — SynthesisEngine sums planned expenses across currencies
7. **P5-P1-08 remainder** — Budget limit PERIOD_END vs spend latest

### P2 (should fix)
8. **NEW-P5-006** — homeCurrency().first() cold Flow on every call
9. **NEW-P5-007** — NormalizedAnalyticsInput.homeCurrency defaults to EUR
10. **NEW-P5-008** — Category aggregates ALL_TYPES vs PURCHASE-only mismatch
11. **NEW-P5-009** — MoneyAggregateBuilder silently drops counts
12. **NEW-P5-010** — computeFromNormalized per-expense average not per-day

### P3 (cleanup)
13. **NEW-P5-012** — Stale-rate detection fixed 7-day threshold
14. **NEW-P5-013** — aggregateCurrencyTotals returns empty on unknown type
15. **NEW-P5-014** — buildTrendFromNormalizedInput timezone edge case

---

## Do-Not-Fix-Locally (wait for universal PR)

| Issue | Wait for |
|-------|----------|
| NEW-P5-006 (homeCurrency Flow) | U-PR6 — cached settings flow / timeout wrapper |
| P5-P1-08 (budget limit basis) | P6-CURRENT-001 — budget normalization alignment |
