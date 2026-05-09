# Money / Time Primitives Implementation Plan

Scope:

```text
M02 M04 M05 M06 M09 M10 M11 M12 M13 M14
```

Additional issues found:

```text
M15 MoneyAggregate.partial() warning still uses failure bucket count as “transactions”
M16 CurrencyCode accepts any 3 uppercase letters, not necessarily supported/active
M17 hardcoded EUR defaults remain in primitive helpers
M18 period APIs still allow system-zone drift through legacy wrappers
M19 raw money/time guards need seeded execution tests and allowlist cleanup
```

Reviewed around latest known stable point:

```text
ddfd8747ccc0420447fcc98ed68d3df056ec022b
```

---

# 0. Current status correction

| ID | Realistic status |
|---|---|
| M02 | **PARTIAL** — `MoneyAmount` rejects NaN/Infinity, but still stores `Double`. |
| M04 | **OPEN** — `PeriodKind.toPeriodRange(zoneId)` records caller zone but delegates to `TimePeriodUtils`, which uses system-default `Calendar`. |
| M05 | **PARTIAL** — `domain.model.PeriodRange` is deprecated, but still exists. Need usage audit/migration. |
| M06 | **OPEN/PARTIAL** — `domain.util.Money(BigDecimal)` and `domain.core.money.MoneyAmount(Double)` still coexist. |
| M09 | **OPEN** — `MoneyAmount.formatDisplay()` and `Money.format()` use locale-sensitive `String.format`. |
| M10 | **PARTIAL** — guard script exists, but must be verified, wired, seeded-tested, and allowlisted safely. |
| M11 | **OPEN** — week helpers are still mixed/ambiguous. |
| M12 | **OPEN** — `LAST_7_DAYS` means full calendar days including today, therefore includes future remainder until tomorrow midnight. |
| M13 | **OPEN/PARTIAL** — `EntityTimeValidation` may exist, but entity timestamps are still raw `Long`. |
| M14 | **PARTIAL** — raw money guard exists, but raw `Double` public money fields still exist and migration rule needs enforcement. |

---

# 1. PR-M0 — Tracker and comment reconciliation

## Goal

Make tracker/comments truthful before code changes.

## Update tracker statuses

```text
M02 → PARTIAL
M04 → OPEN
M05 → PARTIAL
M06 → PARTIAL/OPEN
M09 → OPEN
M10 → PARTIAL
M11 → OPEN
M12 → OPEN
M13 → PARTIAL/OPEN
M14 → PARTIAL
```

## Fix comments

### `MoneyAmount.kt`

Current comment says minor-unit migration is planned. Keep it, but change status:

```kotlin
// M02 PARTIAL: NaN/Infinity are rejected, but MoneyAmount still stores Double.
// Final stabilization requires BigDecimal or minorUnits Long storage.
```

### `PeriodKind.kt`

Current comment says computation uses system zone but records caller zone. Make it actionable:

```kotlin
// M04 OPEN: toPeriodRange(zoneId) must compute boundaries in the provided zoneId.
// Current implementation delegates to TimePeriodUtils legacy system-zone helpers.
```

### `TimePeriodUtils.kt`

Mark legacy helpers:

```kotlin
// Legacy system-zone helpers. New code should use ZonedTimePeriodCalculator.
```

Acceptance:

```text
No TODO says “fixed” unless code and tests prove it.
```

---

# 2. PR-M1 — M04: zone-aware time engine

## Problem

`PeriodKind.toPeriodRange(now, zoneId)` accepts a zone but still calls helpers that use system-default `Calendar`.

This can create wrong ranges when:

```text
user/app zone != device zone
tests run in different CI timezone
DST boundary differs by zone
```

## Implement

Create:

```kotlin
class ZonedTimePeriodCalculator(
    private val zoneId: ZoneId,
    private val weekFields: WeekFields = WeekFields.ISO
)
```

Core APIs:

```kotlin
fun dayRange(nowMs: Long): PeriodRange
fun weekRange(nowMs: Long, offsetWeeks: Long = 0): PeriodRange
fun monthRange(nowMs: Long, offsetMonths: Long = 0): PeriodRange
fun quarterRange(nowMs: Long, offsetQuarters: Long = 0): PeriodRange
fun yearRange(nowMs: Long, offsetYears: Long = 0): PeriodRange
fun lastNCalendarDaysIncludingToday(nowMs: Long, days: Int): PeriodRange
fun lastNCompleteCalendarDays(nowMs: Long, days: Int): PeriodRange
fun trailingElapsed(nowMs: Long, duration: Duration): PeriodRange
```

Use only:

```text
Instant.ofEpochMilli(nowMs).atZone(zoneId)
LocalDate.atStartOfDay(zoneId).toInstant()
ZonedDateTime.plusDays/months/years
```

No `Calendar`.

Update:

```kotlin
fun PeriodKind.toPeriodRange(
    now: Long,
    zoneId: ZoneId,
    ...
): PeriodRange
```

to use `ZonedTimePeriodCalculator(zoneId)`.

## Compatibility

Keep `TimePeriodUtils` but mark legacy/deprecated for new call sites.

## Tests

```text
PeriodKindUsesProvidedZoneTest
DstSpringForwardDayRangeTest
DstFallBackDayRangeTest
MonthRangeZoneAwareTest
QuarterRangeZoneAwareTest
CiTimezoneDoesNotChangePeriodTest
```

Acceptance:

```text
PeriodKind.toPeriodRange(zoneId=X) computes boundaries in X, not system default.
```

---

# 3. PR-M2 — M11: split ISO week vs app-calendar week

## Problem

Week-number helpers are ambiguous:

```text
ISO week number
SQLite week key
app-calendar week display
```

can disagree.

## Implement

Add explicit functions:

```kotlin
fun getIsoWeekNumber(timestampMs: Long, zoneId: ZoneId): Int
fun getIsoWeekYear(timestampMs: Long, zoneId: ZoneId): Int

fun getAppCalendarWeekNumber(
    timestampMs: Long,
    zoneId: ZoneId,
    firstDayOfWeek: DayOfWeek,
    minimalDaysInFirstWeek: Int
): Int
```

Add week-key type:

```kotlin
data class WeekKey(
    val weekYear: Int,
    val weekNumber: Int,
    val system: WeekSystem
)

enum class WeekSystem {
    ISO,
    APP_CALENDAR,
    SQLITE_MONDAY,
    SQLITE_SUNDAY
}
```

Replace ambiguous helpers with explicit names.

## Tests

```text
IsoWeekYearBoundaryTest
AppCalendarWeekSundayStartTest
WeekKeyRoundTripTest
SqliteWeekKeyNormalizedToMondayRangeTest
```

Acceptance:

```text
No production code calls a generic `getWeekNumber()` without specifying week system.
```

---

# 4. PR-M3 — M12: period naming and semantics

## Problem

`LAST_7_DAYS` currently means:

```text
midnight six days ago → tomorrow midnight
```

So at 10:00 today it includes future remainder of today.

This is okay for “last 7 calendar days including today,” but misleading for “trailing 7 days to now.”

## Implement

Add new enum values:

```kotlin
TRAILING_7_DAYS_TO_NOW
TRAILING_30_DAYS_TO_NOW
LAST_7_CALENDAR_DAYS_INCLUDING_TODAY
LAST_30_CALENDAR_DAYS_INCLUDING_TODAY
LAST_7_COMPLETE_DAYS
LAST_30_COMPLETE_DAYS
```

Deprecate:

```kotlin
LAST_7_DAYS
LAST_30_DAYS
```

Map old values for compatibility:

```text
LAST_7_DAYS → LAST_7_CALENDAR_DAYS_INCLUDING_TODAY
LAST_30_DAYS → LAST_30_CALENDAR_DAYS_INCLUDING_TODAY
```

But UI labels must be explicit.

