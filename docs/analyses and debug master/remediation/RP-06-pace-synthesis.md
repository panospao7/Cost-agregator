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

---

## Ported onto reconciled mainline (2026-09-18, lane `rp-06-wip` refreshed at `a0c4ae0d`)

A parallel session independently landed batch 6a on `bug-fixes` (`0f1f587b`,
golden-validated, incl. golden-master updates and the stale
`ForecastInputAssemblerTest` fix) alongside a validated RP-10c with its own
close-out (`419e38c0`/`2d128468`, deduper `tryStart` polarity fix,
suspend-`Continuation` CleanupTest pin). The reconciliation merge `a0c4ae0d`
took origin's implementations for all overlapping code. This lane's original
6a variant is archived at `rp-06-6a-superseded` (66a0414d) — do not merge it.

Ported from the lane onto the refreshed lane (unique work origin does not
have):

- **P5-006**: `HomeViewModel` bumps `dashboardReloadTrigger` exactly once per
  real home-currency change, from the same collector that loads category
  trends (ordering with the repository's separate cache-invalidation collector
  cannot be guaranteed, per plan). First emission does not count as a change;
  no loop (the trigger flow never observes home currency).
- `ForecastInputAssemblerPaceSentinelTest` — legacy `buildSpendingPace` vs
  canonical `SpendingPaceCalculator` parity + -1f sentinel pins. Origin's
  assembler carries the identical sentinel, so the contract holds on this base.
- `HomeViewModelCurrencyReloadTest` — the P5-006 executable spec; hangs under
  the runner (MockK + ViewModel-construction family, same as
  `HomeViewModelStressTest`) and stays `@Ignore`d with evidence; revive with
  the RP-21 harness.
- **REVAL-8 evidence** (re-verified on the reconciled tree):
  `MultiCurrencyRepository.updateExpenseCurrency` still has **zero callers**
  (declaration only). Removal stays deferred to the designated cleanup change.

Next: batch 6b complete on lane rp-06-wip (uncommitted); remaining per plan:
commit + merge decision, optional full unit-tests / static-guards gate before
merge.

### 6b slice 1 (P5-007) — implemented, validated (2026-09-19)

- `SynthesisEngine.convertAmount` migrated off `runBlocking` onto the typed
  suspend path: `convertOutcome(..., rateBasis = RateBasis.LATEST_AVAILABLE,
  stalePolicy = StaleRatePolicy.LatestDefault)` — both named explicitly in
  code per plan. Identity same-currency early-return kept. Failed outcomes
  keep the per-site exclude-and-count (`recurringConversionFailures++` →
  `excludedCount`/`isPartial`); a bounded Timber.w logs only the controlled
  failure-type constant.
- `synthesize(input)` / internal `synthesize` / `synthesizeInternal` are all
  `suspend`; the broad catch rethrows `CancellationException` first (RP-01).
  No new `runBlocking` anywhere in production code.
- Production callers migrated/verified suspend-capable:
  `FinancialWeatherRepository` (combine transform), `CalculateFinancialForecastUseCase`
  (`synthesizeForecast`), `ComputeDashboardWidgetsUseCase` (`computeRunwayAndForecast`).
  `FinancialStressForecastEngine` holds the engine but does not call
  `synthesize` — untouched.
- Tests migrated to `runTest` (no runBlocking adapter added):
  `SynthesisEngineTest` (10), `SynthesisEngineStressTest` (54 plain tests
  converted; executor-thread inner `runBlocking` kept as structurally
  necessary), `SynthesisEngineGoldenTest` (3), `SynthesisEngineBlockPartyPaidExclusionTest`
  (3), `CalculateFinancialForecastUseCaseTest` (6 MockK `every`→`coEvery`),
  `FinancialWeatherRepositoryTest` (10 MockK `every`→`coEvery`).
  `ForecastRunwayIntegrationTest` was already `runTest`-based — unchanged.
- Slice-2 surface untouched: raw fallbacks at engine lines ~385/397
  (`?: expense.amount`), block-party fallbacks (~513/531/588/604), and the
  `effectiveAmount` actual-spend path.
- Status: implementation complete, **validated** (2026-09-19): compile PASS
  (vr-20260919-124317-74a75cff), `*SynthesisEngine*` targeted shard PASS
  (vr-20260919-124955-afc3e3b1), caller shards PASS
  (vr-20260919-130242-d27cfb7a `*FinancialWeatherRepositoryTest*`,
  vr-20260919-130443-0a9ee055 `*CalculateFinancialForecastUseCaseTest*`,
  vr-20260919-130728-606af25b `*DashboardCurrencyIntegration*` 7/7, all via
  the runner). Lane not yet merged.

