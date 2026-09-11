# RP-08 - Budget autopilot correctness (Pipeline 6, release-critical)

> **Status:** CONDITIONAL until the repository/result contract below is approved.
> **Mode:** strict (money semantics, release/R8 behavior, and UI recommendation safety).
> **Scope:** `BudgetAutopilotEngine`, `MultiCurrencyRepository`, `BudgetHistorySeriesBuilder`,
> `BudgetForecastingEngine` callers, `BudgetViewModel`/`BudgetScreen`, `ExpenseDao`, and the
> affected unit/release tests.
> **Important source fact:** `MultiCurrencyRepository` is currently a concrete `@Singleton`
> (`data/repository/MultiCurrencyRepository.kt`), not an interface/implementation pair. Do not
> introduce a DI split as part of this fix unless a separate architecture decision approves it.

---

## P6-002/P6-003 - Remove the R8 reflection bridge and the raw mixed-currency SUM

### Defect

`BudgetAutopilotEngine` reflects on the private `expenseDao` field and calls the deprecated
`ExpenseDao.getMonthlySpendingTotalsByCategoryBetween`. R8 can rename the field, the exception is
currently swallowed, and an empty history is then converted into a destructive -15% recommendation.
When reflection happens to work, the DAO query still sums unlike currencies as if they were one
unit.

### Contract gate (must be settled before coding)

Add a shared domain-money value object consumed by both the repository and engine (for example,
`domain/core/money/CategoryMonthlySpend.kt`):

```kotlin
data class CategoryMonthlySpend(
    val scope: SpendScope,
    val monthKey: String,
    val aggregate: MoneyAggregate
)

sealed interface SpendScope {
    data object Overall : SpendScope
    data class Category(val categoryId: Long?) : SpendScope // null means uncategorized
}
```

`MoneyAggregate` is the canonical aggregate type. It carries the home currency, rate basis,
partial flag, conversion failures, and excluded transaction counts; do not duplicate those fields
as unrelated `Double`/`Boolean` values.

Add this method to the existing concrete `MultiCurrencyRepository`:

```kotlin
suspend fun getHistoricalCategoryMonthlySpend(
    startDate: Long,
    endDate: Long
): List<CategoryMonthlySpend>
```

The method contract is fixed as follows:

- half-open range `[startDate, endDate)`;
- local-time month keys produced by the same `TimePeriodUtils` policy as
  `BudgetHistorySeriesBuilder`;
- `PURCHASE` transactions only, excluding transfers, income/deposits, and `isNotMine` rows;
- uncategorized rows (`categoryId == null`) are a real `SpendScope.Category(null)` bucket;
  the overall budget is a separate `SpendScope.Overall` aggregate across every included category,
  including uncategorized rows. Never use a nullable category id alone to represent both scopes;
- every source row is normalized with `RateBasis.TRANSACTION_DATE` using its transaction date;
- missing/invalid rates are excluded only by the normalizer and surfaced through
  `MoneyAggregate.isPartial`, `conversionFailures`, and `metadata.excludedTransactionCount`;
- no latest-rate or home-currency sentinel is substituted for a failed conversion;
- home-currency resolution/DAO failures propagate as the existing typed repository failure (or
  `HomeCurrencyUnavailableException`) to `BudgetViewModel`; they must not become an empty list.

Prefer a grouped DAO projection of `(categoryId, local monthKey, currency, transaction-date,
total, transactionCount)` and feed those dated buckets to `MoneyNormalizationEngine` with
`BucketDatePolicy.RequireBucketDate`. A row read bounded by the requested date range is acceptable
only inside the data repository when a grouped projection cannot preserve the required rate date.
It must not expose a DAO to the domain engine or reintroduce a raw mixed-currency SUM.

### Implementation

1. Implement the contract above in `MultiCurrencyRepository`, using the existing money
   normalization/conversion pipeline and preserving per-month/per-category aggregates.
2. In `BudgetAutopilotEngine`, load the new list once for the three-month window and index it by
   `(scope, monthKey)`. Use `Overall` for an overall budget and `Category(id)` for category
   budgets, including `Category(null)` for uncategorized-only views; do not retain a special raw
   DAO branch. Test an overall budget containing both categorized and uncategorized purchases.
3. Remove the reflection block, its suppression, and the catch-to-`emptyList` fallback. A repository
   failure must reach the existing ViewModel error state.
4. Remove `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween` only after a repository-wide
   caller search proves it has no remaining callers. Keep the DAO's other queries unchanged.
5. Remove the now-unused `currencySettingsRepository.homeCurrency().first()` read from the engine;
   the repository owns bounded home-currency resolution for this API.

### Required tests

