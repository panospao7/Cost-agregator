# RP-07 — Runway/trend polish (Pipeline 5, low-severity cluster)

> **Scope class:** pipeline-isolated. **Mode:** standard (display-edge semantics; no engine arithmetic changes beyond guards/rounding edges).
> **Files:** `ComputeDashboardWidgetsUseCase` (domain/usecase/dashboard/), `TotalsAggregationEngine` (domain/analytics/), `TimePeriodUtils` (domain/util/), `HomeViewModel` (ui/screens/home/) — read-only consumer checks.
> **PR shape:** one small PR; all four items touch the same use case file except P5-014.

---

## P5-010 — Runway edge cases: day-1 CRITICAL, truncation, undeducted commitments (LOW-MED)

**Problem.** `computeRunwayAndForecast`:
- `:603` `averageDailyBurn = monthSpent / dayOfMonth` → on day 1 with no purchases, burn = 0 → `runwayDays = 0` (`:617-621`) → status CRITICAL (`:623-627`) despite a full budget remaining.
- `:618` `(totalRemaining / averageDailyBurn).toInt()` truncates (13.99 → 13), flipping the CAUTION/HEALTHY boundary.
- `committedExpenses`/`likelyExpenses` are displayed but never deducted from `totalRemaining` (`:609-615` subtracts only `monthSpent`).

**Fix design.**
1. Zero-burn guard: when `averageDailyBurn <= 0`: if `totalRemaining > 0` → `runwayDays = null` and render "30+" (capped at days-to-period-end; pick the smaller of period-end and 30 for honesty) with status derived from budget utilization instead of runway; only if `totalRemaining <= 0` keep the CRITICAL path.
2. Replace `.toInt()` with `.roundToInt()` (kotlin.math) — one-char semantic fix, note in PR.
3. Deduct committed (certain) outflows: `effectiveRemaining = totalRemaining - committedTotal`; keep `likelyTotal` informational (displayed as context, not deducted — uncertain by definition). Guard `effectiveRemaining >= 0` (committed can exceed remaining → clamp to 0, runway null, status from utilization).
4. Keep the existing status thresholds untouched — only inputs become correct.

**What it solves.** No more fake day-1 CRITICAL; rounding no longer flips tiers; bill-heavy users see honest runway.

**Guardrails.** `FinancialRunway` widget consumers (`HomeScreen`/`ForecastRunwayIntegrationTest`) read `runwayDays` as non-null Int today — introduce `runwayDays: Int?` + a display string, or keep Int with a sentinel `Int.MAX_VALUE`… **prefer nullable** and update the widget + test in this PR (small, explicit). Money rule: no formatting changes.

**Tests.** Day-1-empty fixture → no CRITICAL, "30+"; 13.99-day fixture → 14; committed > remaining → clamped + utilization status.

## P5-011 — Block-party history keys ignore DST (LOW)

**Problem.** `:671-673` probes `monthStart + dayIndex * DAY_IN_MILLIS` while `dailySpending` is keyed by zone-aware `getStartOfDay` (`TimePeriodUtils.kt:452-459`, whose own docs warn days can be 23/25h) → after an in-month DST shift, probes miss by 1h → day reads 0/neighbor-day.

**Fix design.** Iterate days with `TimePeriodUtils.addDays(monthStart, dayIndex)` (LocalDate-based, DST-safe — same utility the rest of the dashboard uses) instead of fixed-millis multiplication. No key-map changes.

**Guardrails.** Post-RP-06b the raw-expense path takes precedence; this fixes the normalized fallback — keep it correct anyway (fallback fires for zero-raw days). **Tests:** fixture across a spring-forward day → each day's value resolves.

## P5-013 — MoM insight degeneracies (LOW)

**Problem.** `buildNaturalLanguageInsight` (`:1115-1135`): with `previousMonthTotal == 0` the MoM branch either skips (null aggregate) or, when conversion made it 0.0, fires trivially-true "higher"; mid-month, `diff = MTD − fullPrevMonth` is negative most of the month → premature "spent less".

**Fix design.**
1. When `previousMonthTotal <= 0` → emit no MoM insight (or an explicit "no comparison available" insight if the UI needs a placeholder — check how other unavailable insights render).
2. Project the current month before comparing: `projected = monthSpent / daysElapsed * daysInMonth` (guard `daysElapsed >= 1`; both already computed in the use case). Compare `projected` vs `previousMonthTotal`; threshold text unchanged ("on pace to spend X% more/less").
3. Keep the >20% spike rule; it now applies to the projection.

**Tests.** Prev-empty → no insight; day-3 underspend-but-on-pace-to-overspend → no false "spent less".

## P5-014 — Monthly status average includes the partial current month (LOW)

**Problem.** `TotalsAggregationEngine.getMonthlyTotals` (`:91`) calls `getAverageForPeriodType(MONTH, excludeCurrent = false)`; only the YEAR caller passes `true` (`:257`). Comparing MTD against an average that includes the partial current month biases every month UNDER_AVERAGE early on. The exclusion machinery (`:373-383`, DSH-13) exists and is tested — just not selected.

**Fix design.** At `:91` pass `excludeCurrent = true` for the MONTH status baseline. Audit the other `getAverageForPeriodType(MONTH/WEEK, false)` callers (`HomeViewModel:597/659/708/718/729`) — each is a "compare current vs typical" surface and should also pass `true`; add a KDoc on `getAverageForPeriodType` stating: `excludeCurrent = true` for *comparisons*, `false` only for "total including current" displays. Keep WEEK behavior consistent with whatever each surface displays (verify per call site; do not blanket-change displays that legitimately include the current week).

**Guardrails.** This shifts status badges users see — expected and desired; golden/`TotalsAggregationEngineTest:499-515` pins include-current for a purchase-only case, not the exclusion flag — add explicit excludeCurrent assertions. **Tests:** partial-current fixture → average excludes it; day-2 month no longer auto UNDER_AVERAGE.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*ComputeDashboardWidgets*" --tests "*TotalsAggregationEngine*" --tests "*ForecastRunway*" --tests "*DashboardProjectionSafety*"
```

## Sequencing & risk
- Land after RP-05/RP-06 (consumes their corrected inputs). Risk: low — guards and selection flags; the only API shape change is the nullable runway field (widget + tests updated in-PR).