### 6b slice 2 (P5-CURRENT-009 / U-MONEY-01) — implemented, validated (2026-09-19)

**Raw fallback removal (all synthesis money paths)**

- `synthesizeInternal` projection day-maps (mustExpensesByDay /
  likelyExpensesByDay): `?: expense.amount` fallbacks removed. On conversion
  failure the planned expense is EXCLUDED from its day (contributes 0, never
  the source-currency amount) and counted in a new
  `plannedConversionFailures` counter, which now feeds the same
  `excludedCount`/`isPartial` accounting as `recurringConversionFailures`.
- `monthlyRecurringTotal` (synthesizeInternal): was already exclude-only
  (`mapNotNull`, no raw fallback) but silently dropped failures; now counted
  into `recurringConversionFailures` so the excluded count is truthful.
- `calculateBlockPartyData`: all four `?: raw` fallbacks removed
  (totalMonthlyRecurring, totalMonthlyPlanned, recurringOnDay, plannedOnDay).
  Failures now exclude (contribute 0) and count into a local
  `bpConversionFailures`. The only permitted fallback remains same-currency
  identity conversion inside `convertAmount`.
- **Block-party excluded-count surfacing: RESOLVED (RP-06 lane D6,
  2026-09-19 — uncommitted, validation NOT RUN).** `BlockPartyDay` now carries
  a defaulted `conversionFailureCount: Int = 0` field; the engine threads the
  day-scoped failure count into each returned day (bounded, count-only,
  privacy-safe). The single construction site uses named args, so all existing
  callers compile unchanged. The engine-level bounded Timber.w log is kept for
  the total (month-level + day-level). Original slice-2 note: `BlockPartyDay`
  had no field to carry the count and a forced model change was rejected at
  the time — superseded by the lane-D decision; see the "Slice D3+D4+D6
  status" section at the end of this document.

**Normalized-daily-authoritative mismatch (deferred — stop-condition item)**

- Verified the engine comment against the actual caller:
  `ComputeDashboardWidgetsUseCase.computeBlockParty` (~line 761) passes
  `ctx.expenseEntities` RAW into `calculateBlockPartyData` (annotated
  "display-only transaction list, not money math"), while the engine sums
  `it.effectiveAmount` for `actualSpent` — real money math on possibly
  non-normalized entities. The engine comment's claim ("callers must
  normalize") is NOT currently satisfied.
- Per slice instructions: behavior NOT changed, TODO(RP-06 P5-CURRENT-009
  step 1) added at the site. The minimal caller fix (passing the
  already-computed normalized daily map) requires deciding whether raw
  entities stay as the topTransactions/metadata source — that changes
  `actualFromExpenses ?: actualFromHistory` precedence semantics and is NOT
  mechanical. **Deferred to orchestrator decision.** This is also a plan §
  Sequencing stop condition: "a caller still passes raw mixed-currency
  snapshots to pace/synthesis".

**Tests (new file `SynthesisEngineRatePolicyPinTest.kt`, 7 tests)**

- Policy pin (slice-1 reviewer NOTE): slot-captures `rateBasis` and
  `stalePolicy` actually passed to `convertOutcome` in both `synthesize`
  and `calculateBlockPartyData`; asserts `LATEST_AVAILABLE` +
  `LatestDefault`. Real capture-and-assert, no marker.
- Fallback-removal: all-Failed stub (coEvery) with USD→EUR fixtures —
  asserts no USD amount enters totalCommitted/totalLikely (0.0),
  projection endpoint keeps only lastKnownTotal, block-party
  recurringImpact/plannedImpact are 0.0 with baseTarget = budgetLimit/31;
  excludedCount asserted at exact per-call-site totals (6 / 4 / 2 — the
  engine counts per invocation site, e.g. one failed USD recurring pattern
  hits committed segment + monthlyRecurringTotal = 2).
- Mixed single/multi: EUR pattern (identity, unaffected) + USD pattern
  (Failed) → committed keeps 100.0, excludedCount 2, isPartial true.
- Single-currency identity: EUR-only fixture must not call convertOutcome
  at all (stub throws + coVerify exactly=0); totals unchanged (golden
  semantics preserved).
- Existing goldens untouched: all SynthesisEngine golden tests run with
  blank displayCurrency (no conversion path), so fallback removal is a
  no-op for them — verified by reading, not by editing.

**Status:** implemented, **validated** (2026-09-19): compile PASS
(vr-20260919-124317-74a75cff), `*SynthesisEngine*` targeted shard PASS
(vr-20260919-124955-afc3e3b1, incl. new SynthesisEngineRatePolicyPinTest
8/8), all via the runner. Lane not yet merged. Stop-condition item
(raw entities into block-party actuals) open for orchestrator review.

