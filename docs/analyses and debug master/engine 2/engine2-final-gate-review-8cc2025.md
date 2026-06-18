# Final Gate Review — Engine 2 after commits `3b9490e` + `8cc2025`

Commits reviewed:

- `3b9490e14367846aa8274e6c1a2dae6e2dcc9e53`  
  https://github.com/panospao7/Cost-agregator/commit/3b9490e14367846aa8274e6c1a2dae6e2dcc9e53
- `8cc202512ffd19b2a3cab2c5fc9614a8811354ce`  
  https://github.com/panospao7/Cost-agregator/commit/8cc202512ffd19b2a3cab2c5fc9614a8811354ce

Mode: **static review only**.  
I did **not** run Gradle, tests, lint, KSP, Hilt, Room, or connected tests.

---

# 1. Verdict

**YELLOW / GREEN-candidate**

Engine 2 is now substantially improved. The main production analytics path appears to be correctly moved toward `NormalizedAnalyticsInput`.

I do **not** see a clear blocking regression in the major affected pipelines:

```text
Analytics screen
Insights
Advanced analytics
Budget-vs-actual
Location analytics
Currency/data-quality propagation
Dashboard summary consumers
```

But I would not call it absolute **GREEN** until:

1. full validation passes
2. a few remaining P2/P3 hardening items are addressed or explicitly accepted
3. the documented deferrals remain honest

Current final classification:

```text
Core analytics normalized path: GREEN-candidate
Insights period correctness: GREEN-candidate
Advanced analytics: GREEN-candidate
Budget-vs-actual: YELLOW/GREEN
Location analytics: YELLOW/GREEN
Dashboard/summary aggregate: GREEN-candidate
Historical category identity: DEFERRED
Spending personality: YELLOW / documented caveats
Overall Engine 2: YELLOW until validation, GREEN-candidate after validation
```

---

# 2. Validation status

I did not run:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

Commit `8cc2025` says:

```text
All 37 targeted tests passing.
```

Source: commit message on GitHub.  
https://github.com/panospao7/Cost-agregator/commit/8cc202512ffd19b2a3cab2c5fc9614a8811354ce

The committed Engine 2 final status doc says:

```text
Status: YELLOW → GREEN CANDIDATE
validation pass required for full GREEN
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/docs/analyses%20and%20debug%20master/engine%202/engine2-final-status.md

I agree with that status.

---

# 3. Fix inventory

## Commit `3b9490e`

Major Engine 2 fixes:

```text
PR1 — Insights period correctness
PR2 — Advanced analytics normalized input adoption
PR3 — Budget-vs-actual FX/data-quality work
PR4 — Data-quality/provenance metadata
PR5 — Location normalized API initial adoption
PR6 — UI/test compilation fixes
PR7/PR8 — deprecated/raw API guard direction
```

## Commit `8cc2025`

Follow-up fixes:

```text
PR1 — Populate SpendingSummary.aggregate
PR2 — Analytics UI money null-safety
PR3 — Area/travel normalized API migration
PR4 — Stronger period behavior tests
PR5 — Remove includeDepositsForBehavior
PR6 — Spending personality guard/documentation
PR7 — Historical category identity plan/defer
PR8 — Docs/tracker/guardrail pass
```

Changed files include:

```text
AnalyticsRepository.kt
AnalyticsInputAssembler.kt
SpendingPersonalityClassifier.kt
AreaSpendingEngine.kt
LocatedMoneyExpense.kt
TravelDetectionEngine.kt
AnalyticsViewModel.kt
AnalyticsScreen.kt
DeprecatedApiArchitectureGuardTest.kt
AnalyticsRepositoryAggregateTest.kt
AnalyticsStateMoneySafetyTest.kt
AnalyticsViewModelInsightsTest.kt
BudgetVsActualFxBasisTest.kt
AreaSpendingEngineNormalizedTest.kt
TravelDetectionEngineNormalizedTest.kt
HISTORICAL_CATEGORY_IDENTITY_PLAN.md
engine2-final-status.md
```

Schema changed?

```text
No Engine 2 schema/migration change observed.
Historical category identity is deferred instead of schema-fixed.
```

Hilt/DI changed?

```text
No major Hilt risk found statically.
```

Money/currency/time changed?

```text
Yes:
- normalized analytics input now more widely adopted
- budget conversion uses convertAsOf period-end path
- aggregates/provenance/warnings improved
- area/travel location analytics now use normalized models
```

---

# 4. Issue reconciliation

## E2-NOW-001 / A07 / A08 / A13 — insights period correctness

Previous status: partial.  
Current status: **mostly fixed / GREEN-candidate**

### Evidence

`AnalyticsViewModel` now calls:

```kotlin
insightsEngine.generateInsights(
    currentInput = currentInput,
    historicalInput = allInput,
    categories = analyticsCategories,
    conversionWarnings = conversionWarnings
)
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

