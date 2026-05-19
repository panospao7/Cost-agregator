# Global Currency Normalization / MoneyAggregate — Scouting Report

**Date:** 2026-05-19  
**Baseline commit:** `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
**Status:** Scouting complete, ready for implementation

---

## Executive Summary

The codebase has strong foundations (MoneyAggregate, AnalyticsCurrencyNormalizer, NormalizedAnalyticsInput) but still has **critical gaps** that allow mixed-currency arithmetic, silent EUR fallbacks, and raw-amount fallbacks on conversion failure. The plan document is accurate and well-structured. This report validates it against the actual code and provides a prioritized implementation roadmap.

---

## 1. Current State — What Already Exists

### ✅ Well-Implemented

| Component | Location | Status |
|-----------|----------|--------|
| `MoneyAggregate` | `domain/core/money/MoneyAggregate.kt` | Approved aggregate type with `isPartial`, `warningMessage`, `sourceBuckets`, `conversionFailures` |
| `MoneyAggregateBuilder` | `domain/core/money/MoneyAggregateBuilder.kt` | Builds from buckets via `convertMultiple()`, handles failures gracefully |
| `ConversionFailure` | `domain/core/money/ConversionFailure.kt` | Typed failure with `MoneyAmount`, `FailureReason` enum (MISSING_RATE, INVALID_AMOUNT, RATE_STALE, UNKNOWN) |
| `CurrencyCode` | `domain/core/money/CurrencyCode.kt` | Value class with validation |
| `MoneyAmount` | `domain/core/money/MoneyAmount.kt` | Currency-safe arithmetic operators |
| `AnalyticsCurrencyNormalizer` | `domain/analytics/AnalyticsCurrencyNormalizer.kt` | Per-transaction historical `convertAsOf()`, excludes failures, accumulates warnings |
| `NormalizedAnalyticsInput` | `domain/analytics/NormalizedAnalyticsInput.kt` | Full normalized input with `NormalizedExpense`, `ExcludedExpense`, `AnalyticsDataQuality` |
| `AnalyticsInputAssembler` | `domain/analytics/AnalyticsInputAssembler.kt` | Canonical assembler, throws on missing home currency (no EUR fallback) |
| `MultiCurrencyRepository` (new APIs) | `data/repository/MultiCurrencyRepository.kt` | `getHomeCurrencyPurchaseTotal()`, etc. — all return `MoneyAggregate` |
| `ExchangeRateDao.getRateAsOf()` | `data/database/dao/ExchangeRateDao.kt` | Historical lookup: `validDate <= :validDate ORDER BY validDate DESC LIMIT 1` |
| `CurrencyConverter.convertAsOf()` | `domain/currency/CurrencyConverter.kt` | Historical conversion via `ExchangeRateStore.getRateAsOf()` |
| `CashFlowCalculator` | `domain/cashflow/CashFlowCalculator.kt` | Explicitly refuses EUR fallback, surfaces `isPartial` + `failedConversionCount` |
| Test infrastructure | `app/src/test/` | 295 test files with currency patterns, golden tests, scenario tests |

### ⚠️ Partially Implemented

| Component | Issue |
|-----------|-------|
| `MultiCurrencyRepository` | Dual API: legacy `Result<Double>` methods still exist alongside new `MoneyAggregate` methods |
| `BudgetRepository` | Uses `MoneyAggregate` internally but exposes `BudgetStatus.spentAmount: Double`; forces `ON_TRACK` on conversion failure |
| `MoneyAggregateBuilder` | Input is `List<Pair<Double, String>>` — not typed `MoneyBucketInput` |
| `CurrencyConverter.convert()` | Returns `ConversionResult?` — null conflates "missing rate" with "stale rate" |
| `ExchangeRateDao.getRate()` | Orders by `lastUpdated DESC` — historical backfills can poison latest-rate lookup |

### ❌ Missing (Required by Plan)

| Component | Plan Section |
|-----------|-------------|
| `RateBasis` enum | §1.2 |
| `ConversionOutcome` sealed interface | §2.1 |
| `ConversionPath` enum (IDENTITY/DIRECT/VIA_BASE) | §2.1 |
| `MoneyNormalizationEngine` | §5 |
| `HomeCurrencyResolution` sealed interface | §7 |
| `StaleRatePolicy` | §4 |
| `ConversionQuality` enum | §2.4 |
| `MoneyAggregateMetadata` | §2.4 |
| `NormalizedBudgetStatus` | §10 |
| `CurrencyQualityUi` | §14 |
| Static guard script | §13 |


---

## 2. Critical Bugs / Risks Found

### BUG-1: ExchangeRateDao.getRate() Poisoning (HIGH)

```sql
-- Current query (ExchangeRateDao.kt line 31):
SELECT * FROM exchange_rates 
WHERE fromCurrency = :fromCurrency AND toCurrency = :toCurrency 
ORDER BY lastUpdated DESC LIMIT 1
```

**Problem:** If a historical rate (e.g., USD/EUR for 2024-01-15) is backfilled today, its `lastUpdated` = today, so it becomes the "latest" rate even though its `validDate` is 2024-01-15. This poisons all latest-rate conversions.

**Impact:** Dashboard totals, budget limits, forecast amounts could use wrong rates.

**Fix:** Change to `ORDER BY validDate DESC, lastUpdated DESC LIMIT 1`.

---

### BUG-2: Budget ON_TRACK on Conversion Failure (MEDIUM-HIGH)

```kotlin
// BudgetRepository.kt line 265:
budgetConversionFailed -> BudgetHealthStatus.ON_TRACK
```

**Problem:** When budget limit conversion fails, health is forced to `ON_TRACK` with `percent = 0f`. The `isPartial` flag is set, but the UI shows green "on track" status for a budget whose actual status is unknown.

**Impact:** User sees misleading "on track" for budgets that may be exceeded.

**Fix:** Add `BudgetHealthStatus.UNKNOWN` or `CONVERSION_UNAVAILABLE`.

---

### BUG-3: Dashboard Spending Trend Raw Fallback (MEDIUM)

```kotlin
// ComputeDashboardWidgetsUseCase.kt line 592:
currencyConverter.convert(exp.effectiveAmount, exp.currency, homeCurrency)
    ?.convertedAmount ?: exp.effectiveAmount  // ← adds foreign amount to home total
