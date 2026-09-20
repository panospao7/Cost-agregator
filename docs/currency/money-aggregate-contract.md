# MoneyAggregate Contract

## Overview

`MoneyAggregate` is the **single approved result type** for all financial aggregation. It replaces raw `Double` totals that silently mixed currencies.

## Required Fields

Every `MoneyAggregate` must have:

| Field | Purpose |
|-------|---------|
| `displayAmount` | Converted total in home currency |
| `displayCurrency` | The home currency |
| `rateBasis` | Which rate basis was used |
| `requestedRateBasis` | What the caller asked for |
| `actualRateBasis` | What was actually achieved |
| `conversionQuality` | Quality classification |
| `metadata` | Detailed counts |

## ConversionQuality

| Value | Meaning |
|-------|---------|
| `COMPLETE` | All rows converted with fresh rates |
| `PARTIAL` | Some rows excluded (missing/stale rates) |
| `UNAVAILABLE` | No conversion possible (all rows failed) |
| `ESTIMATED` | Rates are estimates (e.g. `PERIOD_MIDPOINT_ESTIMATE`) |
| `MIXED_BASIS` | Different rows used different actual bases |

## MoneyAggregateMetadata

| Field | Meaning |
|-------|---------|
| `includedTransactionCount` | Rows successfully converted |
| `excludedTransactionCount` | Rows that failed conversion |
| `missingRateCount` | Failures due to missing rate |
| `staleRateCount` | Failures due to stale rate |
| `invalidCurrencyCount` | Failures due to invalid currency |
| `latestRateValidDate` | Most recent rate validDate among included |
| `oldestRateValidDate` | Oldest rate validDate among included |

## Construction Rules

1. **Never construct without `rateBasis`** — always pass explicit basis.
2. **Use factory methods** — `MoneyAggregate.empty()`, `.singleCurrency()`, `.partial()`.
3. **Use `MoneyNormalizationEngine`** for expense aggregation — it sets all fields correctly.
4. **Legacy `MoneyAggregateBuilder.fromBuckets(Pair)`** is restricted to `LATEST_AVAILABLE` only.

## Identity Semantics

When all expenses are in the home currency:
- `actualRateBasis = IDENTITY`
- `rateUsed = 1.0`
- `rateValidDate = null`
- `rateLastUpdated = null`
- `conversionPath = IDENTITY`

## NormalizedExpense Provenance

Each normalized row carries:
- `rateBasis` — actual basis used (e.g. "TRANSACTION_DATE", "IDENTITY")
- `rateUsed` — the exchange rate applied
- `rateValidDate` — when the rate was valid
- `rateLastUpdated` — when the rate was last refreshed
- `conversionPath` — "DIRECT", "VIA_BASE_CURRENCY", or "IDENTITY"

## Unavailable Home Currency

When home currency resolution fails:
- **Do NOT** use `CurrencyCode.EUR` as a placeholder in money containers.
- Use `CurrencyCode("")` for MoneyAggregate unavailable states.
- Use `DashboardNormalizedInputResult.Unavailable` for dashboard paths.
- Use `currency = ""` with `riskLevel = UNKNOWN` for budget forecasts.
- UI must check for empty currency and display "unavailable" state, not EUR totals.

## RP-06c Money-Quality Conversion Contracts (2026-09-21)

### SynthesisEngine (forecast / block-party conversions)

- Conversions must go through `CurrencyConverter.convertOutcome()` and its typed `ConversionOutcome` result (`Converted` / `Failed(failureType)`). There are **no raw-currency fallback paths**: on failure the item is excluded from the sum and counted — a source-currency amount never enters a display-currency total (commits `686b3256`, `9d5ad720`). The only permitted short-circuits are same-currency identity conversion and the legacy blank-currency behavior for single-currency/legacy callers.
- Rate basis contract: future obligations (recurring patterns, planned expenses, confirmed occurrences) convert at `RateBasis.LATEST_AVAILABLE` with the 7-day `StaleRatePolicy.LatestDefault` policy, named explicitly in code; historical actuals arrive already normalized at `TRANSACTION_DATE` by RP-05 normalization and are NOT re-converted — the forward-looking KPI intentionally combines those two bases.
- Conversion failures feed `FinancialForecast.excludedCount` / `isPartial`; each `BlockPartyDay` carries a bounded `conversionFailureCount` (counts only — no amounts, currencies, or exception text). Engine-level failure logs use bounded counts only.

### FinancialHealthScoreV2 (typed unavailability over fabricated scores)

- Returns `HealthScoreOutcome` — `Available(result)` or `Unavailable(reason)` from a closed reason set (`HOME_CURRENCY_UNAVAILABLE`, `NORMALIZATION_FAILED`, `DATA_LOAD_FAILED`). An `Unavailable` outcome is never persisted to history and never becomes a fabricated score.
- Normalization failure or **any excluded row** makes the score `Unavailable(NORMALIZATION_FAILED)`; a failed home-currency read makes it `HOME_CURRENCY_UNAVAILABLE`; any other calculation failure makes it `DATA_LOAD_FAILED`. The raw `Expense` fallback is removed.
- Residual conversion loss on success is surfaced as `FinancialHealthResult.conversionConfidence` — tiered 1.0 / 0.95 / 0.80 / 0.50 by normalization loss percentage.

### SpendingPaceCalculator (canonical pace)

- The canonical pace calculator, wired into dashboard pace via `ComputeDashboardWidgetsUseCase` (commit `0f1f587b`), replacing the `pacePercentage = 100f, NO_BASELINE` placeholder that made the pace widget permanently unreachable.
- It performs no conversion of its own: input `ExpenseSnapshot`s must already be normalized by `AnalyticsCurrencyNormalizer` before reaching it; all sums operate on already-normalized values.
- Data quality propagates as `PaceStatus.NO_BASELINE` with a `-1f` pace-percentage sentinel (never a misleading `0f`, which would be indistinguishable from a true 0% pace); the dashboard emits no pace widget for `NO_BASELINE`.
