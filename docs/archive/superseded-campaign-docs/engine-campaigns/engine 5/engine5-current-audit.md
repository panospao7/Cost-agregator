# Engine 5 Current Audit — Money / Time Primitives

Target branch inspected: `fix/pipeline-1-5-local-issues`  
Mode: static GitHub inspection only.  
No Gradle, compile, KSP, Hilt, Room, lint, or tests were run.

## Self-review verdict

**YELLOW / RED-LEANING**

Engine 5 has improved a lot, especially around conversion provenance and aggregate failure handling, but it is **not clean**.

The strongest improvements:

- `MoneyAggregate.displayAmount` validates finite values.
- `MoneyBucket.amount` validates finite values.
- `CurrencyCode` now requires ASCII uppercase `A-Z`, not Unicode uppercase.
- `ConvertedMoney.identity()` is successful, not failed.
- `ConvertedMoney.failed()` preserves reason/message.
- `MoneyAggregate.failedTransactionCount` sums transaction counts.
- `MoneyAggregateBuilder` now distinguishes legacy latest-rate API from typed rate-basis API.
- `MoneyNormalizationEngine` exists and centralizes transaction-date conversion.
- `CurrencyConverter.convertOutcome()` returns typed success/failure with rate basis/provenance.
- `NormalizationProvenanceTest` and `MoneyAggregateBuilderRestrictionTest` exist.
- `TimeProvider` exists and is documented as the single source of “now.”
- `PeriodRange` core type is explicit, half-open, and zone-aware.

The biggest remaining problems:

1. `MoneyAmount` still stores `Double`.
2. `MoneyAmount.fromBigDecimal()` still converts to `Double`.
3. `CurrencyCode.parse()` accepts syntactically valid but unsupported codes like `ZZZ`.
4. `MoneyNormalizationEngine` can still throw for dirty invalid currency strings instead of returning an excluded/failure row.
5. `PeriodKind.toPeriodRange()` ignores `customStart/customEnd`.
6. `PeriodKind.CUSTOM` silently returns last-30-days instead of throwing.
7. `PeriodKind.LAST_7_DAYS` and `LAST_30_DAYS` are still off by one calendar day.
8. `TimePeriodUtils` still uses system-default `Calendar` helpers widely.
9. Week helpers are still internally inconsistent / misleading.
10. `domain.model.PeriodRange` is still used by `BudgetCalculator`.
11. Direct wall-clock calls remain in `RestoreJournal`.
12. `CurrencyFormatter.formatForExport()` silently converts NaN/Infinity to `0.00`.
13. Deprecated default-EUR formatter APIs still exist at `WARNING`, not `ERROR`.
14. Entity/domain money fields are still mostly raw `Double`.

Conclusion:

> Engine 5 has a better foundation now, but it is still transitional.  
> It is safe enough for current use if callers are careful, but not clean enough to be the final money/time primitive layer.

---

# 1. Engine scout

## Engine

Engine 5 — Money / Time Primitives.

Main components inspected:

- `MoneyAmount`
- `CurrencyCode`
- `ConvertedMoney`
- `MoneyAggregate`
- `MoneyBucket`
- `ConversionFailure`
- `MoneyAggregateBuilder`
- `MoneyMappers`
- `MoneyNormalizationEngine`
- `RateBasis`
- `ConversionQuality`
- `MoneyAggregateMetadata`
- `CurrencyConverter`
- `CurrencyFormatter`
- legacy `Money`
- `PeriodKind`
- core `PeriodRange`
- legacy `domain.model.PeriodRange`
- `TimePeriodUtils`
- `TimeProvider`
- `SystemTimeProvider`
- `BudgetCalculator`
- `RestoreJournal`
- `Expense`
- `Budget`

## Risk level

**Critical / foundational**

This engine affects almost every pipeline:

| Primitive | Affected areas |
|---|---|
| `MoneyAmount` | all financial domain/UI models |
| `MoneyAggregate` | analytics, dashboard, budget, export, tax, groups, investment |
| `CurrencyCode` | all currency validation and formatting |
| `CurrencyConverter` | dashboard, budget, forecast, tax, investment, export |
| `TimeProvider` | workers, analytics, recurring, restore, lifecycle events |
| `TimePeriodUtils` | budgets, analytics, recurring, reports |
| `PeriodKind/PeriodRange` | analytics, budget, export, reports |
| formatter APIs | UI, CSV/export, reports |

## Affected pipelines

