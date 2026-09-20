# RP-05 - Dashboard window, income, and historical baseline correctness

> **Status:** batches 1-2 merged on bug-fixes; known issues from strict review tracked in RP-00-10_MERGED_STRICT_REVIEW.md.
> **Scope:** dashboard aggregation and input preparation (Pipeline 5 / Segment 10).
> **Mode:** strict, money-adjacent. The change alters user-visible totals, forecast inputs, and category percentages.
> **Must land before:** RP-06 and RP-07.
> **Dependencies:** RP-01 is required for the existing cancellation contract, but this slice does not change cancellation handling. RP-06 owns pace wiring, synthesis conversion/fallback semantics, and health-score outcomes.
> **No schema change:** this plan changes in-memory dashboard models and queries only. Stop if a Room schema or migration is proposed.

## Verified source contract

The current dashboard expense flow is:

1. `DashboardContractsAdapter.observeDashboardExpenses()` queries `[previousMonthStart, monthEnd)` and maps `Expense` to `DashboardExpense`.
2. `ComputeDashboardWidgetsUseCase.buildContext()` maps those rows back to lightweight `Expense` entities and calls `produceDashboardNormalizedInput(expenses, monthStart, now)`.
3. `produceDashboardNormalizedInput()` currently builds `periodAggregate`, `depositAggregate`, and `categoryAggregates` from the whole fetched list. Only `monthAggregate` and the explicit previous-month purchase slice are bounded.
4. The trend groups `normalizedExpenses`, so a two-month source window cannot populate the six month keys emitted by `buildTrendFromNormalizedInput()`.

The canonical date contract is half-open: `[startInclusive, endExclusive)`. Every new filter and test in this plan must use that contract; do not use inclusive `LongRange` checks for period boundaries.

The current `DashboardExpense` mapping omits `Expense.isSharedExpense`. `toExpenseEntity()` also does not copy it, so the existing shared-deposit exclusion is currently dead after the adapter boundary. `CategorySpending` and `CategoryInfo` are domain models consumed by Home and retro dashboard composables; uncategorized output therefore needs a UI-safe pseudo-category contract, not only a map entry.

## P5-001 - Current aggregates include the previous-month fetch window

### Confirmed defect

`DashboardContractsAdapter` intentionally fetches the previous month for comparison. In `produceDashboardNormalizedInput`, however, the following values are currently calculated from all fetched purchases/deposits:

- `periodAggregate`, consumed as current spend by Monte Carlo;
- `depositAggregate`, consumed as current monthly income and runway input;
- `categoryAggregates`, consumed by TopCategories and its denominator.

The result is previous-month spend/income being presented as current-month data. This is a live money correctness issue, not a DAO-query defect.

### Required implementation

1. Keep the widened source list intact, but define two explicit slices immediately after loading it:

   - `currentPeriodExpenses = expenses.filter { it.date >= periodStart && it.date < periodEnd }`;
   - `previousMonthExpenses = expenses.filter { it.date >= previousMonthStart && it.date < periodStart }`, where `previousMonthStart = TimePeriodUtils.getMonthRange(periodStart, -1).first` (or the equivalent calendar-safe helper).

   `periodEnd` is the exclusive reference-time boundary supplied by `buildContext`. Do not silently change it to an inclusive end-of-day or to the next month boundary. If a caller needs a complete month, it must pass that complete month range explicitly.

2. Build all current dashboard aggregates from `currentPeriodExpenses`:

   - `periodAggregate`;
   - `monthAggregate`;
   - `depositAggregate` after the ownership/shared filters;
   - `categoryAggregates`;
   - `todayAggregate` and `weekAggregate`, each additionally intersected with its own half-open range.

   Do not rely on a later consumer to clip an aggregate. The aggregate itself must carry the correct scope.

3. Build `previousMonthAggregate` only from `previousMonthExpenses`. Rows older than M-1 remain available in `normalizedExpenses` for the trend and historical baseline, but must not enter the previous-month comparison aggregate.

