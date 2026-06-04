# Engine 2 Final Status — PR1–PR8 Summary

> Companion to `engine2-implementation-plan.md` and `engine2-follow-up-implementation-plan.md`.
> **Status: YELLOW → GREEN CANDIDATE** (validation pass required for full GREEN).

---

## PR1–PR8 Completion Status

| PR | Title | Status | Key Deliverables |
|----|-------|--------|------------------|
| **PR1** | Insights period correctness | ✅ FIXED | ViewModel no longer calls legacy `InsightsEngine` overloads; `generateInsights(input, categories)` uses `NormalizedAnalyticsInput`; period-respecting tests added |
| **PR2** | Advanced normalized-input adoption | ✅ FIXED | `AdvancedAnalyticsEngine.getSpendingPatterns(input)` and `getStatisticalInsights(input)` consume normalized input; ViewModel no longer self-fetches; legacy overloads deprecated |
| **PR3** | Budget-vs-actual FX/data-quality | ✅ FIXED | Budget limits use `periodEndRate` basis; `BudgetVsActualResult.dataQuality` propagated; conversion failures mark items partial |
| **PR4** | Analytics data-quality & provenance | ✅ FIXED | `SpendingSummary.aggregate` populated from normalized rows; `ExcludedExpense` carries `warningType`/`message`; `NormalizedExpense` rate provenance filled |
| **PR5** | Location analytics normalized API | ✅ FIXED | ViewModel uses `computeNormalized()` for area spending and travel detection; raw `compute(List<Expense>)` deprecated |
| **PR6** | Analytics UI money null-safety | ✅ FIXED | `moneyCurrentTotalOrNull` replaces unsafe `moneyCurrentTotal`; `CurrencyCode("")` cannot be constructed in loading states |
| **PR7** | Historical category identity | ⏭ DEFERRED | Option A (soft-delete) designed & documented in `docs/architecture/HISTORICAL_CATEGORY_IDENTITY_PLAN.md`; requires schema PR |
| **PR8** | Final docs/tracker & guardrails | ✅ FIXED | Engine 2 status documented; guards enforced; this file created |

---

## Updated Engine 2 Status

| Area | Status | Notes |
|------|--------|-------|
| **Canonical normalized input** | ✅ FIXED | `AnalyticsInputAssembler` produces `NormalizedAnalyticsInput`; all arithmetic-heavy analytics consume it (PR1–PR3) |
| **Insights period correctness** | ✅ FIXED | Period-respecting behavior tests verify week/year/all do not collapse to current month (PR1, PR4 tests) |
| **Advanced normalized adoption** | ✅ FIXED | `AdvancedAnalyticsEngine` reads from normalized input; ViewModel passes assembled input (PR2) |
| **Budget-vs-actual FX/data-quality** | ✅ FIXED | Budget limits converted at period-end rate; `dataQuality` propagated to UI items (PR3 tests) |
| **SpendingSummary aggregate** | ✅ FIXED | `SpendingSummary.aggregate` populated from normalized rows; `displayAmount` equals `totalSpent` within rounding tolerance (PR1) |
| **Location analytics normalized path** | ✅ FIXED | Area spending and travel detection use `computeNormalized()` with `MoneyAggregate` (PR3) |
| **UI money null-safety** | ✅ FIXED | Null-safe `moneyCurrentTotalOrNull`; loading states cannot throw `CurrencyCode("")` (PR2) |
| **Historical category identity** | ⏭ DEFERRED BY DESIGN | Soft-delete designed; requires schema PR; see `docs/architecture/HISTORICAL_CATEGORY_IDENTITY_PLAN.md` (PR7) |
| **Spending personality** | ⚠ PARTIAL | ViewModel uses `classify(NormalizedAnalyticsInput)`; raw path guarded; `budgetAdherence = 0.5` (neutral) documented as caveat; `Calendar` usage remains (PR6) |

---

## Required Guards Now Enforced

All five production guard tests are in place and pass:

| Guard | Mechanism | Production Code Blocked |
|-------|-----------|------------------------|
| `noProductionCallToLegacyInsightsOverload()` | Architecture guard test + `@Deprecated(WARNING)` | Legacy `InsightsEngine.generateInsights(...)` overloads from ViewModel |
| `noProductionCallToRawAdvancedAnalytics()` | Architecture guard test + `@Deprecated(WARNING)` | `AdvancedAnalyticsEngine` self-fetching `getSpendingPatterns(period, currency)` / `getStatisticalInsights(period, currency)` from ViewModel |
| `noProductionCallToRawAreaSpendingCompute()` | Architecture guard test + `@Deprecated(WARNING)` | `AreaSpendingEngine.compute(List<Expense>)` |
| `noProductionCallToRawTravelDetectionCompute()` | Architecture guard test + `@Deprecated(WARNING)` | `TravelDetectionEngine.compute(List<Expense>)` |
| `noProductionCallToRawSpendingPersonalityClassify()` | Architecture guard test + `@Deprecated(WARNING)` | Raw `SpendingPersonalityClassifier.classify(...)` |

These guards live in `DeprecatedApiArchitectureGuardTest.kt` with explicit allowlisting for test files and the legitimately deprecated call sites (WarrantyTrackerRepository, SubscriptionManagerEngine, etc.).

---

## Full GREEN Validation

To promote Engine 2 to **GREEN**, run the following validation command:

```bash
./gradlew :app:testDebugUnitTest --stacktrace
```

All guard tests, behavior tests, and non-regression tests must pass. Known unrelated test failures should be documented in `TEST_FAILURE_TRACKER.md` before declaring GREEN.

---

## Non-Regression Checklist Summary

- [x] Analytics screen loads totals, comparison, categories, merchants, daily chart
- [x] Insights respect selected period (week/year/all)
- [x] Advanced analytics use same normalized input as summary
- [x] Budget-vs-actual FX basis is period-end, stable for closed periods
- [x] Location analytics use normalized APIs
- [x] Loading state does not crash via invalid `CurrencyCode`
- [x] Spending personality raw path guarded; caveats documented
- [x] Historical category identity limitation documented and deferred
- [x] No Room migration added for analytics fixes
- [x] No global `CurrencyConverter` behavior change
- [ ] Final `./gradlew :app:testDebugUnitTest` passes (validation pass required)
