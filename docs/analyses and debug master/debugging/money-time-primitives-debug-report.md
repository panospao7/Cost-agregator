# Money / Time Primitives Debug Report

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local execution.

## 1. Executive summary

The app is clearly trying to introduce safer primitives:

```text
MoneyAmount
CurrencyCode
MoneyAggregate
MoneyBucket
ConversionFailure
ConvertedMoney
PeriodKind
PeriodRange
TimeProvider
TimePeriodUtils
```

This is the right direction. These primitives should become the app’s “financial physics layer”: everything else — analytics, budget, forecast, dashboard, export, receipt matching, recurring — depends on them being correct.

But the current implementation is still transitional.

Highest-risk findings:

1. **`ConvertedMoney.identity()` is currently treated as failed by `ConvertedMoney.isFailed`.**
2. **`MoneyAmount` is marked as the approved money type but still stores raw `Double` and allows NaN/infinity.**
3. **`CurrencyCode.parse()` accepts unsupported/fake 3-character codes and digits despite claiming ISO-style validation.**
4. **`PeriodKind.toPeriodRange(zoneId)` records the supplied `zoneId` but computes boundaries using system default timezone.**
5. **There are two competing `PeriodRange` types: `domain.core.time.PeriodRange` and `domain.model.PeriodRange`.**
6. **The old precise `Money` BigDecimal type and the new approved `MoneyAmount` type are split, so precision guarantees are unclear.**
7. **`MoneyAggregate.failedTransactionCount` can undercount because failures are often per currency bucket, not per transaction.**
8. **Several runtime paths still call `System.currentTimeMillis()` directly instead of `TimeProvider`.**
9. **Week numbering helpers mix non-ISO week numbering with ISO week-based year logic.**
10. **There are no visible direct unit tests for `domain/core/money` or `domain/core/time` in the test inventory.**

Main recommendation:

> Before using these primitives everywhere, fix their contracts. A bad primitive spreads bugs into every pipeline.

---

# 2. Intended contract

## Money primitives should guarantee

```text
amount is finite
currency is valid
same-currency arithmetic only
cross-currency aggregation exposes partial/missing-rate state
conversion status is explicit
formatting is deterministic
rounding is consistent
```

## Time primitives should guarantee

```text
all periods are half-open [start, end)
timezone used for computation is explicit
period kind semantics are unambiguous
TimeProvider is the only source of now
calendar math is DST/leap/month-end safe
```

Current code partially meets this, but not fully.

---

# 3. Strong parts

## 3.1 Good conceptual direction

`MoneyAmount` and `MoneyAggregate` clearly document that they are intended to replace raw `Double` totals.

`MoneyAggregate` preserves:

```text
displayAmount
displayCurrency
sourceBuckets
conversionFailures
isPartial
warningMessage
```

That is exactly the right model for dashboard/analytics.

## 3.2 `TimePeriodUtils` has good boundary discipline

It documents:

```text
[startInclusive, endExclusive)
day end = next midnight
month end = first of next month
week start = Monday
calendar-aware arithmetic instead of raw millis
```

That is good.

## 3.3 `TimeProvider` exists and is bound through Hilt

This is important for deterministic tests.

## 3.4 Existing time tests are better than money-core tests

The test inventory includes:

```text
TimePeriodUtilsTest
TimePeriodUtilsStressTest
TimePeriodUtilsValidationTest
TemporalConsistencyTest
TimePeriodAlignmentTest
BudgetCalculatorBoundaryTest
BudgetCalculatorGoldenTest
```

So time boundary coverage exists.

But core money/time primitive-specific tests are still missing.

---

# 4. Major findings

## Finding P0-1 — `ConvertedMoney.identity()` is treated as failed

Current `ConvertedMoney`:

```kotlin
val isConverted: Boolean get() = conversionStatus == ConversionStatus.SUCCESS
val isFailed: Boolean get() = conversionStatus != ConversionStatus.SUCCESS
```

But `identity()` creates:

```kotlin
conversionStatus = ConversionStatus.SAME_CURRENCY
```

Therefore:

```text
ConvertedMoney.identity(...).isFailed == true
```

That is wrong.

Also `ConversionStatus.APPROXIMATE_RATE` and `LEGACY_NOT_CONVERTED` may represent usable-but-imperfect data, but `isFailed` marks them failed too.