### 6b slice 3 (P5-CURRENT-009 step 1) — implemented, validated (2026-09-19)

**Stop-condition item resolved: block-party actuals now convert through the
typed converter.**

- `SynthesisEngine.calculateBlockPartyData`: when `forecast.displayCurrency`
  is NOT blank, each raw item's `effectiveAmount` is converted via the
  existing private suspend `convertAmount` (LATEST_AVAILABLE +
  LatestDefault, same as slices 1-2). Conversion failure → item EXCLUDED
  (contributes 0) and counted in the existing `bpConversionFailures`
  counter (bounded Timber.w, class/count only — no amounts, no exception
  text). No source-currency amount enters a bpCurrency sum.
- Identity paths preserved (contract item 4):
  - `bpCurrency` blank → exact legacy behavior (raw `effectiveAmount`
    identity sums, converter never invoked) — all existing goldens/stress
    tests byte-identical, verified by reading.
  - item `currency` blank (legacy callers that don't populate it) →
    identity, no conversion call.
- `?: ` semantics preserved (contract item 3): day with no raw list but a
  normalized daily value still uses the normalized value; an empty raw
  list cannot overwrite it (absent key → null → history fallback).
  A day WITH a raw list keeps FCST-3 precedence (raw-converted over
  history), now with converted values only (contract item 5).
- Enabling model change: `TransactionSummary` gains a defaulted
  `currency: String = ""` field (RP-06 6b slice 3). All existing
  construction sites compile unchanged (default blank = legacy identity).
  `DashboardExpense.toTransactionSummary()` now passes the source currency
  through, so the production dashboard path supplies per-item currency.
  `BlockPartyDay` model fields untouched.
- `ComputeDashboardWidgetsUseCase.computeBlockParty`: TODO(RP-06
  P5-CURRENT-009 step 1) removed, replaced by a contract comment (raw
  entities are metadata-only; arithmetic goes through the engine's typed
  converter). The `G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]` structured tag
  is RETAINED inline on the flagged line (required by
  `scripts/verify_money_boundaries.py`, which matches `ctx.expenseEntities`
  per-line); only the reason text was updated.
- topTransactions remain raw metadata (display only, no arithmetic).

**Tests (new file `SynthesisEngineBlockPartyMultiCurrencyTest.kt`, 7 tests)**

1. `转换失败项的源币金额不进当日actualSpent且成功项照常计入` — day 10 has
   EUR 30 (identity) + USD 50 (Failed): actualSpent = 30.0, not 80.0;
   topTransactions keeps both items (metadata unaffected);
   converter called exactly once (EUR identity short-circuits).
2. `同一fixture下USD成功时两币种金额换算后合并` — same fixture with
   Converted stub: actualSpent = 80.0 (symmetry check).
3. `无raw list时当日actualSpent采用normalized daily值` — empty expenses +
   daily[day10] = 42.5f → actualSpent = 42.5; converter never called.
4. `空raw list且零normalized值时actualSpent保持0不伪造非零` — empty
   expenses + all-zero history → actualSpent stays 0.0 everywhere.
5. `某天无raw也无history时该天为NO_DATA不伪造数值` — empty expenses +
   empty dailySpending → past days are NO_DATA with actualSpent 0.0.
6. `blank displayCurrency时保留恒等求和且不触发converter` — blank
   displayCurrency with MIXED currencies (EUR 30 + USD 50) → identity sum
   80.0, converter must not be called (stub throws).
7. `有raw list时当日不回退到history` — FCST-3 precedence with conversion:
   raw present (30 converted) + history 99 → actualSpent = 30.0, not 99.0.

**Recorded limitation (RESOLVED by RP-06 lane D6, 2026-09-19 — uncommitted,
validation NOT RUN):** `BlockPartyDay` previously had no per-day (or
engine-level, beyond log) failure field — `bpConversionFailures` was visible
only via the bounded Timber.w log. Lane D6 added a defaulted
`conversionFailureCount: Int = 0` field to `BlockPartyDay` and the engine now
threads day-scoped failure counts into each returned day (the engine-level
log is retained; month-level recurring/planned-total failures remain
log-only). The forecast-level `excludedCount`/`isPartial` still do NOT
include block-party failures (they are computed in `synthesize`, before
block-party runs) — unchanged, by design. See the "Slice D3+D4+D6 status"
section at the end of this document.

**Status:** implemented, **validated** (2026-09-19): compile PASS
(vr-20260919-124317-74a75cff), `*SynthesisEngine*` targeted shard PASS
(vr-20260919-124955-afc3e3b1, incl. new SynthesisEngineBlockPartyMultiCurrencyTest
7/7), all via the runner. Lane not yet merged.

### Slice D1 (P5-008 core) - implemented, validation NOT RUN (2026-09-19)

**Typed unavailable health score (batch 6c, lane `rp-06c-wip`).**

- `FinancialHealthScoreV2.kt` (domain/health) now declares the plan's typed
  outcome exactly as specified: `sealed interface HealthScoreOutcome`
  (`Available(result)` / `Unavailable(reason)`) plus closed enum
  `HealthScoreUnavailableReason` = { HOME_CURRENCY_UNAVAILABLE,
  NORMALIZATION_FAILED, DATA_LOAD_FAILED }. A file-private
  `HealthScoreUnavailableError(reason)` control-flow signal propagates typed
  reasons out of nested helpers (baseline derivation) into the broad catch;
  it never escapes and carries no exception text.
- `calculateHealthScore` returns `HealthScoreOutcome`. BOTH raw fallbacks
  removed: the main-path `?: expenses.map { it.toExpenseSnapshot() }` AND the
  second one inside `calculateHistoricalMonthlyBaseline` (`?: it.effectiveAmount`
  + the `?: emptyMap()` association fallback). Normalization failure or
  `excludedCount > 0` (main path or baseline path, including a row missing
  from the normalized association) — `Unavailable(NORMALIZATION_FAILED)`.
  Empty-but-valid normalization (excludedCount = 0) keeps the existing neutral
  component policy (product rule). The private `Expense.toExpenseSnapshot()`
  mapper and the unused `DomainTransferDirection` import were deleted.
- `saveToHistory` reached only from the Available path; Unavailable never
  persists. No placeholder row, no schema change, DAO untouched.
- CE discipline (RP-01) preserved/added: home-currency fetch, main
  normalization inner catch, baseline home-currency + normalization inner
  catches, and the broad catch all rethrow `CancellationException` first.
  The pre-existing `IllegalStateException("Home currency unavailable: ${e.message}")`
  rethrow in the baseline was replaced by the typed error signal - this also
  removes the e.message leak at that site.
- Broad catch now: `catch (CE) throw` — `catch (HealthScoreUnavailableError)`
  — `Unavailable(reason)` — `catch (Exception)` — bounded diagnostic
  (exception class simple name only) + `Unavailable(DATA_LOAD_FAILED)`.
  The all-50 fabricated `FinancialHealthResult` is gone. Known pre-existing
  non-CE-hardened narrow catch (upcoming-bills `catch (e: Exception)` inside
  `calculateRunwayScore`, returns 0.0) is unchanged in this slice - pre-dates
  P5-008, still covered by the file's MIT-034 allowlist entry.
- `ComputeDashboardWidgetsUseCase.computeHealthScoreV2` maps
  `Available — FinancialHealthScoreV2Widget(result)` as before;
  `Unavailable ?` bounded Timber.w with the reason constant and NO widget.
  **V1-fallback decision (orchestrator-approved, recorded per plan):** the
  legacy `FinancialHealthScoreWidget` fallback is REMOVED for the
  Unavailable path - "no widget/unknown UI state" governs. The now-dead
  `computeHealthScore` (V1) method and the `healthScore` parameter of
  `assembleWidgets` were deleted (dead code after the decision); the
  `FinancialHealthScoreV2Widget` data class and registry mapping remain
  unchanged. `healthCalculator` DI dependency is retained (other consumers).
- History tolerance verified by reading: `HealthScoreHistoryDao.getMostRecentBefore`
  returns a nullable row (`HealthScoreHistory?`); `determineTrend` treats a
  null previous record as STABLE - absence is already legal. No test added
  (no co-located seam; existing trend tests cover both branches).
- Guard allowlist review: `CancellationSafetyArchitectureGuardTest` entry for
  `FinancialHealthScoreV2.kt` (CATCH_WITHOUT_CE_RETHROW, MIT-034) left
  UNTOUCHED - the file still contains the pre-existing non-CE-hardened
  upcoming-bills broad catch, so the entry is not stale-protective. No
  shrink, no growth (FG-06 compliant).
- Tests updated/added (MockK dashboard-stub sweep deferred to D2):
  - `FinancialHealthScoreV2Test`: removed `toTestExpenseSnapshot` (mirrored
    the deleted production fallback; stub now uses the shared
    `AnalyticsTestCompat.toExpenseSnapshot`); 11 call sites unwrapped via a
    private `availableResult()` helper; NEW tests (5):
    normalization-throw — Unavailable(NORMALIZATION_FAILED) + no history
    writes; excluded-row — same; data-load-throw — DATA_LOAD_FAILED + no
    history writes; home-currency-throw — HOME_CURRENCY_UNAVAILABLE + no
    history writes; CancellationException propagates (not swallowed into
    Unavailable).
  - `HealthScoreGoldenTest`: return-type migration via `availableResult()`;
    golden pins (55.0 / IMPROVING) unchanged. NOTE: the normalizer mock is
    relaxed (empty-but-valid, excludedCount = 0), so the pin exercises the
    empty-valid — Available neutral path on real golden data - NO identity
    stub was added (an identity stub would change the pinned component
    scores; the empty-valid path is the behavior the pins encode).
  - `HealthScoreEdgeCaseTest`: stub fixed to model fully-successful
    normalization (normalizedExpenses now mirrors inputs; the old stub had
    normalizedExpenses = emptyList() with non-zero input, which would
    trip the new excludedCount gate); 5 call sites migrated + helper.
  - `EmptyZeroNullResilienceTest` (~:220): empty-data neutral pin preserved;
    result now unwrapped from the typed outcome (unavailable — loud error).
- Privacy: all new/changed diagnostics emit controlled reason constants
  only; no e.message, no exception text, no payloads, no stack traces.
- Status: implemented, **validation NOT RUN** (no Gradle per slice rules).
- Known compile risk (deliberate): the 8 MockK dashboard test files still
  stub `calculateHealthScore(any(), any())` returning `FinancialHealthResult`
  - they will not compile until D2 retargets them to
  `HealthScoreOutcome.Available(...)`. Suggested targeted commands (via
  validation-runner profiles): compile; then targeted-unit-test shards
  `*FinancialHealthScoreV2*`, `*HealthScoreGolden*`, `*HealthScoreEdgeCase*`,
  `*EmptyZeroNullResilience*`, `*CancellationSafetyArchitectureGuard*`.

### Slice D2 status (2026-09-19) — implemented, validation NOT RUN

**MockK dashboard-stub retarget to typed `HealthScoreOutcome` (lane `rp-06c-wip`).**

- All 9 stub sites in 8 files mechanically wrapped: existing
  `coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns
  FinancialHealthResult(...)` → `returns HealthScoreOutcome.Available(
  FinancialHealthResult(...))`, same fixture values, no assertion changes.
  `HealthScoreOutcome` import added to each file.
  Files: `DashboardContractsAdapterTest` (2 sites, ~:227/~:480),
  `DashboardWidgetConsistencyTest` (~:93), `DashboardCurrencyIntegrationTest`
  (~:103), `BlockPartyDstAlignmentTest` (~:111),
  `ComputeDashboardWidgetsInsightMoMTest` (~:73),
  `ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest` (~:72),
  `ComputeDashboardWidgetsUseCasePaceWiringTest` (~:68),
  `FinancialRunwayNoBurnTest` (~:88).
- `CrossGroupIntegrationTest` (~:240): the relaxed mock never stubbed
  `calculateHealthScore` — a relaxed MockK cannot fabricate a sealed child,
  so an explicit `HealthScoreOutcome.Available(...)` stub (standard neutral
  50/STABLE fixture, fully-qualified style matching the file) was added. No
  health assertions exist in that file; behavior is unchanged, just sealed.
- Verified NO-EDIT (compile-safe against D1): `NotificationExpenseDashboardPipelineTest`
  (real `FinancialHealthScoreV2` construction, constructor unchanged; failure
  path now yields Unavailable → no widget, tests assert only totals —
  behavior-compatible); `ComputeDashboardNormalizedInputWindowTest` (relaxed
  mock, only `produceDashboardNormalizedInput` exercised);
  `DashboardWidgetRenderCoverageTest` (V1 `FinancialHealthScoreWidget` data
  class still exists; sealed-subclass count 21 unchanged).
- Sweep results: no test-source references to the deleted `computeHealthScore(`
  (V1) remain; no test constructs `FinancialHealthScoreWidget(` directly;
  `SubscriptionManagerEngine.calculateHealthScore` untouched.
- Assertion changes: NONE. No tests deleted, skipped, or weakened.
- Status: implemented, **validation NOT RUN** (no Gradle per slice rules).
- Suggested validation (via validation-runner): compile profile first, then
  targeted-unit-test shards: `*DashboardContractsAdapter*`,
  `*DashboardWidgetConsistency*`, `*DashboardCurrencyIntegration*`,
  `*BlockPartyDstAlignment*`, `*InsightMoM*`, `*DaysRemainingBoundary*`,
  `*PaceWiring*`, `*FinancialRunwayNoBurn*`, `*CrossGroupIntegration*`,
  `*NotificationExpenseDashboardPipeline*`.

### Slice D3+D4+D6 status (2026-09-19) — implemented, validation NOT RUN

Lane `rp-06c-wip`, one continuation after D1/D2. Static work only: NO
commits, NO Gradle/test execution (slice rules). Nothing below is validated;
see suggested validation commands at the end.

**D3 — NEW-P5-009 count integrity (§6c):**

- `MoneyAggregateMetadata` gains defaulted `countsIncomplete: Boolean = false`
  (KDoc states the contract: non-empty counts list misaligned with buckets —
  shorter OR longer — sets the flag; empty counts list is the count-agnostic
  opt-out and is NOT flagged; no negative sentinels).
- `MoneyAggregateBuilder` legacy `fromBuckets` overload: mismatch detection
  now covers BOTH directions (`transactionCounts.isNotEmpty() &&
  transactionCounts.size != buckets.size`). The pre-existing code detected
  only shorter. On mismatch the aggregate is marked `isPartial = true` with a
  controlled warning constant (`"Transaction counts incomplete: count list
  does not match bucket list; per-bucket counts are approximate"` — counts
  and booleans only, no payloads). No silent zero-count fabrication without
  the flag; no negative sentinels. Both return paths honor the flag: the
  home-currency-only early return applies it via `aggregate.copy(...)` (that
  path has no conversion failure warning to combine), and the conversion path
  COMBINES the count-integrity warning with the existing
  conversion-failure warning (`"Transaction counts incomplete: … Total
  excludes N transaction(s) across M currency bucket(s)"`). Metadata is
  explicitly constructed (`MoneyAggregateMetadata(countsIncomplete = …)`) —
  note this legacy overload never populated other metadata fields before;
  they remain at their defaults.
- **Deliberate boundary decision (recorded):** an EMPTY `transactionCounts`
  list (the parameter default) keeps the legacy non-flagged semantics — it
  means "counts unknown by design" (count-agnostic callers), not "count
  integrity damaged". Flagging it would mark every count-agnostic aggregate
  partial (TravelDetectionEngine, AreaSpendingEngine,
  NaturalLanguageSearchViewModel, several InvestmentTracker sites, and the
  TaxEstimator VAT re-aggregation call the builder without counts), which is
  a consumer behavior change explicitly out of this slice's scope. The
  pre-existing guard's `isNotEmpty()` conjunct is therefore preserved and its
  meaning now pinned by test. If the orchestrator wants count-agnostic
  aggregates flagged too, that is a separate decision with its own consumer
  audit.
- Consumer audit (grep-based, audit-only per slice instructions — no behavior
  changes): legacy-overload callers with real counts =
  `AnalyticsRepository` (~:368 location merchant stats →
  `totalTransactionCount`), `WarrantyTrackerRepository` (~:329 → protected
  value aggregate), `TaxEstimator` (~:288 deductible / ~:311 income →
  aggregates), `SubscriptionManagerEngine` (~:628 → monthly totals),
  `AccountantReportPdfExporter` (~:68 → deductible aggregate),
  `MultiCurrencyRepository` (~:883 `aggregateToMoneyAggregate`, ~:928
  `aggregateCurrencyTotalsToMoneyAggregate` — dashboard/trend pipelines).
  These now surface `isPartial=true` + the controlled warning + metadata flag
  on a counts/bucket mismatch (previously silent zero counts). Count-agnostic
  callers (no counts arg): `TravelDetectionEngine` ~:379,
  `AreaSpendingEngine` ~:218, `NaturalLanguageSearchViewModel` ~:135,
  `InvestmentTracker` (~:237/240/250/307/310/440/443/559 — includes two
  derived re-aggregations over prior `sourceBuckets`), `TaxEstimator` ~:145
  (VAT re-aggregation) — unchanged behavior (empty-list opt-out, see above).
  Downstream count consumers (via `aggregate.totalTransactionCount` /
  `failedTransactionCount` / `isPartial` / `warningMessage` /
  `aggregate.metadata.*`): `TotalsAggregationEngine` (~:109/171/216/243/279/
  357), `DashboardContractsAdapter` (~:94/143-145/179-180), `BudgetRepository`
  (~:220-221/258/291-293/309/349), `DashboardNormalizedInput` (~:77-83
  metadata→dataQuality mapping, merge at ~:92-98 — note: merge currently drops
  `countsIncomplete`; out of scope here), `CashFlowCalculator` (~:376/385),
  `ForecastInputAssembler` (~:551/706), `AnalyticsViewModel` (~:1172),
  `MoneyDisplayUi` (~:48-51), `HomeScreen`/`FinancialRunwayCard` (warning
  display), `HomeViewModel` (~:401). Rollover: `BudgetRepository` rollover
  gating at ~:258 keys on `initialLimitAggregate.isPartial` — a mismatched
  counts list on the initial-limit aggregate now (correctly, per plan §6c)
  affects that gate; flagged for reviewer attention, behavior intentional.
  Export/diagnostics consumers read only `warningMessage`/`isPartial` — no
  code changes needed.

**D4 — NEW-P5-012 stale-rate policy explicit (§6c):**

- `CurrencyConverter`: the hard-coded `MAX_RATE_AGE_MS` const (24h) is
  REMOVED; both staleness checks in the legacy `convert` path (direct rate +
  via-EUR composite) now read `StaleRatePolicy.Default.maxAgeMs` — the named
  policy constant. Behavior is byte-identical (same 24h number, same
  `>` comparison against `lastUpdated`, same stale → unavailable → composite
  → MISSING_RATE-null flow). The `?: error("StaleRatePolicy.Default must
  define maxAgeMs")` guards are unreachable-by-construction defensive reads
  of a `Long?` field (Default always sets a value). The `TODO — make
  configurable via AppConfig` is resolved-by-removal (policy now named in
  code; AppConfig configurability remains future work, no TODO left).
  No caller changed (grep-verified: the constant had zero production
  references outside the converter; test references updated, see below).
- `convertOutcome`'s default `stalePolicy = StaleRatePolicy.Default` verified
  unchanged and now pinned by test. `convertAsOf` verified TTL-exempt
  (no staleness check on the as-of path at all; transaction-date basis) and
  now pinned by test. KDoc updated on `convert` (policy named; legacy
  compatibility note), `convertAsOf` (TTL-EXEMPT paragraph), and
  `convertOutcome` (default + forBasis guidance).
- Grep sweep: no `MAX_RATE_AGE_MS` references remain anywhere under
  `app/src/` (production or test) — the two test files that referenced the
  constant were retargeted to `StaleRatePolicy.Default.maxAgeMs`.

**D6 — 6b deferral resolution: BlockPartyDay failure surfacing:**

- `BlockPartyDay` gains defaulted
  `conversionFailureCount: Int = 0` (last field; KDoc bounds it: count-only,
  no amounts/currencies/exception text). Scout claim verified before edit:
  single production construction site (`SynthesisEngine.calculateBlockPartyData`,
  named args) and NO test constructs `BlockPartyDay` directly, so every
  existing construction compiles unchanged via the default.
- `SynthesisEngine.calculateBlockPartyData` threads the per-day share of the
  engine's `bpConversionFailures` into each returned `BlockPartyDay` via a
  local `dayConversionFailures` counter incremented at the three DAY-scoped
  failure sites (recurringOnDay ~:621, plannedOnDay ~:638, actual-spend items
  ~:657). The two MONTH-level sites (totalMonthlyRecurring ~:539,
  totalMonthlyPlanned ~:558) still count into the engine total only —
  day-scoping them is not well-defined; the existing bounded Timber.w log
  (kept unchanged) remains the surfacing for the engine total.
- UI consumers (cards) tolerate the added field: verified the single consumer
  chain `ComputeDashboardWidgetsUseCase.computeBlockParty` →
  `DomainDayBudgetStatus` mapping (~:838-871) reads only named fields and is
  unaffected (a day-level failure-count display decision belongs to UI work,
  not this slice).

**Tests added/updated (exact names):**

- `MoneyAggregateBuilderTest` (+6): `shorter counts list marks aggregate
  partial with countsIncomplete`, `longer counts list marks aggregate partial
  with countsIncomplete`, `empty counts list stays count-agnostic and not
  partial`, `complete counts list regression unchanged behavior`, `count
  mismatch combines warning with conversion-failure warning`, `count mismatch
  on home-currency-only path still marks partial` (negative wiring check: the
  early-return path must not reach the converter).
- `P5AnalyticsFixesTest`: `builder_warns_on_size_mismatch` extended — same
  structural pins kept (total 270.0, per-bucket 1+0) PLUS
  `isPartial`/`metadata.countsIncomplete`/controlled-warning assertions.
- `ConversionSemanticsHardeningTest` (+6): `legacy convert direct rate 24h
  boundary just under passes just over fails`, `legacy convert via EUR
  composite fails when either leg exceeds 24h` (fresh / one-leg-stale /
  both-legs-stale), `legacy convert boundary is derived from StaleRatePolicy
  Default`, `convertOutcome default stalePolicy is StaleRatePolicy Default
  24h`, `convertAsOf is exempt from latest-rate TTL old-but-valid rate
  converts`, `same aged rate via legacy latest path is refused while asOf
  converts` (contrast pin: 90-day-old rate — asOf path converts, latest path
  refuses).
- `CurrencyNormalizationPost9a6Test` (+2): `forBasis latest 7d boundary just
  under passes just over excludes`, `engine normalizeExpense honors 7d
  LatestDefault boundary` — via a boundary-controlled `BoundaryRateStore`
  (the shared TestStore returns a fixed 10-day-old rate and cannot express
  ±1ms around the boundary).
- `CurrencyRateStalenessScenarioTest`: retargeted off the deleted
  `CurrencyConverter.MAX_RATE_AGE_MS` constant onto
  `StaleRatePolicy.Default.maxAgeMs` (imports/comment updates; assertions
  unchanged in meaning, none weakened).
- `SynthesisEngineBlockPartyMultiCurrencyTest` (+3): `转换失败的当日携带
  conversionFailureCount其余天为0` (failed day carries 1, all other days 0,
  per-day sum conserves the engine total), `恒等路径天
  conversionFailureCount全为0且不触发converter` (blank displayCurrency
  identity path, converter never called), `全转换成功的天
  conversionFailureCount为0` (successful-conversion day carries 0).
- No tests deleted, skipped, or weakened; no `@Ignore` added; assertions on
  pre-existing behavior only strengthened.

**Status: implemented, validation NOT RUN** (no Gradle per slice rules).
Nothing here is DONE/GREEN until the reviewer gate + validation pass.

Suggested validation (via validation-runner, in order): `compile` profile
first, then targeted-unit-test shards: `*MoneyAggregateBuilder*`,
`*P5AnalyticsFixes*`, `*ConversionSemanticsHardening*`,
`*CurrencyNormalizationPost9a6*`, `*CurrencyRateStaleness*`,
`*SynthesisEngineBlockPartyMultiCurrency*`, `*CurrencyConverter*`,
`*Curr587*`, then the D1/D2 shards from the sections above.

Known compile-risk areas: none identified for D3/D4/D6 (all new parameters
have defaults; deleted constant verified unreferenced; `assertNotNull`
result-value usage avoided). Residual risks: (1) `BudgetRepository` rollover
gate now sees `isPartial=true` for counts-mismatched initial-limit aggregates
— intended per plan but changes a real gate's input; reviewer should confirm
acceptance. (2) The `MoneyAggregate` `metadata` default-construction on the
legacy path means previously-`MoneyAggregateMetadata()`-equal aggregates now
carry `countsIncomplete=true` on mismatch — any structural test comparing
full metadata equality on mismatched fixtures would notice (none found via
grep). (3) D6: `conversionFailureCount` currently has no UI reader — pure
surfacing for downstream decisions; harmless but dormant until a card renders
it.

**Review-fix addendum (2026-09-19, static test-review round — still
validation NOT RUN):** gaps found by the static test reviewer, fixed in the
same lane:

- V1-fallback suppression PINNED (the lane's actual production behavior
  change): `DashboardContractsAdapterTest` gains
  `computeHealthScoreV2 unavailable emits no health widget and no V1
  fallback` — Unavailable(NORMALIZATION_FAILED) stub through the full
  `compute()` path asserts NEITHER `FinancialHealthScoreV2Widget` NOR legacy
  `FinancialHealthScoreWidget` appears (filterIsInstance isEmpty both ways)
  while unrelated always-on widgets still emit. The
  `windowTestComputeUseCase` harness gained a defaulted
  `healthScoreOutcome` parameter (default = the existing Available fixture,
  so all prior call sites are unchanged).
- Baseline raw-fallback site pinned:
  `FinancialHealthScoreV2Test` gains `baseline normalization failure returns
  unavailable normalization failed` (early-month fixture mirroring the
  baseline-blend test; only the BASELINE normalization fails — proves the
  typed reason propagates from `calculateHistoricalMonthlyBaseline`) and
  `baseline row missing from normalized association returns unavailable
  normalization failed` (association-miss: excludedCount 0 but a historical
  row absent from the normalized map → per-row typed throw).
- `saveToHistory` side-effect pin: `unavailable outcome never persists
  placeholder or fabricated row` now also asserts
  `deleteOlderThan` is never called on the Unavailable path.
- Contradictory pre-existing currency tests retargeted to the D4 policy:
  `CurrencyConverterEdgeCaseTest.stale direct rate is still used and
  timestamp is preserved` (which asserted the OPPOSITE of the 24h policy
  under a relaxed TimeProvider) is replaced by `stale direct rate is refused
  by legacy convert 24h policy` (fixed clock, just-over-24h → null) plus a
  companion `fresh direct rate within 24h converts and preserves rate
  timestamp` pin; `CurrencyConverterGoldenTest` got a fixed TimeProvider and
  fresh fixture rates (the relaxed clock had made now()=0, so the TTL path
  never fired and the 2023-era fixtures silently bypassed the policy).
  Golden numbers unchanged. No tests deleted; the replaced test's intent is
  inverted to match production policy, not weakened.
