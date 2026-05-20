I reviewed commit `9a6afc438093ed6a03c7f831d3e4acd41a7f2a40` and wrote a fresh deep evaluation.

<deep_evaluation_currency_9a6afc4.md>
# Deep Evaluation / Debugging Report — Currency Normalization
Commit: `9a6afc438093ed6a03c7f831d3e4acd41a7f2a40`

## Executive verdict
This is a strong cleanup commit and it fixes several major currency bugs from the previous pass.  
But it still does **not fully close global currency issue #4**.

### Confirmed improvements
- `ExchangeRateStoreAdapter` now rejects `validDate = null/0` at runtime storage.
- `CurrencyConverter.storeRate/storeRates` now stamp `validDate`.
- `convertOutcome()` now honors historical bases better and fails on missing date context.
- `MoneyAggregate` now carries requested/actual basis and quality metadata.
- `MoneyNormalizationEngine` exists and is now the main normalization path in several places.
- `BudgetForecastingEngine` no longer uses the raw source amount on conversion failure.
- `CashFlowCalculator` now uses `FORECAST_DATE` for predicted recurring items.
- A money-boundary guard script is wired into CI.
- The commit adds substantial test coverage.

### Remaining problems
1. `getHomeCurrencyPurchaseTotalHistorical()` in `MultiCurrencyRepository` still uses midpoint conversion plus latest fallback.
2. `produceDashboardNormalizedInput()` still falls back to `CurrencyCode.EUR` on home-currency failure.
3. `computeSpendingTrend()` still silently drops failed conversions and does not surface trend-level currency quality.
4. `BudgetForecastingEngine` still returns a forecast object with `currency = "EUR"` when home currency is unavailable.
5. `MoneyNormalizationEngine` still uses `StaleRatePolicy.None`, so latest-rate paths do not surface staleness.
6. The static money guard is still heuristic and can miss multiline or wrapper-based regressions.
7. Some “behavioral” tests are still fake-store/unit style rather than real Room/repository integration.

---

## What is fixed well
### Exchange-rate correctness
The runtime storage boundary is now much safer:
- undated exchange rates are rejected,
- stored rates get a `validDate`,
- the latest-rate lookup semantics are improved.

This is a real fix.

### Historical conversion hardening
`convertOutcome()` now clearly fails when a historical basis is requested without `atMillis`.  
That closes the prior silent downgrade to latest-rate behavior.

### Budget/cashflow improvements
- budget hard failure no longer returns raw foreign amount,
- cashflow recurring forecast items use forecast-date basis,
- conversion failures are now counted instead of being silently hidden.

Those are all meaningful improvements.

---

## High-priority remaining issues

### CURR-9A6-01 — Historical purchase total still uses midpoint + latest fallback
Severity: **High**  
Type: **actual correctness bug**

`getHomeCurrencyPurchaseTotalHistorical()` still does:
- group by currency,
- convert at period midpoint,
- if that fails, fall back to latest rate.

That is still a mixed-basis historical aggregate.

Why it matters:
- the method’s doc says it is historical,
- but the implementation can still quietly become latest-rate based,
- this is exactly the class of bug the normalization effort is trying to eliminate.

Fix direction:
- use `MoneyNormalizationEngine.aggregateExpenses(..., RateBasis.TRANSACTION_DATE)` per expense,
- or make the method explicitly “estimated” and label the basis accordingly,
- but do not leave it pretending to be exact historical math.

---

### CURR-9A6-02 — Dashboard normalized input still uses EUR on home-currency failure
Severity: **Medium/High**

`produceDashboardNormalizedInput()` returns:
- `homeCurrency = EUR`
- empty normalized expenses
- empty aggregate
- `dataQuality = UNAVAILABLE`

This is safer than silent numeric EUR math, but it is still a fallback representation that can mislead any caller that looks only at the currency field.

Fix direction:
- keep the `UNAVAILABLE` status,
- but ensure no downstream consumer treats the EUR fallback as a usable money basis,
- ideally return a typed unavailable result instead of a fake EUR container.

---

### CURR-9A6-03 — Dashboard spending trend still hides partial failures
Severity: **High**

`computeSpendingTrend()`:
- converts with `convertAsOf(...)`,
- skips failed rows with `return@forEach`,
- returns `DashboardWidget.SpendingTrend(series = trendSeries)` with no visible quality signal.

That means:
- failed historical rows vanish silently,
- the user sees a complete trend even when data was dropped,
- no currency-quality metadata is attached to this widget.

