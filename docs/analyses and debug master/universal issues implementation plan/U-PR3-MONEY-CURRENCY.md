# U-PR3: Money/Currency Universal Issues Implementation Plan

**PR:** U-PR3  
**Branch:** `master-refactor` at commit `f49188e2`  
**Issues:** U-MONEY-01 (P1), U-MONEY-02 (P1), U-MONEY-03 (P2)  
**Affected Pipelines:** 5 (Forecast/Weather), 6 (Stress Forecast), 12 (Cash Flow)  
**Date:** 2026-05-31  

---

## 1. Executive Summary

Three universal currency-safety issues span the forecast, stress-forecast, and cash-flow pipelines. They share a common root cause: the multi-currency refactoring (phases 1–7) normalized the *data layer* and *analytics layer* but left three domain engines performing raw arithmetic on amounts that may be denominated in different currencies.

| Issue | Severity | Root Cause | Impact |
|-------|----------|-----------|--------|
| U-MONEY-01 | P1 | Raw `sumOf { it.averageAmount }` / `sumOf { it.expectedAmount }` across mixed currencies | Incorrect forecast totals for multi-currency users |
| U-MONEY-02 | P1 | `FinancialWeather` / `StressForecastResult` / `DailyCashFlow` DTOs drop `isPartial`/`qualityWarnings`/`excludedCount` | UI cannot warn user about degraded data |
| U-MONEY-03 | P2 | Multiple paths silently default to `"EUR"` on home-currency resolution failure | Wrong-currency arithmetic for non-EUR users when settings fail |

---

## 2. Issue Verification (Code Evidence)

### U-MONEY-01: Mixed-currency arithmetic without conversion

**Site 1 — SynthesisEngine.kt:191–195** (confirmed occurrences summed raw):
```kotlin
confirmedOccurrences
    .filter { it.dueDate >= startOfToday && it.dueDate < endOfMonthExclusive }
    .sumOf { it.expectedAmount }  // ← no currency check, no conversion
```
`ConfirmedOccurrence.expectedCurrency` is ignored. If a user has a EUR Netflix and a USD AWS bill, amounts are summed as if same currency.

**Site 2 — SynthesisEngine.kt:199–201** (recurring patterns summed raw):
```kotlin
recurringPatterns.filter { ... }.sumOf { it.averageAmount }
```
`RecurringPattern.currency` is ignored. The `ForecastInputAssembler.mergeRecurringPatterns()` has a TODO P2-20 acknowledging this.

**Site 3 — FinancialStressForecastEngine.kt:401–420** (`expandDetectedPatterns`):
```kotlin
private fun expandDetectedPatterns(...): Double {
    for (pattern in patterns) {
        while (nextDate in startDate..endDate) {
            total += pattern.averageAmount  // ← no conversion to displayCurrency
        }
    }
    return total
}
```
Manual patterns are converted (line 365–370 via `currencyConverter.convert`), but detected patterns bypass conversion entirely.

**Site 4 — SynthesisEngine.kt:228–235** (likely recurring bills):
```kotlin
detectedPatterns.filter { ... }.sumOf { it.averageAmount }
```
Same issue as Site 2 for the "likely" tier.

### U-MONEY-02: MoneyAggregate quality/warnings dropped by consumers

**Site 1 — FinancialWeatherRepository.kt:89–107** (mapping to `FinancialWeather`):
```kotlin
FinancialWeather(
    state = narrative.state,
    // ... maps totalCommitted, totalLikely, etc.
    // MISSING: forecast.isPartial, forecast.qualityWarnings, forecast.excludedCount
)
```
`FinancialWeather` data class has no `isPartial`/`qualityWarnings` fields.

**Site 2 — StressForecastResult** (line 432):
The `StressForecastResult` carries `displayCurrency` but no `isPartial` or quality metadata from the underlying `AnalyticsCurrencyNormalizer.normalizeExpenses()` call (line 83–89 of `FinancialStressForecastEngine`). The `normExpenses.warnings` and `normExpenses.excludedCount` are silently discarded.