```

**Problem:** When conversion fails, the raw foreign-currency amount is added to the home-currency total. A $100 USD expense with no rate gets added as 100 to a EUR total.

**Impact:** Spending trend chart shows inflated/incorrect totals.

**Fix:** Exclude failed conversions, mark trend as partial.

---

### BUG-4: Silent EUR Fallback in 22 Locations (MEDIUM)

```kotlin
// Pattern found in 16 files, 22 occurrences:
runCatching { currencySettingsRepository.homeCurrency().first() }.getOrDefault("EUR")
```

**Problem:** If DataStore read fails (corruption, race condition), all financial calculations silently use EUR as home currency. User with GBP home currency gets EUR totals without any indication.

**Impact:** Incorrect totals shown without warning.

---

### BUG-5: BudgetForecastingEngine Silent Amount Fallback (MEDIUM)

```kotlin
// BudgetForecastingEngine.kt line 93:
converted?.convertedAmount ?: budget.amount
```

**Problem:** If budget limit conversion fails, the raw amount in the budget's source currency is used as if it were in home currency. A ¥10000 JPY budget becomes "10000 EUR" in forecast calculations.

**Impact:** Forecast predictions wildly incorrect for foreign-currency budgets.

---

### BUG-6: ?: effectiveAmount Fallback in 13 Files (MEDIUM)

```kotlin
// Pattern in AnomalyAlertOrchestrator, CarbonFootprintCalculator, ExpenseUseCases, etc.:
normalizedAmountById[it.id]?.effectiveAmount ?: it.effectiveAmount
```

**Problem:** When normalization lookup misses (race condition, excluded expense), the raw foreign-currency amount is used in home-currency calculations.

**Impact:** Anomaly alerts, carbon calculations, health scores can be wrong.


---

## 3. Gap Analysis — Plan vs Reality

### Plan Section 1 (Target Invariants)

| Invariant | Current State | Gap |
|-----------|--------------|-----|
| No raw mixed-currency arithmetic | 70 `sumOf effectiveAmount` in 23 files | ~15 are unsafe |
| Every aggregate has rate basis | MoneyAggregate has no `rateBasis` field | Full gap |
| Conversion failures explicit | MoneyAggregate path: ✅. Other paths: ❌ | ~50% coverage |
| Original + normalized both survive | NormalizedExpense: ✅. BudgetStatus: ❌ | Partial |
| MoneyAggregate is only aggregate type | Legacy `Result<Double>` still exists | Migration needed |

### Plan Section 3 (Exchange-rate semantics)

| Requirement | Current State | Gap |
|-------------|--------------|-----|
| Latest rate uses validDate | Uses `lastUpdated` | **Critical bug** |
| Historical backfill doesn't poison latest | No protection | **Critical bug** |
| Deprecated getRate() | Still primary method | Full gap |
| validDate=0 migration | Legacy rows have validDate=0 | Needs migration |

### Plan Section 4 (Typed conversion API)

| Requirement | Current State | Gap |
|-------------|--------------|-----|
| `convertOutcome()` returning sealed type | `convert()` returns nullable | Full gap |
| `StaleRatePolicy` configurable | Hardcoded 24h in CurrencyConverter | Full gap |
| Distinguish missing vs stale vs invalid | Only in `convertMultiple()` | Partial |

### Plan Section 5 (Canonical normalizer)

| Requirement | Current State | Gap |
|-------------|--------------|-----|
| `MoneyNormalizationEngine` | Does not exist | Full gap |
| Single normalizer for all pipelines | `AnalyticsCurrencyNormalizer` serves analytics only | Can be promoted |
| `NormalizationResult<T>` sealed type | Does not exist | Full gap |

### Plan Section 7 (Home currency resolution)

| Requirement | Current State | Gap |
|-------------|--------------|-----|
| `HomeCurrencyResolution` sealed type | Does not exist | Full gap |
| Distinguish first-run vs failure | Both return "EUR" | Full gap |
| Failure → partial/unavailable | Failure → silent EUR in 22 locations | Full gap |


---

## 4. Dependency Graph

```
PR 1 (DAO fix) ──────────────────────────────────────────┐
                                                          │