4. Keep `normalizedExpenses` as the full fetched, normalized list so the trend can use M-5..M-0. Document that this list is historical input, while the named aggregates are period-scoped.

5. Include the aggregates that feed the forecast baseline in the data-quality calculation. A missing rate in a historical baseline must be visible as partial input; it must never trigger a raw-currency fallback.

### Acceptance tests

- A purchase/deposit at the last instant of M-1 is present in `previousMonthAggregate` and absent from all current aggregates.
- A purchase/deposit at `periodStart` is present in current aggregates and absent from `previousMonthAggregate`.
- A row at `periodEnd` is excluded by the half-open contract.
- A purchase older than M-1 appears only in historical/trend input, never in `previousMonthAggregate`, monthly income, Monte Carlo `spentToDate`, or TopCategories.
- Missing historical FX marks the relevant normalized input partial and does not add the original amount to a home-currency total.

## P5-004 - Preserve shared-expense identity through the dashboard boundary

### Confirmed defect

`DashboardExpense` does not carry `isSharedExpense`; `DashboardContractsAdapter.toDomainDashboard()` therefore loses the entity value, and `ComputeDashboardWidgetsUseCase.toExpenseEntity()` reconstructs the default `false`. The documented filter excluding shared deposits is consequently tautological. Shared purchases must remain ordinary spend; only shared repayment deposits are excluded from dashboard income.

### Required implementation

1. Add `isSharedExpense: Boolean = false` to `DashboardExpense` as an additive field. The default preserves source compatibility for existing fixtures, but every production mapper must set it explicitly.
2. Map `Expense.isSharedExpense` in `DashboardContractsAdapter.toDomainDashboard()`.
3. Copy it in `DashboardExpense.toExpenseEntity()`.
4. Apply the same predicate in every dashboard deposit list, including the currently unused `ComputeContext.deposits` value; alternatively remove that dead field. There must be one documented predicate:

   `transactionType == DEPOSIT && !isNotMine && !isSharedExpense`.

5. Do not add a second SQL-only exclusion that can diverge from the canonical Kotlin mapping. The adapter query remains a date-window query; ownership and shared semantics are enforced after mapping in the normalized-input owner.
6. Audit constructors, `copy` calls, snapshots/serialization, equality-based fixtures, and test mappers. A defaulted constructor parameter is not evidence that the value is preserved.

### Acceptance tests

- Entity `isSharedExpense=true` -> adapter `DashboardExpense.isSharedExpense=true` -> normalized deposit aggregate excludes it.
- A personal deposit in the same period remains in income.
- A shared purchase remains in `periodAggregate`, category totals, trend, and TopCategories.
- A legacy fixture that omits the new constructor argument still compiles and has the explicit default `false`.

## P5-005 - Build the six-month trend from a six-month source window

### Confirmed defect

`buildTrendFromNormalizedInput()` emits six calendar keys, M-5 through M-0, but the adapter currently fetches only M-1 and M-0. Older trend buckets are therefore deterministically zero-filled, independent of actual stored expenses.

### Required implementation

1. In `DashboardContractsAdapter.observeDashboardExpenses()`, compute the source window with calendar-safe helpers:

   `trendStart = TimePeriodUtils.getMonthRange(now, -5).first` and `monthEnd = TimePeriodUtils.getMonthRange(now, 0).second`.

   Query `[trendStart, monthEnd)`. Do not subtract `5 * 30 days` or `DAY_IN_MILLIS`; month lengths and DST make that incorrect.

2. Keep the variable names and KDoc honest (`trendStart`, not `previousMonthStart`). The adapter still returns one flow and one mapped list; no second trend query is permitted.
3. Keep current and previous aggregation slices from P5-001. Widening the source list must not widen the current income, category, Monte Carlo, or previous-month aggregates.
4. Verify all `normalizedExpenses` consumers. Only the trend and current-day calculations may use the historical list; no consumer may assume it contains only the current/previous month.
5. Document the bounded memory trade-off: six months of already-materialized dashboard rows are intentional. Do not introduce an unbounded full-history query.

