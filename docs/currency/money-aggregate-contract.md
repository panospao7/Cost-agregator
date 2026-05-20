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
