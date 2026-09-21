# Engine 2 Follow-up Implementation Plan

Target commit reviewed: `3b9490e14367846aa8274e6c1a2dae6e2dcc9e53`  
Engine: **Engine 2 — Analytical Engines**  
Current verdict: **YELLOW — major production paths improved, but not clean**

Goal:

> Close the remaining Engine 2 final-gate gaps without regressing analytics, dashboard, budget, location analytics, forecast/health, currency/data-quality, or category/merchant grouping pipelines.

Do **not** start with schema changes.  
Do **not** rewrite global money/currency primitives.  
Do **not** change `CurrencyConverter` public semantics.  
Do **not** combine historical category schema work with analytics quick fixes.

---

# Remaining issues to fix

## Required before Engine 2 GREEN

1. `AnalyticsRepository.SpendingSummary.aggregate` is still `null`.
2. `AnalyticsState.moneyCurrentTotal` can still create `CurrencyCode("")`.
3. Area/travel analytics still call deprecated/raw `compute(...)` APIs.
4. Static/deprecated API guards may not catch remaining raw location compute calls.
5. Period/insight tests may verify overload selection, but not enough behavior.
6. Budget-vs-actual FX/data-quality needs final regression tests.
7. `includeDepositsForBehavior` exists but appears unused.
8. Historical category identity remains unresolved/deferred.
9. Spending personality remains partial:
   - raw path still exists
   - `Calendar` still used
   - normalized budget adherence neutralized

---

# PR0 — Baseline and validation discipline

## Goal

Ensure agents stop looping on bad Gradle commands and freeze current Engine 2 state.

## Rules

During implementation slices:

```text
Do static compile reasoning only unless final validation is explicitly allowed.
```

If targeted tests are run later, use Android test task:

```bash
./gradlew :app:testDebugUnitTest --tests "*AnalyticsViewModelInsightsTest*" --stacktrace
```

Do **not** use:

```bash
./gradlew test --tests ...
```

because root/Android `test` task may reject `--tests`.

## Deliverables

- Save current final-gate review.
- Save this implementation plan.
- Confirm no schema migration in PR1–PR6.

---

# PR1 — Populate `SpendingSummary.aggregate`

## Goal

Fix the broken summary aggregate contract.

## Issues closed

- `A05`
- `E2-NOW-005`
- dashboard/summary aggregate contract gap

## Main files

```text
AnalyticsRepository.kt
MoneyAggregate / MoneyAggregateBuilder usage if needed
AnalyticsRepository tests
dashboard/analytics summary tests
```

## Current problem

`AnalyticsRepository.SpendingSummary` exposes:

```text
aggregate: MoneyAggregate?
```

but `getSpendingSummary()` emits:

```text
aggregate = null
```

This means consumers cannot inspect conversion failures, source buckets, or aggregate quality.

## Implementation strategy

### Step 1 — Identify normalized source rows

Use the same normalized rows currently used to calculate:

```text
totalSpent
dailySpendingHistory
isPartial
```

Do not introduce a second repository query.

### Step 2 — Build aggregate from normalized included rows

The aggregate should represent:

```text
displayAmount = sum of normalized included expense amounts
displayCurrency = home currency
source buckets = original currency buckets where available
failures = excluded conversion failures where available
partial state = true when normalization was partial
```

If `MoneyAggregateBuilder` can build from normalized buckets, use it.  
If not, create the smallest local adapter that preserves:

```text
display amount
display currency
failed transaction count
warning metadata
```

Do not silently drop conversion failures.

### Step 3 — Align displayed total with aggregate

Ensure:

```text
SpendingSummary.totalSpent == SpendingSummary.aggregate.displayAmount
```

within rounding tolerance.

### Step 4 — Preserve existing API

Do not remove `aggregate`. Populate it.

## Engine tests

```text
spendingSummary_populatesAggregate()
spendingSummary_aggregateDisplayAmountEqualsTotalSpent()
spendingSummary_aggregateTotalEqualsDailyHistorySumWithinTolerance()
spendingSummary_partialConversionAggregateContainsFailure()
spendingSummary_emptyPeriodAggregateIsZeroHomeCurrency()
```

## Pipeline tests

```text
dashboardSummary_receivesNonNullAggregate()
analyticsSummary_partialConversionWarningStillVisible()
analyticsSummary_invalidCurrencyDoesNotCrashSummary()
```

## Reviewer focus

- no latest-rate fallback
- no second inconsistent data source
- no raw mixed-currency sum
- aggregate warnings preserved

## Risk

Medium. No schema.

---

# PR2 — Analytics UI money null-safety

## Goal

Prevent analytics UI helper properties from throwing during loading/error/partial state.

## Issues closed

- `E2-NOW-010`

## Main files