Tests now include:

```text
normalized generateInsights overload is called instead of legacy
weekInsightsUseSelectedWeekNotCurrentMonth
yearInsightsUseSelectedYearNotCurrentMonth
allInsightsDoNotCollapseToCurrentMonth
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModelInsightsTest.kt

### Verdict

**GREEN-candidate**

The production ViewModel path is now period-aware and normalized. The behavior tests are stronger than the previous overload-only test.

---

## E2-NOW-002 / E2-NOW-003 — advanced analytics self-fetching / period mismatch

Previous status: partial.  
Current status: **mostly fixed / GREEN-candidate**

### Evidence

`AnalyticsViewModel` calls normalized overloads:

```kotlin
advancedAnalyticsEngine.getSpendingPatterns(currentInput)
advancedAnalyticsEngine.getStatisticalInsights(currentInput)
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

The old `advancedAnalyticsEngine.getPeriodRange(...)` ViewModel mismatch risk appears removed from the main path.

### Verdict

**GREEN-candidate**

Remaining caveat: there is still no single DB snapshot/transaction across all separately fetched inputs, but that is a broader consistency limitation, not a new regression.

---

## E2-NOW-005 / A05 — `SpendingSummary.aggregate` was null

Previous status: open.  
Current status: **fixed / GREEN-candidate**

### Evidence

`AnalyticsRepository.getSpendingSummary()` now builds:

```kotlin
val aggregate = buildMoneyAggregate(currentNormalization, currentExpenses, homeCurrency)
```

and emits:

```kotlin
aggregate = aggregate
isPartial = aggregate.isPartial
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

Tests added:

```text
spendingSummary_populatesAggregate
spendingSummary_aggregateDisplayAmountEqualsTotalSpent
spendingSummary_aggregateTotalEqualsDailyHistorySumWithinTolerance
spendingSummary_partialConversionAggregateContainsFailure
spendingSummary_emptyPeriodAggregateIsZeroHomeCurrency
spendingSummary_sourceBucketsGroupByOriginalCurrency
spendingSummary_rateBasisIsTransactionDate
spendingSummary_conversionFailuresGroupedByCurrencyAndReason
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/data/repository/AnalyticsRepositoryAggregateTest.kt

### Verdict

**GREEN-candidate**

### Minor caveat

`buildMoneyAggregate()` uses:

```kotlin
CurrencyCode.parseOr(currency, CurrencyCode.EUR)
```

for source buckets/failure amounts. For syntactically invalid legacy currency strings, that can still collapse diagnostic currency to EUR. This is not a primary regression because invalid rows should usually be excluded earlier, but it is a follow-up hardening item.

Recommended follow-up:

```text
Do not parseOr invalid source currency to EUR inside aggregate failures.
Use UNKNOWN/invalid metadata if available, or keep raw code in warning.
```

---

## E2-NOW-010 — analytics UI money null-safety

Previous status: open.  
Current status: **mostly fixed / GREEN-candidate**

### Evidence

`AnalyticsState` now has:

```kotlin
moneyCurrentTotalOrNull
```

which returns null when `homeCurrency` is null/blank/invalid.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

Tests verify:

```text
null homeCurrency -> null
blank homeCurrency -> null
invalid homeCurrency -> null
valid EUR -> MoneyAmount
loading state old helper does not throw
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsStateMoneySafetyTest.kt

### Verdict

**GREEN-candidate**

### Caveat

The deprecated property:

```kotlin
moneyCurrentTotal
```

still falls back to:

```kotlin
MoneyAmount(currentTotal, CurrencyCode.EUR)
```

This prevents crashes but is technically a silent EUR fallback if any production code still uses it.

Recommended follow-up:

```text
Add guard/no-production-use check for AnalyticsState.moneyCurrentTotal,
or migrate UI to moneyCurrentTotalOrNull only.
```

Not a blocker if current UI does not rely on the deprecated helper.

---

## A14 / E2-NOW-009 — location analytics raw APIs

Previous status: partial.  
Current status: **mostly fixed / GREEN-candidate**

### Evidence

`AnalyticsViewModel` now builds `LocatedMoneyExpense` from normalized input and calls:

```kotlin
locationInsightsEngine.computeNormalized(locatedMoneyExpenses)
areaSpendingEngine.computeNormalized(locatedMoneyExpenses, homeCurrency, currencyConverter)
travelDetectionEngine.computeNormalized(locatedMoneyExpenses, homeCurrency, currencyConverter)
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

`AreaSpendingEngine.computeNormalized(...)` returns `NormalizedAreaSpending` with `MoneyAggregate`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt

`TravelDetectionEngine.computeNormalized(...)` returns `NormalizedTravelInsight` with aggregate-backed home/local/travel totals.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt

Tests added:

- `AreaSpendingEngineNormalizedTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/domain/location/AreaSpendingEngineNormalizedTest.kt
- `TravelDetectionEngineNormalizedTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/domain/location/TravelDetectionEngineNormalizedTest.kt

### Verdict

**GREEN-candidate**

### Minor caveat

`computeNormalized()` accepts `ConversionStatus.HOME_CURRENCY` / `CONVERTED`, then builds buckets using:

```kotlin
normalizedAmount ?: originalAmount
```

If a caller incorrectly marks an expense as converted but leaves `normalizedAmount = null`, the engine can fall back to raw original amount as if normalized.

Current ViewModel construction makes this unlikely, but the normalized engine contract should enforce it.

Recommended follow-up test/fix:

```text
convertedStatusWithNullNormalizedAmount_isExcludedOrMarkedFailed
homeCurrencyStatusWithNullNormalizedAmount_isExcludedOrMarkedFailed
```

---

## E2-NOW-008 — `includeDepositsForBehavior` unused

Previous status: open.  
Current status: **fixed by removal**

### Evidence

`AnalyticsInputOptions` now only contains:

```kotlin
spendingOnly
excludeNotMine
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt

Test added:

```text
IncludeDepositsForBehaviorCleanupTest
```

Source: commit file list.  
https://github.com/panospao7/Cost-agregator/commit/8cc202512ffd19b2a3cab2c5fc9614a8811354ce

### Verdict

**GREEN**

---

## A04 / A18 — SpendingPersonalityClassifier

Previous status: partial.  
Current status: **mostly fixed / documented caveat**

### Evidence

Production ViewModel uses:

```kotlin
spendingPersonalityClassifier.classify(allInput)
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

Raw no-arg `classify()` is deprecated and guarded:

```text
noProductionCallToRawSpendingPersonalityClassify
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/architecture/DeprecatedApiArchitectureGuardTest.kt

The normalized path is documented with caveats:

```text
budgetAdherence neutralized at 0.5
weekend/night share count-based
impulseRatio only detects DEPOSIT
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt

### Verdict

**YELLOW/GREEN**

It is production-safe enough because the normalized path is used and raw path is guarded. But it is not analytically perfect.

Remaining caveats:

```text
- raw path still exists
- raw path still imports/uses Calendar
- budgetAdherence is neutralized in normalized path
```

Not blocking if documented.

---

## A15 — historical category identity

Previous status: open.  
Current status: **deferred by design**

### Evidence

`AnalyticsInputAssembler` still derives category names from current category table:

```kotlin
categoryNameById = categories.associate { it.id to it.name }
categoryNameSnapshot = categoryNameById[snap.categoryId]
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt

But a design plan now exists:

```text
HISTORICAL_CATEGORY_IDENTITY_PLAN.md
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/docs/architecture/HISTORICAL_CATEGORY_IDENTITY_PLAN.md

### Verdict

**DEFERRED / acceptable**

This is not fixed. It requires schema/design work. It should remain documented as deferred.

---

# 5. Engine correctness review

## What is now correct

### Normalized input adoption

The main analytics path now uses normalized input for:

```text
current totals
previous totals
category breakdown
merchant breakdown
daily buckets
insights
advanced patterns/statistics
spending personality
location insights/area/travel
budget-vs-actual actuals
```