## Tests

```text
Trailing7DaysEndsAtNowTest
Last7CalendarDaysEndsTomorrowStartTest
Last7CompleteDaysEndsTodayStartTest
DeprecatedLast7DaysCompatibilityTest
```

Acceptance:

```text
No label “Last 7 days” is used without exact semantics.
```

---

# 5. PR-M4 — M05: one canonical PeriodRange

## Problem

Two types exist:

```text
domain.core.time.PeriodRange
domain.model.PeriodRange
```

The old one is deprecated but still present.

## Implement

### Step 1 — usage audit

Find all imports of:

```kotlin
com.yourname.expensetracker.domain.model.PeriodRange
```

Migrate to:

```kotlin
com.yourname.expensetracker.domain.core.time.PeriodRange
```

### Step 2 — bridge only if necessary

If old APIs still require the old type:

```kotlin
fun domain.model.PeriodRange.toCore(
    kind: PeriodKind = PeriodKind.CUSTOM,
    zoneId: ZoneId
): core.time.PeriodRange
```

### Step 3 — guard

Add guard:

```text
No new imports of domain.model.PeriodRange
```

## Tests

```text
NoDomainModelPeriodRangeImportGuardTest
PeriodRangeHalfOpenContractTest
PeriodRangeZeroLengthCustomAllowedOrRejectedTest
```

Acceptance:

```text
Only core.time.PeriodRange is used in production code.
```

---

# 6. PR-M5 — M02/M06: unify MoneyAmount and Money

## Problem

There are competing money types:

```text
MoneyAmount(Double + CurrencyCode)
Money(BigDecimal without CurrencyCode)
```

`MoneyAmount` is currency-safe but not precision-safe.
`Money` is precision-safe but not currency-safe.

## Recommended final type

Use one canonical type:

```kotlin
data class MoneyAmount(
    val amount: BigDecimal,
    val currency: CurrencyCode
)
```

Add factories:

```kotlin
companion object {
    fun ofMajor(value: BigDecimal, currency: CurrencyCode): MoneyAmount
    fun ofMajor(value: String, currency: CurrencyCode): MoneyAmount
    fun ofMajor(value: Double, currency: CurrencyCode): MoneyAmount // deprecated
    fun ofMinorUnits(minorUnits: Long, currency: CurrencyCode, exponent: Int): MoneyAmount
}
```

Add helpers:

```kotlin
fun toMinorUnits(exponent: Int = currency.defaultExponent): Long
fun toDoubleForDisplayOnly(): Double
```

## Migration strategy

### Stage A — non-breaking

Keep existing constructor temporarily:

```kotlin
@Deprecated("Use MoneyAmount.ofMajor(...)")
constructor(amount: Double, currency: CurrencyCode) : this(BigDecimal.valueOf(amount), currency)
```

If Kotlin data class cannot keep both cleanly, create:

```kotlin
data class DecimalMoneyAmount(...)
typealias MoneyAmountV2 = DecimalMoneyAmount
```

then migrate gradually.

### Stage B — update computation code

Replace:

```text
amount + other.amount as Double
amount * factor as Double
```

with BigDecimal operations and explicit rounding.

### Stage C — entity/database migration

Only when ready:

```text
amountMinorUnits: Long
currency: String
currencyExponent: Int optional
```

This is a large schema migration. Do not mix it with analytics/UI fixes.

## Tests

```text
MoneyAmountRejectsNaNInfinityTest
MoneyAmountBigDecimalNoFloatingErrorTest
MoneyAmountMinorUnitsEurTest
MoneyAmountMinorUnitsJpyTest
MoneyAmountMinorUnitsBtcTest
MoneyAmountDifferentCurrencyAdditionFailsTest
DeprecatedDoubleFactoryStillFiniteTest
```

Acceptance:

```text
Domain money arithmetic no longer depends on binary floating-point.
```

---

# 7. PR-M6 — M09: split formatters

## Problem