```text
AnalyticsViewModel.kt
AnalyticsState model
BudgetVsActualItem model if similar helpers exist
AnalyticsState tests
AnalyticsViewModel tests
```

## Current problem

Some helpers construct money like:

```text
MoneyAmount(currentTotal, CurrencyCode(homeCurrency ?: ""))
```

If `homeCurrency` is null or blank, `CurrencyCode("")` can throw.

## Implementation strategy

### Option A — preferred

Replace unsafe non-null helper with nullable helper:

```text
moneyCurrentTotalOrNull
```

Behavior:

```text
homeCurrency null/blank/invalid -> null
homeCurrency valid -> MoneyAmount
```

If old property must stay for UI compatibility, make it safe with a documented fallback only in loaded state. Do **not** silently default to EUR.

### Option B — guard existing helper

Return null or avoid constructing `MoneyAmount` when:

```text
homeCurrency is null
homeCurrency is blank
homeCurrency is invalid
```

## Tests

```text
analyticsState_loading_moneyCurrentTotalDoesNotThrow()
analyticsState_nullHomeCurrency_moneyCurrentTotalIsNull()
analyticsState_blankHomeCurrency_moneyCurrentTotalIsNull()
analyticsState_validHomeCurrency_returnsMoneyAmount()
budgetVsActualItem_missingCurrency_moneyHelpersDoNotThrow()
```

## Pipeline tests

```text
analyticsScreen_loadingStateDoesNotCrash()
analyticsScreen_errorStateDoesNotCrash()
analyticsScreen_loadedStateStillShowsMoney()
```

## Reviewer focus

- no `CurrencyCode("")`
- no silent EUR fallback
- UI still handles valid loaded state

## Risk

Low/medium. Possible Compose/UI model call-site updates.

---

# PR3 — Migrate area/travel analytics to normalized APIs

## Goal

Remove remaining production calls to deprecated/raw location analytics APIs.

## Issues closed

- `A14`
- `E2-NOW-009`
- raw mixed-currency location analytics footgun

## Main files

```text
AnalyticsViewModel.kt
AreaSpendingEngine.kt
TravelDetectionEngine.kt
LocationInsightsEngine.kt
location analytics tests
DeprecatedApiArchitectureGuardTest.kt
```

## Current problem

ViewModel still calls:

```text
areaSpendingEngine.compute(normalizedPurchases)
travelDetectionEngine.compute(normalizedPurchases)
```

These are raw/deprecated APIs. The caller currently passes normalized copies, but safety depends on caller discipline.

## Implementation strategy

### Step 1 — Inspect normalized models

Find current normalized input accepted by:

```text
AreaSpendingEngine.computeNormalized(...)
TravelDetectionEngine.computeNormalized(...)
```

or add minimal normalized overloads if missing.

### Step 2 — Convert ViewModel call sites

Replace production calls with normalized APIs.

The normalized input should carry:

```text
amount in home/display currency
currency
location fields
expense date
conversion warning/partial state if available
```

### Step 3 — Preserve output behavior

Ensure output still includes:

```text
area totals
travel insight/trip detection
display currency
partial/warning metadata where supported
```

### Step 4 — Strengthen deprecated API guard

Add static guard to block production calls to:

```text
AreaSpendingEngine.compute(List<Expense>)
TravelDetectionEngine.compute(List<Expense>)
```

Allow tests only.

## Engine tests

```text
areaSpending_computeNormalized_preservesTotals()
areaSpending_computeNormalized_preservesPartialWarnings()
travelDetection_computeNormalized_detectsTrips()
travelDetection_computeNormalized_doesNotRawSumMixedCurrencies()
```

## Pipeline/ViewModel tests

```text
analyticsViewModel_areaUsesNormalizedApi()
analyticsViewModel_travelUsesNormalizedApi()
analyticsViewModel_locationWarningsStillVisible()
analyticsViewModel_locationSectionsStillLoad()
```

## Static guard tests

```text
noProductionCallToRawAreaSpendingCompute()
noProductionCallToRawTravelDetectionCompute()
```

## Reviewer focus

- no raw mixed-currency sum
- no loss of location filtering
- no loss of partial warnings
- guard catches future regressions

## Risk

Medium.

---

# PR4 — Strong period, insight, and budget-vs-actual regression tests

## Goal

Prove the major Engine 2 fixes behaviorally, not only by overload-call verification.

## Issues covered

- `A07`
- `A08`
- `A10`
- `A13`
- `A20`
- insight period correctness
- budget FX/data-quality propagation

## Main files

```text
AnalyticsViewModelInsightsTest.kt
InsightsEngine tests
BudgetVsActualEngine tests
AnalyticsViewModel budget tests
```

## Current concern

Some tests may only verify:

```text
normalized overload was called
```

That is useful but not enough. We also need to prove outputs respect selected period.

## Implementation strategy

