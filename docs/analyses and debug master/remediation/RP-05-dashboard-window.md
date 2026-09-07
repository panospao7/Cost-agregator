# RP-05 — Dashboard window & income correctness (Pipeline 5)

> **Scope class:** pipeline-isolated, money-adjacent. **Mode:** strict (aggregation semantics feed user-visible financial numbers).
> **Files:** `ComputeDashboardWidgetsUseCase` (domain/usecase/dashboard/), `DashboardContractsAdapter` (data/repository/), `DashboardPrimitives` ⟂ (domain/model/dashboard/), `ExpenseDao` (data/database/dao/), `HomeViewModel` (ui/screens/home/), `DashboardDataProvider` (domain/usecase/dashboard/), `SynthesisEngine` (domain/logic/) — read-only dependency for RP-06.
> **PR shape:** one PR; P5-001 and P5-005 must land together (they share the fetch-window change).

---

## P5-001 — Aggregates span the 2-month fetch window but are consumed as current month (HIGH)

**Problem.** `DashboardContractsAdapter.kt:55-63` fetches `[prevMonthStart, monthEnd)`. In `produceDashboardNormalizedInput` only `monthAggregate` is clipped (`:371`); `periodAggregate` (`:368`), `depositAggregate` (`:372`) and `categoryAggregates` (`:393`) aggregate the full window. Verified consumers with no downstream clip: `monthlyIncome = depositAggregate` (`:605`) → inflates `totalRemaining`/runway with last month's salary; MonteCarlo `spentToDate = periodAggregate` (`:737`); TopCategories + denominator (`:771-772`).

**Fix design.**
1. Clip once at the source: in `produceDashboardNormalizedInput`, split `data.expenses` into `currentMonthExpenses = expenses.filter { it.date >= periodStart }` immediately after the window arrives (before any aggregate).
2. Feed `periodAggregate`, `depositAggregate`, `categoryAggregates` from `currentMonthExpenses`; keep `monthAggregate` on the same list (its own filter becomes redundant — remove it); keep `previousMonthAggregate`/`previousMonthTotal` on the complement (`it.date < periodStart`) instead of relying on the adapter's separate fetch — one source of truth for the boundary.
3. Add a KDoc on the method stating the contract: "all *current* aggregates are bounded by `[periodStart, periodEnd)`; previous-month values come from the pre-period slice".
4. Regression guard: add an assertion-style unit test with one expense on the last day of the previous month and one mid-current-month, asserting both appear only in their own aggregates.

**What it solves.** Monthly income/runway/MonteCarlo/TopCategories stop double-counting the previous month from the 1st to the end of each month.

**Guardrails.**
- Money rule: no rounding/format changes — pure windowing.
- `depositAggregate` also feeds the not-mine/shared filters (`:358-366`) — those filters stay applied before aggregation (and become *effective* once RP-05/P5-004 lands).
- Do **not** change `DashboardContractsAdapter`'s fetch here — that is P5-005's change, landed in the same PR.

**Tests.** `ComputeDashboardWidgetsUseCaseTest` (extend or create): boundary-day fixtures as above; `TotalsAggregationEngineTest` untouched (engine already correct — bug is caller-side).

## P5-005 — 6-month trend built from a 2-month fetch (MED)

**Problem.** `buildTrendFromNormalizedInput` (`:829-835`) builds six month keys (M-5…M-0) from `normalizedExpenses`, which derive from the 2-month window → M-2…M-5 render as flat zero.

**Fix design (with P5-001, same PR).**
1. Widen `observeDashboardExpenses` (`DashboardContractsAdapter.kt:55-63`) to 6 full months back from the current month start. Keep the variable names honest (`windowStartMs`).
2. P5-001's clip guarantees current-month aggregates ignore the extra history; `previousMonth*` uses the M-1 slice only (update the complement filter from `< periodStart` to the M-1 month range, or compute previous-month via `>= prevMonthStart && < periodStart` explicitly so M-2…M-5 don't leak into "previous month").
3. Trend consumes the full widened window unchanged (zone-consistent month keys already verified clean).
4. Memory note: 6 months of expense rows on the dashboard path — acceptable (the list is already fully materialized for normalization); note in PR description.

**What it solves.** The trend chart shows real history instead of four zero months. **Guardrails:** confirm `normalizedExpenses` isn't consumed anywhere else with a 2-month assumption (grep `normalizedExpenses` consumers; `MonteCarlo` uses aggregates, not the list — verify). **Tests:** trend fixture with spend in M-4 → non-zero series entry.

## P5-004 — `isSharedExpense` dropped at the adapter boundary → shared deposits counted as income (HIGH)

