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

The **canonical source of truth** for clock exceptions is
`config/guards/time_boundary_exceptions.yml` (enforced by the `G-TIME-01`
guard — see `docs/time/time-boundary-guard.md`). Only exact, source-verified
entries are authorized:

| Path | Class | Method | API | Reason | Owner | Linked Issue |
|------|-------|--------|-----|--------|-------|--------------|
| `domain/util/SystemTimeProvider.kt` | `SystemTimeProvider` | `now` | `System.currentTimeMillis` | Single production clock implementation | `@panospao7` | MIT-003 |
| `domain/groups/SettlementCalculator.kt` | `SettlementCalculator` | `findMinimalTransferPlan` | `System.nanoTime` | Monotonic elapsed-duration DFS timeout | `@panospao7` | MIT-003 |
| `data/database/AppDatabase.kt` | `MIGRATION_16_17` | `migrate` | `System.currentTimeMillis` | Room migration 16→17 data seeding during DB open (before Hilt TimeProvider injection) | `@panospao7` | MIT-003 |
| `data/database/AppDatabase.kt` | `MIGRATION_41_42` | `migrate` | `System.currentTimeMillis` | Room migration 41→42 data seeding during DB open (before Hilt TimeProvider injection) | `@panospao7` | MIT-003 |

The two AppDatabase rows are intentionally **separate exact entries** — one
per migration lambda (`object MIGRATION_16_17 : Migration(16, 17)` /
`object MIGRATION_41_42 : Migration(41, 42)`), so the guard never has to use a
broad `AppDatabase.Companion`/`migrate` bucket that could mask future
unreviewed wall-clock reads. `FRESH_INSTALL_CALLBACK` performs no direct clock
reads and needs no entry.

Each entry is an **exact, source-verified** row — one file, class, method, and API per row. The guard matches against real detected source evidence; stale or unverifiable entries cause a hard failure (exit 2).

Notable non-exceptions:

- `NotificationCaptureService.kt` uses `SystemClock.elapsedRealtime()` (Android
  monotonic scheduling), which is **not** in the guarded API list and needs no
  exception.
- `FinancialHealthScoreV2.kt` and `FinancialStressForecastEngine.kt` previously
  used `System.currentTimeMillis()` for perf timing; those usages are
  **flagged** by the guard and must be migrated to
  `TimeProvider`/`System.nanoTime()` — they are not clock adapters and get no
  exception.
- Temp-file names and UI identity IDs must use UUIDs (e.g.
  `ReceiptOcrService.uniqueTempFileName`, `AssistantViewModel` conversation
  IDs) — never `System.nanoTime()` for unique-name generation.

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

## Deferred Findings

The following direct-time findings are **not authorized** by any exception or
baseline. They are tracked for remediation in Time Batch T2–T6.

### ReceiptAssetStore — deferred to T2

`ReceiptAssetStore.kt:47,69` — `System.currentTimeMillis()` used directly for
temp and persisted receipt naming. No exception or baseline authorizes these
calls. They are deferred to **Time Batch T2** for migration to UUID-based or
`TimeProvider`-backed naming.

### DatabaseBackupRepositoryImpl — deferred to T2B

`DatabaseBackupRepositoryImpl.kt` contains six remaining direct wall-clock
reads, all used for filename / staging / restore naming. **None are
authorized** by any exception entry or baseline. They are deferred to
**Time Batch T2B** for UUID-based and/or `TimeProvider`-backed remediation.

`LocalDateTime.now(ZoneId.systemDefault())`:

| Line | Method (flow) | Naming use |
|------|---------------|------------|
| 430 | `exportDatabase` (legacy debug raw DB export) | Backup filename naming: `expense_tracker_backup_<timestamp>.db` / `.enc` |
| 587 | `createCostBackup` | Snapshot temp-file and output bundle filename naming: `costbackup_snapshot_<timestamp>.db`, `expense_tracker_backup_<timestamp>_<uuid>.costbackup` |
| 2334 | `createSafetyBackupInternalAssumingMaintenance` | Safety backup filename naming: `expense_tracker_backup_SAFETY_<timestamp>.db` |

`System.currentTimeMillis()`:

| Line | Method (flow) | Naming use |
|------|---------------|------------|
| 712 | `restoreCostBackup` | Staging DB name: `expense_tracker_db_import_stage_<millis>` |
| 744 | `restoreCostBackup` | Restore extract temp-directory name: `costbackup_extract_<millis>` |
| 1411 | `importDatabase` (legacy debug import) | Staging DB name: `expense_tracker_db_import_stage_<millis>` |

All six are **unauthorized** today: the guard reports each one and no
exception or baseline covers them. T2B remediation will replace these with
UUID-based naming and/or `TimeProvider`-backed timestamps. Do **not** add
time exceptions or baselines for these findings.

### Remaining guard-reported findings — pending T2–T6

All other guard-reported direct-time findings outside the exact exception
entries above remain **pending remediation** in batches T2 through T6. None
are authorized. Do not add baselines, blanket exceptions, or source-line
exemptions for these findings.

### T1 scope (already authorized)

T1 scope covers only the following authorized exceptions:

1. `AppDatabase.kt` — two **separate exact exceptions** for the two Room
   migration lambdas that seed data with `System.currentTimeMillis()` (DB
   open, before Hilt `TimeProvider` injection): `MIGRATION_16_17.migrate`
   (merchant_canonicals timestamps) and `MIGRATION_41_42.migrate` (default
   exchange_rates.lastUpdated). `FRESH_INSTALL_CALLBACK` performs no direct
   clock reads and needs no entry.
2. `ReceiptOcrService` — `UUID.randomUUID()` for temp-file naming (not a
   clock call; no exception entry needed).
3. `AssistantViewModel` — `UUID.randomUUID()` for conversation IDs (not a
   clock call; no exception entry needed).

No broad exceptions or baselines extend beyond these.

## Enforcing These Rules

After changes, run the automated time-boundary guard:

```bash
python3 scripts/verify_time_boundaries.py --root . --allowlist config/guards/time_boundary_exceptions.yml --fail-on-violation
```

This script scans all Kotlin sources for forbidden direct-time calls
(`System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`,
etc.) and compares every hit against the exception allowlist. Direct-time
violations are **blocking** — the guard exits non-zero and CI fails.

Entries in `config/guards/time_boundary_exceptions.yml` are **exact,
source-verified** exceptions: each row names a specific file, class,
method, and API so the guard knows precisely which usage was audited and
approved. If your change introduces a new direct-time call, add a
corresponding allowlist entry with all seven required fields — `path`,
`class`, `method`, `api`, `reason`, `owner`, `linked_issue` — rather
than disabling the guard.

**Forbidden in exception entries:**

- Wildcard `path`, `class`, `method`, or `api` values (`*`, `**`,
  glob patterns) — each row must name exactly one source location.
- Extra keys not in the seven-field schema — unknown keys cause a
  parse error in the guard.
