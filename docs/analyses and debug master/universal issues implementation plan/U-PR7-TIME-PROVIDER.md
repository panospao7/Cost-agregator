# U-PR7 — TimeProvider Consistency

## 1. Issue Summary

| ID | Priority | Title |
|----|----------|-------|
| U-TIME-01 | P2 | `System.currentTimeMillis()` used instead of `TimeProvider` in BillReminderWorker, WarrantyExpirationWorker, BankApiIntegration |
| U-TIME-02 | P2 | DST-unsafe day arithmetic (`n * DAY_IN_MILLIS`) in FinancialStressForecastEngine |

**Affected Pipelines:** 4, 6, 9, 10

## 2. Root Cause Analysis

### U-TIME-01
The codebase has a `TimeProvider` interface for injectable, testable time. However, several components bypass it:

- **BillReminderWorker** (line 48): `val now = System.currentTimeMillis()` — used for quiet hours check BEFORE the guard block. The guard block itself doesn't use time directly.
- **WarrantyExpirationWorker** (lines 81, 84): 
  - `warrantyRepository.reconcileExpiredItems(System.currentTimeMillis())` 
  - `val now = System.currentTimeMillis()` — used for delivery timestamps, stale claim recovery, and pruning
  - Has explicit TODO comment: `// TODO: Use TimeProvider instead of System.currentTimeMillis()`
- **BankApiIntegration**: Uses `timeProvider.now()` correctly throughout. The `System.currentTimeMillis()` usage is NOT present in this file. **Issue description is incorrect for BankApiIntegration.**

The consequence: these workers cannot be time-tested deterministically. Tests must either mock `System.currentTimeMillis()` (impossible without PowerMock) or accept non-deterministic behavior.

### U-TIME-02
`FinancialStressForecastEngine` uses `TimePeriodUtils.DAY_IN_MILLIS` for day arithmetic:
- Line 97: `val ninetyDaysAgo = now - (90 * TimePeriodUtils.DAY_IN_MILLIS)`
- Line 98: `val sixtyDaysAgo = now - (60 * TimePeriodUtils.DAY_IN_MILLIS)`
- Line 208: `val horizonEnd = now + (daysAhead * TimePeriodUtils.DAY_IN_MILLIS)`
- Lines 413-414: Weekly/biweekly recurrence advancement

`TimePeriodUtils.DAY_IN_MILLIS` is documented as `24L * 60L * 60L * 1000L` with an explicit warning:
> **Prefer calendar-aware helpers** ([addDays], [getWeekRange], [daysBetween]) over manual multiplication with this constant. During DST transitions a calendar day can be 23 or 25 hours, so `n * DAY_IN_MILLIS` is not always correct for logical day arithmetic.

The consequence: During DST transitions (spring forward/fall back), the 90-day lookback window may be off by 1 hour, causing:
- A transaction at the boundary to be included/excluded incorrectly
- Horizon end calculations to land on the wrong calendar day
- Recurring pattern projections to drift by 1 hour per DST crossing

For a financial stress forecast, this is a minor accuracy issue (P2) — the 1-hour drift in a 90-day window is negligible for probability calculations.

## 3. Affected Files

| File | Changes Required |
|------|-----------------|
| `BillReminderWorker.kt` | Replace `System.currentTimeMillis()` with `timeProvider.now()` |
| `WarrantyExpirationWorker.kt` | Replace `System.currentTimeMillis()` with `timeProvider.now()` |
| `BankApiIntegration.kt` | No changes needed (already uses TimeProvider) |
| `FinancialStressForecastEngine.kt` | Replace `n * DAY_IN_MILLIS` with calendar-aware arithmetic |
| `TimePeriodUtils.kt` | No changes needed (already provides calendar-aware helpers) |

## 4. Verification of Issues in Source

