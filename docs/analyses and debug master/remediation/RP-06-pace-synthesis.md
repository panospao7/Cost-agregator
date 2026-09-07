# RP-06 — Pace & synthesis input correctness (Pipeline 5 ↔ 6 crossover)

> **Scope class:** engine crossover (`SynthesisEngine` + `ForecastInputAssembler` shared by dashboard, forecast and stress paths). **Mode:** strict (money math).
> **Files:** `ComputeDashboardWidgetsUseCase` (domain/usecase/dashboard/), `ForecastInputAssembler` (domain/forecasting/), `SynthesisEngine` (domain/logic/), `SpendingPaceCalculator` (domain/analytics/), `SpendingPaceProjection` (domain/analytics/), `FinancialHealthScoreV2` (domain/health/), `HomeViewModel` + `DashboardDataProvider` (dashboard path), `MultiCurrencyRepository` (data/repository/), `MoneyAggregateBuilder` (domain/core/money/), `CurrencyConverter` (domain/currency/), `StaleRatePolicy` (domain/core/money/).
> **PR shape:** 3 PRs — (6a) pace pipeline = P5-002 + NEW-P6-013 + P5-006 + REVAL-8; (6b) synthesis money = P5-CURRENT-009 + U-MONEY-01 residual + P5-007; (6c) scoring & aggregate hygiene = P5-008 (score-fabrication half) + NEW-P5-009/012/013.
> **Depends on:** RP-05 (corrected inputs), RP-01 (CE-rethrow pattern in FinancialHealthScoreV2 already landed).

---

## 6a — Pace pipeline

### P5-002 — Pace widget dead; OVER_PACE risk unreachable (HIGH)

