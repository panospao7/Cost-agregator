# RP-09 — Budget partial-data handling & FX-basis consistency (Pipeline 6 ↔ stress engine)

> **Scope class:** engine crossover (BudgetRepository, BudgetForecastingEngine, BudgetMonitor, FinancialStressForecastEngine, CashFlowCalculator). **Mode:** strict (money).
> **Files:** `FinancialStressForecastEngine` (domain/forecasting/), `BudgetRepository` (data/repository/), `BudgetMonitor` (domain/budget/), `BudgetForecastingEngine` (domain/budget/), `CashFlowCalculator` (domain/cashflow/), `MergedRecurringPatternsProvider` (domain/forecasting/ ⟂), stress-forecast UI consumer ⟂ (ViewModel/screen that renders `StressForecast`).
> **PR shape:** 3 PRs — (9a) stress engine = P6-001, 008, 009, 010, NEW-P6-010, P6-P1-13(UI half); (9b) budget partial-data = P6-005, 006; (9c) forecast basis = P6-007; NEW-P6-015 = documentation-only decision.
> **Depends on:** RP-08 (builder semantics shared with `BudgetForecastingEngine`), RP-06 (exclusion conventions in the synthesis/forecast family).

---

## 9a — Stress engine correctness

### P6-001 — Failed-rule fallback double-counts materialized occurrences (HIGH)

**Problem.** `FinancialStressForecastEngine.kt:307-394`: `ruleIds` (all manual rules) filters the materialized-occurrence read (`:337-340`); when a rule's `projectOccurrences` throws, the rule stays in `ruleIds` **and** enters `failedPatterns`; the fallback (`:388-394`) then re-expands the same rule over the same window with **no dedup** → each materialized PLANNED/OVERDUE occurrence counted twice in `recurringObligations` → inflated crunch probability, false HIGH/CRITICAL. The sibling implementation (`CashFlowCalculator.kt:236-244`) survives only because of its content-dedup pass (`:334-363`).

**Fix design.**
1. In the materialized-occurrence merge loop, skip occurrences whose `(sourceType, sourceId, dueDate-bucket)` key matches a fallback-expanded occurrence — implement exactly as the fix, not the sibling's fuzzy content match: build `expandedKeys: Set<String>` from the fallback expansion (sourceType|sourceId|dayStart — the same components as `occurrenceKey`) and filter the materialized rows: `materialized.filter { key(it) !in expandedKeys }`.
2. Order of operations: expand failed patterns **first** (collect keys), then merge materialized rows with the filter — this prefers *projected* amounts for failed rules on dates where both exist and keeps materialized rows where the projection didn't reach (partial materialization), each obligation exactly once.
3. Add a comment contrasting with `CashFlowCalculator`'s approach and why key-based (not content-based) dedup is sufficient here (same source identity, exact date bucket).

**What it solves.** Stress forecasts stop doubling recurring obligations after a transient projection failure.

**Guardrails.** Do not change the fallback trigger semantics (`failedRuleIds` from caught projection errors); do not touch `CashFlowCalculator` in this PR. Money rule: obligation totals only deduplicate — no arithmetic changes.

**Tests.** `FinancialStressForecastEngineTest`: fixture where one rule has a materialized PLANNED occurrence in-window **and** its projection throws (fake provider) → obligation counted once; a rule with materialized rows *outside* the fallback's expansion range still counted once.

### P6-008 — Overdue detected patterns contribute zero; 90-day staleness dead (LOW)

**Problem.** `:418-433`: `while (nextDate >= startDate && nextDate < endDate)` executes zero iterations when `nextExpectedDate < startDate` — a pattern 5 days overdue contributes nothing until its date advances, making the 90-day stale allowance unreachable for exactly the patterns it exists for. (`CashFlowCalculator.expandPatternDueDates:612-619` does catch up correctly.)

**Fix design.** Before the window loop, advance the anchor: `while (nextDate < startDate) nextDate = advance(nextDate)` (same recurrence step the loop uses), *then* apply the 90-day staleness check against the **original** `nextExpectedDate` (stale = original date older than 90d — preserve the current staleness semantics), then emit dates in `[startDate, endDate)`. This mirrors the sibling's catch-up.

**Tests.** Overdue-by-5-days detected pattern → contributes its in-window occurrences; 100-day-stale pattern → still excluded.

### P6-009 — Currency-blind fallback constants (LOW)

**Problem.** `:512-516`: empty purchase history fabricates `daysAhead * 20.0 ± 5` spend in home-currency units (negligible for JPY, dominant for PHP); `CashFlowCalculator.kt:426-431` risk bands `500/100/0` are absolute home-currency numerals.