- mixed EUR/USD/JPY category history with transaction-date rate changes;
- overall aggregate parity across categorized plus uncategorized rows, and a separate uncategorized
  category bucket;
- missing-rate month reports `isPartial` and an excluded count without a fabricated total;
- repository/home-currency failure reaches the ViewModel as a typed/sanitized
  `BUDGET_HISTORY_UNAVAILABLE`-style state, never an arbitrary exception message;
- category and overall recommendations are identical in debug and release code paths;
- static check proves no reflection or deprecated DAO call remains;
- `assembleRelease`/R8 invocation proves the release path does not depend on member names.

---

## P6-004 - Do not treat the in-progress month as complete history

### Defect

`BudgetHistorySeriesBuilder.build` derives the final month from `windowEndExclusive - 1`, so a
window ending in the middle of a month includes an incomplete bucket as if it were complete. This
causes the early-month downward ratchet in autopilot and understates forecast history.

### Implementation contract

1. Align the requested history window to local month boundaries before building the series, or add
   explicit leading/trailing exclusion. The current three-month caller starts mid-month as well as
   often ending mid-month, so both partial edge buckets must be excluded from trend/volatility and
   from `completeMonthCount`.
2. Add `excludeIncompleteEdgeMonths: Boolean = true` to `BudgetHistorySeriesBuilder.build`. If an
   edge is not a local month boundary, exclude that month; if it is a boundary, the adjacent month
   remains eligible. Use the same timezone policy at both ends.
3. Keep zero-fill semantics for gaps between the first and last included complete months. Add an
   explicit `completeMonthCount` (or an equivalent unambiguous field) so callers do not infer
   completeness from raw row count.
4. Update both autopilot and forecasting callers and document that no pro-rating is performed.

For every category/overall series, map `MoneyAggregate.isPartial`, excluded counts, and conversion
warnings into the recommendation quality. Mixed partial months must produce `PARTIAL_DATA` even
when other months are complete; quality must not be inferred from a numeric zero. Repository or
home-currency failures reach the ViewModel as a typed/sanitized error code (for example,
`BUDGET_HISTORY_UNAVAILABLE`), never an arbitrary exception message.

### Low-history result/UI contract

There is currently no `LOW_HISTORY` production model. Add one rather than encoding it only as a
number:

```kotlin
enum class BudgetRecommendationQuality { COMPLETE, PARTIAL_DATA, LOW_HISTORY }

data class CategoryBudgetRecommendation(
    /* existing fields */,
    val quality: BudgetRecommendationQuality = BudgetRecommendationQuality.COMPLETE,
    val isActionable: Boolean = true
)
```

Propagate the quality to `BudgetAutopilotRecommendations`, `BudgetViewModel`, and the autopilot
Composable. Define the behavior explicitly:

- fewer than **two complete months**: recommendation equals the current budget, quality is
  `LOW_HISTORY`, and `isActionable == false` (the UI must not offer Apply/Apply All);
- complete history with excluded conversions: quality is `PARTIAL_DATA`, the bounded recommendation
  is retained, and the UI shows the existing data-quality warning before any apply action;
- complete, fully converted history: quality is `COMPLETE`, and normal Apply behavior is allowed.

Give new fields compatibility defaults so existing constructors remain source-compatible while
callers migrate. `BudgetViewModel.applyAutopilotRecommendation` and Apply All must reject
`isActionable == false`; no budget write is allowed for `LOW_HISTORY`.

Do not persist a new recommendation row or serialize a quality field unless an existing persistence
contract is found; the current autopilot result is in-memory. Do not change the +/-15% safety bounds
or safety-factor math.

### Required tests

- leading and trailing partial months at local month and timezone boundaries;
- zero-filled gaps with exactly 0, 1, and 2 complete months;
- empty/one-month autopilot returns identity + `LOW_HISTORY` and cannot be applied;
- two complete months still exercise the bounded adjustment path;
- partial conversion produces `PARTIAL_DATA` and an explicit warning;
- forecast confidence/history count uses complete months only;
- update the old test that pinned 85.0 for empty history; that expectation is invalid.

---

## Validation and gates

The plan is not complete until the contract, tests, and release check pass:

```text
./gradlew :app:testDebugUnitTest --tests "*BudgetAutopilot*" --tests "*BudgetHistorySeries*" \
  --tests "*BudgetForecasting*" --tests "*MultiCurrencyRepository*" --tests "*BudgetCalculatorGolden*"
./gradlew :app:assembleRelease
```

Tests were not run while this plan was rewritten. The release build is mandatory because unit tests
cannot prove the R8 failure is gone. Land RP-08 before RP-09's forecast-basis work; the two plans
must use the same transaction-date historical semantics and explicit partial-data signaling.
