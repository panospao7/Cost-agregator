# RP-08 — Budget autopilot correctness (Pipeline 6, release-critical)

> **Scope class:** pipeline-isolated but **release-only** defect (R8). **Mode:** strict (money math + release build behavior).
> **Files:** `BudgetAutopilotEngine` (domain/budget/), `MultiCurrencyRepository` (data/repository/), `ExpenseDao` (data/database/dao/), `BudgetHistorySeriesBuilder` ⟂ (domain/budget/), `BudgetViewModel` + `BudgetScreen` (ui/screens/budget/ ⟂), `app/proguard-rules.pro` (no change needed — we remove reflection instead).
> **PR shape:** one PR (three coupled defects, one test file to re-pin).

---

## P6-002 — Autopilot reflection dies under R8 → every category budget recommended at −15% (HIGH, release-only)

**Problem.** `BudgetAutopilotEngine.kt:238-245` reaches into `multiCurrencyRepository.javaClass.getDeclaredField("expenseDao")` to call the DAO's per-category monthly-totals query; `catch (e: Exception)` swallows the failure and returns `emptyList()`. Verified: `app/build.gradle.kts:55` release `isMinifyEnabled = true`; `app/proguard-rules.pro` (read in full) contains no keep for `MultiCurrencyRepository` or its fields; Dagger/Hilt consumer rules do not preserve injected member names; `-keepattributes *Annotation*` preserves annotations, not names. In release: `NoSuchFieldException` → empty history → `trendAdjustedSpend = 0` → `recommendedBudget` coerced into `[amount − 15%, amount + 15%]` (`:102-125`) → **−15% for every category budget**; "apply all" (`BudgetViewModel.kt:328-346`) writes them in one transaction. Debug builds and unit tests never exercise R8, so nothing catches it.

**Fix design — replace the reflection bridge with a first-class repository API.**
1. Add to `MultiCurrencyRepository` (interface + impl — it already exposes currency-aware aggregates and its own TODO asks for exactly this):
   ```kotlin
   suspend fun getMonthlySpendingTotalsByCategoryBetween(
       startMs: Long, endMs: Long
   ): List<CategoryMonthlySpend>   // data class: categoryId: Long?, monthKey: String, homeCurrencyTotal: Double, isPartial: Boolean, excludedCount: Int
   ```
   Implementation: group expenses by (categoryId, calendar month) in code (the repository already loads/normalizes via `MoneyNormalizationEngine`/`AnalyticsCurrencyNormalizer` with `RateBasis.TRANSACTION_DATE`), or add a currency-aware DAO query mirroring `getHomeCurrencyCategoryAggregatesHistorical` — prefer reusing the existing repository plumbing (the data-layer may touch `expenseDao` legitimately; the **domain engine may not** — that layering rule is why reflection was used in the first place).
2. In `BudgetAutopilotEngine`: inject nothing new — `multiCurrencyRepository` is already injected; call the new method; **delete** the reflection block (`:238-245`), the `@Suppress("DEPRECATION_ERROR")` (`:242`), and the try/catch.
3. Failure semantics: if the repository call throws, let it propagate as the engine's typed failure — **no silent emptyList** (that silence is what turned a crash into a budget cut).
4. `isPartial` per category-month is carried into `CategoryMonthlySpend`; see P6-004 for how it's consumed.

**What it solves.** Autopilot works identically in debug and release; recommendations derive from real history; removes the only reflection bridge in the money path.

**Guardrails.**
- **Architecture:** `BudgetAutopilotEngine` is domain — it must not import `ExpenseDao` (that would trade a reflection hack for a layering violation). All DAO access stays inside `MultiCurrencyRepository` (data).
- **ProGuard:** intentionally do **not** add a keep rule — the fix removes the reflective access; a keep rule would only mask future violations.
- Money rule: `TRANSACTION_DATE` basis for historical sums (consistent with dashboards/budget status); no rounding changes.

**Tests.**
- `BudgetAutopilotEngineTest`: replace the fake-reflection setup with the repository fake; add a release-parity test — engine logic must produce identical recommendations whether the repository returns data normally (no code path can "silently" return empty anymore).
- `MultiCurrencyRepositoryTest`: new API — mixed-currency fixture → home-currency per-month totals, `isPartial` when a rate is missing, excluded counts.