### Acceptance tests

- A purchase in M-4 produces a non-zero M-4 trend point.
- M-5..M-2 history does not change current-month spend, previous-month comparison, income, Monte Carlo `spentToDate`, or category percentages.
- A row exactly at `trendStart` is included; a row exactly at `monthEnd` is excluded.
- Month and day keys remain correct across a DST boundary.

## P5-003 - Use completed history for the synthesis baseline

### Confirmed defect

The dashboard currently passes current month-to-date spend as `SpendingPace.averageMonthlyTotal`. `SynthesisEngine` treats that field as a typical historical month, so early-month forecasts use a tiny baseline and the intended null-baseline confidence penalty is bypassed.

### Required implementation

1. Do not use `monthAggregate.displayAmount` as `averageMonthlyTotal`.
2. Add an additive historical-month aggregate collection to `DashboardNormalizedInput` (for example, a list of `monthStart + MoneyAggregate` values for complete M-1..M-5 buckets). Populate it from the already-normalized six-month list; do not re-read the DAO or reconvert rows.
3. Define the baseline policy explicitly:

   - include only completed calendar months M-1..M-5;
   - include zero-spend completed months in the arithmetic mean (zero is real history, not missing data);
   - if at least two completed months are available, use their mean;
   - otherwise use the previous complete month when it has data;
   - if no completed month has spend/history, return `null`.

   The implementation may use the previous-month aggregate as the one-month fallback, but it must not fall back to current MTD merely because it is non-zero.

4. Propagate the historical aggregates' conversion quality into the forecast input. If a month is partially normalized, retain its included amount and mark the input partial; never substitute the source-currency amount.
5. Keep `SpendingPace.previousMonthTotal` separate from `averageMonthlyTotal`; the former is a comparison baseline, the latter is the historical typical-month input.

### Acceptance tests

- Day-3 current month with a full M-1 and M-2 history uses the completed-month mean, not MTD.
- One completed month uses that month as the fallback.
- No completed history returns `averageMonthlyTotal=null` and preserves the synthesis confidence penalty.
- A completed zero-spend month is retained in the mean.
- A partially converted historical month never causes a raw-currency fallback.
- Existing single-currency golden values remain unchanged except where the baseline was previously current MTD; every changed fixture is listed in the PR.

## P5-012 - Keep uncategorized spend visible and percentage-complete

### Confirmed defect

`categoryAggregates` preserves the `null` category bucket and the total denominator includes it, but `computeCategoryTotals()` drops that entry when it cannot find a category entity. The largest unknown bucket can therefore disappear while percentages under-sum.

### Required implementation

1. Preserve the total as the sum of all category aggregates, including the null bucket. Do not replace it with a current-period fallback when category aggregates are non-empty.
2. Map `categoryId == null` to the existing dashboard pseudo-category convention used by `TotalsAggregationEngine`: reserved ID `0L`, name `"Uncategorized"`, icon `"?"`, neutral color `"#808080"`, `isIncome=false`. Confirm that persisted category IDs cannot be zero before relying on the sentinel.
3. Add an explicit `isUncategorized`/non-clickable signal to `CategorySpending`, or update every TopCategories/retro click and transaction-filter consumer to treat the reserved ID as a pseudo-category. A visible row must not navigate as if category `0L` were a real user category.
4. Keep localization ownership explicit. The domain model currently carries `String` names and the existing totals engine uses the same canonical label; do not inject Android resources into the domain layer in this slice. If a localized `UiText` migration is desired, make it a separate cross-layer change with all consumers enumerated.
5. Sort and limit after the pseudo-category is mapped, so uncategorized spend competes fairly for the top-five slots.

### Acceptance tests

