# RP-09 - Budget partial-data handling and FX-basis consistency (Pipeline 6)

> **Status:** CONDITIONAL. The correctness work below is executable after the stated contracts are
> verified. The risk-threshold redesign is deliberately a separate product decision, not a hidden
> side effect of a currency bug fix.
> **Mode:** strict (money, forecasting, recurring occurrence state, and UI data quality).
> **Depends on:** RP-08 for shared history-builder semantics. Do not change the same forecast file
> concurrently without reconciling the rate-basis contract.

---

## 9a - Stress-engine occurrence correctness

### P6-001 - Failed-rule fallback must be status-aware

The current fallback can add a projected occurrence for a rule that also has a materialized row.
The old plan's blanket `source/date` filter is unsafe: it can discard lifecycle state that the
projection does not contain.

Implement this precedence:

1. Reuse the persisted `RecurringOccurrence.occurrenceKey` identity
   (`sourceType|sourceId|dueDate|frequency`), including the existing timezone/day normalization;
   do not invent a source/date-only key that can collapse distinct frequencies.
2. Read reminder-delivery state through an explicit repository projection/join with
   `RecurringReminderDelivery` (claimed/sent/dismissed/snoozed), or an authoritative set of
   occurrence ids. If that read fails, mark the forecast partial and do not discard materialized
   rows. A claimed delivery makes the materialized occurrence authoritative.
3. For a matching materialized row with a terminal status (`PAID`, `SKIPPED`, `MISSED`,
   `CANCELLED`, or `IGNORED`), keep one merged occurrence identity and count **zero future
   obligations**. A linked expense also makes it terminal for counting. For `PLANNED` (the only
   currently open status; verify any additional status before coding), preserve linked/reminder
   metadata and count the occurrence once.
4. If no matching fallback key exists, keep the materialized row. If a fallback key has no
   materialized row, add the projection once. Never delete/recreate rows in this read-only path.

Do not use merchant/amount fuzzy matching and do not delete or recreate occurrence rows in this
read-only forecast path.

Tests must cover the same persisted occurrence key with `PLANNED`, `PAID`, `SKIPPED`, `MISSED`, a
linked expense, an already-claimed reminder from the delivery join, no fallback output, and a
projection failure. Assert one merged record; assert one counted obligation only for `PLANNED`, and
zero counted future obligations for terminal/linked rows.

### P6-008 - Catch up overdue detected patterns

Advance a detected pattern's working date with the same recurrence function until it reaches the
window start, then emit dates in `[startDate, endDate)`. Apply the 90-day stale check to the
original `nextExpectedDate`, not the advanced cursor. Add overdue-by-five-days and older-than-90-
days tests, including month-end recurrence boundaries.

### P6-009a - Remove fabricated empty-history spend

When `FinancialStressForecastEngine` has no purchase history, do not generate
`daysAhead * 20.0 +/- 5` home-currency amounts. Simulate real recurring obligations with zero
discretionary spend and mark the result using the existing `StressForecastResult.isPartial` and
`qualityWarnings` fields (controlled code such as `LOW_PURCHASE_HISTORY`). The UI must render this
quality marker alongside the existing `StressForecastMode`; do not invent a currency-specific
numeric fallback.

Test JPY/EUR fixtures to prove the empty-history path is currency-neutral and visibly degraded.

### P6-010 - Name the horizon result honestly

`earliestCrunchDate` currently returns the end of the at-risk horizon. Either rename the model field
to `atRiskHorizonEnd` and update all readers/string resources, or retain the old field name with an
explicit deprecation/documentation note. Do not claim a day-level crunch date without implementing
the first simulated zero-balance crossing. Remove the `/30.0 * 30.0` no-op and use the actual
per-day obligation value.

### NEW-P6-010 - Injectable thresholds without changing behavior

Move the existing stress probability constants into a Hilt-provided `StressRiskThresholds` value
object, preserving the current defaults and enum behavior. This is configuration hygiene only;
it is not the product risk-band redesign below.

### P6-P1-13 - Render degraded stress output

Locate the real `StressForecast` render consumer before editing. When `mode != ESTIMATED_INDEX`,
render a bounded string-resource qualifier; when `isPartial`/`qualityWarnings` is set, render the
existing data-quality chip. If no production consumer exists, record that as a wiring blocker rather
than adding a second, bypassing UI path.

---

## 9b - Budget partial-data semantics

### P6-005 - Reuse the existing `BudgetStatus` quality contract

`BudgetStatus` already exposes `isPartial`, `conversionWarning`, and `BudgetHealthStatus.UNKNOWN`,
and the budget screen already renders a partial warning. Do not add a parallel `PARTIAL_DATA` enum
without mapping it to those fields. Do not make `percentUsed` nullable in a local patch: it is a
non-null `Float` consumed by the monitor, UI, dashboard adapters, AI prompt builders, and forecast
assemblers.

