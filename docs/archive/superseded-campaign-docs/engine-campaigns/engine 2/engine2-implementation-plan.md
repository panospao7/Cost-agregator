# Engine 2 Implementation Plan — Analytical Engines

## Goal

Clean up Engine 2 without regressing analytics, dashboard, budget, forecast, location, or currency pipelines.

Core strategy:

> Finish migrating production analytics from mixed legacy/self-fetching paths to `NormalizedAnalyticsInput`, then lock down raw/deprecated paths with tests and guards.

Do **not** start with schema changes. Do **not** rewrite global currency primitives. Do not touch `CurrencyConverter` semantics globally.

---

# Current Engine 2 verdict

Status: **YELLOW — improved but mixed old/new paths remain**

Strong foundations already exist:

- `AnalyticsInputAssembler`
- `NormalizedAnalyticsInput`
- `AnalyticsCurrencyNormalizer`
- `DailyBucketEngine`
- `BudgetVsActualEngine`
- historical-rate `MultiCurrencyRepository` paths
- `AnalyticsDataQuality`
- many analytics tests

Main remaining problem:

> Some production paths still bypass the canonical normalized input and use legacy self-fetching/current-period/current-rate behavior.

---

# High-level affected pipelines

| Pipeline | Risk |
|---|---|
| Analytics & Insights | High |
| Dashboard totals/widgets | Medium-high |
| Budget vs actual | High |
| Forecast / financial health | Medium |
| Location analytics | Medium |
| Currency conversion / FX basis | High |
| Category history | Medium-high |
| UI data-quality warnings | Medium |

---

# Non-regression definition

A change is acceptable only if:

1. existing valid analytics screens still load
2. totals still reconcile across summary/category/daily views
3. selected period is respected everywhere
4. no historical total silently uses latest rate
5. partial conversion warnings remain visible
6. budget-vs-actual does not hide conversion/data-quality problems
7. no raw mixed-currency analytics path is reintroduced
8. no current-month-only insight appears for a selected non-month period
9. no Room migration is added unless the slice explicitly requires it
10. dashboard/budget/forecast consumers still receive compatible models

---

# Recommended PR sequence

## PR0 — Baseline checkpoint

### Goal

Freeze current working state before Engine 2 changes.

### Steps

```bash
git checkout -b engine2-analytics-hardening
git tag working-before-engine2
```

Do not change code yet.

### Deliverables

- confirm current app boots
- preserve DB backup
- preserve CSV backup
- rescue remains disabled

---

# PR1 — Insights period correctness

## Goal

Stop `AnalyticsViewModel` from calling legacy `InsightsEngine` overloads that calculate current-month insights from `timeProvider.now()`.

## Issues closed

- `E2-NOW-001`
- parts of `A07`, `A08`, `A13`

## Files to inspect/change

```text
AnalyticsViewModel.kt
InsightsEngine.kt
InsightsEngine tests
AnalyticsViewModel tests
```

## Current problem

`AnalyticsViewModel` still calls a legacy overload similar to:

```kotlin
insightsEngine.generateInsights(
    analyticsCategories,
    allExpenseSnapshots,
    displayCurrency = homeCurrency,
    conversionWarnings = conversionWarnings
)
```

That overload can internally compute current-month insights from `timeProvider.now()`, even when the selected UI period is week/year/all.

## Implementation plan

### Step 1 — Prefer normalized overload

Change ViewModel to call:

```kotlin
insightsEngine.generateInsights(
    input = currentInput,
    categories = analyticsCategories
)
```

If the existing normalized overload lacks historical context, add a new overload:

```kotlin
suspend fun generateInsights(
    currentInput: NormalizedAnalyticsInput,
    historicalInput: NormalizedAnalyticsInput?,
    categories: List<AnalyticsCategoryRef>
): List<Insight>
```

Do not remove old overload yet.

### Step 2 — Preserve conversion warnings

Make sure warnings from:

```text
currentInput.dataQuality
historicalInput?.dataQuality
```

are still surfaced in UI state.

### Step 3 — Keep behavior compatible

If old ViewModel expected insights from `allExpenseSnapshots`, use `allInput` only as explicit historical context, not as the current period.

## Engine tests

```text
insightsEngine_normalizedOverload_usesInputPeriod()
insightsEngine_weekInput_doesNotUseCurrentMonth()
insightsEngine_yearInput_doesNotUseCurrentMonth()
insightsEngine_preservesConversionWarnings()
```