**Problem.** `DashboardExpense` (`DashboardPrimitives.kt:12-26`) has no `isSharedExpense`; `toDomainDashboard` (`DashboardContractsAdapter.kt:179-191`) doesn't map it; `toExpenseEntity` (`ComputeDashboardWidgetsUseCase.kt:492-510`) relies on the entity default `false` (`Expense.kt:110`) → the documented NEW-P5-003 exclusion (`:362-366`, `!it.isSharedExpense`) is tautologically true. The DAO query (`ExpenseDao:178`) has no shared predicate (contrast `:1315`).

**Fix design.**
1. Add `isSharedExpense: Boolean` to `DashboardExpense` (default `false` to keep other constructors compiling) and map it in `toDomainDashboard` from the entity.
2. Set it in `toExpenseEntity` (`copy(isSharedExpense = src.isSharedExpense)`).
3. The existing filter at `:362-366` then becomes live — no logic change needed there.
4. Add the shared predicate to the adapter query as defense-in-depth (`AND (transactionType != 'DEPOSIT' OR isSharedExpense = 0)` — or simpler, keep filtering in Kotlin as today; pick Kotlin to avoid SQL drift, and note why).

**What it solves.** Shared-expense repayment deposits stop inflating `monthlyIncome`, `totalRemaining` and runway.

**Guardrails.**
- `DashboardExpense` is a cross-layer primitive — adding a defaulted field is additive; check its `toExpenseEntity`/serialization consumers for exhaustive `copy`/`equals` assumptions (grep constructors).
- Do **not** exclude shared *purchases* — only the deposit filter cares (`:362-366` semantics preserved exactly).

**Tests.** Fixture: shared DEPOSIT (repayment) + personal DEPOSIT → income counts only the personal one; shared PURCHASE still counted in spend.

## P5-012 — Uncategorized spend invisible in TopCategories but in the denominator (LOW)

**Problem.** `categoryAggregates` includes the null-category group; `:775-776` `categoryNameById[categoryId] ?: return@mapNotNull null` drops it while `:771` keeps it in `total` → percentages under-sum; the often-largest bucket is invisible.

**Fix design.** Emit a pseudo-category row mirroring the totals engine's approach (`TotalsAggregationEngine.kt:317-325`): `categoryId = null` → display name "Uncategorized" (string resource, not hardcoded English — check existing strings file for a reusable entry). Keep sort/limit logic unchanged.

**What it solves.** Percentages sum to ~100; uncategorized spend visible. **Guardrails:** UI already renders arbitrary category names — no composables change expected. **Tests:** fixture with null-category expense → row present, denominator consistent.

## P5-003 — Current MTD passed as `averageMonthlyTotal` baseline (MED-HIGH)

**Problem.** `:575` `averageMonthlyTotal = normalized.monthAggregate.displayAmount.takeIf { it > 0 }` — semantically a *historical typical month* (`ForecastInputAssembler.kt:333-344`). Downstream: `SynthesisEngine.kt:288-292` `typicalDailyDiscretionary = (averageMonthlyTotal − monthlyRecurring)/daysInMonth` collapses early-month; `:407` null→confidence −0.10 is unreachable after the month's first purchase.

**Fix design.**
1. Prefer the previous full month: `averageMonthlyTotal = previousMonthTotal.takeIf { it > 0 } ?: monthAggregate.displayAmount.takeIf { it > 0 }` (previous month is complete by definition; MTD remains the bootstrap fallback for first-month users).
2. After RP-05's window widening (6 months), upgrade to a rolling average of *completed* months M-1…M-5 when ≥2 are non-zero (mean, excluding zero-months only if they are genuinely empty — keep zero months in the mean; they are real "no spend" months).
3. Keep the `null` case (both zero) so SynthesisEngine's −0.10 confidence penalty works as designed.

**What it solves.** Early-month discretionary forecasts stop collapsing; the no-history penalty fires when it should.

**Guardrails.** This changes synthesis outputs for multi-month users → `ForecastSynthesisGoldenTest` fixtures updated in this PR (money rule: golden updates accompany the semantic change — call out each delta in the PR description). Coordinate with RP-06 which touches the same engine inputs — **land RP-05 first**.

**Tests.** Day-3 fixture (small MTD, full prev month) → `typicalDailyDiscretionary` derived from prev month, not MTD; both-zero → null → confidence penalty asserted.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*ComputeDashboardWidgets*" --tests "*DashboardContractsAdapter*" --tests "*ForecastSynthesisGolden*" --tests "*DashboardProjectionSafety*"
./gradlew :app:compileDebugKotlin
```

## Sequencing & risk
- Single PR; land **before** RP-06/RP-07 (they consume the corrected inputs). Risk: medium — golden-number shifts are expected and must be enumerated; no rounding changes anywhere.