### Impact

A same-currency “conversion” can be displayed/logged as failed.

This can poison:

```text
dashboard warnings
analytics data-quality reports
export conversion metadata
budget partial flags
```

### Fix

Use separate concepts:

```kotlin
val isExactSuccess: Boolean
val isUsable: Boolean
val isFailed: Boolean
val isApproximate: Boolean
```

Example:

```kotlin
val isExactSuccess: Boolean
    get() = conversionStatus == ConversionStatus.SUCCESS ||
            conversionStatus == ConversionStatus.SAME_CURRENCY

val isUsable: Boolean
    get() = convertedAmount != null &&
            conversionStatus != ConversionStatus.FAILED_MISSING_RATE

val isFailed: Boolean
    get() = !isUsable
```

Priority: highest.

---

## Finding P0-2 — `MoneyAmount` still uses raw `Double`

`MoneyAmount` is documented as:

```text
single approved domain type for all monetary values
```

But the value is:

```kotlin
val amount: Double
```

and there is no validation:

```text
NaN allowed
Infinity allowed
-0.0 allowed
arbitrary scale allowed
floating precision drift allowed
```

Example risk:

```kotlin
MoneyAmount(0.1, EUR) + MoneyAmount(0.2, EUR)
```

can produce:

```text
0.30000000000000004 EUR
```

The older `domain.util.Money` uses `BigDecimal` and avoids this, but the new approved type does not reuse that precision.

### Impact

This undercuts the entire “approved type” migration.

Financial primitives should be safer than the legacy raw model, not only currency-tagged.

### Fix options

Best:

```kotlin
data class MoneyAmount(
    val minorUnits: Long,
    val currency: CurrencyCode
)
```

with currency-aware scale:

```text
EUR/USD/GBP = 2 decimals
JPY = 0 decimals
custom/crypto = explicit scale
```

Alternative:

```kotlin
data class MoneyAmount(
    val amount: BigDecimal,
    val currency: CurrencyCode
)
```

Short-term minimum:

```kotlin
init {
    require(amount.isFinite())
}
```

and centralize rounding/formatting.

Priority: highest.

---

## Finding P0-3 — `CurrencyCode` validation is too loose

`CurrencyCode` says it is a wrapper for ISO 4217 codes.

But constructor allows:

```kotlin
code.length == 3
code.all { it.isUpperCase() || it.isDigit() }
```

So these are valid:

```text
ABC
ZZZ
US1
123
```

`CurrencyCode.parse()` also accepts any 3 uppercase alphanumeric value.

### Impact

Invalid currencies can enter:

```text
MoneyAmount
MoneyAggregate
Expense.moneyAmount
Budget.moneyAmount
conversion failures
dashboard labels
export files
```

Then `CurrencyCode.symbolFor()` just returns the raw code if unknown, hiding the issue.

### Fix

Choose one explicit policy:

## Strict app-supported currencies

```kotlin
require(code in CurrencyCode.ALL_SUPPORTED)
```

## ISO-like but not necessarily app-supported

```kotlin
require(code.length == 3 && code.all { it in 'A'..'Z' })
```

and add:

```kotlin
val isSupportedByApp: Boolean
```

Do not allow digits unless you intentionally support non-ISO assets.

Priority: highest.

---

## Finding P0-4 — `PeriodKind.toPeriodRange(zoneId)` lies about timezone

`PeriodKind.toPeriodRange()` accepts:

```kotlin
zoneId: ZoneId = ZoneId.systemDefault()
```

and stores it in `PeriodRange`.

But it delegates to `TimePeriodUtils`, which uses `Calendar.getInstance()` and system default timezone internally.

So this can happen:

```kotlin
PeriodKind.THIS_MONTH.toPeriodRange(
    now = someTimestamp,
    zoneId = ZoneId.of("UTC")
)
```

If device timezone is `Europe/Athens`, boundaries are computed in Athens time but the result says:

```text
zoneId = UTC
```

### Impact

This is a serious contract bug for:

```text
reports
exports
backup/replay
tests
travel users
timezone changes
analytics period labels
```

### Fix

Either:

1. remove the `zoneId` parameter and honestly use system default, or
2. implement all core time period math using `java.time` and the supplied `ZoneId`.

Recommended:

```kotlin
object PeriodRangeCalculator {
    fun rangeFor(kind: PeriodKind, now: Instant, zoneId: ZoneId): PeriodRange
}
```

Priority: highest.

---

## Finding P0-5 — Two `PeriodRange` types exist

There is:

```text
domain.core.time.PeriodRange
```

with:

```text
kind
startInclusiveMillis
endExclusiveMillis
zoneId
label
```

and also:

```text
domain.model.PeriodRange
```

with:

```text
start
end
```

`BudgetCalculator` still imports:

```kotlin
com.yourname.expensetracker.domain.model.PeriodRange
```

not the new approved core type.

### Impact

The app can pass around two incompatible period concepts.

Symptoms:

```text
period kind lost
timezone lost
label lost
custom vs calendar vs rolling semantics lost
half-open semantics must be rediscovered
```

### Fix

Deprecate old type:

```kotlin
@Deprecated("Use domain.core.time.PeriodRange")
```

Migrate:

```text
BudgetCalculator
ForecastInputAssembler
AnalyticsInputAssembler
CashFlowCalculator
dashboard period models
export/report date range models
```

to `domain.core.time.PeriodRange`.

Priority: highest.

---

## Finding P0-6 — Old precise `Money` and new approved `MoneyAmount` split the money model

The old:

```text
domain.util.Money
```

uses `BigDecimal`.

The new:

```text
domain.core.money.MoneyAmount
```

uses `Double`.

Tests exist for old `Money`, including ignored split-precision tests, but no visible direct tests exist for new `MoneyAmount`.

### Impact

Developers may use the “approved” type and accidentally lose the precision protection that the old `Money` type had.

### Fix

Unify:

```text
MoneyAmount = Money + CurrencyCode
```

or:

```kotlin
data class MoneyAmount(
    val value: Money,
    val currency: CurrencyCode
)
```

Then migrate old tests to new type.

Priority: highest.

---

## Finding P1-1 — `MoneyAggregate.failedTransactionCount` is misleading

`MoneyAggregate.failedTransactionCount` is:

```kotlin
conversionFailures.size
```

But `MultiCurrencyRepository` often creates failures from aggregate currency buckets.

Example:

```text
GBP bucket:
  amount = 200
  transactionCount = 8
  missing GBP→EUR rate
```

This becomes one failure.

The UI may say:

```text
excludes 1 transaction
```

when it actually excluded 8 transactions.

### Fix

`ConversionFailure` should include:

```kotlin
val transactionCount: Int
val bucketAmount: MoneyAmount
val source: FailureSource
```

Then expose:

```kotlin
failedBucketCount
failedTransactionCount
failedAmountBuckets
```

Priority: high.

---

## Finding P1-2 — `ConvertedMoney.failed(reason)` ignores `reason`

`ConvertedMoney.failed()` accepts:

```kotlin
reason: String
```

but always returns:

```kotlin
conversionStatus = FAILED_MISSING_RATE
```

The reason is discarded.

### Impact

You cannot distinguish:

```text
missing rate
stale rate
invalid amount
unsupported currency
provider failure
```

### Fix

Use:

```kotlin
ConversionFailure.FailureReason
```

or add:

```kotlin
failureReason: FailureReason?
failureMessage: String?
```

Priority: high.

---

## Finding P1-3 — Formatting is locale-sensitive and underspecified

`MoneyAmount.formatDisplay()` uses:

```kotlin
String.format("%.2f", amount)
```

This uses the default locale.

So in some locales:

```text
€12,34
```

and in others:

```text
€12.34
```

That can be fine for UI, but bad for:

```text
golden tests
exports
logs
stable snapshots
CSV/accounting reports
```

Also:

```text
symbol before amount
always 2 decimals
```

is not correct for every currency.

### Fix

Split formatting:

```text
MoneyFormatter.display(locale)
MoneyFormatter.exportStable(Locale.US)
MoneyFormatter.accounting(formatPolicy)
```

And use currency-specific minor units.

Priority: high.

---

## Finding P1-4 — Direct wall-clock calls still exist

Examples found:

```text
SnoozeReminderReceiver → System.currentTimeMillis()
PrivacyAuditLoggerImpl → System.currentTimeMillis()
SystemTimeProvider → expected allowed implementation
```