## Pipeline/UI tests

```text
analyticsViewModel_weekPeriod_insightsUseSelectedWeek()
analyticsViewModel_yearPeriod_insightsUseSelectedYear()
analyticsViewModel_allPeriod_insightsDoNotBecomeCurrentMonthOnly()
analyticsViewModel_conversionWarningsStillVisibleInInsights()
```

## Regression focus

- selected period must be respected
- no current-month fallback
- no loss of warnings
- no change to Room/Hilt

## Risk

Medium, no schema impact.

---

# PR2 — Advanced analytics normalized-input adoption

## Goal

Stop advanced spending patterns/statistics from self-fetching repository data independently.

## Issues closed

- `E2-NOW-002`
- `E2-NOW-003`
- parts of `A06`, `A09`, `A16`

## Files to inspect/change

```text
AdvancedAnalyticsEngine.kt
AnalyticsViewModel.kt
AdvancedAnalyticsEngine tests
AnalyticsViewModel tests
```

## Current problem

`AdvancedAnalyticsEngine` still has paths like:

```kotlin
getSpendingPatterns(period, displayCurrency)
getStatisticalInsights(period, displayCurrency)
```

that fetch expenses internally.

Also for `WEEK`, ViewModel can call:

```kotlin
advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.WEEK)
```

which recomputes the range using engine `now`.

## Implementation plan

### Step 1 — Add normalized overloads

Add:

```kotlin
fun getSpendingPatterns(
    input: NormalizedAnalyticsInput
): SpendingPatterns
```

and:

```kotlin
fun getStatisticalInsights(
    input: NormalizedAnalyticsInput
): StatisticalInsights
```

If historical comparison is required:

```kotlin
fun getStatisticalInsights(
    currentInput: NormalizedAnalyticsInput,
    historicalInput: NormalizedAnalyticsInput?
): StatisticalInsights
```

### Step 2 — Update ViewModel

Use already-built inputs:

```kotlin
advancedAnalyticsEngine.getSpendingPatterns(currentInput)
advancedAnalyticsEngine.getStatisticalInsights(currentInput)
```

### Step 3 — Stop ViewModel from recomputing advanced range

Use explicit range already calculated by ViewModel:

```kotlin
AnalyticsPeriodRange(
    currentStart = currentStart,
    currentEnd = currentEnd,
    previousStart = previousStart,
    previousEnd = previousEnd
)
```

Do not call `advancedAnalyticsEngine.getPeriodRange()` from ViewModel for active UI computation.

### Step 4 — Deprecate old self-fetching overloads

Mark old methods:

```kotlin
@Deprecated(
    "Use NormalizedAnalyticsInput overload",
    level = DeprecationLevel.WARNING
)
```

Do not use `ERROR` until call sites are clean.

## Engine tests

```text
advancedPatterns_fromNormalizedInput_usesOnlyInputRows()
statisticalInsights_fromNormalizedInput_usesOnlyInputRows()
advancedPatterns_emptyInput_returnsEmptySafeResult()
advancedStatistics_partialInputPreservesDataQuality()
```

## Pipeline/UI tests

```text
analyticsViewModel_advancedTotalsMatchSummaryInput()
analyticsViewModel_weekAdvancedRangeMatchesMainRange()
analyticsViewModel_advancedDoesNotRefetchDifferentPeriod()
analyticsViewModel_advancedWarningsStillVisible()
```

## Regression focus

- main total and advanced total reconcile
- selected period stays consistent
- no repository self-fetch from ViewModel path
- warnings preserved

## Risk

Medium. No schema impact.

---

# PR3 — Budget-vs-actual FX basis and quality propagation

## Goal

Make budget-vs-actual explicit and stable about budget conversion basis.

## Issues closed

- `E2-NOW-004`
- parts of `A10`, `A20`

## Files to inspect/change

```text
AnalyticsViewModel.kt
BudgetVsActualEngine.kt
BudgetVsActualResult / BudgetVsActualItem models
Currency conversion helper if present
Budget/analytics tests
```

## Current problem

Actual spending is transaction-date normalized, but budget limit conversion in ViewModel uses current/latest:

```kotlin
currencyConverter.convert(...)
```

This can make a closed historical period change when latest FX changes.

Also `BudgetVsActualResult.dataQuality` is not fully propagated to UI items.

## Implementation plan

### Step 1 — Define budget FX basis

Pick one explicit basis:

Recommended for now:

```text
Budget limit conversion basis = period-end rate
```

Reason:

- stable for closed period
- easy to explain
- avoids latest-rate mutation after period closes

Represent this as:

```kotlin
enum class BudgetRateBasis {
    PERIOD_END,
    HOME_CURRENCY_IDENTITY,
    CONVERSION_FAILED
}
```

or as a string field if you want minimal change.

### Step 2 — Convert budgets as of period end

Use:

```kotlin
currencyConverter.convertAsOf(
    amount = budget.amount,
    fromCurrency = budget.currency,
    toCurrency = homeCurrency,
    atMillis = period.endExclusive - 1
)
```

If your converter expects exact millis and half-open period end is exclusive, use the last millisecond inside the period.

### Step 3 — Preserve conversion failures

If budget conversion fails:

- do not silently use raw amount
- mark budget item partial
- add warning to `BudgetVsActualResult.dataQuality`
- item should show unavailable/partial budget if needed

### Step 4 — Expose data quality

Extend `BudgetVsActualItem` with:

```kotlin
val isPartial: Boolean
val warningMessage: String?
val budgetRateBasis: String?
val dataQuality: AnalyticsDataQuality?
```

If UI model should remain small, at least expose:

```kotlin
val isPartial: Boolean
val warningMessage: String?
```

## Engine tests

```text
budgetVsActual_budgetLimitUsesPeriodEndRate()
budgetVsActual_sameCurrencyUsesIdentityBasis()
budgetVsActual_budgetConversionFailureMarksPartial()
budgetVsActual_actualConversionFailurePropagatesDataQuality()
```

## Pipeline/UI tests

```text
analyticsViewModel_budgetItemCarriesPartialWarning()
analyticsViewModel_budgetClosedPeriodStableWhenLatestRateChanges()
analyticsViewModel_budgetVsActualStillRendersValidBudgets()
```

## Regression focus

- budget screen/analytics still render
- warnings visible
- no latest-rate silent fallback
- no global CurrencyConverter semantics change

## Risk

Medium-high because FX basis affects user-visible numbers.

---

# PR4 — Analytics data-quality and provenance completion

## Goal

Make analytics output explainable and internally consistent.

## Issues closed

- `E2-NOW-005`
- `E2-NOW-006`
- `E2-NOW-007`
- parts of `A05`, `A11`, `A19`

## Files to inspect/change

```text
AnalyticsCurrencyNormalizer.kt
AnalyticsInputAssembler.kt
NormalizedAnalyticsInput.kt
AnalyticsRepository.kt
DataQualityReport.kt
tests
```

## Current problems

1. `SpendingSummary.aggregate` exists but is emitted as `null`.
2. `ExcludedExpense.warningType/message` exist but are not populated.
3. `NormalizedExpense` has rate provenance fields but they are not filled.

## Implementation plan

### Step 1 — Populate `SpendingSummary.aggregate`

Options:

#### Option A — Build from normalized rows

Create aggregate from normalized included expenses:

```text
displayAmount = sum(normalizedAmount)
displayCurrency = homeCurrency
sourceBuckets = original currency buckets
failures = excluded conversion failures
```

#### Option B — If full aggregate is too much

Rename/remove the misleading field later. But for now, prefer populating it.

### Step 2 — Populate excluded warning metadata

Modify normalizer result so each excluded expense can include:

```kotlin
warningType
message
sourceCurrency
targetCurrency
```

Potential mapping:

| Exclusion reason | warningType |
|---|---|
| invalid currency | `INVALID_CURRENCY` |
| missing FX rate | `MISSING_RATE` |
| conversion exception | `CONVERSION_FAILED` |
| invalid amount | `INVALID_AMOUNT` |

### Step 3 — Populate rate provenance

`NormalizedExpense` currently has fields like:

```kotlin
rateBasis
rateUsed
rateValidDate
rateLastUpdated
conversionPath
```

Fill them from conversion outcome if available.

For same-currency rows:

```text
rateBasis = HOME_CURRENCY_IDENTITY
rateUsed = 1.0
conversionPath = SAME_CURRENCY
```

For foreign currency converted rows:

```text
rateBasis = TRANSACTION_DATE
rateUsed = actual rate
rateValidDate = rate date
rateLastUpdated = rate metadata if available
conversionPath = DIRECT or TRIANGULATED if known
```

If converter does not expose all metadata yet, populate what exists and leave unknown fields null with explicit basis.

