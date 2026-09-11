# RP-06 - Pace, synthesis, and money-quality contracts

> **Status:** corrected implementation plan; documentation only.
> **Mode:** strict. This slice crosses dashboard, forecasting, currency, and health-score boundaries.
> **Depends on:** RP-05 for the dashboard windows and normalized historical inputs.
> **Owns:** pace wiring, synthesis conversion/failure semantics, health-score availability, aggregate quality, and currency-rate policy.
> **Does not own:** runway display edge cases (RP-07), budget history (RP-08/RP-09), or any Room schema change.

The previous draft was not implementation-ready. It assumed that `SpendingPaceCalculator` could consume the dashboard's raw entities, claimed that stale-rate behavior would remain unchanged while two policies already disagree, treated a count mismatch as a warning-only condition, and proposed throwing from an aggregate helper whose callers currently expect a usable result. The contracts below are the required correction.

## Verified source contracts

- `SpendingPaceCalculator.calculate(...)` accepts `List<ExpenseSnapshot>` and explicitly requires the caller to normalize `effectiveAmount` into one currency. It returns `pacePercentage = -1f` and `PaceStatus.NO_BASELINE` when the previous-month daily baseline is not positive. The half-open date test (`date < currentWindowEnd`) is part of the contract.
- `ComputeDashboardWidgetsUseCase` currently constructs a `SpendingPace` manually for `assembleNormalized` and hard-codes `NO_BASELINE`; `SynthesisEngine` only consumes the value. There is no later refinement step.
- `NormalizedForecastInput.normalizedExpenses` is home-currency data, but recurring-pattern amounts remain in their source currency. Planned amounts are normalized by `MoneyNormalizationEngine`. The synthesis engine therefore still has legitimate asynchronous conversion work for future obligations and patterns.
- `SynthesisEngine.convertAmount` is private, non-suspending, and calls `runBlocking` around the suspend converter. Several conversion sites exclude failures, while other `?: sourceAmount` branches add a source-currency value to a home-currency total.
- `FinancialHealthScoreV2.calculateHealthScore` catches broad failures and returns an all-50 `FinancialHealthResult`; its normalization failure path can fall back to raw `Expense` snapshots. Successful results are persisted by `saveToHistory`.
- `MoneyAggregateBuilder` defaults missing `transactionCounts` to zero. `MoneyAggregate.isPartial` is currently derived from conversion failures, and `MoneyAggregateResult` already provides `Available`/`Unavailable` for callers that need a typed unavailable state.
- `CurrencyConverter.convert` hard-codes a 24-hour freshness check. `StaleRatePolicy.Default` is 24 hours, while `StaleRatePolicy.LatestDefault` is seven days and `forBasis(LATEST_AVAILABLE)` selects the latter. These are different policies and must not be described as equivalent.
- `MultiCurrencyRepository.updateExpenseCurrency` is a no-op stub. Its deletion or retention must be based on a production-caller inventory; this plan does not invent a bulk currency migration.

## 6a - Canonical pace and currency-change invalidation

### P5-002 / NEW-P6-013 - Make the pace widget reachable without mixing currencies

1. In `ComputeDashboardWidgetsUseCase`, convert the already-normalized dashboard expenses to the canonical `ExpenseSnapshot` shape (or pass the existing normalized snapshots if the adapter can expose them without reconversion). Call `SpendingPaceCalculator.calculate` with explicit current and previous month starts, the previous-month exclusive end, the dashboard reference time, and the resolved home-currency code.
2. Do not reconstruct pace from `MoneyAggregate.displayAmount` and do not pass `ExpenseSnapshot`s whose `currency` differs from the resolved display currency. A missing rate must remain excluded and visible through the existing data-quality metadata; it must never become a source-currency fallback.
3. Replace the manually constructed `SpendingPace` and the `NO_BASELINE` placeholder in the normalized dashboard path. Keep the legacy `ForecastInputAssembler.buildSpendingPace` until its callers are migrated; add a same-input parity test before considering either path for deletion.
4. Change the assembler's no-baseline percentage to the canonical `-1f` sentinel. Audit every percentage consumer: `-1f` is legal only when `paceStatus == NO_BASELINE`, and must not reach formatting, threshold, or chart math.
5. Keep the existing purchase/ownership rules in the calculator: purchases only and `!isNotMine`; preserve the half-open current-window boundary. Do not make shared-expense changes here; RP-05 owns the dashboard shared flag.

**Acceptance tests**