**Site 3 — DailyCashFlow** already carries `isPartial` and `failedConversionCount` (correctly implemented in CashFlowCalculator). However, the *recurring section* quality from `expandPatternDueDates` fallback is only partially surfaced via `occurrenceGenerationFailed` — it does NOT flag that the recurring amounts were summed without conversion for detected patterns.

### U-MONEY-03: Silent EUR fallback on homeCurrency resolution failure

**Site 1 — MultiCurrencyRepository.kt:79** (companion constant):
```kotlin
const val DEFAULT_HOME_CURRENCY = "EUR"
```
Used as default parameter in deprecated methods (lines 101, 163, etc.).

**Site 2 — InsightsEngine.kt:169**:
```kotlin
displayCurrency: String = "EUR",
```
Default parameter silently uses EUR if caller doesn't pass currency.

**Site 3 — MonteCarloSpendingSimulator.kt:74, 172, 218**:
```kotlin
displayCurrency: String = "EUR",
```
Three methods default to EUR.

**Site 4 — HistoricalSpendingDistribution.kt:51, 67**:
```kotlin
suspend fun computeDistribution(homeCurrency: String = "EUR"): DistributionFit?
```
And line 67 checks `if (homeCurrency == "EUR")` to decide whether to re-resolve.

**Site 5 — SharedExpenseBudgetOffsetEngine.kt:79**:
```kotlin
"EUR"  // fallback when home currency resolution fails
```

**Site 6 — WarrantyTrackerRepository.kt:260, 340**:
```kotlin
val homeCurrency = homeResolution.currencyOrNull?.code ?: "EUR"
```

---

## 3. Affected Pipelines & Data Flow

```
Pipeline 5 (Forecast/Weather):
  ForecastInputAssembler.assemble()
    → mergeRecurringPatterns() [patterns retain original currency — TODO P2-20]
    → SynthesisEngine.synthesize()
      → sumOf { it.expectedAmount }  ← U-MONEY-01
      → sumOf { it.averageAmount }   ← U-MONEY-01
    → FinancialWeatherRepository
      → FinancialWeather DTO          ← U-MONEY-02 (quality dropped)

Pipeline 6 (Stress Forecast):
  FinancialStressForecastEngine.computeStressForecast()
    → calculateRecurringOutflows()
      → expandDetectedPatterns()       ← U-MONEY-01 (no conversion)
    → StressForecastResult             ← U-MONEY-02 (quality dropped)

Pipeline 12 (Cash Flow):
  CashFlowCalculator.calculateDailyCashFlow()
    → recurring conversion: ✅ ALREADY FIXED (CURR-70F-12)
    → detected patterns via ad-hoc expansion: partially fixed
    → DailyCashFlow DTO: ✅ carries isPartial/failedConversionCount
```

---

## 4. Root Cause Analysis

The multi-currency refactoring (phases 1–7, completed May 2026) established:
1. `MoneyAggregate` as the canonical type-safe aggregate
2. `AnalyticsCurrencyNormalizer` for batch normalization
3. `HomeCurrencyResolution` sealed interface for typed resolution
4. `CurrencyConverter` with `convertAsOf` / `convertOutcome` APIs

However, the **domain engines** (SynthesisEngine, FinancialStressForecastEngine) were not fully migrated:
- They receive `RecurringPattern` and `ConfirmedOccurrence` objects that carry a `currency` field but perform raw `sumOf` without checking or converting.
- The `ForecastInputAssembler.mergeRecurringPatterns()` explicitly documents this gap (TODO P2-20).
- The `FinancialWeather` DTO was designed before quality metadata existed and was never updated.
- EUR fallbacks predate the `HomeCurrencyResolution` sealed interface and were never cleaned up.

---

## 5. Fix Strategy