### Step 4 — Do not change converter globally

Only consume available metadata. Do not redesign `CurrencyConverter` in this PR.

## Engine tests

```text
spendingSummary_populatesMoneyAggregate()
spendingSummary_aggregateTotalEqualsNormalizedIncludedSum()
excludedExpense_invalidCurrencyHasWarningTypeAndMessage()
excludedExpense_missingRateHasWarningTypeAndMessage()
normalizedExpense_sameCurrencyHasIdentityProvenance()
normalizedExpense_foreignCurrencyHasTransactionDateBasis()
```

## Pipeline/UI tests

```text
analyticsViewModel_partialConversionShowsWarning()
analyticsViewModel_summaryAggregateMatchesDisplayedTotal()
dashboardAnalyticsSummaryStillLoadsWithAggregate()
```

## Regression focus

- summary total unchanged
- warnings more detailed
- no silent fallback
- no global currency behavior changes

## Risk

Medium.

---

# PR5 — Location analytics normalized API adoption

## Goal

Remove fragile caller-normalized raw location analytics usage.

## Issues closed

- `E2-NOW-009`
- part of `A14`

## Files to inspect/change

```text
AnalyticsViewModel.kt
AreaSpendingEngine.kt
TravelDetectionEngine.kt
LocationInsightsEngine.kt
tests
```

## Current problem

ViewModel may call raw/deprecated APIs with pre-normalized `Expense.copy(...)` rows. This works only because caller is careful.

## Implementation plan

### Step 1 — Use normalized APIs directly

Replace ViewModel calls like:

```kotlin
areaSpendingEngine.compute(normalizedPurchases)
travelDetectionEngine.compute(normalizedPurchases)
```

with:

```kotlin
areaSpendingEngine.computeNormalized(locatedMoneyExpenses)
travelDetectionEngine.computeNormalized(locatedMoneyExpenses)
```

or equivalent actual normalized input models.

### Step 2 — Preserve data quality

Location outputs should preserve:

```text
isPartial
warningMessage
failedConversionCount
displayCurrency
```

### Step 3 — Deprecate raw APIs harder

After call sites are gone:

```kotlin
@Deprecated(
    "Use computeNormalized",
    level = DeprecationLevel.WARNING
)
```

Later in guard PR, move to `ERROR`.

## Engine tests

```text
areaSpending_computeNormalized_preservesCurrencyWarnings()
travelDetection_computeNormalized_preservesCurrencyWarnings()
areaSpending_rawComputeNotUsedByAnalyticsViewModel()
```

## Pipeline/UI tests

```text
analyticsViewModel_locationAreaUsesNormalizedApi()
analyticsViewModel_travelUsesNormalizedApi()
analyticsViewModel_locationWarningsStillVisible()
```

## Regression focus

- map/location analytics still load
- travel detection still works
- no mixed-currency raw-sum path in production ViewModel

## Risk

Medium.

---

# PR6 — Analytics UI money/null-safety

## Goal

Prevent UI helper properties from throwing while state is loading or currency is temporarily null.

## Issues closed

- `E2-NOW-010`

## Files to inspect/change

```text
AnalyticsViewModel.kt
AnalyticsState model
BudgetVsActualItem model
UI tests
```

## Current problem

Some computed properties construct:

```kotlin
MoneyAmount(amount, CurrencyCode(homeCurrency ?: ""))
```

If `homeCurrency` is null/blank while loading, `CurrencyCode("")` can throw.

## Implementation plan

### Option A — nullable money helpers

Replace dangerous properties:

```kotlin
val moneyCurrentTotal: MoneyAmount
```

with:

```kotlin
val moneyCurrentTotalOrNull: MoneyAmount?
    get() = homeCurrency?.takeIf { it.isNotBlank() }?.let {
        MoneyAmount(currentTotal, CurrencyCode(it))
    }
```

### Option B — only expose after loaded

If UI always needs non-null, add safe state guard:

```kotlin
if (!isLoading && homeCurrency != null) ...
```

Option A is safer.

## Tests

```text
analyticsState_loadingMoneyCurrentTotalDoesNotThrow()
budgetVsActualItem_missingCurrencyMoneyHelpersDoNotThrow()
analyticsState_validCurrencyReturnsMoneyAmount()
```

## Regression focus

- loading UI does not crash
- loaded UI unchanged

## Risk

Low.

---

# PR7 — Historical category identity