Use one of these source-compatible contracts, chosen before coding:

- preferred: add `percentKnown: Boolean = true` to `BudgetStatus`, keep the non-null field as a
  compatibility value, and require every consumer to gate display/alerts on `percentKnown`; or
- perform a complete nullable-percent migration across every listed consumer with compile/test
  coverage in the same batch.

The preferred path sets `percentKnown == false`, `healthStatus == UNKNOWN`, and a controlled
`conversionWarning` when the budget limit cannot be converted. The value `0f` is never presented as
0% healthy and never enters threshold comparisons. If spend is partially converted but the limit is
known, keep `percentKnown == true`, preserve `isPartial == true`, and allow threshold checks while
adding a bounded "some transactions excluded" notification qualifier and a `partialData` diagnostic
field. No amounts or exception text go into diagnostics.

Tests must cover full conversion failure, partial spend with a usable limit, every existing
`percentUsed` consumer, UI warning text, and notification diagnostics.

### P6-006 - Distinguish usable fallback from unavailable limit in rollover

`initialLimitAggregate.isPartial` currently conflates a latest-rate fallback with a total conversion
failure. Use `conversionQuality`/an equivalent explicit outcome:

- historical conversion succeeded: apply rollover;
- historical rate missing but latest-rate fallback produced a usable home-currency limit: apply
  rollover, keep `isPartial` and the warning;
- no conversion possible (`ConversionQuality.UNAVAILABLE`): skip rollover, set `percentKnown` false
  and `healthStatus.UNKNOWN`; never subtract home-currency spend from a source-currency limit.

Keep rollover arithmetic and period ordering unchanged. Add tests for exact historical rate, usable
latest fallback, total failure, and a partial prior-period aggregate.

---

## 9c - Forecast FX basis

### P6-007 - Use one basis for current-period risk math

The forecast compares a budget limit converted at `PERIOD_END` with current-period spend normalized
at `TRANSACTION_DATE`. That makes the risk ratio move solely because rates changed after a purchase.

1. Add/use a repository-owned method on the already-injected `BudgetRepository` that returns the
   bounded current-period purchase aggregate at `RateBasis.PERIOD_END` and the period-end timestamp.
   The domain engine must not reach directly into a DAO for this operation.
2. Use that aggregate for `spentToDate`, risk level, overspend probability, and predicted remaining.
   Keep transaction-date rates for historical series/analytics only.
3. Persist `BudgetForecast.rateBasis = PERIOD_END` for the persisted risk calculation and update
   readers/docs that interpret the field. If a caller needs historical-series basis, expose that as
   a separate in-memory contract rather than overloading this field.
4. Missing-rate results remain partial/unavailable; they must reduce confidence or return the
   existing typed unavailable result, never fall back to a fabricated currency.

Golden tests must cover rate appreciation/depreciation mid-period, mixed currencies, missing rates,
single-currency parity, and the period/timezone boundary.

---

## 9d - Explicitly deferred product decisions

### CFC absolute risk bands

`CashFlowCalculator` currently maps `500/100/0` absolute home-currency balances to risk bands. A
ratio-to-income policy is a product/model change, not a narrow FX fix. Do not silently replace those
bands in this RP. Before implementation, a separate approved spec must define:

- normalized income source and period;
- zero, negative, missing, and multi-currency income;
- partial conversion behavior;
- historical comparability and UI copy;
- golden fixtures for EUR, JPY, missing income, and partial income.

Until that spec exists, preserve the current enum contract but mark low-data/unsupported quality
explicitly where the existing model permits; never claim the absolute values are currency-neutral.

### NEW-P6-015 - Unsupported recurring income

The current `ManualRecurringExpense`/`RecurringPattern` contract has no direction field. Do not
pretend the engine can selectively identify income rules. Choose one explicit product/schema path:

* treat all existing manual recurring rows as expenses and expose a global
  `UNSUPPORTED_INCOME_RECURRING` capability/status; or
* add a typed direction/transaction-type field, provider mapping, Room migration/schema snapshot,
  and then exclude only rows explicitly marked income.

In either case, do not infer income from a negative amount. Record the capability gap and add a
regression test for the selected contract; no selective “income rule” fixture is valid until a
source field exists.

---

## Validation and sequencing

```text
./gradlew :app:testDebugUnitTest --tests "*FinancialStressForecastEngine*" --tests "*CashFlowCalculator*" \
  --tests "*BudgetMonitor*" --tests "*BudgetRollover*" --tests "*BudgetRepository*" \
  --tests "*BudgetForecastingEngine*"
```

Tests were not run while this plan was rewritten. Land in order: RP-08, 9a, 9b, then 9c. Stop if
the repository reveals a different occurrence-status or conversion-quality contract; do not widen
the batch by inventing a new risk product.
