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

---

## Slice status

### C1 — shared domain-money contract + repository API: **partial** (implemented, tests NOT RUN)

Implemented (2026-09-19), following the RP-06 doc status style:

- `domain/core/money/CategoryMonthlySpend.kt` — new shared contract types:
  `CategoryMonthlySpend(scope, monthKey, aggregate)` + `SpendScope.Overall` /
  `SpendScope.Category(categoryId: Long?)` (null = uncategorized), with the full
  contract KDoc (PURCHASE-only, `TRANSACTION_DATE` normalization, `TimePeriodUtils`
  month-key policy, half-open range, no rate-sentinel substitution, typed failures
  propagate — never an empty list).
- `MultiCurrencyRepository.getHistoricalCategoryMonthlySpend(startDate, endDate)` —
  new API per plan lines 46-75: separate `Overall` scope rows (including uncategorized
  rows) + real `Category(null)` buckets, per-expense `TRANSACTION_DATE` normalization
  via `MoneyNormalizationEngine`, repository-owned bounded home-currency resolution
  (throws `HomeCurrencyUnavailableException` on failure), no reflection, no deprecated
  DAO call, no `runCatching` (so `CancellationException` propagates unchanged).
- Tests: `MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest.kt` (10 tests) —
  PURCHASE-only filtering, uncategorized bucket, Overall separation, month-key policy +
  half-open pass-through, multi-month grouping, multi-currency transaction-date
  conversion, typed partial outcome for conversion failure, empty range, typed
  home-currency failure propagation.

**Deviation (recorded):** the grouped
`(categoryId, monthKey, currency, txDate, total, txCount)` DAO projection was **not**
added in C1 — the C1 lane constraint is "do not touch ExpenseDao at all in this slice"
(the dedupe region of the DAO is owned by a parallel lane). The plan-permitted
alternative (bounded row read inside the repository, plan line 73-74) is used:
`getExpensesBetweenUncapped(startDate, endDate)` — a half-open, `isNotMine = 0`-filtered,
read-only SQL range read with no row cap. C2 may swap in the grouped projection behind
the unchanged method signature; the KDoc records this migration intent.

**Tests NOT RUN** (C1 is static-only; no Gradle/compile execution in this slice). Status
stays *partial* until the validation runner confirms compile + targeted
`*MultiCurrencyRepository*` shard PASS. C2 (engine de-reflection P6-002/003) and C3
(series-builder/quality P6-004) are **pending**.

### C2 — engine de-reflection + series history quality (P6-002/P6-003/P6-004 core): **partial** (implemented, validation NOT RUN)

Slice C2 status (2026-09-19) — implemented, validation NOT RUN.

Implemented in this slice (static edits only; no Gradle/build/test execution):

- **P6-002 (de-reflection):** `BudgetAutopilotEngine` no longer reflects on
  `MultiCurrencyRepository`'s private `expenseDao` field and no longer calls the deprecated
  `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween`. The category-history input is now
  `multiCurrencyRepository.getHistoricalCategoryMonthlySpend(start, end)` (the C1 API). The
  engine's `@Suppress("DEPRECATION_ERROR")` is removed; the CancellationException rethrow
  discipline (RP-01) is preserved — typed `HomeCurrencyUnavailableException` propagates through
  the engine's existing catch-and-rethrow path and is never swallowed to an empty history.
- **P6-003 (no unlike-currency sums, no sentinel/empty flattening):** all history math consumes
  the C1 `MoneyAggregate` rows. The overall-budget path no longer calls the deprecated
  `getMonthlyTotalsInHomeCurrency` (whose `Result.Error` flattened to `emptyList()`); overall
  budgets read `SpendScope.Overall` rows from the same C1 result. Conversion failures surface as
  partial aggregates (`MoneyAggregate.isPartial`) → `BudgetRecommendationQuality.PARTIAL_DATA`,
  never sentinel substitution, never a silent `emptyList()`.