### Step 1 — Week insight behavior test

Create fixed `TimeProvider`.

Data:

```text
current calendar month has expenses outside selected week
selected week has distinct category/amount pattern
```

Assert insights mention/use selected week input, not full current month.

Test:

```text
analyticsViewModel_weekInsightsUseSelectedWeekNotCurrentMonth()
```

### Step 2 — Year/all behavior tests

Data:

```text
current month small spend
selected year/all has distinct large/historical pattern
```

Tests:

```text
analyticsViewModel_yearInsightsUseSelectedYear()
analyticsViewModel_allInsightsDoNotCollapseToCurrentMonth()
```

### Step 3 — Budget FX basis tests

Verify:

```text
closed historical budget period stable when latest rate changes
budget limit uses explicit period-end or documented basis
conversion failure marks item/result partial
```

Tests:

```text
budgetVsActual_budgetLimitUsesExplicitPeriodBasis()
budgetVsActual_closedPeriodStableWhenLatestRateChanges()
budgetVsActual_conversionFailureMarksPartial()
analyticsViewModel_budgetItemCarriesWarning()
```

### Step 4 — Data-quality UI propagation

Ensure budget-vs-actual result quality is not dropped.

Tests:

```text
budgetVsActual_dataQualityPropagatesToUiState()
budgetVsActual_partialItemShowsWarning()
```

## Reviewer focus

- tests use fixed `TimeProvider`
- tests include mixed currency
- tests include failed conversion
- no weak only-not-null assertions

## Risk

Low/medium. Mostly tests.

---

# PR5 — Clean up `includeDepositsForBehavior`

## Goal

Remove misleading unused option or implement it clearly.

## Issue closed

- `E2-NOW-008`

## Main files

```text
AnalyticsInputAssembler.kt
NormalizedAnalyticsInput.kt
AnalyticsViewModel.kt
assembler tests
spending personality tests
```

## Current problem

`AnalyticsInputOptions.includeDepositsForBehavior` appears to exist but does not affect filtering.

## Decision required

Choose one.

## Option A — implement

Define behavior:

```text
spendingOnly = true
includeDepositsForBehavior = true
=> include PURCHASE + DEPOSIT for behavior engines
=> still exclude TRANSFER/WITHDRAWAL unless explicitly requested
```

Use only where behavior analysis needs deposits.

Tests:

```text
assembler_includeDepositsForBehavior_includesDeposits()
assembler_includeDepositsForBehavior_excludesTransfers()
spendingPersonality_depositBehaviorUsesNormalizedInput()
```

## Option B — remove/deprecate

If ViewModel already uses:

```text
spendingOnly = false
```

where deposits are needed, then remove or deprecate the unused option to avoid false contract.

Tests:

```text
noProductionCodeReliesOnIncludeDepositsForBehavior()
```

## Recommendation

Prefer **Option B** unless there is a real behavior-analysis requirement. Less risk.

## Reviewer focus

- no hidden behavior change
- option contract honest
- spending-only analytics unchanged

## Risk

Low/medium.

---

# PR6 — Spending personality hardening

## Goal

Finish enough of `SpendingPersonalityClassifier` to prevent production regressions.

## Issues covered

- `A04`
- `A18`
- partial Engine 2 raw-path debt

## Main files

```text
SpendingPersonalityClassifier.kt
AnalyticsViewModel.kt
SpendingPersonalityClassifier tests
DeprecatedApiArchitectureGuardTest.kt
```

## Current state

Good:

```text
ViewModel uses classify(NormalizedAnalyticsInput)
```

Remaining:

```text
raw classify path exists
Calendar usage remains
budgetAdherence = 0.5 in normalized path
```

## Implementation strategy

### Step 1 — Guard production raw path

If raw path cannot be removed, mark deprecated and add guard:

```text
no production call to raw SpendingPersonalityClassifier.classify()
```

### Step 2 — Document budget adherence caveat

If real normalized budget adherence is too broad, document:

```text
budgetAdherence is neutral until budget-normalized input is available
```

Do not pretend fully fixed.

### Step 3 — Optional java.time cleanup

If small, replace `Calendar` with `java.time` using explicit zone/time provider.

If not small, defer to Engine 5/time cleanup and document.

## Tests

```text
analyticsViewModel_usesNormalizedSpendingPersonality()
noProductionCallToRawSpendingPersonalityClassify()
spendingPersonality_partialInputReducesOrExposesConfidence()
```

If budget adherence fixed:

```text
spendingPersonality_budgetAdherenceUsesNormalizedBudgetActual()
```

## Reviewer focus

- no raw mixed-currency behavior path in production
- caveats documented honestly
- no current-time nondeterminism

## Risk

Medium.

---

# PR7 — Historical category identity design/defer explicitly

## Goal

Avoid pretending historical category identity is fixed when it requires data-model policy.