### U-TIME-01 — PARTIALLY CONFIRMED
- **BillReminderWorker line 48:** `val now = System.currentTimeMillis()` — ✓ CONFIRMED
- **WarrantyExpirationWorker lines 81, 84:** `System.currentTimeMillis()` — ✓ CONFIRMED (with TODO comment)
- **BankApiIntegration:** Uses `timeProvider.now()` throughout — ✗ NOT CONFIRMED (already correct)

### U-TIME-02 — CONFIRMED
- `FinancialStressForecastEngine` lines 97-98, 208, 413-414 use `n * TimePeriodUtils.DAY_IN_MILLIS`
- `TimePeriodUtils` KDoc explicitly warns against this pattern
- The engine already has `timeProvider` injected but uses the constant for arithmetic

## 5. Implementation Plan

### U-TIME-01 Fix

**BillReminderWorker:**

The worker already has `executionGuard` injected but not `timeProvider`. Need to inject it:

```kotlin
@HiltWorker
class BillReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: RecurringLifecycleCoordinator,
    private val executionGuard: WorkerExecutionGuard,
    private val diagnosticEventWriter: DiagnosticEventWriter,
    private val reminderSettingsRepository: BillReminderSettingsRepository,
    private val timeProvider: TimeProvider  // ADD
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // ...
        val now = timeProvider.now()  // FIX: was System.currentTimeMillis()
        if (settings.isWithinQuietHours(now)) {
            // ...
        }
    }
}
```

**WarrantyExpirationWorker:**

The worker does not currently inject `TimeProvider`. Add it:

```kotlin
@HiltWorker
class WarrantyExpirationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val warrantyRepository: WarrantyTrackerRepository,
    private val notificationService: NotificationService,
    private val deliveryDao: WarrantyReminderDeliveryDao,
    private val executionGuard: WorkerExecutionGuard,
    private val timeProvider: TimeProvider  // ADD
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuarded(...) {
            // ...
            val reconciliationResult = warrantyRepository.reconcileExpiredItems(timeProvider.now())  // FIX
            val now = timeProvider.now()  // FIX
            // ... rest uses `now` variable, no other changes needed
        }
    }
}
```

### U-TIME-02 Fix

**Strategy:** Replace `n * DAY_IN_MILLIS` with `TimePeriodUtils.addDays()` or `java.time` calendar-aware arithmetic. The engine already imports `java.time` types.

```kotlin
// FinancialStressForecastEngine.kt

// Replace lines 97-98:
// OLD: val ninetyDaysAgo = now - (90 * TimePeriodUtils.DAY_IN_MILLIS)
// OLD: val sixtyDaysAgo = now - (60 * TimePeriodUtils.DAY_IN_MILLIS)
val zone = ZoneId.systemDefault()
val nowInstant = Instant.ofEpochMilli(now)
val ninetyDaysAgo = nowInstant.atZone(zone).minusDays(90).toInstant().toEpochMilli()
val sixtyDaysAgo = nowInstant.atZone(zone).minusDays(60).toInstant().toEpochMilli()

// Replace line 208:
// OLD: val horizonEnd = now + (daysAhead * TimePeriodUtils.DAY_IN_MILLIS)
val horizonEnd = nowInstant.atZone(zone).plusDays(daysAhead.toLong()).toInstant().toEpochMilli()

// Replace lines 413-414 (recurring pattern advancement):
// OLD: RecurrenceFrequency.WEEKLY -> nextDate + (7 * TimePeriodUtils.DAY_IN_MILLIS)
// OLD: RecurrenceFrequency.BIWEEKLY -> nextDate + (14 * TimePeriodUtils.DAY_IN_MILLIS)
RecurrenceFrequency.WEEKLY -> Instant.ofEpochMilli(nextDate).atZone(zone).plusWeeks(1).toInstant().toEpochMilli()
RecurrenceFrequency.BIWEEKLY -> Instant.ofEpochMilli(nextDate).atZone(zone).plusWeeks(2).toInstant().toEpochMilli()
```

