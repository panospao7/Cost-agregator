# CURR-587-05 + CURR-587-06 Finalization Plan

Baseline:
```text
28155d5e33b1bfd78662558c0029f95d588d19f6
fix(currency): CURR-587-07/09 finalization — guard v5, stale-rate policy
```

## What is still open

### CURR-587-05
Dashboard money is not fully canonical yet. Remaining raw/legacy sources still appear in:
- `ctx.data.summary.dailyHistory`
- `ctx.expenseEntities`
- `ctx.totalBudgetAmount`
- `ctx.overallBudget?.budgetAmount`
- `buildNaturalLanguageInsight(...)`
- `computeBlockParty(...)`
- `computeMonteCarlo(...)`

`PeriodSummary`, `TopCategories`, and `SpendingTrend` are already on the right path.

### CURR-587-06
Forecast/runway is still not truly normalized-safe because:
- unavailable normalized input still synthesizes from `emptyList()`
- `ForecastInputAssembler` still accepts raw `ExpenseSnapshot`
- `NormalizedForecastInput` is still not the actual forecast boundary
- planned/recurring items can still flow through raw/mixed-currency math

---

# CURR-587-05 — Finalize dashboard canonical money

## Goal
All dashboard money widgets must use normalized dashboard input or typed unavailable state.  
No raw summary/weather/latest-rate fallback should remain in money widgets.

## 1) Expand normalized dashboard input where needed
File: `DashboardNormalizedInput.kt`

Keep the existing normalized aggregates, and ensure dashboard can source:
- today/week/month/selected period
- previous month
- deposits/income
- category aggregates
- budget remaining / budget limit if available

If budget normalization is not yet available, make the dashboard widgets that depend on it explicitly unavailable rather than using zero or weather fallback.

## 2) Stop deriving money widgets from raw context fields
File: `ComputeDashboardWidgetsUseCase.kt`

Stop using these as canonical money sources:
- `summary.totalSpent`
- `summary.previousTotalSpent`
- `summary.dailyHistory`
- `weather.discretionaryBudget`
- `getHomeCurrencyPurchaseTotal(...)`
- `getHomeCurrencyDepositTotal(...)`
- `ctx.totalBudgetAmount`
- `ctx.expenseEntities`

Keep them only as legacy/non-money metadata if needed.

## 3) Finalize the remaining widget migrations

### SafeToSpend
Do not use weather/discretionary budget as money.
Use normalized budget remaining if present; otherwise return typed unavailable or nullable amount.

### FinancialRunway
Use only:
- normalized month spend
- normalized deposit/income aggregate
- normalized budget remaining/limit

If any required piece is missing, return unavailable/partial. Do not show a fake zero runway.

### NaturalLanguageInsight
Use normalized month/today/previous-month aggregates only.  
If previous month is unavailable, omit comparison instead of falling back to raw totals.

### BlockParty / MonteCarlo
Do not feed them raw `dailyHistory`, `expenseEntities`, or `totalBudgetAmount`.  
Either migrate them to normalized inputs or return unavailable.

## 4) Keep `ComputeContext` canonical-only
If raw fields remain in `ComputeContext`, rename them to legacy-only and stop using them for widget money math.

## 5) Guard update
In `verify_money_boundaries.py`, expand G-MONEY-15 to catch:
- `summary.totalSpent`
- `summary.previousTotalSpent`
- `summary.dailyHistory`
- `weather.discretionaryBudget`
- `getHomeCurrencyPurchaseTotal(`
- `getHomeCurrencyDepositTotal(`
- `ctx.expenseEntities`
- `ctx.totalBudgetAmount`
- `overallBudget?.budgetAmount`

Add a dashboard-specific rule for fallback regressions if needed.

## 6) Tests
Add tests proving:
- dashboard money widgets use normalized input
- SafeToSpend is unavailable when budget is not normalized
- FinancialRunway is unavailable when budget/income are not normalized
- NaturalLanguageInsight uses normalized values only
- no raw summary/weather/latest-rate fallback remains

---

# CURR-587-06 — Finalize normalized forecast/runway pipeline

## Goal
Forecast/runway must never synthesize from raw or empty fallback data when normalized input is unavailable.

## 1) Remove empty-list synthesis fallback
Current unavailable branch still synthesizes from `emptyList()`.  
Replace it with a typed unavailable result.

