# Rate Basis Policy

## Overview

Every currency conversion in ExpenseTracker must declare which **rate basis** it uses. This prevents silent mixing of historical and latest-rate conversions within the same aggregate.

## RateBasis Enum

| Value | Meaning | Requires `atMillis` | Lookup Method |
|-------|---------|---------------------|---------------|
| `TRANSACTION_DATE` | Rate valid on the expense's date | Yes | `getRateAsOf(atMillis)` |
| `PERIOD_START` | Rate valid at period start | Yes | `getRateAsOf(atMillis)` |
| `PERIOD_END` | Rate valid at period end | Yes | `getRateAsOf(atMillis)` |
| `PERIOD_MIDPOINT_ESTIMATE` | Estimated rate at period midpoint | Yes | `getRateAsOf(atMillis)` |
| `FORECAST_DATE` | Rate for a future predicted date | Yes | `getRateAsOf(atMillis)` |
| `LATEST_AVAILABLE` | Most recent rate regardless of date | No | `getLatestRateForPair()` |
| `IDENTITY` | Same-currency (rate = 1.0) | No | N/A |

## Rules

1. **Historical bases require `atMillis`** — calling `convertOutcome()` with a historical basis and `atMillis = null` returns `Failed(MISSING_HISTORICAL_RATE)`.

2. **No silent latest fallback** — if a historical rate is unavailable, the conversion fails. It does NOT fall back to `getLatestRateForPair()`.

3. **`PERIOD_MIDPOINT_ESTIMATE` is historical** — it uses `getRateAsOf()`, not latest lookup. The result is labeled `ESTIMATED` quality.

4. **`LATEST_AVAILABLE` must be explicit** — any method using latest-rate conversion must have "Latest" or "LatestRate" in its name, or be documented as `**LATEST-RATE**`.

## When to Use Which

| Use Case | Rate Basis |
|----------|-----------|
| Dashboard historical totals | `TRANSACTION_DATE` |
| Budget limit conversion | `PERIOD_END` |
| Cashflow actual expenses | `TRANSACTION_DATE` |
| Cashflow predicted recurring | `FORECAST_DATE` |
| Current portfolio valuation | `LATEST_AVAILABLE` |
| Row-level display conversion | `LATEST_AVAILABLE` |
| Period report estimates | `PERIOD_MIDPOINT_ESTIMATE` |

## Staleness Policy

`StaleRatePolicy` controls when a rate is considered too old:

- `compareAgainst = NOW` — age = |now - rate.validDate|
- `compareAgainst = TRANSACTION_DATE` — age = |atMillis - rate.validDate|
- `compareAgainst = RATE_VALID_DATE` — age = |rate.lastUpdated - rate.validDate|

If age cannot be computed (missing reference), the rate is treated as **stale**, not fresh.

### Named Policies

| Policy | maxAge | compareAgainst | Usage |
|--------|--------|----------------|-------|
| `Default` | 24h | NOW | General-purpose |
| `LatestDefault` | 7 days | NOW | `MoneyNormalizationEngine` for `LATEST_AVAILABLE` |
| `None` | null | — | Historical rates (never stale by definition) |

### MoneyNormalizationEngine Behavior

- `LATEST_AVAILABLE` → uses `LatestDefault` (7 days). Rates older than 7 days are excluded and marked stale.
- All historical bases → uses `None`. Historical rates are never stale — they're old by definition.

## Composite (EUR-Bridge) Conversions

When converting via EUR intermediate:
- `validDate` = min of both legs (oldest/weakest)
- `lastUpdated` = min of both legs (least fresh)
- `source` = both sources joined with "+"
- `path` = `VIA_BASE_CURRENCY`

This ensures staleness checks evaluate against the weakest leg.