- **P6-004 core (series builder):** `BudgetHistorySeriesBuilder.build` gains
  `excludeIncompleteEdgeMonths: Boolean = true` (window trimmed to local month boundaries; a
  trailing bucket derived from `windowEndExclusive - 1` counts as complete only when the window
  end lands exactly on a month boundary) and an honest `completeMonthCount` in both modes.
  Both callers updated: `BudgetAutopilotEngine` and `BudgetForecastingEngine` (the forecasting
  engine's stale KDoc mention of the deprecated DAO path removed).
- **P6-004 quality contract (part of C3 landed early):** `BudgetRecommendationQuality
  { COMPLETE, PARTIAL_DATA, LOW_HISTORY }` + `isActionable` on `CategoryBudgetRecommendation`.
  Fewer than 2 complete months of history (`MIN_COMPLETE_HISTORY_MONTHS = 2`) → identity
  recommendation (current budget kept), `quality = LOW_HISTORY`, `isActionable = false`.
  ±15% bounds and safety factors unchanged. Quality is not persisted (no schema change).
  `BudgetViewModel` gating (Apply/Apply-All reject non-actionable, sanitized
  `BUDGET_HISTORY_UNAVAILABLE`/constant error states replacing the raw `e.message` leaks at the
  three autopilot call sites) was included in this slice so the contract is end-to-end
  fail-closed; `BudgetScreen` UI text/decorations remain for the later slice.
- **Tests:** the invalid 85.0-for-empty-history pins (old lines ~176/262/338) are removed —
  empty/low history now asserts identity + `LOW_HISTORY` + `isActionable=false` per plan line 174.
  `BudgetHistorySeriesBuilderTest` rewritten for edge-exclusion semantics (trailing/leading
  mid-month exclusion, boundary-aligned window, legacy `excludeIncompleteEdgeMonths=false`
  regression, `completeMonthCount` pins). `BudgetTrendBoundaryTest` rewritten (plan-mandated
  rewrite, not deletion-to-pass): it stubbed the deprecated DAO path the forecasting engine no
  longer calls; the ±10% boundary assertions now run through the live
  snapshot→normalizer→series path. `BudgetForecastingEngineTest` pins recomputed for the
  trimmed-window semantics (golden values updated in the same change per the money-semantics
  rule; no float/rounding changes). New autopilot tests: empty-history identity/LOW_HISTORY,
  single-complete-month LOW_HISTORY, two-complete-month bounded path, PARTIAL_DATA-not-zero
  (conversion failure ≠ empty history), Overall/Category scope routing, typed repository failure
  propagation, autopilot/forecasting shared-series parity.

**Deviations (recorded):**
1. `BudgetViewModel` gating + sanitized error constants landed inside C2 (the plan assigns them
   to C3) so the new `isActionable=false` contract is enforced at the mutation boundary
   immediately; without it, a LOW_HISTORY identity recommendation could still be applied via
   Apply All. C3 remaining: `BudgetScreen` rendering of quality/isActionable and any
   copy changes.
2. The deprecated `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween` block is **not**
   deleted in this slice (lane-B constraint; C4 defers the deletion until after the parallel
   ExpenseDao changes land). `BudgetAutopilotEngine` no longer references it.

**Tests NOT RUN** (C2 is static-only). Status stays *partial* until the validation runner
confirms compile + targeted `*Budget*` / `*MultiCurrencyRepository*` shards PASS **and** the
plan-mandated `assembleRelease` (R8) gate passes — unit tests cannot prove the R8 reflection
failure is gone.

### C3 — autopilot quality UI (P6-004 low-history result/UI contract): **partial** (implemented, validation NOT RUN)

Slice C3 status (2026-09-19) — implemented, validation NOT RUN.

Implemented in this slice (static edits only; no Gradle/build/test execution):

- **`BudgetScreen.kt` (display-only):**
  - `AutopilotRecommendationItem` renders a bounded quality chip for non-`COMPLETE`
    recommendations (`AutopilotQualityChip`): `LOW_HISTORY` → "Low history",
    `PARTIAL_DATA` → "Partial data", both `SemanticColors.WarningOrange`; `COMPLETE` renders
    nothing (normal Apply behavior unaffected).
  - Non-actionable (`isActionable == false`, i.e. `LOW_HISTORY`) recommendations keep their
    identity value display (current → recommended, both = current budget) but their Apply
    `TextButton` is `enabled = recommendation.isActionable` — the destructive-sounding apply
    affordance is disabled for LOW_HISTORY. The ViewModel still fails closed with
    `ERROR_AUTOPILOT_NOT_ACTIONABLE` on any apply attempt (defense in depth).
  - Apply All button is disabled when **no** recommendation is actionable; the banner's mixed
    case (some actionable, some LOW_HISTORY) keeps Apply All enabled and relies on the
    ViewModel's fail-closed filtering (existing C2 behavior).
  - Banner text stays hardcoded Compose literals, matching the file's existing autopilot-section
    style ("AI Budget Autopilot", "Analyze", "Apply All", "Dismiss All", "Apply" are all
    literals; the budget-card/summary sections use `stringResource`, but the autopilot section
    never did) — no strings.xml expansion, keeping this lane disjoint from other lanes'
    resource files.
- **No business logic added to the screen**: quality comes from the `CategoryBudgetRecommendation`
  objects the ViewModel already exposes. Verified `BudgetUiState.autopilotRecommendations`
  carries the full recommendation objects (C2 added `quality`/`isActionable` there) — **no
  ViewModel mapping change was needed**; `BudgetViewModel.kt` is untouched in this slice.
- **Tests:** new `BudgetAutopilotUiContractTest` (ViewModel-level; follows the
  `BudgetViewModelStressTest` mockk/`InstantTaskExecutorRule`/`StandardTestDispatcher` harness
  style but is NOT `@Ignore`d — the stress harness is a ledgered hang suspect and the rule
   forbids new `@Ignore`. Because `uiState` is `stateIn(WhileSubscribed)`, each test keeps an
   unconfined background collector on the StateFlow (runTest `backgroundScope`) so upstream
   collection is active for assertions):
  - `LOW_HISTORY recommendation is exposed with isActionable false and apply attempt fails closed`
    — apply attempt yields `ERROR_AUTOPILOT_NOT_ACTIONABLE` and zero budget writes;
  - `PARTIAL_DATA recommendation remains applicable and labeled` — quality flows to the UI state
    and the apply path performs the budget write;
  - `COMPLETE actionable recommendation is unaffected by quality gating` — normal apply succeeds;
  - `apply all with only LOW_HISTORY recommendations fails closed with typed error` — zero budget
    writes, `ERROR_AUTOPILOT_NOT_ACTIONABLE`;
  - `generate failure from repository history surfaces sanitized constant only` —
    `HomeCurrencyUnavailableException` maps to `ERROR_BUDGET_HISTORY_UNAVAILABLE`; the raw
    exception message never reaches the UI state.
  No existing Compose UI test module pattern exists for budget screens (only
  `androidTest` DAO tests), so ViewModel-level tests are used per the slice contract.

**Deviations (recorded):** none. No ±15% bounds, safety-factor, or rounding changes; no
quality persistence; no raw `e.message` introduced; no CancellationException catch sites touched.

**Still outstanding:**
- **C4 (`ExpenseDao.getMonthlySpendingTotalsByCategoryBetween` deletion)** remains **deferred
  pending lane-B coordination** — the deprecated method block is left untouched until the
  parallel ExpenseDao lane lands; `BudgetAutopilotEngine` no longer references it.
- The **plan-mandated `assembleRelease`/R8 gate** is still outstanding — unit tests cannot
  prove the R8 reflection failure is gone.

**Tests NOT RUN** (C3 is static-only). Status stays *partial* until the validation runner
confirms compile + targeted `*BudgetViewModel*` / `*BudgetAutopilot*` shards PASS **and** the
plan-mandated `assembleRelease` (R8) gate passes.