| Pipeline / segment | Impact |
|---|---|
| Analytics | totals, daily/weekly/monthly, data quality |
| Dashboard | totals/widgets |
| Budget | period windows, budget-vs-actual, rollover |
| Forecast/cashflow | date windows, currency basis |
| Groups | shared expense totals, events |
| Investment | portfolio aggregates/history |
| Tax/business | filing currency, historical FX |
| Export/accounting | currency formatting, CSV stable output |
| Backup/restore | direct time calls, diagnostics |
| Notification/receipt/email | expense timestamps/currency validation |

## Schema/migration impact

Current audit does not require immediate schema changes.

Future schema impact possible for:

- `MoneyAmount` v2 if changed to minor units or BigDecimal
- entity timestamp value types
- category/budget/entity factory enforcement
- Room columns if raw `Double` money fields are replaced

Given recent DB rescue and v145 stabilization, **do not start Engine 5 with schema-heavy MoneyAmount migration**.

## Hilt/DI impact

Potential future DI impact:

- injecting `TimeProvider` into `RestoreJournal`
- adding static guard test sources
- replacing formatter APIs usually no Hilt
- `MoneyNormalizationEngine` already injectable

---

# 2. Sources inspected

Money:

- `MoneyAmount.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAmount.kt
- `CurrencyCode.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/CurrencyCode.kt
- `ConvertedMoney.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/ConvertedMoney.kt
- `MoneyAggregate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt
- `MoneyBucket.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyBucket.kt
- `ConversionFailure.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/ConversionFailure.kt
- `MoneyAggregateBuilder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt
- `MoneyMappers.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyMappers.kt
- `MoneyNormalizationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyNormalizationEngine.kt
- `RateBasis.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/money/RateBasis.kt
- `CurrencyConverter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt
- `CurrencyFormatter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyFormatter.kt
- legacy `Money.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt

Time:

- `PeriodKind.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/time/PeriodKind.kt
- core `PeriodRange.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/core/time/PeriodRange.kt
- legacy `domain.model.PeriodRange.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/model/PeriodRange.kt
- `TimePeriodUtils.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt
- `TimeProvider.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/util/TimeProvider.kt
- `SystemTimeProvider.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/util/SystemTimeProvider.kt

Consumers / evidence:

- `BudgetCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt
- `RestoreJournal.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
- `Expense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt
- `Budget.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt

Tests inspected/listed:

- money test directory  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/core/money
- time test directory  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/core/time
- `PeriodRangeTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/core/time/PeriodRangeTest.kt
- `MoneyAggregateBuilderRestrictionTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt
- `CurrencyNormalizationBehavioralTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/core/money/CurrencyNormalizationBehavioralTest.kt
- `NormalizationProvenanceTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/core/money/NormalizationProvenanceTest.kt

---

# 3. Positive findings

## 3.1 `CurrencyCode` ASCII validation improved

`CurrencyCode` now requires:

```kotlin
code.length == 3
code.all { it in 'A'..'Z' }
code.none { it.isDigit() }
```

This fixes the old Unicode-uppercase acceptance problem.

## 3.2 `MoneyAmount` rejects NaN/Infinity

`MoneyAmount` init rejects non-finite values.

That is better than raw `Double`.

## 3.3 `MoneyAggregate` and `MoneyBucket` reject non-finite values

`MoneyAggregate.displayAmount` and `MoneyBucket.amount` validate `isFinite()`.

This closes one old aggregate corruption path.

## 3.4 `ConvertedMoney` identity/failure semantics are fixed

`ConvertedMoney.identity()` returns success.

`ConvertedMoney.failed()` preserves:

- `failureReason`
- `failureMessage`

`ConversionStatus.SAME_CURRENCY.isSuccess` is true.

## 3.5 Aggregate failure counts improved

`ConversionFailure.transactionCount` exists.

`MoneyAggregate.failedTransactionCount` sums transaction counts, while `failedBucketCount` reports failure-entry count.

`MoneyAggregateBuilder` warnings now use transaction count.

## 3.6 `MoneyAggregateBuilder` rate-basis separation improved

Legacy `fromBuckets(List<Pair<Double,String>>...)` now rejects non-`LATEST_AVAILABLE` rate basis.

Typed `fromBuckets(List<MoneyBucketInput>...)` supports:

- `BucketDatePolicy.RequireBucketDate`
- `BucketDatePolicy.FixedDate`
- `BucketDatePolicy.Latest`

This is a big improvement for historical analytics/tax/budget correctness.

## 3.7 `MoneyNormalizationEngine` exists

This is now the central currency normalization path for expenses.

It uses:

- transaction-date conversion by default
- `CurrencyConverter.convertOutcome()`
- `MoneyAggregate`
- partial/failure metadata
- conversion quality

This is the right architecture.

## 3.8 `CurrencyConverter.convertOutcome()` is strong

It:

- returns typed `Converted` / `Failed`
- requires `atMillis` for historical bases
- validates currencies against `SupportedCurrency`
- distinguishes missing/stale/invalid failures
- carries `rateUsed`, `rateBasis`, valid date, last updated, source, path

## 3.9 Tests are better

Existing tests cover:

- latest vs historical rate behavior
- historical conversion requires date
- identity conversion
- missing/invalid currency failure
- aggregate partial state
- rate provenance on normalized rows
- aggregate builder restrictions

This is significantly improved versus the old report.

---

# 4. Old issue reconciliation

## M01 — `ConvertedMoney.identity()` treated as failed

Old tracker: FIXED  
Current status: **FIXED**

Evidence:

`ConvertedMoney.identity()` returns `isSuccess = true`, `isExactIdentity = true`.

Decision: **keep fixed**

---

## M02 — `MoneyAmount` uses raw `Double`

Old tracker: DEFERRED / PARTIAL  
Current status: **PARTIAL / still open**

Evidence:

`MoneyAmount` still stores:

```kotlin
val amount: Double
```

It rejects NaN/Infinity, but precision drift remains possible.

`fromBigDecimal()` converts to:

```kotlin
value.toDouble()
```

Impact:

- precision loss for large/high-precision amounts
- no currency minor-unit enforcement
- no scale policy
- `0.1 + 0.2` style drift can still happen

Decision: **partial / deferred design remains**

Do not fix first unless you are ready for broad migration.

---

## M03 — `CurrencyCode` validation too loose

Old tracker: FIXED  
Current status: **PARTIAL**

Fixed:

- uppercase ASCII enforced
- digits rejected

Still open:

- `CurrencyCode.parse("ZZZ")` returns `CurrencyCode("ZZZ")`
- unsupported-but-syntactically-valid currencies can exist in `MoneyAmount`
- `CurrencyCode.symbolFor()` returns raw code if unsupported
- active/inactive policy not enforced by `CurrencyCode`

But:

- `CurrencyConverter.convertOutcome()` does validate against `SupportedCurrency`.

Decision: **partial**

Recommended policy split:

```text
CurrencyCode = syntactic ISO-like code
AppCurrencyCode / SupportedCurrencyCode = supported active app currency
```

or make `parseSupported()` explicit.

---

## M04 — PeriodKind timezone math

Old tracker: TODO / PARTIAL  
Current status: **PARTIAL / bugs remain**

Fixed:

- `PeriodKind.toPeriodRangeZoned()` uses `java.time` and explicit `ZoneId`.

Still broken:

1. `PeriodKind.toPeriodRange()` ignores `customStart/customEnd`.
2. `PeriodKind.CUSTOM` in `toPeriodRangeZoned()` silently returns last-30-days.
3. KDoc says CUSTOM requires explicit bounds and throws, but code does not.
4. `TimePeriodUtils` still uses system-default `Calendar` in many helpers.
5. `TimePeriodUtils.toPeriodRange(pair, kind, label)` creates a typed range without caller-provided zone.

Decision: **partial / reopen key bugs**

---

## M05 — Two competing `PeriodRange` types

Old tracker: DEFERRED  
Current status: **OPEN**

Evidence:

- core `domain.core.time.PeriodRange` exists.
- legacy `domain.model.PeriodRange` still exists.
- `BudgetCalculator` imports legacy `domain.model.PeriodRange`.

Deprecation is warning-level, not error.

Decision: **open**

---

## M06 — `Money` BigDecimal vs `MoneyAmount` Double split

Old tracker: DEFERRED  
Current status: **OPEN / partial bridge**

Evidence:

- legacy `domain.util.Money` is BigDecimal-backed.
- approved `MoneyAmount` is Double-backed.
- `MoneyAmount.fromBigDecimal()` is lossy.

Decision: **open / deferred design**

---

## M07 — `MoneyAggregate.failedTransactionCount` misleading

Old tracker: FIXED  
Current status: **MOSTLY FIXED**

Evidence:

- `ConversionFailure.transactionCount` exists.
- `MoneyAggregate.failedTransactionCount` sums counts.
- `MoneyAggregateBuilder` maps transaction counts correctly where callers pass them.
- `MoneyMappers.toMoneyAggregate()` now accepts `transactionCounts: Map<String, Int>` and falls back to bucket count wording if counts are zero.

Remaining caveat:

- `FailedConversion.toConversionFailure()` still creates transactionCount default `0`.
- callers that do not supply counts still get weaker diagnostics.

Decision: **mostly fixed / bridge caveat remains**

---

## M08 — `ConvertedMoney.failed(reason)` ignores reason

Old tracker: FIXED  
Current status: **FIXED**

Decision: **keep fixed**

---

## M09 — Formatting underspecified

Old tracker: DEFERRED  
Current status: **OPEN**

Evidence:

- `MoneyAmount.formatDisplay()` uses `String.format("%.2f")` and always 2 decimals.
- It does not respect JPY zero decimals or 3-decimal currencies.
- `CurrencyFormatter.formatForExport()` silently converts non-finite amounts to `0.00`.
- deprecated formatter APIs still default to EUR at warning level.
- `CurrencyFormatter.getCurrencySymbol()` falls back to the EUR symbol on invalid currency.

Decision: **open**

---

## M10 — Direct wall-clock calls

Old tracker: TODO  
Current status: **OPEN**

Evidence:

`RestoreJournal` still calls:

- `System.currentTimeMillis()` in `JournalEntry.startedAt`
- fallback `startedAt` in `fromJson`
- event `occurredAt`
- success/failure journal import timestamps

`GroupTransactionCoordinator` no longer showed direct `System.currentTimeMillis()` in inspected file, which is improvement.

Decision: **open**

Recommendation:

Inject `TimeProvider` into `RestoreJournal`.

---

## M11 — Week-number helpers inconsistent

Old tracker: TODO  
Current status: **OPEN**

Evidence:

`TimePeriodUtils.getWeekOfYear()` uses:

```kotlin
Calendar.MONDAY
minimalDaysInFirstWeek = 1
```

`getWeekBasedYear()` uses ISO-like:

```kotlin
WeekFields.of(DayOfWeek.MONDAY, 4)
```

The docs say to use them together for week-scoped keys, but that pairs a non-ISO week number with an ISO week-based year.

Decision: **open**

Needed:

- `getIsoWeekNumber()`
- `getIsoWeekBasedYear()`
- `getIsoWeekKey()`
- separate app-calendar week helpers

---

## M12 — `LAST_7_DAYS` semantics

Old tracker: TODO  
Current status: **OPEN BUG**

Evidence:

`TimePeriodUtils.getLastNCalendarDaysRange(now, 7)` correctly uses:

```text
start = today - 6 days
end = tomorrow start
```

But `PeriodKind.LAST_7_DAYS` uses:

```text
start = today - 7 days
end = tomorrow start
```

That covers **8 calendar dates**, not 7.

Same issue exists for `LAST_30_DAYS`, which covers 31 calendar dates.

Decision: **reopen / bug**

---

## M13 — Entity time sentinel contracts

Old tracker: DEFERRED  
Current status: **OPEN**

Evidence:

`Expense.createdAt` still defaults to `0L`.

`Budget.createdAt` still defaults to `0L`.

No `CreatedAt`/`UpdatedAt` value types are enforced.

Decision: **open**

---

## M14 — Raw Double money output models dominate

Old tracker: DEFERRED  
Current status: **OPEN / partially mitigated by new primitives**

Evidence:

`Expense.amount`, `Budget.amount`, business/tax/investment/dashboard models still expose raw `Double`.

`Expense.moneyAmount` and `Budget.moneyAmount` helper properties exist, but the entity fields remain raw.

Decision: **open**

---

# 5. New/current issues found

## E5-NOW-001 — `PeriodKind.toPeriodRange()` ignores custom bounds

Severity: **P1_TIME_CORRECTNESS**

Evidence:

`toPeriodRange(now, zoneId, customStart, customEnd)` immediately delegates to:

```kotlin
toPeriodRangeZoned(now, zoneId)
```

It does not use `customStart` or `customEnd`.

Impact:

Any caller expecting a custom date range silently gets a default range.

Fix:

Handle `CUSTOM` before delegation:

```kotlin
if (this == PeriodKind.CUSTOM) {
    require(customStart != null && customEnd != null)
    return PeriodRange(this, customStart, customEnd, zoneId, "Custom")
}
```

Tests:

```text
customPeriod_usesExplicitBounds()
customPeriod_withoutBoundsThrows()
customPeriod_rejectsEndBeforeStart()
```

---

## E5-NOW-002 — `PeriodKind.CUSTOM` silently returns last-30-days

Severity: **P1_TIME_CORRECTNESS**

Evidence:

`toPeriodRangeZoned(CUSTOM)` creates:

```text
start = today.minusDays(30)
end = tomorrow
```

Impact:

Custom analytics/export/budget periods can become wrong with no error.

Fix:

`toPeriodRangeZoned()` should not support `CUSTOM` without bounds. Either throw or remove `CUSTOM` from that method.

Tests:

```text
customPeriodZonedWithoutBoundsThrows()
```

---

## E5-NOW-003 — `LAST_7_DAYS` / `LAST_30_DAYS` off-by-one

Severity: **P1_TIME_CORRECTNESS**

Evidence:

`LAST_7_DAYS` uses `today.minusDays(7)` to `tomorrow`, giving 8 full calendar days.

`LAST_30_DAYS` uses `today.minusDays(30)` to `tomorrow`, giving 31 full calendar days.

Fix:

Use:

```kotlin
start = today.minusDays(6)  // for 7 calendar days including today
start = today.minusDays(29) // for 30 calendar days including today
```

Or rename semantics explicitly.

Tests:

```text
last7Days_containsExactly7LocalDates()
last30Days_containsExactly30LocalDates()
periodKindLast7_matchesTimePeriodUtils()
periodKindLast30_matchesTimePeriodUtils()
```

---

## E5-NOW-004 — `MoneyNormalizationEngine` can throw on dirty invalid currency

Severity: **P1_DATA_QUALITY**

Evidence:

When conversion fails, it builds:

```kotlin
CurrencyCode(from)
```

If `from = "123"` or another non-ASCII dirty DB value, constructor throws.

Same general risk exists in:

- `MoneyAggregateBuilder` legacy bucket path
- entity helper properties like `Expense.moneyAmount`
- `Budget.moneyAmount`

Impact:

One dirty legacy currency can crash analytics/budget/export instead of becoming an excluded invalid-currency row.

Fix:

Use safe parse:

```kotlin
val fromCode = CurrencyCode.parse(from)
if (fromCode == null) return Excluded(INVALID_AMOUNT/INVALID_CURRENCY)
```

Add `FailureReason.INVALID_CURRENCY` or map to a clearer failure.

Tests:

```text
normalizer_invalidCurrency123_returnsExcludedNotThrow()
aggregateExpenses_invalidCurrency_returnsPartial()
expenseMoneyAmount_invalidCurrency_doesNotCrashOrIsNotUsedOnDirtyRows()
```

---

## E5-NOW-005 — `CurrencyCode.parse()` does not distinguish supported vs syntactic code

Severity: **P1/P2 depending policy**

Evidence:

`CurrencyCode.parse("ZZZ")` passes syntax.

`CurrencyConverter.convertOutcome()` later rejects unsupported currencies, but `MoneyAmount(1.0, CurrencyCode("ZZZ"))` can exist.

Impact:

Unsupported codes can reach domain models and fail later.

Fix:

Add explicit APIs:

```kotlin
parseIsoLike()
parseSupported()
parseActiveSupported()
```

Do not silently change existing `parse()` without call-site review.

Tests:

```text
parseSupported_rejectsZZZ()
parseActiveSupported_rejectsHRKForNewTransactions()
parseIsoLike_acceptsZZZIfNeededForDiagnostics()
```

---

## E5-NOW-006 — formatter “safe” explicit APIs still delegate to deprecated default-EUR APIs

Severity: **P2_FORMATTING**

Evidence:

`formatMoney(amount, currencyCode)` calls deprecated `format(amount, currencyCode, showCents)`. This is safe only because currency is passed explicitly, but the deprecated function still has default-EUR behavior.

`CurrencyFormatter.getCurrencySymbol(invalid)` falls back to default EUR symbol.

Impact:

Invalid currency can be formatted as EUR symbol, misleading reports/UI.

Fix:

Split implementation:

- private implementation requiring explicit valid currency
- deprecated wrappers call safe implementation
- invalid currency should show raw code or return error, not EUR symbol

Tests:

```text
formatMoney_invalidCurrency_doesNotShowEuro()
formatForExport_nanDoesNotBecomeZero()
```

---

## E5-NOW-007 — `formatForExport()` silently coerces corruption to zero

Severity: **P1_EXPORT_CORRECTNESS**

Evidence:

```kotlin
val safeAmount = if (amount.isFinite()) amount else 0.0
```

Impact:

CSV/accounting export can turn corrupt NaN/Infinity into plausible `0.00`.

Fix:

Either:

- throw validation error
- return explicit invalid marker
- use `Result<String>`

Do not silently coerce.

Tests:

```text
formatForExport_nanThrowsOrReturnsInvalid()
formatForExport_infinityThrowsOrReturnsInvalid()
exportRejectsNonFiniteMoney()
```

---

## E5-NOW-008 — `toCurrencyCodeOrLegacyEur()` silently falls back to EUR

Severity: **P2/P1 depending caller**

Evidence:

`MoneyMappers.toCurrencyCodeOrLegacyEur()` and entity helper methods use parse-or-EUR fallback.

Impact:

Dirty/missing currency can be mislabeled as EUR.

Fix:

Use structured resolution:

```text
Resolved
LegacyAssumedEUR
InvalidCurrency
MissingCurrency
```

Tests:

```text
invalidCurrencyResolution_isWarningNotSilentEur()
```

---

## E5-NOW-009 — `BudgetCalculator` still uses legacy `PeriodRange`

Severity: **P1_ARCHITECTURE**

Evidence:

`BudgetCalculator` imports:

```kotlin
com.yourname.expensetracker.domain.model.PeriodRange
```

Impact:

Budget periods do not carry `PeriodKind`, `ZoneId`, or labels.

Fix:

Migrate `BudgetCalculator` to `domain.core.time.PeriodRange`.

Tests:

```text
budgetCalculator_returnsCorePeriodRange()
noProductionImport_domain_model_PeriodRange()
```

---

## E5-NOW-010 — `RestoreJournal` violates TimeProvider contract

Severity: **P1_TESTABILITY / RESTORE_DIAGNOSTICS**

Evidence:

`RestoreJournal` uses `System.currentTimeMillis()` directly in defaults and events.

Impact:

Restore diagnostics are hard to test/replay deterministically.

Fix:

Inject `TimeProvider`.

Tests:

```text
restoreJournal_startedAt_usesTimeProvider()
restoreJournal_eventOccurredAt_usesTimeProvider()
```

---

# 6. Current issue list

## P1 issues

| ID | Title |
|---|---|
| E5-NOW-001 | `PeriodKind.toPeriodRange()` ignores custom bounds |
| E5-NOW-002 | `CUSTOM` silently becomes last-30-days |
| E5-NOW-003 | `LAST_7_DAYS` / `LAST_30_DAYS` off by one |
| E5-NOW-004 | money normalization can throw on dirty invalid currency |
| E5-NOW-007 | export formatter coerces NaN/Infinity to zero |
| E5-NOW-009 | `BudgetCalculator` still uses legacy `PeriodRange` |
| E5-NOW-010 | `RestoreJournal` uses direct wall-clock |
| M02/M06 | MoneyAmount/Money BigDecimal split remains |
| M10 | direct time guard missing |
| M11 | week helper inconsistency |
| M13/M14 | sentinel timestamps and raw Double money remain |

## P2 issues

| ID | Title |
|---|---|
| E5-NOW-005 | supported-vs-syntactic currency policy unclear |
| E5-NOW-006 | formatter fallback behavior still misleading |
| E5-NOW-008 | legacy EUR fallback still exists in mappers |
| M09 | display/export/accounting formatter split incomplete |
| M05 | old `PeriodRange` still live |
| M12 | naming/semantics need documentation after off-by-one fix |

---

# 7. Recommended fix order

## PR1 — PeriodKind correctness quick fix

### Closes

- E5-NOW-001
- E5-NOW-002
- E5-NOW-003
- M04/M12 partial

### Files

```text
PeriodKind.kt
PeriodRangeTest.kt
new PeriodKindContractTest.kt
```

### Implementation

1. Make `CUSTOM` require explicit `customStart/customEnd`.
2. Make `toPeriodRange()` use custom bounds.
3. Make `toPeriodRangeZoned(CUSTOM)` throw if no bounds.
4. Fix `LAST_7_DAYS` to exactly 7 calendar days.
5. Fix `LAST_30_DAYS` to exactly 30 calendar days.
6. Align tests with `TimePeriodUtils.getLastNCalendarDaysRange`.

### Tests

```text
customPeriod_usesExplicitBounds()
customPeriod_withoutBoundsThrows()
last7Days_exactly7CalendarDatesIncludingToday()
last30Days_exactly30CalendarDatesIncludingToday()
periodKindLast7_matchesTimePeriodUtils()
periodKindLast30_matchesTimePeriodUtils()
```

### Risk

Medium, no schema.

---

## PR2 — Invalid currency must become partial failure, not crash

### Closes

- E5-NOW-004
- parts of M03/M07/M14

### Files

```text
MoneyNormalizationEngine.kt
MoneyAggregateBuilder.kt
MoneyMappers.kt
CurrencyCode.kt maybe
tests
```

### Implementation

1. Add safe parser use before constructing `CurrencyCode`.
2. Add `FailureReason.INVALID_CURRENCY` if feasible.
3. Invalid dirty DB currency should produce:
   - excluded row
   - partial aggregate
   - warning
   - no crash
4. Avoid changing global supported-currency semantics too broadly.

### Tests

```text
normalizer_invalidCurrency123_excludedNotThrown()
aggregateExpenses_invalidCurrency_partial()
aggregateBuilder_invalidBucketCurrency_failureNotCrash()
moneyMappers_invalidOldFailureCurrency_safeFallbackOrInvalidFailure()
```

### Risk

Medium-high because many pipelines consume aggregates.

---

## PR3 — Formatter/export safety

### Closes

- E5-NOW-006
- E5-NOW-007
- M09 partial

### Files

```text
CurrencyFormatter.kt
MoneyAmount.kt maybe
export/report tests
```

### Implementation

1. Do not coerce NaN/Infinity to zero in `formatForExport`.
2. Split explicit implementation from deprecated default-EUR wrappers.
3. Invalid explicit currency should not fall back to EUR symbol.
4. Add `formatDisplay`, `formatExportStable`, `formatAccounting` direction if small.

### Tests

```text
formatForExport_nanRejected()
formatForExport_infinityRejected()
formatMoney_invalidCurrency_doesNotShowEuro()
moneyAmount_jpyDisplayUsesZeroDecimals_ifRoutedThroughFormatter()
```

### Risk

Medium. Affects exports/reports/UI formatting.

---

## PR4 — RestoreJournal TimeProvider

### Closes

- E5-NOW-010
- M10 partial

### Files

```text
RestoreJournal.kt
DI constructor call sites
restore journal tests
```

### Implementation

1. Inject `TimeProvider`.
2. Replace all `System.currentTimeMillis()` in `RestoreJournal`.
3. Keep `SystemTimeProvider` as the only allowed production direct call.

### Tests

```text
restoreJournal_beginUsesFakeTime()
restoreJournal_appendEventUsesFakeTime()
restoreJournal_importMarkersUseFakeTime()
```

### Risk

Medium. Hilt/constructor impact.

---

## PR5 — Week helper split

### Closes

- M11

### Files

```text
TimePeriodUtils.kt
tests
docs
```

### Implementation

Add explicit helpers:

```kotlin
getIsoWeekNumber()
getIsoWeekBasedYear()
getIsoWeekKey()
getAppCalendarWeekNumber()
getAppCalendarWeekYear()
getAppCalendarWeekKey()
```

Deprecate ambiguous `getWeekOfYear/getWeekBasedYear` pairing.

### Tests

```text
isoWeekKey_2021_01_01_is_2020_W53()
appCalendarWeekKey_usesCalendarYearAndWeekNumber()
weekHelpers_doNotMixIsoYearWithNonIsoWeek()
```

### Risk

Medium.

---

## PR6 — Migrate BudgetCalculator to core `PeriodRange`

### Closes

- E5-NOW-009
- M05 partial

### Files

```text
BudgetCalculator.kt
call sites
budget tests
```

### Implementation

Return `domain.core.time.PeriodRange` from period-window APIs or add new API first:

```kotlin
calculateCorePeriodWindowForTime(...)
```

Then migrate callers.

After call sites are clean:

```kotlin
@Deprecated(..., level = DeprecationLevel.ERROR)
domain.model.PeriodRange
```

### Tests

```text
budgetCalculator_returnsCorePeriodRange()
budgetPeriodRange_preservesHalfOpenBoundaries()
noProductionImport_domain_model_PeriodRange()
```

### Risk

Medium-high because budgets are central.

---

## PR7 — Currency policy APIs

### Closes

- E5-NOW-005
- E5-NOW-008
- M03 partial

### Files

```text
CurrencyCode.kt
SupportedCurrency usage
repositories/entities that parse currency
tests
```

### Implementation

Add explicit parse variants:

```kotlin
parseIsoLike()
parseSupported()
parseActiveSupported()
parseOrInvalidResult()
```

Do not silently change every call site. Migrate high-risk write paths first.

### Tests

```text
parseIsoLike_acceptsZZZ()
parseSupported_rejectsZZZ()
parseActiveSupported_rejectsHRK()
legacyEurFallbackProducesWarning()
```

### Risk

Medium-high.

---

## PR8 — MoneyAmount v2 design

### Closes

- M02
- M06
- M08? no, already fixed
- part of M14

### Do later.

Options:

1. `MoneyAmount(minorUnits: Long, currency: CurrencyCode)`
2. `MoneyAmount(amount: BigDecimal, currency: CurrencyCode)`

Given the app uses Room raw doubles heavily, this needs a separate design/migration plan.

Risk: high.

---

## PR9 — Static guardrails

### Closes

- M10
- M14
- formatting/raw API regressions

Add static tests/guards for:

```text
no direct System.currentTimeMillis outside SystemTimeProvider/test allowlist
no production use of default-EUR CurrencyFormatter overloads
no new public domain/UI bare Double money fields without allowlist
no production import of domain.model.PeriodRange
no raw CurrencyCode(...) construction from untrusted strings
```

Risk: medium because guards expose many call sites.

---

# 8. Pipeline regression matrix

## Analytics/dashboard

Must verify after money/time fixes:

- daily totals exact selected range
- weekly/monthly/yearly boundaries correct
- last 7/30 days charts not off by one
- partial conversion warnings still show
- invalid currency rows do not crash analytics

## Budget

Must verify:

- active budget period windows unchanged where expected
- rolling budget windows still work
- calendar budget windows still work
- no legacy `PeriodRange` mismatch
- budget-vs-actual still displays

## Export/accounting

Must verify:

- CSV export does not convert NaN/Infinity to zero
- export formatting uses stable decimal separator
- invalid money produces explicit error/warning
- no default-EUR relabeling

## Restore/backup

Must verify:

- restore journal timestamps deterministic in tests
- no direct wall-clock except `SystemTimeProvider`
- success/failure journal import still works

## Groups/investment/tax

Must verify:

- aggregate builders still preserve transaction counts
- historical rate basis remains explicit
- invalid currencies produce partial failures
- no raw mixed-currency sums reappear

## UI

Must verify:

- display formatter still handles normal EUR/USD/JPY
- invalid currency does not crash UI or lie as EUR
- MoneyAmount formatting change does not break visible amounts

---

# 9. Static checks performed

Checked statically:

- MoneyAmount finite validation and BigDecimal bridge
- CurrencyCode validation
- ConvertedMoney status/reason semantics
- MoneyAggregate and MoneyBucket finite validation
- failure transaction count handling
- MoneyAggregateBuilder legacy vs typed overloads
- MoneyNormalizationEngine invalid currency/failure paths
- CurrencyConverter typed conversion behavior
- CurrencyFormatter default-EUR/export behavior
- PeriodKind custom and last-N semantics
- TimePeriodUtils week/date helpers
- PeriodRange type split
- BudgetCalculator legacy PeriodRange use
- RestoreJournal direct wall-clock usage
- Expense/Budget raw money/timestamp sentinel fields
- current money/time tests

Not fully checked:

- repo-wide direct wall-clock grep because GitHub code search requires login
- all production `CurrencyFormatter` call sites
- all raw `Double` domain/UI fields
- all `domain.model.PeriodRange` imports
- compile/Hilt graph
- Room schema

---

# 10. Known compile risks for future fixes

Potential risks:

- fixing `PeriodKind.CUSTOM` may require updating callers that relied on fallback last-30-days
- changing `LAST_7_DAYS` will alter user-visible analytics ranges
- changing formatter export behavior may require callers to handle errors
- injecting `TimeProvider` into `RestoreJournal` may need Hilt constructor update
- changing `BudgetCalculator` return type can ripple into budget UI/tests
- adding `FailureReason.INVALID_CURRENCY` affects exhaustive `when` statements
- stricter currency parsing may expose dirty legacy DB rows

---

# 11. Human validation commands

Do not run during individual static slices if following your orchestrator workflow.

After all Engine 5 PRs are finalized:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema/migration changes are introduced:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changes are introduced:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# 12. Final conclusion

Engine 5 is **materially improved**, but still not clean.

Best current summary:

```text
Money aggregation: improved and mostly safe when using MoneyNormalizationEngine.
Currency validation: syntactically safer, but supported-currency policy still unclear.
Formatting/export: still unsafe around invalid/non-finite values.
Time periods: core model exists, but PeriodKind has real bugs.
TimeProvider: exists, but direct wall-clock remains in restore.
PeriodRange: old and new types still coexist.
Raw Double money: still dominant in entities and many public models.
```

Best first PR:

> **PR1 — PeriodKind correctness quick fix**

Why:

- no schema
- clear bugs
- strong tests
- fixes selected-range analytics/reporting correctness
- low-to-medium blast radius

Best second PR:

> **PR2 — Invalid currency becomes partial failure, not crash**

Why:

- protects rescued/legacy DB data
- prevents one bad row from crashing analytics/budget/export
- aligns with partial-data philosophy

Do **not** start with MoneyAmount v2. That is a later high-risk migration/design slice.

Verdict: **YELLOW / RED-LEANING — improved foundation, but core correctness bugs remain.**