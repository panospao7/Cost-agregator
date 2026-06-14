# Pipeline 5 Debug / Review — Cost-agregator

Target: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: P5 — Currency / Dashboard / Analytics  
Mode: review only; no code changes.

## Verdict

**RED for release** until two dashboard correctness issues are fixed.

Many documented P5 fixes are real, but the current dashboard path still has at least one raw multi-currency sum and one ownership/shared-expense flag-loss path.

Sources used: target commit page confirms commit `83b798e` and changed architecture docs【turn0view0† docs list historical P5 issues and stale file names. Architecture law requires `MoneyAggregate` / quality propagation and forbids raw cross-currency `sumOf { effectiveAmount }`.

## Tracker / code drift

The tracker references `DashboardSynthesisEngine.kt`, `AnalyticsComputeEngine.kt`, and `TrendBuilder.kt`. At this SHA, the actual code is under:
- `domain/logic/SynthesisEngine.kt`
- `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- analytics folder has `AdvancedAnalyticsEngine`, `AnalyticsInputAssembler`, `TotalsAggregationEngine`, etc., but no `AnalyticsComputeEngine` / `TrendBuilder` in the listed inventory.

## Confirmed fixed / mostly fixed

1. **Dashboard normalization exists.** `produceDashboardNormalizedInput()` resolves home currency, uses `MoneyNormalizationEngine`, and uses `RateBasis.TRANSACTION_DATE`.
2. **Previous-month aggregate is populated.** The dashboard normalizer computes `previousMonthAggregate` from previous-month purchases.
3. **Projected total division guard exists.** `projectedTotal` uses `if (daysElapsed > 0) ... else monthAggregate.displayAmount`.
4. **Financial runway is no longer always zero.** It computes `totalRemaining`, `runwayDays`, and a status.
5. **Analytics input no longer defaults to EUR.** `NormalizedAnalyticsInput` explicitly says no hardcoded `"EUR"` default.
6. **Totals drilldowns use historical aggregation.** `TotalsAggregationEngine` documents purchase-only, per-expense `TRANSACTION_DATE` paths via `MultiCurrencyRepository`.

## Open / new findings

### P5-REOPEN-001 — Block Party uses raw `effectiveAmount` instead of normalized daily totals

Severity: **P1**

`ComputeDashboardWidgetsUseCase.computeBlockParty()` builds normalized `dailyHistory`, but then calls `SynthesisEngine.calculateBlockPartyData()` with `ctx.expenseEntities` marked as “display-only”.

Inside `SynthesisEngine`, `actualFromExpenses = expensesByDay[day]?.sumOf { it.effectiveAmount }`, and `actual = actualFromExpenses ?: actualFromHistory`, so raw expense sums override normalized daily history.

This violates the Money / Currency legal path forbidding raw cross-currency totals.

Fix:
- Make `calculateBlockPartyData()` use normalized `dailySpending` as the source of truth for money math.
- Use `expenses` only for display/top transaction metadata, or pass normalized amounts into the transaction summaries.
- Add a test: same day has `10 EUR` + `10 USD`; block-party actual must equal normalized home-currency total, not raw `20`.

### P5-REOPEN-002 — Shared-expense deposit exclusion is incomplete in dashboard normalization

Severity: **P1**

The normalizer tries to exclude shared-expense deposits: `DEPOSIT && !isNotMine && !isSharedExpense`.

But the dashboard mapper `DashboardExpense.toExpenseEntity()` does not copy `isSharedExpense`; it only sets fields like id, amount, currency, type, date, categoryId, `isNotMine`, and `isManualEntry`. Also, `DashboardRepository.kt` has no `isSharedExpense` match in the inspected file.

So a shared repayment can enter the dashboard as a normal deposit if the shared flag is lost before normalization.

Fix:
- Add `isSharedExpense` / ownership fields to `DashboardExpense` and repository projection.
- Propagate them in `toExpenseEntity()`.
- Test: shared repayment deposit with `isSharedExpense=true` must be excluded from `depositAggregate`.

### P5-OBS-003 — Runway status can show `NO_INCOME` even when budget-based runway is valid

Severity: **P2**

`totalRemaining` uses budget first, then income. But status is `NO_INCOME` whenever `monthlyIncome == 0.0`. A user with a budget but no deposit tracking can have positive runway days while status says no income.

Fix:
- Prefer runway-day status when `ctx.totalBudgetAmount > 0`.
- Reserve `NO_INCOME` for no budget and no income.

### P5-PARTIAL-004 — MoneyAggregateBuilder mismatch handling is defensive but still undercounts

Severity: **P2/P3**

`MoneyAggregateBuilder` handles missing transaction counts with `getOrElse(index) { 0 }` and notes “Missing counts defaulted to 0”. This avoids a crash, but metadata and warning transaction counts can be understated.

Fix:
- Treat count-list size mismatch as a data-quality warning.
- Prefer requiring equal sizes, or default non-empty bucket counts to at least 1 if exact count is unknown.

## Recommended validation commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Dashboard*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Synthesis*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Analytics*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*MoneyAggregate*" --stacktrace
```

I did not run Gradle in this browser/API review environment.