Fix direction:
- add a `currencyQuality` / `isPartial` flag to the trend widget output,
- or return a typed trend result that includes missing/stale counts,
- do not silently drop failed rows without surfacing partial quality.

---

### CURR-9A6-04 — Budget forecast still returns EUR on home-currency unavailability
Severity: **Medium**

`BudgetForecastingEngine` now returns:
- `riskLevel = UNKNOWN`
- but also `currency = "EUR"` when home currency resolution fails.

This is better than pretending a precise forecast exists, but it still embeds a fallback currency in a failure state.

Fix direction:
- keep UNKNOWN,
- also carry an explicit unavailable/partial currency status,
- or return a typed unavailable forecast model instead of a fake EUR forecast.

---

### CURR-9A6-05 — `MoneyNormalizationEngine` still does not surface staleness for latest-rate paths
Severity: **Medium**

In `normalizeExpense(...)`, latest-path normalization still uses `StaleRatePolicy.None`.

That means:
- latest-rate conversions can be accepted without any staleness quality signal,
- the quality model can say “complete” even if the latest rate is stale.

Fix direction:
- decide whether latest-rate conversions should be checked for staleness,
- if yes, use a real stale policy instead of `None`,
- if no, document that latest-rate paths are intentionally freshness-blind.

---

### CURR-9A6-06 — Legacy historical repository APIs still exist and still look dangerous
Severity: **Medium/High**

`MultiCurrencyRepository` now has historical APIs, but the legacy APIs still remain and some of them are explicitly latest-rate based.

That’s not automatically wrong, but it means:
- old callers can still choose ambiguous or outdated behavior,
- some historical-looking methods still use bucket midpoint/latest fallback behavior.

Fix direction:
- keep the explicit historical/latest split,
- migrate all real consumers,
- deprecate the ambiguous methods harder or remove them once call sites are gone.

---

### CURR-9A6-07 — Static money guard is still heuristic
Severity: **High regression risk**

The new guard is useful, but it is still regex-based and can miss:
- multiline fallback logic,
- wrapper/helper indirections,
- some direct latest-rate usage outside allowlisted contexts.

Fix direction:
- keep the guard,
- but do not trust it as the only defense,
- pair it with real repository/dashboard/budget/cashflow integration tests.

---

### CURR-9A6-08 — Some “behavioral” tests are still fake-store/unit style
Severity: **Medium**

At least one visible test file uses:
- fake exchange-rate stores,
- fake time provider,
- direct in-memory logic.

That’s useful, but it is not the same as:
- Room migration tests,
- real DAO latest/as-of ordering tests,
- real repository integration tests.

Fix direction:
- keep the fake-store tests,
- add Room/integration tests for the key exchange-rate and repository flows.

---

## Bug vs architecture classification

### Actual bugs affecting users
- historical purchase totals still use midpoint/latest fallback,
- dashboard trend can hide failed conversion rows,
- budget forecast still uses EUR as a failure placeholder,
- dashboard normalized input still returns EUR on unavailable home currency.

### Architectural debt
- latest-rate APIs still coexist with historical APIs,
- `MoneyNormalizationEngine` does not yet fully encode staleness policy for latest paths,
- static guard is still heuristic.

### Good cleanup
- runtime invalid exchange-rate storage is now much safer,
- `convertOutcome()` failure handling is much better,
- cashflow recurring forecast conversion is improved.

---

## Priority next fixes
1. Rework `getHomeCurrencyPurchaseTotalHistorical()` so it no longer midpoint-converts with latest fallback.
2. Attach currency-quality metadata to `computeSpendingTrend()`.
3. Remove or strongly constrain EUR placeholders in unavailable home-currency paths.
4. Decide and enforce staleness behavior for latest-rate normalization.
5. Add real integration tests for exchange-rate and repository behavior.
6. Keep the money-boundary guard, but treat it as a secondary line of defense.

## Bottom line
This commit closes several major correctness gaps, but **historical mixed-basis aggregation and dashboard quality propagation are still not fully resolved**.

## Sources reviewed
- Commit: https://github.com/panospao7/Cost-agregator/commit/9a6afc438093ed6a03c7f831d3e4acd41a7f2a40
- `CurrencyConverter.kt`
- `ExchangeRateStoreAdapter.kt`
- `MoneyNormalizationEngine.kt`
- `MultiCurrencyRepository.kt`
- `BudgetForecastingEngine.kt`
- `CashFlowCalculator.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- `verify_money_boundaries.py`
</deep_evaluation_currency_9a6afc.md>

If you want, I can now turn this into a **PR checklist for the remaining currency issues**.