### Period correctness

Tests now verify selected period boundaries for:

```text
WEEK
YEAR
ALL
```

This directly addresses the old current-month/current-now leakage risk.

### Data quality/provenance

`AnalyticsInputAssembler` now carries:

```text
rateBasis
rateUsed
rateValidDate
rateLastUpdated
rateSource
conversionPath
ExcludedExpense.warningType
ExcludedExpense.message
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt

### Summary aggregate

`SpendingSummary.aggregate` is populated and tested.

### Location analytics

`AnalyticsViewModel` no longer calls raw `AreaSpendingEngine.compute(...)` or `TravelDetectionEngine.compute(...)`.

### Budget-vs-actual

Budget conversion now uses:

```kotlin
currencyConverter.convertAsOf(..., atMillis = currentEnd - 1)
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

Test verifies `convertAsOf` is called and latest-rate `convert(...)` is not.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/ui/screens/analytics/BudgetVsActualFxBasisTest.kt

---

# 6. New/current issues found in this final-gate review

## E2-FINAL-001 — `AnalyticsViewModel` still swallows `CancellationException` in personality block

Severity: **P2**

### Evidence

The ViewModel has:

```kotlin
val personalityProfile = try {
    spendingPersonalityClassifier.classify(allInput)
} catch (e: Exception) {
    null
}
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

`AnalyticsViewModel.kt` is currently in `CancellationSafetyArchitectureGuardTest.KNOWN_VIOLATIONS`, so this may not fail the current guard.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt

### Impact

If analytics load is cancelled during personality classification, the code can convert cancellation into `null` personality result and continue.

### Fix

Use:

```kotlin
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
```

### Blocking?

Not a full Engine 2 blocker because `AnalyticsViewModel` is already allowlisted and this is UI-layer cancellation. But it is easy and should be fixed.

---

## E2-FINAL-002 — normalized location engines trust inconsistent `LocatedMoneyExpense`

Severity: **P2**

### Evidence

`AreaSpendingEngine.computeNormalized()` and `TravelDetectionEngine.computeNormalized()` filter by status:

```kotlin
HOME_CURRENCY or CONVERTED
```

but later build amounts with:

```kotlin
normalizedAmount ?: originalAmount
```

Sources:

- Area engine:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt
- Travel engine:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt

### Impact

If a future caller marks `conversionStatus = CONVERTED` but leaves `normalizedAmount = null`, the engine can use raw `originalAmount` under `normalizedCurrency`.

The current ViewModel builds this correctly, so this is a contract-hardening issue, not an observed production regression.

### Fix

Require normalized amount for successful statuses:

```kotlin
val validExpenses = expenses.filter {
    it.conversionStatus in setOf(HOME_CURRENCY, CONVERTED) &&
    it.normalizedAmount != null &&
    it.normalizedAmount.isFinite()
}
```

Add tests:

```text
convertedStatusWithNullNormalizedAmount_isExcluded
homeCurrencyStatusWithNullNormalizedAmount_isExcluded
```

### Blocking?

No, but fix before declaring the normalized location engine contract fully hardened.

---

## E2-FINAL-003 — deprecated `moneyCurrentTotal` uses EUR fallback

Severity: **P2/P3**

### Evidence

`moneyCurrentTotalOrNull` is safe, but deprecated `moneyCurrentTotal` now returns:

```kotlin
moneyCurrentTotalOrNull ?: MoneyAmount(currentTotal, CurrencyCode.EUR)
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

### Impact

This prevents crashes but can silently label a loading/null currency state as EUR if any production caller still uses the deprecated property.

### Fix options

1. Add guard that no production UI code uses `moneyCurrentTotal`.
2. Make old property throw in invalid state.
3. Keep as compatibility fallback but document as UI-loading-only and remove later.

### Blocking?

No if production UI has migrated to `moneyCurrentTotalOrNull` or direct currency-guarded display. But it remains a technical debt item.

---

## E2-FINAL-004 — invalid currency in `SpendingSummary.aggregate` can still become EUR in diagnostics

Severity: **P2/P3**

### Evidence

`buildMoneyAggregate()` uses:

```kotlin
CurrencyCode.parseOr(currency, CurrencyCode.EUR)
```

for source/failure currencies.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