**Problem.** `ComputeDashboardWidgetsUseCase.kt:577` hardcodes `paceStatus = NO_BASELINE` with a comment "Will be refined by synthesis" — nothing refines it (exhaustive grep: the only writers of `paceStatus` are the assembler's `buildSpendingPace` on the legacy `assemble()` path and `SpendingPaceCalculator`, neither reachable from `assembleNormalized`); `SynthesisEngine` only *reads* it (`:788`, `:824`). The widget gate (`:1081`) can never pass → SpendingPaceWidget never renders; OVER_PACE branches dead.

**Fix design.**
1. Compute real pace in `produceDashboardNormalizedInput`: build `SpendingPace` from the (post-RP-05) normalized input — `currentMonthSpent = monthAggregate.displayAmount`, baseline from `previousMonthTotal` (daily baseline = prev/prevDays) — by calling the **canonical** `SpendingPaceCalculator` (domain/analytics) rather than duplicating the assembler's inline math.
2. Replace the hardcoded `:577` with the computed value; delete the false comment.
3. `SynthesisEngine` needs no change (its readers now receive real status); the `:1081` gate and OVER_PACE branches come alive on their own.

**What it solves.** The pace widget renders; OVER_PACE risk level/insight reachable.

**Guardrails.**
- Use `SpendingPaceCalculator`'s semantics: NO_BASELINE + `-1f` sentinel when no baseline (do not emit a misleading `0%`).
- If `SpendingPaceCalculator`'s input type doesn't fit the dashboard's normalized shape, adapt at the call site — do not fork a third implementation.
- Cross-check with the assembler's `buildSpendingPace` (`:367-382`): after this fix the two paths should agree for the same data; add a consistency test (same inputs → same status/percentage) rather than deleting either yet (legacy `assemble()` serves the forecast use case).

**Tests.** Dashboard fixture with prev-month baseline → widget emitted with correct percentage; no baseline → widget absent (not "0%"); OVER_PACE insight asserted for an over-baseline fixture.

### NEW-P6-013 (tracked) — Assembler emits `pacePercentage = 0f` with NO_BASELINE

**Problem.** `ForecastInputAssembler.kt:361-368`: `pacePercentage = 0f` when `baselineDailyRate <= 0`, distinguished only by `paceStatus = NO_BASELINE`.

**Fix design.** Align with the canonical sentinel: `pacePercentage = -1f` when no baseline. Grep percentage consumers and ensure every read is gated on `paceStatus != NO_BASELINE` (SynthesisEngine already is — verify). Update the assembler test that pins `0f`.

**Guardrails.** `-1f` must never reach display math — the gating audit is the actual fix; the sentinel change just makes ungated misuse loud.

### P5-006 — Home-currency change never recomputes the dashboard (MED)

**Problem.** `MultiCurrencyRepository`'s cache is invalidated reactively (`:86-95`) but `processedDataFlow` (`HomeViewModel.kt:228-248`) re-runs only on `dashboardReloadTrigger` (midnight loop `:216`, retry `:225`) or Room-flow emissions; no source depends on the currency DataStore flow (`DashboardDataProvider.kt:46-106` combines expenses/categories/budgets/… only). Widgets keep old-currency values for the whole session after a currency change.

**Fix design.** In `HomeViewModel`'s existing home-currency collector (`:159-162` / `:222`), increment `dashboardReloadTrigger` after the cache invalidation fires (a one-line `_dashboardReloadTrigger.update { it + 1 }`). This reuses the established reload mechanism instead of adding a combine source deep in `DashboardDataProvider` (which would change emission semantics for every consumer).

**What it solves.** Currency change → widgets recompute with new labels/values immediately.

**Guardrails.** Debounce not needed (currency changes are rare, user-initiated); ensure the trigger bump happens *after* `MultiCurrencyRepository`'s invalidation collector runs (collect the same flow downstream — if ordering is nondeterministic, bump the trigger from the same collector that invalidates, or accept one extra recompute).

**Tests.** ViewModel test: emit new currency → `processedDataFlow` re-emits with recomputed values.

### REVAL-8 — `MultiCurrencyRepository.updateExpenseCurrency` is a stub

**Problem.** `:935-943` writes nothing — dead/misleading API (part of why nothing downstream reacts to currency changes beyond the cache).

**Fix design.** Do **not** implement bulk re-conversion here (money-critical feature work, needs its own design). Either delete the method (grep callers first — expected zero) or rename/mark `TODO(currency-migration-feature)` with a KDoc stating it is a stub. Deleting is preferred if uncalled (RP-20 hygiene rules apply).

---

## 6b — Synthesis money correctness

### P5-CURRENT-009 — Block-party actuals sum raw amounts across currencies (tracked)

**Problem.** `SynthesisEngine.calculateBlockPartyData` (`:560-568`): `actual` = raw `expensesByDay[day]?.sumOf { it.effectiveAmount }`, and this raw sum **takes precedence** (`:566-568`) over the normalized `dailySpending` map; the only production caller passes raw `ctx.expenseEntities` (`ComputeDashboardWidgetsUseCase.kt:679`) with an incorrect "display-only" comment. Status classification (`:581-582`) and `actualSpent` (`:588`) therefore mix currencies.

**Fix design.**
1. In `calculateBlockPartyData`, convert each day's expenses per-item via the (now suspend — P5-007) `convertAmount` using the same failure policy as patterns: **exclude + count** on conversion failure (no raw fallback). Reuse the exclusion/`isPartial` plumbing already present for patterns.
2. Drop the raw-precedence branch (`:566-568`): the normalized `dailySpending` map remains the fallback when a day has no raw expenses (post-RP-07 it is zone-keyed correctly).
3. Fix the caller comment (`ComputeDashboardWidgetsUseCase.kt:679`) and leave the call passing raw entities (engine now owns conversion) — alternatively pass pre-normalized entities; pick **engine-side conversion** to keep a single conversion point.

**What it solves.** Block-party actual/UNDER-OVER status is home-currency for multi-currency users.

### U-MONEY-01 residual — Raw `?:` fallbacks on conversion failure

**Problem.** `SynthesisEngine.kt:333, :345` (`?: expense.amount`), `:460` (`?: monthly`), `:478, :554` (`?: raw`), `:538` (`?: pattern.averageAmount`) — when a rate is missing, the original-currency amount is silently added to a home-currency sum.

**Fix design.** Replace every fallback with the exclusion pattern used elsewhere in the engine: on `null` conversion → skip the item, increment the excluded counter, set `isPartial = true`. The TODO P2-20 comment in `ForecastInputAssembler.kt:231-234` should be updated to reflect the final state (per-pattern conversion at consumption exists; assembler-side merge normalization stays out of scope — the residual was the fallbacks, now gone).

**What it solves.** Closes U-MONEY-01 fully: no path in the synthesis engine mixes currencies; failures degrade loudly (isPartial) instead of silently.

**Guardrails (both items).**
- Golden tests: `ForecastSynthesisGoldenTest` + block-party fixtures will shift **only for multi-currency inputs** — single-currency numbers must be byte-identical; enumerate any multi-currency delta in the PR.
- Money rule: conversion via `CurrencyConverter` only; no new rate fallbacks (never "latest" for historical, never raw).

### P5-007 — `runBlocking` per conversion + undeclared rate-basis mixing (MED)

**Problem.** `SynthesisEngine.convertAmount` (`:83-88`) wraps the suspend `currencyConverter.convert` in `runBlocking`, called in loops while the dashboard computes on `Dispatchers.Default` → worker-thread starvation. Additionally `discretionaryBudget` (`:384-387`) adds `spentSoFar` (TRANSACTION_DATE basis) to obligations converted at LATEST basis without declaring that contract.

**Fix design.**
1. Make `convertAmount` `suspend` (delete `runBlocking`); it already wraps a suspend call. Thread suspends through `synthesizeInternal`/`calculateBlockPartyData` call sites (all reachable from suspend contexts — verify each caller; the non-suspend entry `synthesize()` is already suspend).
2. Basis contract: obligations are *future* amounts — LATEST basis is legitimate; spent-so-far is historical — TRANSACTION_DATE is legitimate. Add a KDoc on the engine declaring the mixed-basis contract for forward-looking KPIs, and surface the converter's staleness warning into the existing `isPartial`/warning plumbing when the latest rate is stale (the converter already returns staleness info — thread it).
3. No basis *change* — only declaration + observability; the re-validation flagged the undeclared mix, not the mix itself.

**Tests.** Conversion-failure exclusion counts (per 6b); staleness → `isPartial` asserted.

---

## 6c — Scoring & aggregate hygiene

### P5-008 (score-fabrication half) — `FinancialHealthScoreV2` fabricates an all-50 score on failure

**Problem.** `:93` converts a swallowed normalization failure into `IllegalStateException`, which bypasses the CE catch and lands in `catch(Exception)` (`:222-235`) → returns a default all-50 `ScoreResult` — and on the other paths (`:99-101`, `:383-385`) failures fall back to raw mixed-currency snapshot sums, then `saveToHistory` (`:187`) persists the result. (CE rethrows land in RP-01.)

**Fix design.**
1. Introduce a typed outcome: `sealed HealthScoreOutcome { Data(ScoreResult); Unavailable(reason: HealthScoreReason) }` (reason = controlled constant, e.g. `NORMALIZATION_FAILED`, `INSUFFICIENT_DATA`).
2. Normalization failure → `Unavailable(NORMALIZATION_FAILED)` — **no** raw-currency fallback, **no** default-50, **no** `saveToHistory`.
3. ViewModel/consumer maps `Unavailable` → "—" / hides the score card (check the score's UI consumer; keep the layout stable).
4. Keep genuine unexpected exceptions on the existing error path — but they must also not persist a fabricated score.

**What it solves.** No fake 50/100 scores or mixed-currency scores reach UI/history.

**Guardrails.** `saveToHistory` only on `Data`. History consumers must tolerate gaps (they already tolerate absence of rows). Tests: normalization failure → Unavailable, no history row; success unchanged.

### NEW-P5-009 — `MoneyAggregateBuilder` defaults missing counts to 0

**Fix design.** `MoneyAggregateBuilder.kt:42-55`: keep the warning; when `transactionCounts` size ≠ buckets size, set the aggregate's `isPartial = true` (metadata already carries `staleRateCount` style fields — add `countsIncomplete = true` if needed) instead of silently materializing zeros. Consumers of counts treat them as approximate. **Tests:** mismatch fixture → isPartial set, warning logged.

### NEW-P5-012 — `CurrencyConverter` hard-coded 24h staleness

**Fix design.** `CurrencyConverter.kt:86-87`: inject `StaleRatePolicy` (domain/core/money — already models Default 24h vs LatestDefault 7d) instead of the local `MAX_RATE_AGE_MS`; select `forBasis(...)` per call-site basis as the policy already supports. Constructor/DI update only; default behavior identical (24h) until a caller chooses otherwise — delete the "TODO — make configurable". **Guardrails:** no behavioral change for existing call sites (policy defaults must reproduce today's numbers); staleness tests updated only if they pinned the constant.

### NEW-P5-013 — Unknown bucket type → silent `MoneyAggregate.empty`

**Fix design.** `MultiCurrencyRepository.kt:919-925`: unknown bucket class is a programming error (sealed hierarchy violated) — throw `IllegalStateException("unknown_bucket_type:${bucket.javaClass.simpleName}")` (class name only — privacy-safe) instead of returning empty money. Keep the Timber warning removed in favor of the throw. **Guardrails:** confirm no production caller can hit it with persisted data (bucket type is built in code); test asserts the throw.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*SynthesisEngine*" --tests "*ForecastInputAssembler*" --tests "*SpendingPace*" --tests "*FinancialHealthScoreV2*" --tests "*MoneyAggregate*" --tests "*CurrencyConverter*" --tests "*ForecastSynthesisGolden*"
```

## Sequencing & risk
- Order: RP-05 → 6a → 6b → 6c. 6b is the money-critical PR — smallest possible diff, golden deltas enumerated. RP-09 (FSFE fixes) rebases on 6b's exclusion conventions.