`MoneyAmount.formatDisplay()` and old `Money.format()` use `String.format("%.2f")`, which depends on default locale and lacks export/accounting contracts.

## Implement

Create:

```kotlin
interface MoneyFormatter {
    fun display(amount: MoneyAmount, locale: Locale): String
    fun exportStable(amount: MoneyAmount): String
    fun accounting(amount: MoneyAmount, locale: Locale): String
}
```

Implementation:

```kotlin
class DefaultMoneyFormatter : MoneyFormatter
```

Rules:

```text
display:
  uses NumberFormat.getCurrencyInstance(locale)
  uses java.util.Currency if ISO code supported

exportStable:
  Locale.US
  no grouping
  dot decimal
  explicit currency code column preferred

accounting:
  negative values shown as parentheses
  e.g. (€12.34)
```

Deprecate:

```kotlin
MoneyAmount.formatDisplay()
Money.format()
```

or make them delegate to a safe default.

## Tests

```text
MoneyDisplayUsLocaleTest
MoneyDisplayGreekLocaleTest
MoneyExportStableAlwaysDotDecimalTest
MoneyAccountingNegativeParenthesesTest
MoneyFormatterJpyNoDecimalsTest
```

Acceptance:

```text
UI display, export, and accounting formatting cannot accidentally share locale behavior.
```

---

# 8. PR-M7 — M10: direct wall-clock guard finalization

## Current issue

A guard script exists, but it needs verification. In the raw file, the final `println` appears as a multiline string that may break Kotlin script execution. Also the app Gradle search did not show obvious task wiring in `app/build.gradle.kts`.

## Implement

### Fix script syntax

Use:

```kotlin
println(
    "\nFound $violations direct wall-clock time call(s). " +
    "Use TimeProvider.now() instead."
)
```

### Add allowlist file

```text
scripts/guards/direct_time_allowlist.txt
```

Entries:

```text
SystemTimeProvider.kt
FakeTimeProvider.kt
DateFormatterUtils.kt
ZonedTimePeriodCalculator.kt if needed
migration files
```

Avoid broad file allowlists like all `TimePeriodUtils` once M04 is migrated.

### Wire to Gradle

In app or root Gradle:

```kotlin
tasks.register<Exec>("checkDirectTimeCalls") {
    commandLine("kotlin", "scripts/guards/check_direct_time_calls.kts")
}
tasks.named("check") {
    dependsOn("checkDirectTimeCalls")
}
```

### Seeded tests

Create test that writes a temp Kotlin file with:

```kotlin
System.currentTimeMillis()
Instant.now()
Calendar.getInstance()
```

runs the script, expects non-zero.

## Tests

```text
DirectTimeGuardSeededViolationTest
DirectTimeGuardAllowlistedProviderTest
DirectTimeGuardNoFalsePositiveOnTimeProviderNowTest
```

Acceptance:

```text
CI fails on new direct wall-clock calls outside allowlist.
```

---

# 9. PR-M8 — M13: type-safe entity timestamps

## Problem

Entity timestamps use raw `Long`, often with sentinel values:

```text
0L
-1L
null depending field
```

This is not type-safe.

## Implement

Domain wrappers:

```kotlin
@JvmInline
value class CreatedAt private constructor(val millis: Long)
@JvmInline
value class UpdatedAt private constructor(val millis: Long)
@JvmInline
value class OccurredAt private constructor(val millis: Long)
```

Factories:

```kotlin
CreatedAt.from(millis): Result<CreatedAt>
CreatedAt.now(timeProvider): CreatedAt
UpdatedAt.from(millis): Result<UpdatedAt>
```

Rules:

```text
CreatedAt > 0
UpdatedAt > 0
UpdatedAt >= CreatedAt where both exist
OccurredAt may be > 0
```

Migration path:

```text
Room entities can keep raw Long initially.
Entity/domain mappers validate and convert to typed wrappers.
```

Optional later:

```text
Room TypeConverters for value classes.
```

## Tests