Only `SystemTimeProvider` should call the system clock.

### Impact

Tests become flaky and runtime behavior cannot be replayed.

Examples:

```text
privacy audit timestamps
snooze windows
background worker records
restore journal events
```

### Fix

Add CI guard:

```text
No production code may call System.currentTimeMillis()
Instant.now()
LocalDate.now()
LocalDateTime.now()
Date()
Calendar.getInstance() for "now"
```

Allowlist:

```text
SystemTimeProvider
TimePeriodUtils internal Calendar only when immediately seeded from explicit timestamp
platform adapters if unavoidable
```

Priority: high.

---

## Finding P1-5 — Week-number helpers are inconsistent

`TimePeriodUtils.getWeekOfYear()` uses:

```text
Monday first day
minimalDaysInFirstWeek = 1
```

That is not ISO week numbering.

`getWeekBasedYear()` uses:

```text
WeekFields.of(MONDAY, 4)
```

which is ISO-like.

The docs say to use `getWeekBasedYear()` together with `getWeekOfYear()`, but those two methods use different week rules.

### Impact

Around New Year, week keys can be wrong.

This affects:

```text
weekly analytics
weekly budget reports
sparkline grouping
export/report period keys
```

### Fix

Create explicit methods:

```kotlin
getIsoWeekNumber(timestamp)
getIsoWeekBasedYear(timestamp)
getIsoWeekKey(timestamp)

getAppCalendarWeekNumber(timestamp)
getAppCalendarWeekYear(timestamp)
getAppCalendarWeekKey(timestamp)
```

Do not mix ISO and app-calendar week rules.

Priority: high.

---

## Finding P1-6 — `LAST_7_DAYS` includes future remainder of today

`PeriodKind.LAST_7_DAYS` maps to:

```kotlin
getLastNCalendarDaysRange(now, 7)
```

That returns:

```text
start = midnight 6 days ago
end = tomorrow midnight
```

So at noon today, the range includes the rest of today in the future.

This is documented in `TimePeriodUtils`, but many analytics users expect “last 7 days” to end at `now`.

### Fix

Make UI semantics explicit:

```text
LAST_7_CALENDAR_DAYS_INCLUDING_TODAY
LAST_7_COMPLETE_DAYS
TRAILING_7_DAYS_TO_NOW
```

Or keep current code but rename labels clearly.

Priority: medium-high.

---

## Finding P1-7 — Entity time sentinel contracts are not type-safe

Entities like `Expense` and `Budget` document:

```text
createdAt must be set to timeProvider.now()
0L = unset sentinel
```

But the type is still plain `Long`.

### Impact

Callers can forget to set timestamps. Some earlier pipeline reports already found examples.

### Fix

Introduce typed creation helpers:

```kotlin
ExpenseFactory
BudgetFactory
CreatedAt
UpdatedAt
```

Or make repositories/coordinators always set timestamps and prevent direct entity construction in UI paths.

Priority: medium-high.

---

## Finding P1-8 — Raw `Double` monetary output models still dominate

Examples:

```text
Expense.amount
Budget.amount
PeriodTotal.totalAmount
AnalyticsRepository output totals
Dashboard widget totals
CashFlow rows
Forecast inputs
```

The new core money types exist, but the app still mostly operates on:

```text
Double + String currency
```

### Fix

Add a migration rule:

```text
No new public domain/UI money field may be bare Double.
```

Allowed only for:

```text
database entities
legacy adapters
serialization DTOs with explicit schema
```

Priority: medium-high.

---

# 5. Debugging checklist

## Money primitives

Check:

- [ ] amount finite,
- [ ] no NaN/infinity,
- [ ] currency valid,
- [ ] unsupported currency handled explicitly,
- [ ] same-currency arithmetic works,
- [ ] cross-currency arithmetic throws,
- [ ] BigDecimal/minor-unit precision preserved,
- [ ] currency-specific scale,
- [ ] JPY zero-decimal formatting,
- [ ] partial aggregate warning,
- [ ] failed transaction count correct,
- [ ] same-currency conversion is not failure,
- [ ] approximate conversion is distinguished from exact/failure.

## Time primitives

Check:

- [ ] all ranges half-open,
- [ ] timezone used for computation matches `PeriodRange.zoneId`,
- [ ] no direct wall-clock access outside `SystemTimeProvider`,
- [ ] TODAY boundaries DST-safe,
- [ ] WEEK starts Monday,
- [ ] week key logic consistent,
- [ ] MONTH boundaries month-end safe,
- [ ] YEAR boundaries leap-year safe,
- [ ] CUSTOM requires explicit bounds,
- [ ] LAST_N labels match actual semantics.

## Integration checks

Check:

- [ ] budget period windows use typed period range,
- [ ] analytics period windows use same typed range,
- [ ] dashboard and analytics totals use same range,
- [ ] recurring occurrence expansion uses typed date policy,
- [ ] export date ranges are explicit and half-open,
- [ ] notification/review timestamps use TimeProvider,
- [ ] workers use TimeProvider.

---

# 6. Recommended fix plan

## PR 1 — Fix `ConvertedMoney` status semantics

Acceptance:

```text
identity conversion is not failed
approximate conversion is usable but approximate
missing-rate conversion is failed
```

Priority: P0.

---

## PR 2 — Redesign `MoneyAmount` precision

Recommended:

```kotlin
data class MoneyAmount(
    val minorUnits: Long,
    val currency: CurrencyCode
)
```

or:

```kotlin
data class MoneyAmount(
    val value: Money,
    val currency: CurrencyCode
)
```

Acceptance:

```text
0.1 + 0.2 = 0.30
NaN/infinity rejected
currency scale respected
```

Priority: P0.

---

## PR 3 — Tighten `CurrencyCode`

Acceptance:

```text
"eur" parses to EUR
"EUR" parses to EUR
"EU" rejected
"EURO" rejected
"US1" rejected
"ZZZ" rejected or marked unsupported according to policy
```

Priority: P0.

---

## PR 4 — Make period range timezone honest

Either remove `zoneId` parameter or implement zone-aware `java.time` calculations.

Acceptance:

```text
same timestamp with UTC vs Europe/Athens produces correct different boundaries and records correct zone
```

Priority: P0.

---

## PR 5 — Migrate to one `PeriodRange`

Deprecate:

```text
domain.model.PeriodRange
```

Migrate to:

```text
domain.core.time.PeriodRange
```

Acceptance:

```text
BudgetCalculator and analytics period builders return the same typed range model.
```

Priority: P0/P1.

---

## PR 6 — Add CI guards

Add scripts:

```text
check-no-raw-clock-access.kts
check-no-new-bare-money-doubles.kts
check-period-range-type-usage.kts
```

Priority: P1.

---

## PR 7 — Add missing core tests

Create direct tests under:

```text
app/src/test/java/.../domain/core/money/
app/src/test/java/.../domain/core/time/
```

Priority: P1.

---

# 7. Tests to add

## `MoneyAmountPrecisionContractTest`

Cases:

```text
0.1 EUR + 0.2 EUR = 0.30 EUR
100 EUR / 3 split policy exact or explicit remainder
NaN rejected
Infinity rejected
-0.0 normalized
JPY no-decimal formatting
cross-currency addition throws
```

## `CurrencyCodeContractTest`

Cases:

```text
parse lowercase
parse whitespace
reject invalid length
reject digits
reject unsupported fake code
symbol fallback policy
```

## `ConvertedMoneyStatusTest`

Cases:

```text
identity is usable and not failed
success is usable
missing-rate failed is failed
approximate is usable but approximate
legacy-not-converted is not exact
```

## `MoneyAggregatePartialContractTest`

Seed:

```text
EUR bucket count 2
USD bucket count 1 converted
GBP bucket count 8 failed
```

Assert:

```text
display total excludes GBP
failedTransactionCount = 8
failedBucketCount = 1
warning says excluded 8 transactions or 1 bucket explicitly
source buckets preserve GBP
```

## `PeriodKindZoneContractTest`

Set system zone to Athens, request UTC.

Assert:

```text
PeriodRange.zoneId and computed boundaries agree
```

## `PeriodKindBoundaryContractTest`

Cases:

```text
TODAY DST spring-forward
TODAY DST fall-back
THIS_WEEK around Sunday/Monday
LAST_MONTH around March 31
THIS_MONTH February leap day
THIS_YEAR leap year
CUSTOM missing bounds throws
```

## `WeekKeyConsistencyTest`

Cases:

```text
2021-01-01
2020-12-31
2026-01-01
```

Assert:

```text
ISO week key uses ISO year and ISO week number consistently
app week key uses app year and app week number consistently
```

## `TimeProviderUsageGuardTest`

Static scan:

```text
System.currentTimeMillis()
Instant.now()
LocalDate.now()
LocalDateTime.now()
Date()
```

Allowed only in:

```text
SystemTimeProvider
tests
explicit platform adapters
```

## `PeriodRangeTypeMigrationGuardTest`

Fail if new production code imports:

```text
com.yourname.expensetracker.domain.model.PeriodRange
```

unless allowlisted.

---

# 8. Suggested canonical scenario

## `core_money_time_boundary_contract`

Seed:

```text
home currency EUR
system timezone Europe/Athens
fixed now = 2026-03-29 12:00 Europe/Athens
DST transition day
expenses:
  0.10 EUR
  0.20 EUR
  10 USD with historical rate
  20 GBP missing rate
period:
  TODAY
  THIS_MONTH
  LAST_7_DAYS
```

Expected:

```text
0.10 + 0.20 displays exactly 0.30 EUR
USD conversion uses expected rate
GBP missing rate creates partial aggregate
failed count reflects actual transactions
TODAY range is [midnight, next midnight) across DST
THIS_MONTH range is Mar 1-Apr 1
LAST_7_DAYS semantics are explicit
no direct wall-clock access needed
same-currency conversion is not marked failed
```

This scenario should sit below analytics/budget/forecast scenario tests as the primitive contract.

---

# 9. Most likely instability sources

Ranked:

1. **`ConvertedMoney.identity()` flagged as failed.**
2. **`MoneyAmount` uses raw `Double` despite being “approved.”**
3. **`CurrencyCode` accepts fake/unsupported codes.**
4. **`PeriodKind.toPeriodRange(zoneId)` computes in system zone but records caller zone.**
5. **Two `PeriodRange` types coexist.**
6. **Old BigDecimal `Money` and new `MoneyAmount` are not unified.**
7. **Partial aggregate failure counts are bucket-based but named transaction-based.**
8. **Direct clock access outside `TimeProvider`.**
9. **Week key helpers mix ISO and non-ISO rules.**
10. **Core money/time tests are missing.**

---

# 10. Final recommendation

Stabilize money/time primitives in this order:

```text
1. Fix ConvertedMoney status semantics.
2. Redesign MoneyAmount to use minor units or BigDecimal-backed Money.
3. Tighten CurrencyCode validation.
4. Make PeriodKind/PeriodRange timezone-correct.
5. Migrate to one PeriodRange type.
6. Add no-raw-clock and no-new-bare-money guards.
7. Add direct domain/core/money and domain/core/time tests.
8. Then migrate analytics/budget/forecast/dashboard outputs onto these primitives.
```

Guiding rule:

> A money primitive must make invalid money impossible or at least immediately visible.

Second guiding rule:

> A time primitive must make boundary semantics and timezone semantics explicit, not implied by device defaults.

---

# Sources

- `MoneyAmount.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAmount.kt

- `MoneyAggregate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `CurrencyCode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/CurrencyCode.kt

- `ConvertedMoney.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/ConvertedMoney.kt

- `MoneyBucket.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyBucket.kt

- `ConversionFailure.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/ConversionFailure.kt

- `MoneyMappers.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyMappers.kt

- Legacy precise `Money.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt

- `PeriodKind.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/time/PeriodKind.kt

- `PeriodRange.kt` core type:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/time/PeriodRange.kt

- Old `domain.model.PeriodRange.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/model/PeriodRange.kt

- `TimeProvider.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/util/TimeProvider.kt

- `SystemTimeProvider.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/util/SystemTimeProvider.kt

- `TimePeriodUtils.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt

- `TimeModule.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/di/TimeModule.kt

- `AmountUtils.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/util/AmountUtils.kt

- `DateFormatterUtils.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/util/DateFormatterUtils.kt

- `Expense.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `Budget.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt

- `BudgetCalculator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt

- Direct clock examples:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt

- Test inventory:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docsplans/_all_rel_paths.txt

- Existing legacy money/time tests:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/util/MoneyTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/consistency/TemporalConsistencyTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/consistency/FinancialArithmeticPrecisionTest.kt