## Goal

Prevent historical analytics from losing category names after category deletion/rename.

## Issues closed

- `A15`

## Risk

High because likely schema/design.

## Do this later.

## Options

### Option A — Soft-delete categories

Instead of hard deleting categories, mark:

```kotlin
isArchived = true
```

Then analytics can still resolve old names.

Requires schema if field does not exist.

### Option B — Snapshot category name on Expense

Add to `Expense`:

```kotlin
categoryNameSnapshot: String?
categoryColorSnapshot: String?
categoryIconSnapshot: String?
```

Populate on expense creation/update.

Requires migration.

### Option C — Category history table

Create:

```text
category_history
```

with id/name/color/validFrom/validTo.

Most correct, more work.

## Recommendation

For this app, choose **Option A soft-delete** if feasible. It is simpler and reduces data loss.

## Tests

```text
deletedCategoryHistoricalAnalyticsKeepsOldName()
renamedCategoryHistoricalAnalyticsUsesExpectedPolicy()
categoryDeletionDoesNotBreakExpenseCategoryBreakdown()
```

## Migration warning

Only do this after DB baseline v145 is stable.

---

# PR8 — Legacy/raw analytics guardrails

## Goal

Prevent future regressions by blocking old raw APIs.

## Issues closed

- parts of `A01`, `A04`, `A06`, `A07`, `A14`, `A17`, `A18`

## Implementation

After PR1–PR6 call sites are migrated:

### Step 1 — Deprecation escalation

Mark dangerous APIs:

```kotlin
@Deprecated(
    "Use NormalizedAnalyticsInput overload",
    level = DeprecationLevel.ERROR
)
```

Candidates:

```text
InsightsEngine legacy generateInsights overloads
SpendingPersonalityClassifier raw classify()
AdvancedAnalyticsEngine self-fetching pattern/stat methods
AreaSpendingEngine.compute(List<Expense>)
TravelDetectionEngine.compute(List<Expense>)
```

### Step 2 — Static source guards

Add tests/scripts that fail if production code calls:

```text
legacy insights overloads
raw spending personality classify
raw area/travel compute
latest-rate analytics total APIs where historical required
```

### Step 3 — Calendar cleanup

Once behavior is covered, replace remaining `java.util.Calendar` usage with `java.time`.

## Tests

```text
noProductionCallToLegacyInsightsOverload()
noProductionCallToRawSpendingPersonalityClassify()
noProductionCallToRawLocationAnalyticsCompute()
noCalendarUsageInAnalyticsPackageExceptAllowlist()
```

## Risk

Medium. This may reveal call sites.

---

# Engine 2 specific non-regression checklist

Use this after every Engine 2 PR.

## Analytics screen

- [ ] App opens analytics screen.
- [ ] Current total displays.
- [ ] Previous period comparison displays.
- [ ] Category breakdown displays.
- [ ] Merchant breakdown displays.
- [ ] Daily chart displays exact selected range.
- [ ] Insights match selected period.
- [ ] Advanced category cards match selected period.
- [ ] Merchant analytics use same input period.
- [ ] Spending patterns use same input period.
- [ ] Statistical insights use same input period.
- [ ] Partial conversion warning banner still appears when needed.
- [ ] Empty period does not crash.
- [ ] Loading state does not construct invalid `CurrencyCode("")`.

## Period correctness

- [ ] WEEK analytics use the same `currentStart/currentEnd` in summary, daily, insights, and advanced cards.
- [ ] MONTH analytics do not leak previous/current month from `timeProvider.now()` unless selected.
- [ ] YEAR analytics use full selected year.
- [ ] ALL analytics does not become current month.
- [ ] Custom/exact range analytics use exact half-open boundaries.
- [ ] Daily buckets include every local day in period and no extra future day.

## Currency / FX correctness

- [ ] Transaction actuals use transaction-date conversion.
- [ ] Historical analytics do not silently fall back to latest rate.
- [ ] Category total sum reconciles with summary total within rounding tolerance.
- [ ] Daily total sum reconciles with summary total within rounding tolerance.
- [ ] Merchant total sum reconciles with summary total within rounding tolerance.
- [ ] Failed conversions are excluded or marked partial, never silently raw-summed.
- [ ] Home-currency identity rows use rate `1.0`.
- [ ] Foreign-currency rows carry rate basis/provenance where supported.
- [ ] Conversion warnings remain visible in UI/debug state.

## Budget-vs-actual