## Issue covered

- `A15`

## Current problem

Assembler uses current category table:

```text
categoryNameSnapshot = current category name by ID
```

If category is deleted/renamed, historical analytics can change.

## Recommended action now

Do **not** add schema in this Engine 2 follow-up unless human approves.

Create or update docs:

```text
docs/architecture/HISTORICAL_CATEGORY_IDENTITY_PLAN.md
```

Include options:

1. soft-delete categories
2. snapshot category name/color/icon on expense
3. category history table with validFrom/validTo

Recommendation:

```text
Soft-delete categories first if feasible.
```

## Tests now

Add a documenting test if possible:

```text
deletedCategory_currentBehaviorMarkedUncategorizedOrCurrentName()
```

or tracker entry:

```text
Historical category identity: DEFERRED, requires schema/design.
```

## Future schema PR

Only later:

```text
PR-CAT1 soft-delete category migration
PR-CAT2 expense snapshot fields
PR-CAT3 category history table
```

## Reviewer focus

- tracker honest
- no schema rushed into analytics fixes
- UI behavior documented

## Risk

Low now, high if schema attempted.

---

# PR8 — Final docs/tracker and guardrail pass

## Goal

Make Engine 2 status honest and enforce future regression guardrails.

## Files

```text
ENGINE_ISSUES_MASTER_TRACKER.md
engine2 final-gate report docs
DeprecatedApiArchitectureGuardTest.kt
architecture docs
```

## Update statuses

After PR1–PR7:

```text
Canonical normalized input: MOSTLY FIXED / production path fixed
Insights period correctness: FIXED after behavior tests
Advanced normalized adoption: FIXED
Budget-vs-actual FX/data-quality: FIXED or MOSTLY FIXED depending tests
SpendingSummary aggregate: FIXED after PR1
Location analytics normalized path: FIXED after PR3
UI money null-safety: FIXED after PR2
Historical category identity: DEFERRED BY DESIGN
Spending personality: MOSTLY FIXED / caveats documented
```

## Required guards

```text
no production legacy InsightsEngine overload from ViewModel
no production AdvancedAnalyticsEngine self-fetching path from ViewModel
no production raw AreaSpendingEngine.compute
no production raw TravelDetectionEngine.compute
no production raw SpendingPersonalityClassifier.classify
no production import of deprecated analytics raw APIs unless allowlisted
```

## Final validation commands

Only after all follow-up PRs are finalized:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

Targeted test examples:

```bash
./gradlew :app:testDebugUnitTest --tests "*AnalyticsViewModelInsightsTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AnalyticsRepositoryTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BudgetVsActual*" --stacktrace
```

Do not use:

```bash
./gradlew test --tests ...
```

---

# Pipeline non-regression checklist for Engine 2 fixes

## Analytics screen

- current total still loads
- previous comparison still loads
- category breakdown still loads
- merchant breakdown still loads
- daily chart still covers exact selected range
- insights use selected period
- advanced spending patterns use selected period
- statistical insights use selected period
- loading state does not crash on null currency
- partial conversion warning still visible

## Dashboard

- monthly summary still loads
- dashboard aggregate is non-null if using `SpendingSummary`
- dashboard warning/partial state preserved
- no raw mixed-currency total introduced

## Budget

- budget actuals match normalized analytics actuals
- budget limits use explicit FX basis
- conversion failure marks item/result partial
- budget-vs-actual UI still renders valid budgets
- closed historical budget period stable when latest rate changes

## Location analytics

- location insight still loads
- area spending uses normalized API
- travel detection uses normalized API
- mixed-currency location rows do not raw-sum
- conversion warnings preserved

## Currency/data quality

- invalid currency rows excluded, not crashed
- missing FX rate creates partial warning
- rate provenance available on normalized rows
- excluded expenses have warning type/message
- summary aggregate preserves failure count

## Category/merchant history

- current categories still display
- deleted/renamed category limitation documented
- no misleading claim that historical category identity is fixed

## Tests/guards

- no `@Ignore`
- no weak only-not-null assertions
- fixed `TimeProvider` in period tests
- mixed-currency cases included
- failed conversion cases included
- production raw/deprecated analytics calls guarded

---

# Definition of done

Engine 2 can be marked **GREEN candidate** when:

```text
- SpendingSummary.aggregate is populated and tested
- analytics money helpers cannot crash on null/blank currency
- area/travel analytics production path uses normalized APIs
- selected-period insights are behavior-tested
- budget-vs-actual FX/data-quality tests pass
- includeDepositsForBehavior is implemented or removed/deprecated
- raw spending personality path is guarded from production
- historical category identity is honestly deferred
- docs/tracker match actual state
- compile/unit/check validation passes or unrelated failures are documented
```

Engine 2 should remain **YELLOW** until those are complete and final validation passes.