```text
CreatedAtRejectsZeroTest
UpdatedAtRejectsNegativeTest
UpdatedAtBeforeCreatedAtFailsTest
EntityMapperValidatesTimestampsTest
```

Acceptance:

```text
New domain models do not expose raw createdAt/updatedAt Long without validation.
```

---

# 10. PR-M9 — M14: no new bare Double money public APIs

## Problem

Raw `Double` money fields still dominate. Full migration is large, but new code should not add more.

## Implement

Improve raw-money guard.

Flag public/domain model fields matching:

```text
amount: Double
total: Double
price: Double
cost: Double
value: Double
fee: Double
budget: Double
limit: Double
```

Allow only with explicit annotation/comment:

```kotlin
@RawMoneyAllowed("native entity persistence field; converted at boundary")
```

or line comment:

```kotlin
// RAW_MONEY_ALLOWED: Room persisted field
```

Guard should fail if no allowlist reason exists.

## Migration rule

Public result models must use:

```text
MoneyAmount
MoneyAggregate
NativeAmountDisplay(amount, currency)
```

Allowed raw Double locations:

```text
Room entity persisted amount fields during transition
DAO SUM raw bucket rows
CurrencyConverter internals
tests
chart normalized non-money ratios
```

## Tests

```text
RawMoneyGuardSeededPublicTotalFailsTest
RawMoneyGuardAllowsEntityWithReasonTest
RawMoneyGuardAllowsMoneyAggregateBuilderTest
```

Acceptance:

```text
No new public API exposes money as bare Double without currency/data-quality.
```

---

# 11. PR-M10 — M15: fix MoneyAggregate.partial warning

## Problem found

`MoneyAggregateBuilder` correctly says:

```text
failed transaction count across currency buckets
```

But `MoneyAggregate.partial()` still builds warning from:

```kotlin
failures.size
```

and calls it transactions.

## Fix

Use:

```kotlin
val failedTx = failures.sumOf { it.transactionCount }
val bucketCount = failures.size
"Total excludes $failedTx transaction(s) across $bucketCount currency bucket(s)"
```

Or route all partial creation through `MoneyAggregateBuilder`.

## Tests

```text
MoneyAggregatePartialUsesTransactionCountTest
MoneyAggregatePartialWarningBucketCountTest
```

Acceptance:

```text
No MoneyAggregate path reports bucket count as transaction count.
```

---

# 12. PR-M11 — M16: CurrencyCode policy

## Problem found

`CurrencyCode.parse()` accepts any 3 uppercase letters. That may be okay for ISO extensibility, but the app also has `SupportedCurrency` and active/inactive state.

## Implement

Separate validation levels:

```kotlin
enum class CurrencyValidationPolicy {
    ISO_LIKE,
    SUPPORTED_ONLY,
    ACTIVE_SUPPORTED_ONLY
}
```

APIs:

```kotlin
CurrencyCode.parse(input, policy = ISO_LIKE)
CurrencyCode.parseSupported(input)
CurrencyCode.parseActive(input)
```

Use:

```text
ACTIVE_SUPPORTED_ONLY for new user-entered expenses
SUPPORTED_ONLY for historical/imported rows
ISO_LIKE for external imports before mapping
```

## Tests

```text
CurrencyCodeParseIsoLikeTest
CurrencyCodeParseSupportedRejectsUnknownTest
CurrencyCodeParseActiveRejectsLegacyHrkTest
```

Acceptance:

```text
New app-created money cannot silently use unsupported/inactive currency.
```

---

# 13. PR-M12 — M17: remove hardcoded EUR primitive defaults

## Problem found

Some primitive helpers still expose defaults like:

```kotlin
MoneyAmount.ZERO_EUR
```

This is convenient but can leak into home-currency contexts.

## Implement

Keep for tests/examples only or deprecate:

```kotlin
@Deprecated("Use MoneyAmount.zero(homeCurrency)")
val ZERO_EUR
```

Search for:

```text
ZERO_EUR
CurrencyCode.EUR
"EUR"
```

in domain/engine code.

Replace with:

```text
homeCurrency from settings
input.displayCurrency
explicit test fixture currency
```

## Tests

```text
NoHardcodedEurInEngineDefaultsGuardTest
MoneyAggregateEmptyUsesHomeCurrencyTest
```

Acceptance:

```text
Domain engine empty/default money uses caller/home currency, not EUR.
```

---

# 14. Recommended execution order

```text
1. PR-M0 tracker/comment reconciliation
2. PR-M10 MoneyAggregate.partial warning fix
3. PR-M7 direct-time guard syntax/wiring/seeded tests
4. PR-M1 zone-aware time calculator
5. PR-M2 week-number split
6. PR-M3 explicit LAST_7/TRAILING period semantics
7. PR-M4 migrate to single PeriodRange
8. PR-M6 split money formatters
9. PR-M9 raw-money public API guard
10. PR-M5 MoneyAmount/Money unification, staged
11. PR-M8 typed timestamps
12. PR-M11 currency validation policy
13. PR-M12 hardcoded EUR cleanup
```

If you want minimum stabilization before moving on:

```text
Must do now:
- M04
- M10
- M11
- M12
- M15
- M09
- M14 guard strengthening

Can defer as design/migration:
- M02 full minor-unit DB migration
- M06 full BigDecimal unification
- M13 typed timestamp Room migration
```

---

# 15. Golden scenario tests

## Money scenario

Seed:

```text
EUR amount
JPY amount
BTC amount
USD amount with cents
NaN/Infinity attempted
```

Assert:

```text
NaN/Infinity rejected
JPY has 0 decimals in display
export uses dot decimal
mixed totals require MoneyAggregate
missing conversion marks partial
```

## Time scenario

Run with app zone:

```text
Europe/Athens
America/New_York
UTC
```

Use dates around DST:

```text
spring forward
fall back
year boundary
week boundary
```

Assert:

```text
PeriodKind.toPeriodRange uses provided zone
LAST_7_CALENDAR ends tomorrow start
TRAILING_7_DAYS ends exactly now
ISO week and app week differ intentionally
```

## Guard scenario

Seed temporary files with:

```text
System.currentTimeMillis()
sumOf { it.amount }
public val total: Double
```

Assert:

```text
guards fail
allowlisted infrastructure passes
```

---

# 16. Definition of done

Money/time primitives are stable when:

```text
1. PeriodKind ranges are computed in the caller/app zone.
2. Calendar/system-zone legacy helpers are deprecated or removed from production paths.
3. Week-number APIs distinguish ISO/app/SQLite semantics.
4. LAST_7_DAYS ambiguity is removed or compatibility-mapped.
5. Only core.time.PeriodRange is used in production code.
6. MoneyAmount rejects invalid numbers and has a clear BigDecimal/minor-unit migration path.
7. Money vs MoneyAmount split is resolved or strongly deprecated.
8. Display/export/accounting formatters are separate.
9. Direct wall-clock guard runs in CI and has seeded tests.
10. Public money models cannot add new bare Double fields without explicit allowlist.
11. MoneyAggregate warnings count failed transactions correctly.
12. Entity timestamps are validated at domain boundaries.
```

---

# Sources checked

- `MoneyAmount.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAmount.kt

- `Money.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt

- `MoneyAggregate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyAggregateBuilder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

- `CurrencyCode.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/core/money/CurrencyCode.kt

- `PeriodRange.kt` core  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/core/time/PeriodRange.kt

- `PeriodRange.kt` old model  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/model/PeriodRange.kt

- `PeriodKind.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/core/time/PeriodKind.kt

- `TimePeriodUtils.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt

- `TimeProvider.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/util/TimeProvider.kt

- `check_direct_time_calls.kts`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/scripts/guards/check_direct_time_calls.kts

- `check_raw_money_aggregates.kts`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/scripts/guards/check_raw_money_aggregates.kts