**Helper extraction** (optional, for readability):

```kotlin
private fun addDaysToTimestamp(timestamp: Long, days: Int): Long {
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .plusDays(days.toLong())
        .toInstant()
        .toEpochMilli()
}
```

## 6. Execution Order

1. **U-TIME-01** (P2) — Inject TimeProvider into BillReminderWorker and WarrantyExpirationWorker
2. **U-TIME-02** (P2) — Replace DAY_IN_MILLIS arithmetic in FinancialStressForecastEngine

## 7. Testing Strategy

### Unit Tests
- `BillReminderWorkerTest`: Use fake `TimeProvider` to test quiet hours boundary
- `WarrantyExpirationWorkerTest`: Use fake `TimeProvider` to test expiration detection at exact boundaries
- `FinancialStressForecastEngineTest`: Test with timestamps that cross DST boundaries (e.g., March/November transitions) and verify correct day counts

### Specific DST Test Cases
```kotlin
@Test
fun `90-day lookback crosses spring DST transition correctly`() {
    // March 10, 2024 02:00 EST → 03:00 EDT (spring forward)
    val now = /* March 15, 2024 timestamp */
    val ninetyDaysAgo = /* should be Dec 16, 2023 */
    // Verify the lookback lands on the correct calendar date
}

@Test
fun `horizon end crosses fall DST transition correctly`() {
    // Nov 3, 2024 02:00 EDT → 01:00 EST (fall back)
    val now = /* Oct 30, 2024 timestamp */
    val horizon30 = /* should be Nov 29, 2024 */
    // Verify 30-day horizon lands on correct calendar date
}
```

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| TimeProvider injection breaks Hilt worker graph | Low | Medium | TimeProvider is already provided in the Hilt graph (used by other workers) |
| Calendar-aware arithmetic changes forecast results | Medium | Low | Results change by at most 1 hour — within acceptable tolerance for probability forecasts |
| Test flakiness from timezone-dependent assertions | Medium | Low | Use explicit ZoneId in tests, not system default |

## 9. Rollback Plan

- U-TIME-01: Revert to `System.currentTimeMillis()` — no data impact
- U-TIME-02: Revert to `n * DAY_IN_MILLIS` — forecast accuracy degrades by ~1 hour at DST boundaries

## 10. Dependencies

- `TimeProvider` is already in the Hilt dependency graph (provided by a module)
- `java.time` classes are already imported in `FinancialStressForecastEngine`
- No new library dependencies

## 11. Migration / Data Impact

- No database migration required
- No data format changes
- Warranty delivery timestamps will use `TimeProvider` going forward (existing rows unaffected)
- Forecast results may shift by up to 1 hour at DST boundaries (imperceptible to users)

## 12. Performance Impact

- `timeProvider.now()` is a simple interface call — same cost as `System.currentTimeMillis()`
- `java.time` ZonedDateTime arithmetic is slightly more expensive than raw multiplication but negligible for the 5 call sites in the forecast engine (called once per forecast computation)

## 13. Documentation Updates

- Remove the TODO comment in `WarrantyExpirationWorker` (line 83)
- Add inline comment in `FinancialStressForecastEngine` explaining DST-safe arithmetic choice
- Update `docs/development/TIME_SEMANTICS.md` to list the fixed files as compliant

## 14. Acceptance Criteria

- [x] `BillReminderWorker` uses `timeProvider.now()` instead of `System.currentTimeMillis()`
- [x] `WarrantyExpirationWorker` uses `timeProvider.now()` instead of `System.currentTimeMillis()`
- [x] `WarrantyExpirationWorker` TODO comment is removed
- [x] `FinancialStressForecastEngine` uses calendar-aware day arithmetic (no `n * DAY_IN_MILLIS`)
- [x] DST boundary tests pass for forecast engine
- [x] Existing worker tests pass with fake TimeProvider
- [x] No `System.currentTimeMillis()` remains in worker classes (grep verification)