Do **not** call:
- `forecastInputAssembler.assemble(expenses = emptyList(), ...)`
- `synthesisEngine.synthesize(...)`

when normalized input is unavailable.

## 2) Make `NormalizedForecastInput` the canonical boundary
File: `ForecastInputAssembler.kt`

Add/finish a normalized entrypoint such as:
```kotlin
assembleNormalized(NormalizedForecastInput ...)
```

Use normalized actual expenses only:
- normalized amount
- normalized currency
- explicit rate basis
- explicit quality metadata

Keep the raw `assemble(expenses: List<ExpenseSnapshot>, ...)` method out of the dashboard forecast path.

## 3) Remove raw `ExpenseSnapshot` from dashboard forecast math
The dashboard forecast/runway path should not build or pass raw `ExpenseSnapshot` for money arithmetic.

If a source-boundary adapter still needs `ExpenseSnapshot`, it must normalize before synthesis.

## 4) Normalize planned and recurring future items explicitly
Use forecast-date basis for future items:
- `RateBasis.FORECAST_DATE`
- `convertOutcome(...)` or equivalent typed conversion
- failed conversion => exclude from numeric synthesis and mark partial/unavailable

Do not sum raw planned/recurring amounts.

## 5) Make synthesis typed-aware
File: `SynthesisEngine.kt`

`SynthesisEngine` should consume normalized inputs only, or a normalized wrapper that already guarantees currency-consistent values.

If future items cannot be normalized:
- do not synthesize a valid-looking forecast
- return unavailable/partial runway instead

## 6) Runway result should be explicit
If needed, add:
- `RunwayResult.Unavailable`
- or `FinancialRunway.isUnavailable` + `currencyQuality`

Do not substitute zero-valued money as if it were valid.

## 7) Guard update
Keep G-MONEY-10 non-allowlistable for raw `ExpenseSnapshot` math.

Strengthen G-MONEY-21 so it catches the actual current regression:
- `DashboardNormalizedInputResult.Unavailable`
- followed by any of:
  - `assemble(...)`
  - `synthesize(...)`
  - `emptyList()`
  - raw fallback construction

The rule should catch the whole branch, not only a literal `Unavailable -> emptyList()` pattern.

## 8) Tests
Add tests proving:
- unavailable normalized input does not synthesize from empty input
- normalized forecast input is the real boundary
- forecast input assembly uses normalized amounts only
- planned/recurring items use forecast-date normalization
- failed future conversion becomes partial/unavailable, not raw fallback
- raw snapshot fallback is gone

---

# Suggested implementation order
1. Finalize dashboard money widgets.
2. Replace forecast/runway empty fallback with typed unavailable.
3. Introduce/use normalized forecast input.
4. Normalize planned/recurring future items.
5. Tighten guard rules and add tests.

---

# Acceptance criteria

## CURR-587-05 is done when:
```text
1. Dashboard money widgets consume normalized input only.
2. SafeToSpend and FinancialRunway do not use raw weather/summary money.
3. NaturalLanguageInsight does not compare against raw fallback totals.
4. BlockParty and MonteCarlo do not consume raw legacy money paths.
5. No dashboard money widget uses summary/weather/latest-rate fallback when normalized input is unavailable.
```

## CURR-587-06 is done when:
```text
1. Unavailable normalized input never synthesizes from empty/raw fallback input.
2. NormalizedForecastInput is the real forecast boundary.
3. ForecastInputAssembler and SynthesisEngine consume normalized amounts only.
4. Future/planned items use forecast-date normalization or are excluded as partial.
5. Guard blocks empty-fallback and raw-snapshot regressions.
```

---

# Sources used
- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/28155d5e33b1bfd78662558c0029f95d588d19f6
- `ComputeDashboardWidgetsUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/28155d5e33b1bfd78662558c0029f95d588d19f6/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- `DashboardNormalizedInput.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/28155d5e33b1bfd78662558c0029f95d588d19f6/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardNormalizedInput.kt
- `ForecastInputAssembler.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/28155d5e33b1bfd78662558c0029f95d588d19f6/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt
- `SynthesisEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/28155d5e33b1bfd78662558c0029f95d588d19f6/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt
- `verify_money_boundaries.py`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/28155d5e33b1bfd78662558c0029f95d588d19f6/scripts/verify_money_boundaries.py