PR 2 (ConversionOutcome + RateBasis) ───────────────────┐│
                                                         ││
PR 5 (HomeCurrencyResolution) ──────────────────────────┤│
                                                         ││
PR 3 (MoneyNormalizationEngine) ← PR 1, PR 2 ──────────┤│
                                                         ││
PR 4 (MoneyAggregateBuilder v2) ← PR 2 ────────────────┤│
                                                         ││
PR 6 (MultiCurrencyRepo split) ← PR 3, PR 4 ───────────┤│
                                                         ││
PR 7 (Dashboard migration) ← PR 5, PR 6 ───────────────┤│
                                                         ││
PR 8 (Budget/forecast migration) ← PR 5, PR 6 ─────────┤│
                                                         ││
PR 9 (Transaction provenance) ← independent ────────────┤│
                                                         ││
PR 10 (Bank/export) ← PR 9 ────────────────────────────┤│
                                                         ││
PR 11 (Static guard) ← PR 7, PR 8 ─────────────────────┤│
                                                         ││
PR 12 (UI propagation) ← PR 7, PR 8 ───────────────────┘│
                                                          │
Critical path: PR 1 → PR 2 → PR 3 → PR 6 → PR 7/8 → 11 │
```

---

## 5. Prioritized Implementation Plan

### Phase 1 — Fix Critical Bugs (Backward compatible)

**PR 1: Exchange-rate DAO semantics** (CRITICAL, ~2h)

Files to modify:
- `ExchangeRateDao.kt` — change `getRate()` ORDER BY to `validDate DESC, lastUpdated DESC`
- Add Room migration: `UPDATE exchange_rates SET validDate = lastUpdated WHERE validDate = 0`

Tests to add:
- `latest_rate_uses_highest_validDate_not_lastUpdated`
- `historical_backfill_inserted_today_does_not_poison_latest_rate`

Risk: LOW — query change is backward compatible; migration fills missing data.

---

### Phase 2 — Core Type Additions (Additive, no breaking changes)

**PR 2: ConversionOutcome + RateBasis** (~3h)

New files:
- `domain/core/money/RateBasis.kt`
- `domain/core/money/ConversionOutcome.kt`
- `domain/core/money/ConversionPath.kt`
- `domain/core/money/StaleRatePolicy.kt`
- `domain/core/money/ConversionFailureType.kt`

Modify:
- `CurrencyConverter.kt` — add `convertOutcome()` alongside existing methods

Risk: LOW — purely additive.

**PR 5: HomeCurrencyResolution** (~1.5h)

New files:
- `domain/currency/HomeCurrencyResolution.kt`

Modify:
- `CurrencySettingsRepository` — add `resolveHomeCurrency(): HomeCurrencyResolution`

Risk: LOW — additive. Consumers migrate incrementally.

---

### Phase 3 — Canonical Normalizer

**PR 3: MoneyNormalizationEngine** (~4h)

New files:
- `domain/core/money/MoneyNormalizationEngine.kt`
- `domain/core/money/NormalizationResult.kt`
- `domain/core/money/MoneyBucketInput.kt`
- `domain/core/money/TransactionTypeFilter.kt`
- `domain/core/money/BucketDatePolicy.kt`

Strategy: Promote `AnalyticsCurrencyNormalizer` logic into the new engine. Keep old normalizer as thin wrapper.

**PR 4: MoneyAggregateBuilder v2** (~2h)

Modify:
- `MoneyAggregateBuilder.kt` — add overload with `List<MoneyBucketInput>` + `RateBasis`
- `MoneyAggregate.kt` — add `rateBasis` field (default `LATEST_AVAILABLE`)

---

### Phase 4 — Pipeline Migration

**PR 6: MultiCurrencyRepository API split** (~3h)
**PR 7: Dashboard migration** (~3h) — remove `?: exp.effectiveAmount`
**PR 8: Budget/forecast/cashflow** (~4h) — remove ON_TRACK fallback, use engine

---

### Phase 5 — Enforcement

**PR 9:** Transaction provenance (~2h)
**PR 10:** Bank/export (~2h)
**PR 11:** Static guard (~1.5h)
**PR 12:** UI propagation (~2h)


---

## 6. Fastest Risk-Reduction Order

If time is limited, this order maximizes correctness improvement per hour:

1. **PR 1** (DAO fix) — 2h, fixes critical rate poisoning bug
2. **PR 7 partial** (remove `?: effectiveAmount` in dashboard) — 1h, fixes silent mixed-currency
3. **PR 2** (ConversionOutcome types) — 3h, enables all downstream work
4. **PR 5** (HomeCurrencyResolution) — 1.5h, fixes 22 silent EUR fallbacks
5. **PR 8 partial** (budget ON_TRACK fix) — 1h, fixes misleading budget status
6. **PR 3** (MoneyNormalizationEngine) — 4h, enables canonical normalization
7. **PR 4 + PR 6** (builder + repository) — 5h, completes type-safe pipeline
8. **PR 11** (static guard) — 1.5h, prevents regression

**Total estimated effort:** ~21 hours for full implementation.

---

## 7. Files Requiring Changes

### Core additions (new files)
```
domain/core/money/RateBasis.kt
domain/core/money/ConversionOutcome.kt
domain/core/money/ConversionPath.kt
domain/core/money/ConversionFailureType.kt
domain/core/money/StaleRatePolicy.kt
domain/core/money/MoneyBucketInput.kt
domain/core/money/BucketDatePolicy.kt
domain/core/money/TransactionTypeFilter.kt
domain/core/money/MoneyNormalizationEngine.kt
domain/core/money/NormalizationResult.kt
domain/core/money/MoneyAggregateMetadata.kt
domain/core/money/ConversionQuality.kt
domain/currency/HomeCurrencyResolution.kt
domain/budget/BudgetReliability.kt
ui/model/CurrencyQualityUi.kt
scripts/verify_money_boundaries.py
```

### Core modifications
```
data/database/dao/ExchangeRateDao.kt          — ORDER BY fix + deprecation
data/currency/ExchangeRateStoreAdapter.kt     — add getLatestRateForPair()
domain/currency/ExchangeRateContracts.kt      — add getLatestRateForPair()
domain/currency/CurrencyConverter.kt          — add convertOutcome()
domain/core/money/MoneyAggregate.kt           — add rateBasis, metadata fields
domain/core/money/MoneyAggregateBuilder.kt    — add typed overload
domain/analytics/AnalyticsCurrencyNormalizer.kt — delegate to engine
domain/currency/CurrencySettingsRepository.kt — add resolveHomeCurrency()
```

### Pipeline migrations
```
data/repository/MultiCurrencyRepository.kt
data/repository/BudgetRepository.kt
domain/budget/BudgetModels.kt                 — add UNKNOWN to BudgetHealthStatus
domain/budget/BudgetForecastingEngine.kt
domain/cashflow/CashFlowCalculator.kt
domain/forecasting/FinancialStressForecastEngine.kt
domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
data/repository/AnalyticsRepository.kt
```

### Secondary migrations (22 EUR fallback locations across 16 files)
```
domain/analytics/AdvancedAnalyticsEngine.kt
domain/health/FinancialHealthCalculator.kt
domain/health/FinancialHealthScoreV2.kt
domain/savings/SmartSavingsEngine.kt
domain/analytics/SpendingPersonalityClassifier.kt
domain/alerts/AnomalyAlertOrchestrator.kt
domain/carbon/CarbonFootprintCalculator.kt
domain/usecase/expense/ExpenseUseCases.kt
domain/lifestyle/LifestyleInflationDetector.kt
domain/business/BusinessExpenseReportGenerator.kt
data/repository/WarrantyTrackerRepository.kt
domain/investment/InvestmentTracker.kt
domain/forecasting/HistoricalSpendingDistribution.kt
domain/receipt/ReceiptLifecycleCoordinator.kt
domain/subscription/SubscriptionManagerEngine.kt
domain/usecase/savings/MonthlySavingsSweepUseCase.kt
data/importer/CsvExpenseImporter.kt
ui/components/BentoCard.kt
```

---

## 8. Test Strategy

### Existing coverage to leverage
- `MultiCurrencyRepositoryTest.kt` — 261 currency matches
- `CanonicalMultiCurrencyFixture.kt` — 155 matches, reusable
- `MoneyAggregateConversionScenarioTest.kt` — 94 matches
- `CurrencyConversionTest.kt` — 91 matches
- `MoneyAggregateBuilderTest.kt` — 66 matches
- `AnalyticsCurrencyNormalizerTest.kt` — 45 matches
- `StaleRateCurrencyConversionGoldenTest.kt` — 30 matches

### New tests needed
```
ExchangeRateDaoTest — latest_rate_uses_validDate_not_lastUpdated
ExchangeRateDaoTest — historical_backfill_does_not_poison_latest_rate
ConversionOutcomeTest — all outcome variants
MoneyNormalizationEngineTest — aggregate with each RateBasis
MoneyNormalizationEngineTest — partial exclusion behavior
BudgetRepositoryTest — conversion_failure_status_UNKNOWN
ComputeDashboardWidgetsTest — spending_trend_no_raw_fallback
HomeCurrencyResolutionTest — failure_does_not_default_EUR
MoneyGuardTest — static analysis assertions
```

---

## 9. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| DAO ORDER BY change breaks existing rate lookups | LOW | HIGH | Migration backfills validDate; test with existing data |
| Budget UNKNOWN status confuses users | MEDIUM | MEDIUM | UI shows "Unable to calculate" with retry action |
| Removing ?: fallback shows partial warnings | MEDIUM | LOW | Only warn when >5% transactions excluded |
| MoneyNormalizationEngine doesn't match normalizer | LOW | HIGH | Parity tests against AnalyticsCurrencyNormalizer |
| 295 test files need updates | LOW | MEDIUM | Most use fixtures that already produce correct data |

---

## 10. Validation Checklist (Current Score: 4/14)

| # | Requirement | Status |
|---|-------------|--------|
| 1 | Every aggregate API returns MoneyAggregate | ~60% done |
| 2 | Every aggregate declares RateBasis | ❌ 0% |
| 3 | ExchangeRateDao latest uses validDate | ❌ uses lastUpdated |
| 4 | Historical reports use transaction-date conversion | ✅ via AnalyticsCurrencyNormalizer |
| 5 | No fallback to raw foreign amount | ❌ 13+ locations |
| 6 | Home currency failure doesn't default EUR | ❌ 22 locations |
| 7 | Budget failure doesn't produce ON_TRACK | ❌ forces ON_TRACK |
| 8 | Drilldowns use same filter and rate basis | ✅ via TotalsAggregationEngine |
| 9 | Forecast patterns normalized before synthesis | ❌ raw Double |
| 10 | Cashflow returns normalized line items | ⚠️ partial |
| 11 | Bank/import/export preserve provenance | ⚠️ partial |
| 12 | Accounting export handles mixed currencies | ✅ rejects multi-currency |
| 13 | Currency quality visible in UI | ❌ no CurrencyQualityUi |
| 14 | Static guard blocks new violations | ❌ no guard |

---

## 11. Recommended Starting Point

**Start with PR 1 (DAO fix)** — highest-risk bug, simplest fix. One query change + one migration + two tests.

Then **PR 2 (types)** — purely additive, unblocks everything else.

Then **PR 7 partial (dashboard ?: fix)** — removes the most visible user-facing bug with a 5-line change.