### U-MONEY-01: Convert before summing

**Approach A (chosen): Convert at the engine boundary.**

Inject `CurrencyConverter` into `SynthesisEngine` and convert each `RecurringPattern.averageAmount` / `ConfirmedOccurrence.expectedAmount` to the forecast's `displayCurrency` before summing. Track conversion failures and increment `excludedRecurringCount` in `ForecastDataQuality`.

For `FinancialStressForecastEngine.expandDetectedPatterns()`: convert each pattern's `averageAmount` to `displayCurrency` before accumulating, matching the existing manual-pattern path.

**Why not Approach B (normalize in assembler)?** The assembler already normalizes *actual expenses* and *planned expenses*. Normalizing recurring patterns there would require making `mergeRecurringPatterns` a suspend function (it currently isn't) and would change the `RecurringPattern` model semantics (amount would no longer match the original currency). The engine-boundary approach is less invasive and keeps patterns in their original currency for display purposes.

### U-MONEY-02: Surface quality metadata on consumer DTOs

Add `isPartial`, `qualityWarnings`, and `excludedCount` fields (with neutral defaults) to:
- `FinancialWeather`
- `StressForecastResult`

Map them from the underlying `FinancialForecast` / normalizer results at the repository boundary.

### U-MONEY-03: Replace silent EUR fallbacks with fail-closed resolution

Replace `= "EUR"` default parameters and `?: "EUR"` fallbacks with:
1. **Throw** (for domain engines that cannot proceed without a currency) — mirrors `CashFlowCalculator` and `BudgetRepository` patterns.
2. **Return unavailable/partial** (for UI-facing methods that can degrade gracefully).

---

## 6. Detailed Changes

### 6.1 SynthesisEngine.kt

| Change | Lines | Description |
|--------|-------|-------------|
| Add `CurrencyConverter` constructor param | ~65 | Inject via Hilt |
| Add `displayCurrency: String` param to `synthesizeInternal` | ~162 | Passed from `ForecastInput.displayCurrency` |
| Convert `confirmedOccurrences` before sum | ~191 | `currencyConverter.convert(it.expectedAmount, it.expectedCurrency, displayCurrency)` |
| Convert `recurringPatterns` before sum (committed) | ~199 | Same pattern |
| Convert `recurringPatterns` before sum (likely) | ~228 | Same pattern |
| Convert `recurringPatterns` in `monthlyRecurringTotal` | ~243 | Same pattern |
| Track conversion failures | new | Accumulate count, log warnings |
| Convert `recurringOnDay` in block-party | ~491 | Convert each pattern's amount |

**Conversion failure policy:** When conversion fails for a pattern, exclude it from the sum, increment a failure counter, and log a warning. The existing `ForecastDataQuality.excludedRecurringCount` field will carry this count.

### 6.2 FinancialStressForecastEngine.kt

| Change | Lines | Description |
|--------|-------|-------------|
| Convert in `expandDetectedPatterns` | ~401–420 | Add `displayCurrency` param, convert each `pattern.averageAmount` |
| Surface normalizer quality on `StressForecastResult` | ~130 | Add `isPartial`, `qualityWarnings` fields |
| Map `normExpenses.warnings` / `normDeposits.warnings` | ~83–89 | Capture and forward |

### 6.3 ForecastInputAssembler.kt

| Change | Lines | Description |
|--------|-------|-------------|
| Remove TODO P2-20 comment | ~223 | Replaced by engine-boundary conversion |
| Add `excludedRecurringCount` tracking | ~550 | When recurring patterns fail conversion in engine |

### 6.4 FinancialWeatherRepository.kt

| Change | Lines | Description |
|--------|-------|-------------|
| Map `forecast.isPartial` → `FinancialWeather.isPartial` | ~89 | New field |
| Map `forecast.qualityWarnings` → `FinancialWeather.qualityWarnings` | ~89 | New field |
| Map `forecast.excludedCount` → `FinancialWeather.excludedCount` | ~89 | New field |

### 6.5 FinancialWeather.kt (model)

| Change | Lines | Description |
|--------|-------|-------------|
| Add `isPartial: Boolean = false` | new | Additive, backward-compatible |
| Add `qualityWarnings: List<String> = emptyList()` | new | Additive |
| Add `excludedCount: Int = 0` | new | Additive |

### 6.6 StressForecastResult (model in FinancialStressForecastEngine.kt)

| Change | Lines | Description |
|--------|-------|-------------|
| Add `isPartial: Boolean = false` | ~432 | Additive |
| Add `qualityWarnings: List<String> = emptyList()` | ~432 | Additive |
| Add `excludedCount: Int = 0` | ~432 | Additive |

### 6.7 EUR Fallback Elimination

| File | Change |
|------|--------|
| `InsightsEngine.kt:169` | Remove `= "EUR"` default; make param required or resolve from settings |
| `MonteCarloSpendingSimulator.kt:74,172,218` | Remove `= "EUR"` defaults; require explicit currency |
| `HistoricalSpendingDistribution.kt:51` | Remove `= "EUR"` default; resolve from `CurrencySettingsRepository` |
| `SharedExpenseBudgetOffsetEngine.kt:79` | Replace `"EUR"` with `throw IllegalStateException(...)` |
| `WarrantyTrackerRepository.kt:260,340` | Replace `?: "EUR"` with typed resolution or throw |
| `MultiCurrencyRepository.kt:79` | Keep `DEFAULT_HOME_CURRENCY` constant but mark `@Deprecated` with `level = ERROR`; ensure no new usages |

---

## 7. Implementation Order

```
Phase 1: Models (no logic change, additive only)
  1. Add fields to FinancialWeather
  2. Add fields to StressForecastResult

Phase 2: U-MONEY-01 core fix
  3. Inject CurrencyConverter into SynthesisEngine
  4. Convert confirmedOccurrences before summing
  5. Convert recurringPatterns before summing (committed + likely + monthly)
  6. Convert in block-party recurringOnDay
  7. Convert in FinancialStressForecastEngine.expandDetectedPatterns

Phase 3: U-MONEY-02 quality propagation
  8. Map forecast quality → FinancialWeather in FinancialWeatherRepository
  9. Map normalizer quality → StressForecastResult in FinancialStressForecastEngine

Phase 4: U-MONEY-03 EUR fallback elimination
  10. InsightsEngine — require explicit currency
  11. MonteCarloSpendingSimulator — require explicit currency
  12. HistoricalSpendingDistribution — resolve from settings
  13. SharedExpenseBudgetOffsetEngine — fail-closed
  14. WarrantyTrackerRepository — fail-closed
  15. Deprecate MultiCurrencyRepository.DEFAULT_HOME_CURRENCY
```

---

## 8. API Surface Changes

### New constructor parameter (SynthesisEngine)
```kotlin
@Singleton
class SynthesisEngine @Inject constructor(
    private val timeProvider: TimeProvider,
    private val recurringOccurrenceDao: RecurringOccurrenceDao? = null,
    private val currencyConverter: CurrencyConverter  // NEW
)
```

### New fields on FinancialWeather
```kotlin
data class FinancialWeather(
    // ... existing fields ...
    val isPartial: Boolean = false,
    val qualityWarnings: List<String> = emptyList(),
    val excludedCount: Int = 0
)
```

### New fields on StressForecastResult
```kotlin
data class StressForecastResult(
    // ... existing fields ...
    val isPartial: Boolean = false,
    val qualityWarnings: List<String> = emptyList(),
    val excludedCount: Int = 0
)
```

### Removed default parameters (breaking for callers using positional defaults)
```kotlin
// Before:
fun generateInsights(displayCurrency: String = "EUR", ...)
// After:
fun generateInsights(displayCurrency: String, ...)
```

---

## 9. Hilt/DI Impact

| Module | Change |
|--------|--------|
| `ForecastModule` (or equivalent) | `CurrencyConverter` already bound; `SynthesisEngine` gains it as a new constructor param — Hilt auto-resolves |
| No new modules needed | All dependencies already exist in the graph |

---

## 10. Migration & Backward Compatibility

- All new DTO fields have neutral defaults (`false`, `emptyList()`, `0`) — **no breaking change** for existing consumers.
- Removing `= "EUR"` default parameters is a **source-breaking change** for callers that relied on positional defaults. Mitigation: audit all call sites and pass explicit currency. The affected callers are internal (not public API).
- `SynthesisEngine` constructor change requires updating test construction sites that instantiate it directly. Mitigation: pass a `FakeCurrencyConverter` in tests.
- No database migration needed.
- No schema change.

---

## 11. Testing Strategy

### Unit Tests (new)

| Test | Validates |
|------|-----------|
| `SynthesisEngine_mixedCurrencyOccurrences_convertsBeforeSumming` | U-MONEY-01: EUR+USD occurrences produce correct home-currency total |
| `SynthesisEngine_conversionFailure_excludesAndTracksCount` | U-MONEY-01: failed conversion excluded, count incremented |
| `StressForecast_expandDetectedPatterns_convertsToDisplayCurrency` | U-MONEY-01: detected patterns converted |
| `FinancialWeather_surfacesPartialFlag_whenForecastIsPartial` | U-MONEY-02: quality propagated |
| `StressForecastResult_surfacesQualityWarnings` | U-MONEY-02: warnings propagated |
| `InsightsEngine_throwsOnMissingCurrency` | U-MONEY-03: no silent EUR |
| `SharedExpenseBudgetOffsetEngine_throwsOnResolutionFailure` | U-MONEY-03: no silent EUR |

### Existing Tests (update)

| Test File | Change |
|-----------|--------|
| `SynthesisEngineTest.kt` | Add `currencyConverter` to constructor; update all instantiation sites |
| `FinancialStressForecastEngineTest.kt` | Verify detected patterns are converted |
| `ForecastInputAssemblerTest.kt` | Verify `excludedRecurringCount` populated |
| `FinancialWeatherRepositoryTest.kt` | Assert `isPartial` mapped from forecast |

### Integration Tests

| Test | Validates |
|------|-----------|
| `ForecastPipeline_multiCurrency_endToEnd` | Full pipeline with EUR+USD patterns produces correct totals |
| `StressForecast_multiCurrency_endToEnd` | Stress forecast with mixed-currency patterns |

---

## 12. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Conversion failures increase forecast exclusions | Medium | Low | Graceful degradation already designed; UI shows partial indicator |
| Performance regression from per-pattern conversion | Low | Low | Patterns are few (typically <20); conversion is in-memory lookup |
| Breaking callers of removed EUR defaults | Medium | Medium | Compile-time error; fix all call sites in same PR |
| SynthesisEngine test breakage | High | Low | Mechanical fix: add fake converter to all test constructors |
| CurrencyConverter returns null for exotic pairs | Low | Medium | Exclude pattern from sum, track in quality metadata |

---

## 13. Acceptance Criteria

- [ ] **U-MONEY-01:** `SynthesisEngine` never sums amounts across different currencies without conversion. All `sumOf { it.averageAmount }` and `sumOf { it.expectedAmount }` paths check currency and convert.
- [ ] **U-MONEY-01:** `FinancialStressForecastEngine.expandDetectedPatterns` converts each pattern to `displayCurrency` before accumulating.
- [ ] **U-MONEY-01:** Conversion failures are tracked in `ForecastDataQuality.excludedRecurringCount` and surfaced as warnings.
- [ ] **U-MONEY-02:** `FinancialWeather` carries `isPartial`, `qualityWarnings`, `excludedCount` from the underlying forecast.
- [ ] **U-MONEY-02:** `StressForecastResult` carries `isPartial`, `qualityWarnings`, `excludedCount` from the normalizer.
- [ ] **U-MONEY-03:** No code path silently defaults to `"EUR"` on home-currency resolution failure. All paths either throw (fail-closed) or return an explicit unavailable/partial result.
- [ ] **U-MONEY-03:** `MultiCurrencyRepository.DEFAULT_HOME_CURRENCY` is `@Deprecated(level = ERROR)` with no new usages.
- [ ] All existing tests pass (with updated constructor sites).
- [ ] New unit tests cover each fix site.
- [ ] Build compiles cleanly with no new warnings.

---

## 14. Estimated Effort

| Phase | Files | Effort |
|-------|-------|--------|
| Phase 1: Models | 2 | 15 min |
| Phase 2: U-MONEY-01 | 3 | 2–3 hours |
| Phase 3: U-MONEY-02 | 3 | 30 min |
| Phase 4: U-MONEY-03 | 6 | 1–2 hours |
| Test updates | 8–10 | 2–3 hours |
| **Total** | **~15 files** | **6–8 hours** |

---

## Appendix A: Conversion Pattern (Reference Implementation)

```kotlin
// Pattern for converting recurring amounts in SynthesisEngine:
private fun convertRecurringAmount(
    amount: Double,
    fromCurrency: String,
    toCurrency: String
): Double? {
    if (fromCurrency.equals(toCurrency, ignoreCase = true)) return amount
    return currencyConverter.convert(amount, fromCurrency, toCurrency)?.convertedAmount
}

// Usage in committed bills:
var conversionFailures = 0
val committedUpcomingBills = confirmedOccurrences
    .filter { it.dueDate >= startOfToday && it.dueDate < endOfMonthExclusive }
    .mapNotNull { occ ->
        convertRecurringAmount(occ.expectedAmount, occ.expectedCurrency, displayCurrency)
            ?: run { conversionFailures++; null }
    }
    .sum()
```

## Appendix B: EUR Fallback Audit (Complete List)

| File | Line | Current Code | Fix |
|------|------|-------------|-----|
| `MultiCurrencyRepository.kt` | 79 | `const val DEFAULT_HOME_CURRENCY = "EUR"` | `@Deprecated(level = ERROR)` |
| `MultiCurrencyRepository.kt` | 101, 163 | `homeCurrency: String = DEFAULT_HOME_CURRENCY` | Already deprecated methods — no change needed |
| `InsightsEngine.kt` | 169 | `displayCurrency: String = "EUR"` | Remove default |
| `MonteCarloSpendingSimulator.kt` | 74, 172, 218 | `displayCurrency: String = "EUR"` | Remove default |
| `HistoricalSpendingDistribution.kt` | 51 | `homeCurrency: String = "EUR"` | Resolve from `CurrencySettingsRepository` |
| `SharedExpenseBudgetOffsetEngine.kt` | 79 | `"EUR"` | Throw `IllegalStateException` |
| `WarrantyTrackerRepository.kt` | 260, 340 | `?: "EUR"` | Throw or return partial |
| `NotificationProcessingPipeline.kt` | 775, 777 | `?: "EUR"` | Throw or use typed resolution |
| `BankStatementParser.kt` | 116, 120 | `"EUR"` | Log warning + use typed resolution |
| `ReceiptParser.kt` | 209 | `homeCurrency: String = "EUR"` | Remove default |
| `SmartSavingsEngine.kt` | 84, 101 | `homeCurrency: String = "EUR"` | Remove default |
| `DashboardPrimitives.kt` | 22 | `val currency: String = "EUR"` | Keep default (UI placeholder, always overwritten by callers) |
| `CurrencyNormalizer.kt` | 21 | `return "EUR"` | Return null or throw (utility function) |

**Scope for this PR:** Fix sites in pipelines 5, 6, 12 (rows 1–8). Other sites are tracked separately.