### Impact

If a dirty legacy row has an invalid currency string, the diagnostic `ConversionFailure.originalAmount.currency` can show EUR instead of preserving “invalid source currency.”

### Fix

Do not silently parse invalid source currency to EUR in failure metadata. Use a structured invalid-currency reason once Engine 5 supports it, or keep raw code in warning text.

### Blocking?

No. Engine 5 still has broader currency-policy cleanup. But this should be tracked.

---

## E2-FINAL-005 — stale TODO says budget conversion still uses latest rate

Severity: **P3 docs/code-comment**

### Evidence

The code now uses `convertAsOf`, but the comment still says:

```text
Budget limit conversion currently uses the latest available rate via currencyConverter.convert()
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

### Impact

This is misleading for future maintainers.

### Fix

Update the comment to:

```text
Budget conversion now uses period-end convertAsOf. Future improvement: make the rate basis explicit in UI/model.
```

### Blocking?

No.

---

# 7. Pipeline regression review

## Analytics screen

Status: **GREEN-candidate / YELLOW until validation**

### Positive

- selected period now flows into normalized input
- week/year/all insight tests exist
- loading/error currency null state handled by UI
- daily buckets from `DailyBucketEngine`
- advanced analytics use normalized input
- location sections use normalized models

### Remaining risks

- deprecated `moneyCurrentTotal` fallback to EUR if any production caller uses it
- personality classification cancellation catch should rethrow
- full Compose compile not verified by me

### Verdict

No obvious blocking regression found.

---

## Dashboard totals/widgets

Status: **GREEN-candidate**

### Positive

`SpendingSummary.aggregate` is now populated, not null.

Tests prove:

```text
display amount equals totalSpent
display amount equals daily sum
partial aggregate has conversion failures
source buckets group by original currency
```

### Remaining risk

Invalid dirty currency diagnostics can fallback to EUR in aggregate failure metadata.

### Verdict

Major dashboard/summary contract gap fixed.

---

## Budget-vs-actual

Status: **GREEN/YELLOW**

### Positive

Budget conversion now uses:

```text
period-end convertAsOf
```

and test verifies latest-rate `convert()` is not called.

### Remaining risks

- Budget conversion failure warnings are merged into UI warnings.
- But item-level quality is still minimal: `isPartial`/`warningMessage`, not full structured `AnalyticsDataQuality`.

### Verdict

Safe enough for Engine 2 green-candidate. Future improvement: expose explicit `budgetRateBasis` and structured quality.

---

## Forecast / financial health

Status: **YELLOW/GREEN**

### Positive

Data quality/provenance is better:

```text
excluded warningType/message
rate provenance
confidence penalty/multiplier
```

### Remaining risk

Not every downstream forecast/health consumer was deeply rechecked in this pass.

### Verdict

No new regression found, but full forecast pipeline validation still needed later.

---

## Location analytics

Status: **GREEN-candidate**

### Positive

Production ViewModel no longer uses raw area/travel compute.

UI now accepts:

```text
NormalizedAreaSpending
NormalizedTravelInsight
```

with aggregate-backed values.

### Remaining risks

- normalized engine should reject `normalizedAmount == null` when status says converted/home
- aggregate source buckets are home-currency normalized, not original source-currency diagnostic buckets

### Verdict

No obvious production regression; normalized API migration is successful.

---

## Currency / FX pipeline

Status: **GREEN/YELLOW**

### Positive

- actual analytics rows use transaction-date normalization
- budget limits use `convertAsOf`
- summary aggregate rateBasis is `TRANSACTION_DATE`
- location normalized path avoids raw mixed-currency sum
- excluded rows have warning metadata

### Remaining risks

- some aggregate diagnostic fallbacks still parse invalid currency to EUR
- `MoneyAggregateBuilder.fromBuckets` use in location with already-normalized home-currency buckets is safe, but it does not preserve original source-bucket provenance

### Verdict

Much improved. No blocker found.

---

## Category / merchant history

Status: **YELLOW / deferred**

### Positive

No new regression.

### Remaining issue

Historical category identity is not fixed. It is documented/deferred.

### Verdict

Acceptable as deferred, not green-clean.

---

## Spending personality

Status: **YELLOW/GREEN**

### Positive

Production path is normalized and raw path guarded.

### Remaining caveats

- budget adherence neutralized
- raw path still exists
- raw path still uses Calendar
- cancellation catch in ViewModel should rethrow

### Verdict

Safe enough with caveats; not analytically perfect.

---

## Tests/static guards

Status: **GREEN-candidate / validation pending**

### Positive

Added/strengthened tests:

```text
AnalyticsRepositoryAggregateTest
AnalyticsStateMoneySafetyTest
AnalyticsViewModelInsightsTest
BudgetVsActualFxBasisTest
AreaSpendingEngineNormalizedTest
TravelDetectionEngineNormalizedTest
IncludeDepositsForBehaviorCleanupTest
DeprecatedApiArchitectureGuardTest
```

### Guard caveat

Some guards use broad filename allowlists, e.g. `AnalyticsViewModel.kt`, because regex cannot distinguish safe normalized overloads from deprecated overloads.

This is acceptable short-term, but stronger guards should eventually inspect signatures or specific forbidden call forms.

---

# 8. Test quality review

## Strong tests

Strong coverage exists for:

```text
SpendingSummary.aggregate non-null and semantically aligned
partial aggregate failures
source buckets
moneyCurrentTotalOrNull null safety
period boundaries for week/year/all
budget convertAsOf usage
area/travel normalized engines
deprecated raw personality guard
```

## Missing useful tests

Non-blocking but recommended:

```text
analyticsViewModel_personalityCancellationRethrows
analyticsState_deprecatedMoneyCurrentTotalNotUsedByProductionUi
areaSpending_convertedStatusNullNormalizedAmountExcluded
travelDetection_convertedStatusNullNormalizedAmountExcluded
spendingSummary_invalidCurrencyDoesNotBecomeEurInFailureMetadata
budgetVsActual_conversionFailureCarriesStructuredQuality
```

---

# 9. Docs/tracker review

Docs are now more honest.

Good:

```text
Engine 2 final status says GREEN candidate, validation required
Historical category identity is documented/deferred
Spending personality caveats documented
ENERGY-style overclaims avoided
```

Source:

- Engine 2 final status  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/docs/analyses%20and%20debug%20master/engine%202/engine2-final-status.md
- Historical category plan  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/8cc202512ffd19b2a3cab2c5fc9614a8811354ce/docs/architecture/HISTORICAL_CATEGORY_IDENTITY_PLAN.md

Recommended doc tweak:

```text
Update stale budget conversion TODO in AnalyticsViewModel.
```

---

# 10. Required fixes before absolute GREEN

I would not require these before merge if targeted tests pass, but they should be done before calling Engine 2 “fully clean”:

## Small PR-A — cancellation + stale comments

```text
- rethrow CancellationException in personalityProfile try/catch
- update stale budget conversion TODO
```

## Small PR-B — location normalized contract hardening

```text
- exclude HOME/CONVERTED LocatedMoneyExpense if normalizedAmount is null/non-finite
- add tests
```

## Small PR-C — deprecated money helper guard

```text
- add static guard against production use of AnalyticsState.moneyCurrentTotal
- prefer moneyCurrentTotalOrNull everywhere
```

## Deferred Engine 5/category work

```text
- invalid currency diagnostics without parseOr EUR fallback
- historical category identity schema/design
- raw Double MoneyAmount v2
```

---

# 11. Final recommendation

## Merge recommendation

```text
Mergeable as GREEN-candidate if targeted tests truly pass and compile is green.
```

## Full GREEN requires

Run:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

Targeted examples:

```bash
./gradlew :app:testDebugUnitTest --tests "*AnalyticsRepositoryAggregateTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AnalyticsStateMoneySafetyTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AnalyticsViewModelInsightsTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BudgetVsActualFxBasisTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AreaSpendingEngineNormalizedTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TravelDetectionEngineNormalizedTest*" --stacktrace
```

Do **not** use:

```bash
./gradlew test --tests ...
```

Use Android unit-test task:

```bash
./gradlew :app:testDebugUnitTest --tests "*TestClassPattern*" --stacktrace
```

## Final verdict

```text
Engine 2 after 8cc2025: YELLOW / GREEN-candidate.
No obvious blocking pipeline regression found statically.
Major old Engine 2 issues are fixed or honestly deferred.
Remaining items are mostly P2/P3 hardening and validation.
```