- A null-category purchase emits one pseudo-category row.
- Category percentages sum to 100% (within the existing floating-point tolerance) when all aggregates are included.
- A shared purchase remains categorized/spend-visible; a shared deposit is not a category purchase.
- Clicking or filtering the pseudo-category does not query category ID `0L` as a real category.

## Explicitly out of scope / ownership matrix

| Finding | Owner | Reason |
|---|---|---|
| P5-002 pace widget hardcoded `NO_BASELINE` | RP-06 6a | Requires canonical `SpendingPaceCalculator` wiring and sentinel contract |
| P5-006 currency change does not trigger recompute | RP-06 6a | Touches `HomeViewModel` reload/currency-flow lifecycle |
| P5-007 `runBlocking` and rate-basis observability | RP-06 6b | Shared `SynthesisEngine` suspend contract |
| P5-008 health-score cancellation/fabricated result | RP-01 (CE) + RP-06 (typed unavailable outcome) | Avoid duplicate edits to `FinancialHealthScoreV2` |
| P5-009 inconsistent home-currency failure modes | RP-06 6c | Foundational money result propagation across consumers |
| P5-CURRENT-009 block-party raw multi-currency sum | RP-06 6b | Synthesis engine owns normalized block-party arithmetic |
| P5-010/011/013/014 runway, DST fallback, MoM, and monthly-status polish | RP-07 | Display/edge semantics after money input contracts stabilize |
| P6/P12 backup, retention, receipt, and import findings | RP-03/RP-10/RP-12/RP-13/RP-19 | Separate lifecycle/worker/privacy owners |

Do not fix an out-of-scope item opportunistically in this slice. If implementing P5-003 requires changing `SynthesisEngine` or its public API, stop and hand that crossover to RP-06.

## Required test inventory

Add or update tests in the owning families before implementation is accepted:

- `DashboardContractsAdapterTest`: six-month query window and mapper preservation of `isSharedExpense` (use a fake/ticker-controlled flow where practical).
- `DashboardExpenseMapperTest`: shared deposit and shared purchase round-trip.
- `P5AnalyticsFixesTest` or a focused `ComputeDashboardWidgetsUseCaseTest`: half-open current/previous slices, trend history, baseline selection, category pseudo-row, and percentage sum.
- `DashboardProjectionSafetyTest`: pseudo-category non-clickability and no raw fallback.
- `ForecastSynthesisGoldenTest` / dashboard golden tests: enumerate only the expected historical-baseline deltas.
- Existing multi-currency dashboard tests: missing-rate historical baseline remains partial and never mixes currencies.

Tests must assert controlled values and quality flags, not exception messages, raw SQL text, or user financial payloads in diagnostics.

## Stop conditions

Stop and request architecture review if:

- a proposed fix changes Room schema, database version, migration, or snapshot files;
- a current aggregate still includes any row outside its documented half-open range;
- a shared value is restored with a default after crossing an adapter or snapshot boundary;
- the pseudo-category can be mistaken for a real category by any production click/filter path;
- a missing FX rate is replaced by a source-currency amount, a fabricated home-currency value, or an untracked zero;
- a public `SpendingPace`, `DashboardNormalizedInput`, or UI model change overlaps RP-06 without an ownership decision;
- a six-month query becomes an unbounded full-history query or introduces a second reactive subscription.

## Validation gate

No Gradle command was run during this documentation batch. After implementation, review, and any required guardian gates, run sequentially with output captured:

```text
./gradlew :app:testDebugUnitTest --tests "*DashboardContractsAdapter*" --tests "*DashboardExpenseMapper*" --tests "*P5AnalyticsFixes*" --tests "*ComputeDashboardWidgets*" --tests "*DashboardProjectionSafety*" --tests "*ForecastSynthesisGolden*" --console=plain
./gradlew :app:compileDebugKotlin --console=plain
```

Completion requires the focused tests, a clean strict review of the dashboard diff, and explicit confirmation that no RP-06 ownership was accidentally implemented here.