- A normalized previous-month baseline produces `UNDER_PACE`, `ON_PACE`, or `OVER_PACE` and causes the pace widget/insight to be emitted through the real dashboard path.
- No completed baseline produces `NO_BASELINE`, `pacePercentage == -1f`, no pace widget, and no display of `-1` or `0%`.
- A mixed-currency fixture with one missing rate is partial and never adds the original amount to the pace total.
- Historical reference time uses the period end, not wall-clock `now`; the boundary at `currentWindowEnd` is excluded.
- Legacy assembler and normalized dashboard paths agree for the same normalized snapshots.

### P5-006 - Recompute after a home-currency change

The repository already invalidates its cache from `CurrencySettingsRepository.homeCurrency()`, while `HomeViewModel.processedDataFlow` is driven by `dashboardReloadTrigger` and database emissions. Preserve the existing reload architecture:

1. Identify the single collector that observes the home-currency change. After cache invalidation has completed (or from the same collector if ordering cannot be guaranteed), increment `dashboardReloadTrigger` once.
2. Do not add a second currency subscription inside `DashboardDataProvider`; that would change emission behavior for every dashboard consumer and could create duplicate recomputations.
3. Ensure the first-run/default/failed home-currency states retain their existing fail-closed behavior. A failed resolution must not trigger a recompute with a fabricated EUR value.

**Acceptance test:** emit a new resolved home currency, await one reload, and assert that the processed dashboard recomputes its amount and currency labels. Assert that one change does not produce an unbounded reload loop.

### REVAL-8 - `updateExpenseCurrency`

Before implementation, run a production and test caller inventory (including Hilt bindings and reflection). If there are no callers, remove the stub in the designated cleanup change and record the zero-caller evidence. If a caller exists, stop and design a separate currency-migration feature with a transaction-lifecycle owner, historical-rate policy, rounding rules, and migration tests. Do not turn this stub into an ad-hoc DAO write in RP-06.

## 6b - Synthesis money correctness

### P5-007 - Remove blocking conversion and define rate bases

`SynthesisEngine` must not call `runBlocking` from a dashboard/forecast calculation. Because the production call chain is already suspend-capable (`FinancialWeatherRepository` flow transform, `CalculateFinancialForecastUseCase.synthesizeForecast`, and dashboard production), make the conversion-bearing synthesis path suspend and update all three callers plus tests to use `runTest`. Do not add a new `runBlocking` adapter for old tests.

Required migration:

- Make `convertAmount` suspend and thread suspension through `synthesizeInternal`, `calculateBlockPartyData`, and every conversion loop. Keep a pure/internal path only for fixtures that contain amounts already in the target currency; it must not silently bypass conversion for source-currency data.
- Use an explicit typed conversion outcome. For historical actuals already normalized by RP-05, no second conversion is permitted. For future recurring/planned obligations, use `RateBasis.LATEST_AVAILABLE` and `StaleRatePolicy.LatestDefault` (seven-day latest-rate policy) unless the product owner explicitly selects the 24-hour policy; whichever is selected must be named in code and tests. `CurrencyConverter.convert`'s legacy 24-hour behavior is not evidence for the latest-basis contract.
- Keep the basis mix explicit: historical spent-to-date uses transaction-date normalization; future obligations use latest available rates. Add KDoc and quality metadata to the forecast explaining that the resulting forward-looking KPI intentionally combines those bases.
- Propagate stale/missing-rate outcomes into `ForecastDataQuality.isPartial`, excluded counts, and controlled warnings. A stale rate is not a raw-value fallback and does not become a successful notification/metric.

### P5-CURRENT-009 / U-MONEY-01 - Block-party and fallback audit

`calculateBlockPartyData` currently receives raw entities from the dashboard and may prefer `effectiveAmount` sums over the normalized daily map. Change the contract as follows:

1. Pass normalized daily values as the authoritative actual-spend input. If raw entities are retained for merchant/date metadata, convert each item through the same typed converter before it participates in arithmetic.
2. Remove every `?: sourceAmount`, `?: monthly`, `?: raw`, and `?: pattern.averageAmount` fallback in the synthesis money path. On conversion failure, exclude the item, increment the appropriate excluded counter, and mark the forecast partial. The only permitted fallback is a same-currency identity conversion.
3. Ensure a day with no raw list but a normalized daily value uses that normalized value; do not let an empty raw list overwrite a valid normalized zero/non-zero distinction.
4. Preserve single-currency golden outputs. Enumerate multi-currency deltas in the implementation review, including block-party status and `actualSpent` changes.

**Acceptance tests:** missing-rate recurring, planned, block-party, and pattern fixtures; no source amount appears in a home-currency sum; all single-currency goldens remain unchanged; `isPartial` and excluded counts are asserted.

## 6c - Health-score availability and aggregate quality

### P5-008 - Typed unavailable health score

Introduce a domain result such as:

```text
sealed interface HealthScoreOutcome {
  data class Available(val result: FinancialHealthResult) : HealthScoreOutcome
  data class Unavailable(val reason: HealthScoreUnavailableReason) : HealthScoreOutcome
}
```

`HealthScoreUnavailableReason` must be a closed set of controlled codes (for example `HOME_CURRENCY_UNAVAILABLE`, `NORMALIZATION_FAILED`, `DATA_LOAD_FAILED`). The implementation must:

- remove the raw `Expense.toExpenseSnapshot()` fallback from the score calculation;
- return `Unavailable(NORMALIZATION_FAILED)` when normalization returns no result or `excludedCount > 0` (including missing, stale, or invalid-currency rows); an empty but valid single-currency input remains eligible for the existing neutral component policy;
- never fabricate an all-50 result for a calculation failure and never call `saveToHistory` for `Unavailable`;
- rethrow `CancellationException` before the broad diagnostic catch, as required by RP-01;
- keep existing neutral component scores only for an explicitly valid, single-currency data set with no income/baseline (that is a product rule, not an error result).

Update every direct caller and test (`ComputeDashboardWidgetsUseCase`, `HomeScreen` widget production, health-score unit/golden tests, dashboard mocks, and consistency tests). The dashboard maps `Available` to the existing widget and maps `Unavailable` to no widget/unknown UI state with a controlled diagnostic. History readers must tolerate the absence of a row. Do not persist a placeholder row.

### NEW-P5-009 - Count-integrity is partial data

When `transactionCounts.size != buckets.size`, do not silently manufacture zero counts. Add an explicit `countsIncomplete` (or equivalent) field to `MoneyAggregateMetadata`; set it and mark the aggregate partial with a controlled warning. Preserve the amounts that were actually converted, but make count-dependent consumers treat counts as unknown/approximate. Audit rollover, budget, dashboard, analytics, export, and diagnostics consumers before changing their behavior. Do not use a negative count sentinel unless the model explicitly supports it.

Add tests for shorter, longer, and empty count lists, plus a regression proving a complete count list is unchanged.

### NEW-P5-012 - Stale-rate policy is explicit

Do not inject an ambiguous global TTL and do not claim the current behavior is unchanged. Centralize the legacy `convert` path on `StaleRatePolicy.Default` (24 hours) if compatibility is required, while rate-basis-aware callers select `StaleRatePolicy.forBasis`/`LatestDefault` explicitly. Add tests for direct and via-EUR rates at 24-hour and seven-day boundaries, and tests that historical `convertAsOf`/transaction-date paths are not accidentally subjected to latest-rate TTL rules. Update KDoc and DI only after the policy decision is recorded.

### NEW-P5-013 - Unknown bucket cannot become an empty total

The current `List<*>` helper returns `MoneyAggregate.empty` for an unknown bucket type, which can silently erase money and can also break callers if changed to an uncaught throw. Prefer typed overloads for `CategoryCurrencyTotal`, `MerchantCurrencyTotal`, and `MonthlyCurrencyTotal`. If a compatibility boundary still accepts an untyped list, return `MoneyAggregateResult.Unavailable(reason = UNKNOWN_BUCKET_TYPE, requestedRateBasis = ...)` and make each caller map that result to its existing unavailable/partial contract. Diagnostics may include a bounded class name, but the reason field must remain a controlled constant.

Add tests proving unknown input never becomes a zero total and that dashboard, budget, analytics, and export callers fail closed without crashing unrelated core work. Do not use an empty aggregate as an error signal.

## Sequencing and stop conditions

1. Land pace wiring and sentinel/consumer tests.
2. Migrate synthesis to suspend conversion and typed rate outcomes; then remove raw fallbacks and add block-party tests.
3. Migrate health-score callers and persistence gating.
4. Add aggregate count/unknown-bucket quality contracts and run cross-consumer tests.

Stop and request architecture review if a caller still passes raw mixed-currency snapshots to pace/synthesis, a conversion failure is replaced by a source amount or fabricated zero, a health failure produces/persists a score, a new `runBlocking` is required, or an unknown bucket can still be represented as a successful empty total. Stop for any Room/schema change; none is authorized here.

## Validation (not run for this documentation change)

After implementation and strict review, run sequentially with output captured:

```text
./gradlew :app:testDebugUnitTest --tests "*SpendingPace*" --tests "*ForecastInputAssembler*" --tests "*SynthesisEngine*" --tests "*ForecastSynthesisGolden*" --tests "*FinancialHealthScoreV2*" --tests "*HealthScoreGolden*" --tests "*MoneyAggregate*" --tests "*CurrencyConverter*" --tests "*DashboardCurrencyIntegration*" --console=plain
./gradlew :app:compileDebugKotlin --console=plain
```

No Gradle command was run while preparing this plan.