**Fix design.**
1. FSFE: when purchase history is empty, do **not** fabricate — set a `lowData: Boolean` flag on the forecast result and run the simulation with obligations-only burn (recurring obligations are real data; discretionary = 0 with the flag). The UI (P6-P1-13 consumer) renders the low-data marker.
2. CFC bands: replace absolute thresholds with ratio-to-income bands when income data exists (e.g. LOW < 10% of monthly normalized income, MED < 25%, HIGH above); when no income, degrade to ordinal labels without thresholds (LOW/MED/HIGH computed from the day-expense distribution's own quantiles). Keep the enum values; change band assignment only.

**Guardrails.** Both engines feed UI text — check string resources for "%"-based band labels; no user-visible regression for single-currency EUR-typical users should occur (ratio bands reproduce similar classifications at typical income levels — verify with a EUR fixture in tests).

**Tests.** JPY-user empty-history fixture → no fabricated crunch (low-data flag); CFC band assignment by ratio fixture.

### P6-010 — "Earliest crunch" is the horizon end; no-op math (LOW)

**Problem.** `findEarliestCrunchDate` (`:622-630`) returns `now + daysAhead` of the first at-risk horizon — the horizon's **end**; `:647` `recurringObligations / 30.0 * 30.0` is a literal no-op labelled "monthly rate".

**Fix design.** Rename the result field to `atRiskHorizonEnd` (or keep the field name and fix the label/doc + all UI strings reading it — search string resources). If a true "earliest plausible crunch day" is wanted, compute the first simulated day where the median balance crosses zero within the at-risk horizon (the simulation already produces daily paths — cheap median extraction); otherwise document the semantic honestly. **Recommendation:** rename + document (UI copy change), skip the interpolation unless the UI demands a day-level answer. Delete the `/30.0*30.0` line and use per-day obligations directly at its consumer.

### NEW-P6-010 — Hardcoded risk thresholds (tracked)

**Fix design.** `:76-81` `RISK_THRESHOLD_*` → move into a `StressRiskThresholds` data class with Hilt-provided defaults (`@Provides fun defaults() = StressRiskThresholds(...)`) injected into the engine. No AppConfig infra exists — this injection point *is* the configurability fix; delete the `TODO: Migrate to AppConfig` comment. Behavior identical.

### P6-P1-13 (UI half) — `StressForecastMode` never rendered (tracked)

**Problem.** The engine honestly labels degraded outputs (`NET_CASHFLOW_ESTIMATE` vs `ESTIMATED_INDEX`, `:169, :202, :786-796`) but **zero UI consumers** read `StressForecastMode` — the honesty exists only as an unused field.

**Fix design.** Find the stress-forecast render site (the ViewModel/screen consuming `computeStressForecast` via `ComputeDashboardWidgetsUseCase`); render a small qualifier when `mode != ESTIMATED_INDEX` (string resource: "estimate based on 90-day net cash flow", plus the P6-009 low-data marker). Pure UI addition; engine unchanged.

---

## 9b — Budget partial-data semantics

### P6-005 — BudgetMonitor ignores `isPartial` → false-safe alerts (MED)

**Problem.** `status.isPartial` (set by `BudgetRepository.kt:221, :348` when limit or spend aggregation dropped data) appears only in diagnostic metadata (`BudgetMonitor.kt:301`); the alert switch (`:318-352`) and notification text (`:400-435`) never see it → understated spend (missing rates) never reaches `notifyAtWarning` → no alert while data is incomplete. Related: full limit-conversion failure sets `percent = 0f` (`BudgetRepository.kt:309-314`), which also suppresses alerts.

**Fix design.**
1. `BudgetMonitor`: when `status.isPartial` → append a bounded qualifier to the notification body ("some transactions excluded — rates unavailable"; string resource, no amounts) and include `partialData = true` in the alert diagnostic event.
2. Surface a `PARTIAL_DATA` state to the budget UI (the status snapshot already flows to the list screen) → small badge/"data incomplete" chip. Alert thresholds keep firing as computed (we cannot alert on unknowable spend); the honest signal replaces the silent gap.
3. In `BudgetRepository`, stop zeroing `percent` on limit-conversion failure when spend is known: emit `percent = null` (unknown) and let the monitor treat null-percent as PARTIAL_DATA rather than "0% healthy". Check percent consumers for null-handling (`BudgetStatus` consumers in UI/tests — make the field nullable or add `percentKnown: Boolean`).

**Guardrails.** Controlled reason codes only in diagnostics; no raw exception text. UI: no new colors for partial — reuse existing informational chip styles. **Tests:** `BudgetMonitorTest` partial fixture → qualifier + diagnostic flag; null-percent fixture → PARTIAL_DATA not 0%.

### P6-006 — Rollover skipped on any limit-conversion partial (MED)

**Problem.** `BudgetRepository.kt:258` gates the entire rollover loop on `!initialLimitAggregate.isPartial`; the benign latest-rate fallback (`:500-512`) sets `isPartial = true` while still producing a usable home-currency limit → historical-as-of misses (pruned rates) silently disable rollover: surplus vanishes, `effectiveLimit = baseLimit`, monitor can immediately alert "Exceeded" against the un-rolled limit.

**Fix design.** Replace the gate with a value-preserving one:
```kotlin
val homeLimit = initialLimitAggregate.homeCurrencyTotal  // or equivalent non-null check
if (budget.rollover && homeLimit != null) { /* rollover loop */ }
```
i.e. rollover proceeds whenever a home-currency limit exists (exact or latest-fallback); only a *total* conversion failure (null) skips rollover — and in that case the P6-005 PARTIAL_DATA/unknown-percent path reports it instead of pretending the base limit is authoritative. The `isPartial` flag continues to flow into status for the badge.

**What it solves.** Foreign-currency budgets keep their accumulated surplus; no false "Exceeded" alerts after rate pruning.

**Guardrails.** Rollover arithmetic unchanged (cap, ArrayDeque semantics verified earlier); money rule: per-period conversion basis untouched. **Tests:** `BudgetRolloverTest` + `BudgetRepositoryHistoricalStatusTest`: fallback-partial limit → rollover applied with isPartial badge; total failure → skipped + PARTIAL_DATA.

---

## 9c — Forecast FX basis

### P6-007 — `BudgetForecastingEngine` mixes rate bases (MED, regression vs the repo's own fix)

**Problem.** Limit converted at `RateBasis.PERIOD_END, atMillis = periodEnd` (`:122-129`); spend normalized at `TRANSACTION_DATE` (`:185-186`, persisted `:206`). `determineRiskLevel`/`calculateOverspendProbability` divide the two (`:549-561, :578`). A ~10% home-currency move mid-period flips HIGH→MEDIUM and shifts probability bands. `BudgetRepository` fixed exactly this class (P6-CURRENT-001, `:202-206, :393-399` — both sides at period-end); the engine never mirrored it.

**Fix design.**
1. Convert spend at the same period-end basis: reuse the repository's approach — either expose the existing bounded grouped spend-bucket query/normalization as a repository API (preferred; the engine already injects or can inject `BudgetRepository`) or replicate the `convertBudgetAmountToHomeCurrencyAsOf(periodEnd)` basis for each currency bucket in the engine.
2. Keep `RateBasis.TRANSACTION_DATE` for any *historical analytics* the engine reports (history series), and switch only the **current-period** `spentToDate` used against the limit. Record the basis on the forecast row (`:206` area already records a basis field — update the value and its readers).
3. Golden: `BudgetCalculatorGoldenTest` + forecast fixtures with a mid-period rate change — assert risk level stable under a spend-currency appreciation that previously flipped it.

**Guardrails.** Money rule: basis change alters forecast numbers for multi-currency users — golden deltas enumerated; single-currency unaffected. Land after RP-08 (same file).

---

## NEW-P6-015 — Income recurring patterns (tracked) → documentation-only decision

**Problem.** `CashFlowCalculator.isIncomePattern()` (`:647-651`) unconditionally `false` + `MergedRecurringPatternsProvider` (`:23-24`) sources only expense rules — recurring **income** cannot be represented in the cashflow forecast.

**Fix design (decision, not code).** Check the `ManualRecurringExpense` entity for an income/direction field. If none exists, income-recurring is a **feature gap** (entity + UI + engine), not a bug — record it as such in the registry (update the row: "blocked-by-feature: income recurring rules") and leave the TODO pointing at the feature request. If a direction field exists but is unwired, wire `isIncomePattern` to it and route income patterns into the income side of the day map (`:412-416` shape). **Do not invent a heuristic** (e.g. negative amounts) — misclassified income as expense is worse than absent income.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*FinancialStressForecastEngine*" --tests "*CashFlowCalculator*" --tests "*BudgetMonitor*" --tests "*BudgetRollover*" --tests "*BudgetRepository*" --tests "*BudgetForecastingEngine*"
```

## Sequencing & risk
- Order: RP-08 → 9a → 9b → 9c. Risk: 9c changes forecast numbers for multi-currency users (intended correction); 9b changes alert copy (string review); 9a is engine-internal. All money changes carry golden updates in-PR.