## P6-003 — Deprecated mixed-currency raw SUM behind the bridge (MED)

**Problem.** The reflected call is `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween` (`:1516-1530`), `@Deprecated(level = ERROR)` for "Raw SUM across mixed currencies" — even where reflection *works* (debug), €300 + $100 totals as unitless 400, poisoning trend/volatility; the recommendation is then written into the budget's own currency.

**Fix design.** Superseded by P6-002's new currency-aware API: after migration, **delete the deprecated DAO method** (`ExpenseDao.kt:1516-1530`) — its only caller was the bridge. Grep to confirm zero remaining callers, then remove (deprecation contract fulfilled).

**Guardrails.** Deleting a `@Deprecated(ERROR)` method is safe (no compilable callers); note it in the PR for reviewers scanning the DAO diff.

## P6-004 — Partial current month counted as a full history bucket → −15% ratchet (MED)

**Problem.** `BudgetHistorySeriesBuilder.build` (`:48-51, :76`): `lastIncludedTimestamp = windowEndExclusive − 1` with callers passing `now` (`BudgetAutopilotEngine.kt:231, :267`; `BudgetForecastingEngine.kt:386, :437`) → the in-progress month enters the trend as a complete bucket. On day 2 with history `[0.8M, M, M, 0.02M]`, the average collapses → autopilot recommends the −15% cap; repeated monthly application ratchets budgets down. Same bias deflates `averageMonthly` → `predictedSpending` in the forecast engine.

**Fix design.**
1. `BudgetHistorySeriesBuilder.build(...)`: add parameter `excludeIncompleteFinalMonth: Boolean = true`; when the final bucket's month == the month of `windowEndExclusive` (i.e. window ends mid-month), drop that bucket from the series (do **not** pro-rate — projections from partial data embed assumptions; dropping is honest).
2. **Empty-history behavior (the amplifer):** in `BudgetAutopilotEngine`, when fewer than 2 complete months of history exist for a budget → `recommendedBudget = current amount` (identity, flagged `LOW_HISTORY` in the recommendation payload) instead of `trendAdjustedSpend = 0 → −15%`. Bounded adjustment (±15%) applies only with ≥2 complete months.
3. `BudgetForecastingEngine` callers keep the same builder call and automatically get completed-months-only history; verify its `MIN_HISTORY`-style confidence handling degrades gracefully with the shorter series (it already gates on months of history — adjust the count semantics if it was implicitly counting the partial month).
4. Update `BudgetAutopilotEngineTest.kt:253-262` ("empty spend history applies bounded decrease" pins 85.0 for 100.0): re-pin to `recommendedBudget == 100.0` + `LOW_HISTORY` flag (this is REVAL-6 — the old test codified the destructive default). Add a 2-complete-months fixture asserting the bounded path still works.

**What it solves.** No systematic downward ratchet; honest LOW_HISTORY no-op recommendations; forecasts stop understating early each period.

**Guardrails.**
- Golden: `BudgetCalculatorGoldenTest`/forecast goldens may shift for early-month fixtures — enumerate deltas.
- Do not change the ±15% bounds or the safety-factor math — only which history feeds them.
- Crossover: `BudgetForecastingEngine` shares the builder — run its tests in the same PR (listed below).

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*BudgetAutopilot*" --tests "*BudgetHistorySeries*" --tests "*BudgetForecasting*" --tests "*MultiCurrencyRepository*" --tests "*BudgetCalculatorGolden*"
./gradlew :app:assembleRelease   # verify no reflection-dependent code remains compiling under R8 config
```
(`assembleRelease` is the only trustworthy check for this class of bug — the unit suite never minifies. Run it once in this PR even if the broader suite is still gated.)

## Sequencing & risk
- Independent of RP-09 except shared files (`BudgetForecastingEngine` appears in both — land RP-08 first; RP-09's FX-basis fix builds on the completed-months builder). Risk: medium — recommendation semantics change for low-history users (intended); call out in PR description.