- [ ] Budget actuals use same normalized actual spend as analytics.
- [ ] Budget limits use explicit rate basis.
- [ ] Closed historical budget periods do not change because latest FX changed.
- [ ] Budget conversion failure marks item/result partial.
- [ ] Budget UI still renders valid same-currency budgets.
- [ ] Budget UI still renders valid foreign-currency budgets.
- [ ] Budget-vs-actual data quality is not dropped.

## Advanced analytics

- [ ] Advanced spending patterns consume `NormalizedAnalyticsInput`.
- [ ] Advanced statistical insights consume `NormalizedAnalyticsInput`.
- [ ] Advanced analytics do not self-fetch data from repository on the ViewModel path.
- [ ] Advanced analytics do not recompute period from `now` on the ViewModel path.
- [ ] Advanced total/average calculations reconcile with normalized input.
- [ ] Advanced insights preserve data-quality warnings.

## Insights

- [ ] Production ViewModel uses normalized `InsightsEngine` overload.
- [ ] Legacy current-month overload is not used by production ViewModel.
- [ ] Spending pace uses selected period, not current month.
- [ ] Anomaly baseline is explicit and not accidentally current-year-only unless intended.
- [ ] Insight confidence/severity reflects partial data where implemented.
- [ ] Insights remain stable for empty/small data sets.

## Spending personality

- [ ] ViewModel uses `classify(NormalizedAnalyticsInput)`.
- [ ] Raw `classify()` is not called from production.
- [ ] Personality features use normalized amounts.
- [ ] Budget adherence is either correctly normalized or explicitly neutral with documented caveat.
- [ ] Partial data reduces confidence or is exposed.

## Location analytics

- [ ] Location insights use normalized/currency-safe values.
- [ ] Area spending does not raw-sum mixed currencies.
- [ ] Travel detection does not raw-sum mixed currencies.
- [ ] Location analytics preserve partial conversion warnings.
- [ ] Spending-only filter remains respected where expected.
- [ ] Raw location compute APIs are not used by production ViewModel.

## Category history

- [ ] Existing category names still display.
- [ ] Deleted/archived categories do not crash analytics.
- [ ] Historical expenses do not become misleadingly uncategorized after category deletion, or the limitation is documented.
- [ ] Category rename/deletion behavior is explicitly defined.

## Dashboard / external consumers

- [ ] Dashboard monthly total still loads.
- [ ] Dashboard weekly/daily drilldowns still load.
- [ ] Dashboard category widget matches analytics basis.
- [ ] Forecast/financial health still receives expected analytics quality/confidence.
- [ ] No external consumer receives null/invalid `MoneyAmount`.

## Tests/static guards

- [ ] Engine unit tests added for each bug.
- [ ] ViewModel/pipeline tests added for each affected consumer.
- [ ] No `@Ignore`.
- [ ] No weak assertions only checking non-null.
- [ ] Tests use fixed `TimeProvider`.
- [ ] FX tests include mixed currencies and failed conversion.
- [ ] Period tests include week/year/custom boundaries.
- [ ] Static guard blocks production legacy overload calls after migration.

## Build/schema discipline

- [ ] No Room migration added unless the PR explicitly requires it.
- [ ] No DB schema change in PR1–PR6.
- [ ] No global `CurrencyConverter` behavior change.
- [ ] No global `TimePeriodUtils` behavior change.
- [ ] No Hilt constructor change without call-site and module review.
- [ ] No destructive migration/fallback.

---

# Suggested final validation commands

Do not run during individual slices if your orchestrator rule forbids it.

After all Engine 2 PRs are finalized:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If PR7 adds schema/category snapshot migration:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt constructor changes occur:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# Definition of done for Engine 2

Engine 2 can be considered clean when:

- production analytics ViewModel uses `NormalizedAnalyticsInput` for all arithmetic-heavy analytics
- insights respect selected period
- advanced analytics do not self-fetch on production path
- summary/category/merchant/daily totals reconcile under the same FX basis
- historical actuals use transaction-date conversion
- budget limits use explicit stable rate basis
- partial conversion/data-quality warnings are propagated to UI
- excluded expenses carry structured warning metadata
- normalized rows carry rate provenance where possible
- raw/deprecated analytics APIs are blocked from production call sites
- location analytics production path is normalized
- loading/error states cannot crash through invalid `CurrencyCode`
- category deletion/rename behavior is explicitly solved or documented as deferred