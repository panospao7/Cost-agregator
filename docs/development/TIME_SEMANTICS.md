# Time Semantics — Developer Rules

> **Phase 2 — Time / Period Semantics Foundation**
> This document is the canonical reference for all time-related code in the ExpenseTracker app.

---

## Half-Open Contract

**Every** period range in this app uses the `[startInclusive, endExclusive)` convention:

```
timestamp >= startInclusive && timestamp < endExclusive
```

Examples:
- **Day end** = start of the **next** day (midnight), NOT `23:59:59.999`.
- **Week end** = next Monday `00:00:00.000`.
- **Month end** = 1st of next month `00:00:00.000`.
- **Quarter end** = 1st of next quarter `00:00:00.000`.
- **Year end** = Jan 1st of next year `00:00:00.000`.

**Never use inclusive endpoints** (`23:59:59`, `23:59:59.999999999`). These double-count the boundary moment and break comparisons.

---

## Where "Now" Comes From

### Allowed clock sources

| Use case | Source |
|----------|--------|
| Logical app time | `TimeProvider.now()` |
| Production implementation | `SystemTimeProvider.now()` only |
| Performance timing | `System.nanoTime()` or `TimeSource.Monotonic` |
| Android elapsed alarms | `SystemClock.elapsedRealtime()` |
| WorkManager intervals | `Duration` / `TimeUnit` |

### Forbidden patterns (outside explicit whitelist)

```kotlin
// ❌ NEVER use these directly:
System.currentTimeMillis()
Instant.now()
LocalDate.now()
LocalDateTime.now()
ZonedDateTime.now()
Calendar.getInstance().get(Calendar.YEAR)  // for "current year"
Date()                                       // no-arg constructor
```

### Whitelisted files

These files are **allowed** to call system clock APIs:

| File | Reason |
|------|--------|
| `SystemTimeProvider.kt` | Single production clock implementation |
| `AppDatabase.kt` | Old migration code — do NOT touch |
| `NotificationCaptureService.kt` | `SystemClock.elapsedRealtime()` for Android scheduling |
| `SettlementCalculator.kt` | `System.nanoTime()` for DFS timeout |
| `FinancialHealthScoreV2.kt` | `System.currentTimeMillis()` for perf timing |
| `FinancialStressForecastEngine.kt` | `System.currentTimeMillis()` for perf timing |
| `AppConstants.kt` | Duration constants (not calendar periods) |

### New Phase 3 consumers follow the same contract

The `TransactionLifecycleCoordinator` and `TransactionSideEffectDispatcher` both inject
`TimeProvider` and call `timeProvider.now()` at the point of use — never
`System.currentTimeMillis()`. This ensures that the time-semantics rules
(half-open periods, no silent `Instant.now()`) extend to the transaction
lifecycle pipeline.

---

## How to Get "Now" in Your Code

### In a class (ViewModel, UseCase, Repository, Engine)

```kotlin
class MyViewModel @Inject constructor(
    private val timeProvider: TimeProvider
) {
    fun doSomething() {
        val now = timeProvider.now()  // ✅ Single capture
        val monthRange = TimePeriodUtils.getMonthRange(now)
        // ...
    }
}
```

### In a Composable

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel = hiltViewModel()) {
    val now by viewModel.nowFlow.collectAsStateWithLifecycle()
    // Or pass referenceNowMs from ViewModel state
}
```

### In a test

```kotlin
val timeProvider = FakeTimeProvider().apply {
    setTime(LocalDate.of(2026, 4, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
}
```

---

## Calendar vs. Rolling Periods

**These are NOT the same thing.** Confusing them was the #1 bug found in the audit.

| UI Label | Semantics | Helper |
|----------|-----------|--------|
| Today | Calendar day | `getDayRange(now)` |
| This Week | Monday-start calendar week | `getWeekRange(now)` |
| This Month | Calendar month | `getMonthRange(now)` |
| This Quarter | Calendar quarter | `getQuarterRange(now)` |
| This Year | Calendar year | `getYearRange(now)` |
| Last 7 Days | Rolling calendar days | `getLastNCalendarDaysRange(now, 7)` |
| Last 30 Days | Rolling calendar days | `getLastNCalendarDaysRange(now, 30)` |

```kotlin
// ❌ BUG: "This Month" should be a calendar month, not 30 rolling days
val range = getLastNDaysRange(now, 30)

// ✅ CORRECT:
val range = getMonthRange(now)
```

---

## Day Math

```kotlin
// ❌ BUG: Raw millis division fails on DST days (23 or 25 hours)
val days = (end - start) / 86_400_000

// ✅ CORRECT: Use DST-safe calendar day difference
val days = TimePeriodUtils.daysBetween(start, end)
```

---

## PeriodRange / PeriodKind

Use the typed `PeriodRange` model (in `domain.core.time`) instead of raw `Pair<Long, Long>` when you need to communicate period semantics across layers:

```kotlin
val range = PeriodRange(
    kind = PeriodKind.THIS_MONTH,
    startInclusiveMillis = TimePeriodUtils.getStartOfMonth(now),
    endExclusiveMillis = TimePeriodUtils.getEndOfMonth(now),
    zoneId = ZoneId.systemDefault(),
    label = "April 2026"
)
```

This replaces untyped pairs that carried no information about period kind, timezone, or boundary semantics.

---

## TimePeriodUtils

`TimePeriodUtils` is the **canonical owner of all calendar boundary math**. Key rules:

1. **It NEVER calls the system clock internally.** All functions are pure and seeded from the timestamp parameter you pass.
2. **It follows the half-open contract** (`[start, end)`).
3. **It is a Kotlin `object`** — no DI needed. Use it as a pure utility.
4. **For new code:** prefer the typed `PeriodRange` return type where possible. Existing `Pair<Long, Long>` returns remain for backward compatibility.

---

## Testing Time-Dependent Code

1. **Inject `TimeProvider`** into the class under test.
2. **Use `FakeTimeProvider`** in tests to control time.
3. **Never** use `System.currentTimeMillis()` in test fixtures for logical assertions.
4. **Test edge cases**: DST boundaries, leap days, month-end coercion, week/year rollovers.

```kotlin
// Test "This Month" on April 15
val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
val range = TimePeriodUtils.getMonthRange(timeProvider.now())
// Expect: April 1 00:00 to May 1 00:00
```

---

## Date Formatting

1. **Format methods accept explicit timestamps.** `DateFormatterUtils.javaTime("MMM dd").format(Instant.ofEpochMilli(timestamp))` — NOT a zero-arg "format now" method.
2. **UI display** may use `Locale.getDefault()`.
3. **Machine-stable exports** (filenames, API payloads) use `Locale.US`.
4. **No formatter utility fetches wall-clock "now"** internally.

---

## Enforcing These Rules

After changes, verify with grep scans:

```bash
# Should only show whitelisted files
rg "System\.currentTimeMillis\(\)" app/src/main/java/ --include="*.kt" -l | grep -v SystemTimeProvider | grep -v AppDatabase

# Should return empty
rg "Instant\.now\(\)" app/src/main/java/ --include="*.kt" -l
rg "LocalDate\.now\(\)" app/src/main/java/ --include="*.kt" -l
rg "LocalDateTime\.now\(\)" app/src/main/java/ --include="*.kt" -l

# Should return empty
rg "23:59:59" app/src/main/java/ --include="*.kt" -l
rg "365L \* 24 \* 60 \* 60 \* 1000" app/src/main/java/ --include="*.kt" -l
```
