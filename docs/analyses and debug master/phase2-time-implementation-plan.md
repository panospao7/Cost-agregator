# Phase 2 — Time / Period Semantics Foundation: Implementation Plan

**Author**: Planner (based on Scout audit, user template, and roadmap)
**Date**: 2026-05-01
**Status**: Ready for execution

---

## 1. Goal Statement — What "Done" Means

Phase 2 is **done** when:

1. All logical "current time" decisions flow through `TimeProvider.now()` — no direct `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`, or `LocalDateTime.now()` in main source outside the whitelist (`SystemTimeProvider`, perf timing, Android elapsed scheduling, old Room migrations).
2. Every calendar period range follows the half-open `[startInclusive, endExclusive)` contract consistently — domain, data layer, and tests all agree.
3. UI labels match semantics: "This Month" = `getMonthRange(now)`, NOT `getLastNDaysRange(now, 30)`.
4. Rolling windows are explicitly named and use explicit rolling helpers.
5. No raw millisecond day math (`(a-b) / 86_400_000`) in logical day calculations — `TimePeriodUtils.daysBetween()` is used instead.
6. Entity timestamps (`createdAt`, `updatedAt`) are captured once at the use-case/repository boundary, not defaulted in constructors to `System.currentTimeMillis()`.
7. Invalid parser/import dates do NOT fall back to today — they are explicit errors.
8. `DateFormatterUtils` convenience methods accept a timestamp; zero-arg "format now" methods are removed.
9. `NaturalLanguageSearchEngine` derives `LocalDate` from `timeProvider.now()`, not `LocalDate.now()`.
10. Recurrence/Forecast models do not encode calendar meaning through magic zero sentinels (`intervalInMs` = 0 for IRREGULAR, `REST_OF_MONTH.days = 0`).
11. Current-period flows use `TimeBoundaryTicker` with `flatMapLatest` — rollover-safe.
12. `TestUtils.endOfMonth()` follows half-open convention.
13. All tests pass; compile succeeds.
14. `PeriodRange`/`PeriodKind` typed model is introduced and adopted by at least the domain layer contracts.

---

## 2. Design Decisions (Resolving Template Ambiguities)

### 2.1 Should `TimeProvider` be injected into `TimePeriodUtils`?

**Decision: NO.** `TimePeriodUtils` is a `object` (Kotlin singleton) and a pure utility. Every function already accepts an explicit `timestamp: Long` or `now: Long` parameter. The `Calendar.getInstance()` calls inside `TimePeriodUtils` are **not** fetching "now" — they create a new `Calendar` instance and then immediately set its `timeInMillis` to the passed timestamp. This is correct.

The ONLY function that internally depends on a "now" concept is `getLastNDaysRange(now, days)`, and even that takes `now` as an explicit parameter. We will:
- Keep `TimePeriodUtils` as a pure utility (no DI).
- Add KDoc explicitly stating: "This utility never calls the system clock internally. All boundary calculations are seeded from the timestamp parameter you pass."
- Add three explicit replacement helpers (see Section 3, PR 1).

### 2.2 Should `PeriodRange` / `PeriodKind` be introduced?

**Decision: YES, in PR 0.5 (foundation types).** The roadmap explicitly requires this, and it provides a shared vocabulary that prevents the kind of ambiguity that led to the rolling-vs-calendar bug. However, we introduce it as a **data model only** initially — no type replacement of `Pair<Long,Long>` throughout the codebase. That full migration belongs in a future phase (Phase 10 — Analytics/Forecast cleanup). For Phase 2, `PeriodRange` serves as:
- A documentation anchor for what callers should expect
- A return type for new helpers
- A type that existing helpers can be wrapped with

### 2.3 How to handle entity `createdAt` defaults without massive compile fan-out?

**Decision: Two-phase approach with transitional default.**

**Phase 2 (now)**: Replace `= System.currentTimeMillis()` with `= 0L` (a sentinel) and add a KDoc comment: *"Must be set to timeProvider.now() at the creation boundary. 0L indicates unset."* This:
- Removes the direct `System.currentTimeMillis()` call (satisfying the audit)
- Does NOT break any existing call sites (they can still omit the parameter)
- Makes the sentinel detectable (assert or log at insertion time)

**Phase 3+ (future TransactionLifecycleCoordinator)**: Remove the default entirely and require explicit timestamps everywhere.

### 2.4 The `RecurrenceFrequency` cleanup — in Phase 2 or later?

**Decision: Partially in Phase 2.** The `RecurrenceFrequency` enum already has `fixedIntervalDays`, `calendarMonths`, and `isIrregular` helpers. The `days` and `intervalInMs` properties are already `@Deprecated`. For Phase 2 we:
- Remove **direct production callers** of `intervalInMs` that are in files we're already touching
- Add doc comments explaining the sentinel problem
- The `RecurrenceCalculator` is already doing the right thing (using `addFrequencyInterval` with calendar-aware math). The remaining callers of `days`/`intervalInMs` that need to be cleaned up are tracked for Phase 10.

---

## 3. PR / Batch Order

### PR 0 (ZERO) — Baseline & Foundation Types

**Purpose**: Establish a stable starting point and introduce core models before any behavioral changes.

**Files**:
- **create**: `domain/core/time/PeriodRange.kt` (new package)
- **create**: `domain/core/time/PeriodKind.kt` (new package)
- **create**: `docs/development/TIME_SEMANTICS.md`
- **modify**: `domain/util/TimePeriodUtils.kt` — add KDoc contract reinforcing no-internal-now
- **modify**: `domain/util/TimeProvider.kt` — add KDoc clarifying it is the single source of "now"

**Actions**:
1. Rebase Phase 2 branch on finalized Phase 1.
2. Run baseline: `./gradlew.bat :app:compileDebugKotlin` and `./gradlew.bat :app:testDebugUnitTest`. Record failures.
3. Create `PeriodRange` data class:
   ```kotlin
   package com.yourname.expensetracker.domain.core.time

   data class PeriodRange(
       val kind: PeriodKind,
       val startInclusiveMillis: Long,
       val endExclusiveMillis: Long,
       val zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
       val label: String = ""
   ) {
       init {
           require(endExclusiveMillis >= startInclusiveMillis) {
               "endExclusiveMillis ($endExclusiveMillis) must be >= startInclusiveMillis ($startInclusiveMillis)"
           }
       }

       fun contains(timestamp: Long): Boolean =
           timestamp >= startInclusiveMillis && timestamp < endExclusiveMillis
   }
   ```
4. Create `PeriodKind` enum:
   ```kotlin
   package com.yourname.expensetracker.domain.core.time

   enum class PeriodKind {
       TODAY,
       THIS_WEEK,
       LAST_WEEK,
       LAST_7_DAYS,
       THIS_MONTH,
       LAST_MONTH,
       LAST_30_DAYS,
       THIS_QUARTER,
       LAST_QUARTER,
       THIS_YEAR,
       LAST_YEAR,
       CUSTOM
   }
   ```
5. Add `docs/development/TIME_SEMANTICS.md` documenting:
   - Half-open contract
   - Whitelisted clock sources
   - Forbidden patterns
   - How to test time-dependent code
   - PeriodRange / PeriodKind model
6. Add KDoc to `TimePeriodUtils.kt`: "This object NEVER calls the system clock. All functions are pure, seeded from the timestamp you provide."

**Tests**: No behavioral tests — compile-only verification.

**Done when**:
- Baseline compile/test status is documented.
- `PeriodRange` and `PeriodKind` compile.
- Documentation exists.
- No Room schema or migration files modified.

**Validation**:
```bash
./gradlew.bat :app:compileDebugKotlin
git diff --stat  # verify no entity/schema files changed
```

---

### PR 1 — Canonical TimePeriodUtils: New Helpers & Contract Hardening

**Purpose**: Fill gaps in `TimePeriodUtils`, add explicit rolling/calendar helpers, deprecate ambiguous `getLastNDaysRange`, add `parseMonthKeyToRange`.

**Files**:
- **modify**: `domain/util/TimePeriodUtils.kt`
- **modify**: `domain/util/TimePeriodUtilsTest.kt`
- **modify**: `domain/util/TimePeriodUtilsValidationTest.kt`
- **modify**: `domain/util/TimePeriodUtilsStressTest.kt`
- **modify**: `metrics/TimePeriodAlignmentTest.kt`
- **modify**: `metrics/TimePeriodAlignmentStressTest.kt`

**Actions**:

1. **Add `parseMonthKeyToRange(monthKey: String): Pair<Long, Long>`** — Parses `"2026-04"` into `[Apr 1 00:00, May 1 00:00)`. Delegate to existing `parseMonthKey()` + `getMonthRange(year, month)`.

2. **Add `getLastNCalendarDaysRange(now: Long, days: Int): Pair<Long, Long>`** — Full local calendar days, inclusive of today. Start = `addDays(getStartOfDay(now), -(days-1))`, End = `getEndOfDay(now)` (= start of tomorrow). Replaces the "I want the last N calendar days including today" use case.

3. **Add `getLastNCompleteDaysRange(now: Long, days: Int): Pair<Long, Long>`** — Complete days ending at today start. Start = `addDays(getStartOfDay(now), -days)`, End = `getStartOfDay(now)`. For "give me the last N full days, excluding today."

4. **Add `getTrailingElapsedRange(now: Long, durationMs: Long): Pair<Long, Long>`** — Exact elapsed duration only, not for calendar reports. Start = `now - durationMs`, End = `now`. For use cases like "transactions in the last 5 minutes" (duplicate detection window).

5. **Add `toPeriodRange(pair: Pair<Long,Long>, kind: PeriodKind, label: String = ""): PeriodRange`** — Convenience wrapper converting existing `Pair` returns to typed `PeriodRange`.

6. **Add `getDayIndexForSparkline(timestamp: Long, periodStart: Long): Int`** — Returns 0-based day index within a period for sparkline/bucket placement. Uses `daysBetween(periodStart, timestamp)`, clamped to `>= 0`.

7. **Deprecate `getLastNDaysRange()`** — Add `@Deprecated` annotation with `ReplaceWith` pointing to the appropriate new helper based on use case:
   - For "last N calendar days including today" → `getLastNCalendarDaysRange`
   - For "last N complete days" → `getLastNCompleteDaysRange`
   - For exact elapsed intervals → `getTrailingElapsedRange`

8. **Audit and fix `getLastNDaysRange` asymmetry**: Mark in KDoc that the end boundary is raw `now` (not day-aligned). Add a note that `getLastNCalendarDaysRange` fixes this.

9. **Fix `getLastNDaysRange` negative days handling**: The current implementation with negative days (used by `CashFlowCalculator` for future windows) works but is undocumented. Add KDoc documenting this use case.

10. **Ensure all "getEndOf*" helpers are half-open**: Verify `getEndOfDay`, `getEndOfWeek`, `getEndOfMonth`, `getEndOfQuarter`, `getEndOfYear` all return next-period start. (They already do — this is verification.)

**Tests to add**:
- DST spring-forward: Mar 12, 2026 02:30 → day range is 23 hours
- DST fall-back: Nov 1, 2026 01:30 → day range is 25 hours
- Leap day: Feb 29, 2024 → month range is 29 days
- Jan 31 + 1 month → Feb 28/29 (month-end coercion)
- Dec/Jan week rollover: Dec 31, 2025 (Wed) → week range crosses year
- Monday-start week: Sunday's week starts the previous Monday
- Boundary: timestamp exactly at `endExclusive` is excluded (`isInRange` returns false)
- Boundary: timestamp at `startInclusive` is included (`isInRange` returns true)
- `parseMonthKeyToRange("2026-01")` → Jan 1 00:00 to Feb 1 00:00
- `getLastNCalendarDaysRange`: today at 10:30, N=7 → includes today (day start to tomorrow start)
- `getLastNCompleteDaysRange`: today at 10:30, N=7 → ends at today start (7 complete days)
- `getDayIndexForSparkline`: first day of month returns 0, 15th returns 14
- Quarter boundaries: Jan 1, Apr 1, Jul 1, Oct 1
- Year boundaries: Jan 1 to next Jan 1
- `buildMonthKeyRange`: cross-year (2025-10 to 2026-03) → 6 keys

**Done when**:
- All new helpers exist and are tested.
- `getLastNDaysRange` is deprecated with guidance.
- Half-open contract is verified for all end helpers.
- Tests prove DST-safe `daysBetween`, correct quarter/year boundaries, leap day handling, month-end coercion.

**Validation**:
```bash
./gradlew.bat :app:testDebugUnitTest --tests "*TimePeriodUtils*"
./gradlew.bat :app:testDebugUnitTest --tests "*TimePeriodAlignment*"
```

---

### PR 2 — `DateFormatterUtils` Purification + `NaturalLanguageSearchEngine` Injection

**Purpose**: Remove all direct `Instant.now()` calls from formatting utilities and inject `TimeProvider` into the natural language search engine. These are isolated changes with clear boundaries.

**Files**:
- **modify**: `domain/util/DateFormatterUtils.kt`
- **modify**: `domain/naturallanguage/NaturalLanguageSearchEngine.kt`
- **modify**: `data/repository/AccountingExportRepository.kt`
- **modify**: `data/ai/provider/DashboardBriefingPromptFormatter.kt`
- **modify**: All call sites of removed `DateFormatterUtils` zero-arg methods

**Actions**:

### Part A — DateFormatterUtils.kt

1. **Replace all 13 zero-arg convenience methods** (`javaTimeMonthDay()`, `javaTimeFullDate()`, etc.) with timestamp-accepting overloads:
   ```kotlin
   // OLD (remove):
   fun javaTimeMonthDay(): String = javaTime("MMM dd").format(Instant.now()...)

   // NEW (add):
   fun javaTimeMonthDay(timestamp: Long): String =
       javaTime("MMM dd").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
   ```
   Keep the same method names but change signatures. This WILL cause compile errors at all call sites — that's intentional and forces callers to think about what timestamp they want.

2. **Find all call sites** of the removed zero-arg methods and update them:
   - Call sites in ViewModels: pass `timeProvider.now()`
   - Call sites in Composables: accept `referenceNowMs` parameter or get from ViewModel state
   - Call sites in formatters/engines: pass the timestamp being formatted (not "now")

   **Call site inventory** (from audit grep):
   - `AdvancedAnalyticsEngine.kt:91` — formatting week range label: pass the week start/end timestamps, not "now"
   - `AdvancedAnalyticsEngine.kt:99` — formatting month label: pass the month start timestamp
   - `HomeViewModel.kt:728` — already uses `SimpleDateFormat`, migrate to `javaTime("pattern").format(...)`
   - `AnalyticsViewModel.kt:488,677` — already uses `SimpleDateFormat`, migrate
   - Various screens: update to pass timestamps

3. **Add KDoc to `javaTime()`**: note that `withZone(ZoneId.systemDefault())` is applied at formatter creation. Callers that want a different zone should create their own formatter.

### Part B — NaturalLanguageSearchEngine.kt

1. **Add `TimeProvider` as a constructor parameter**:
   ```kotlin
   class NaturalLanguageSearchEngine @Inject constructor(
       private val expenseQueryRepository: NaturalLanguageExpenseQueryRepository,
       private val speechInputGateway: SpeechInputGateway,
       private val timeProvider: TimeProvider  // NEW
   )
   ```

2. **Replace all `LocalDate.now()` calls** (lines 25, 39, 54, 55, 56, 66, 328):
   ```kotlin
   // OLD: val end = LocalDate.now()
   // NEW: val now = timeProvider.now()
   //      val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
   ```
   Capture `now` once at the top of the function, derive `today` once, use throughout.

3. **Replace `System.currentTimeMillis()` fallback** (line 185):
   ```kotlin
   // OLD: } ?: System.currentTimeMillis()
   // NEW: } ?: timeProvider.now()
   ```

4. **Fix the month-search pattern** (line 66): Currently uses `LocalDate.now().year` to resolve "January" without a year. This becomes `today.year` using the captured `today`.

### Part C — AccountingExportRepository.kt

1. Replace `LocalDateTime.now()` (line 79) with `timeProvider.now()` → convert to `LocalDateTime`.

### Part D — DashboardBriefingPromptFormatter.kt

1. Replace any `Instant.now()` usage with `timeProvider.now()`.

**Tests**:
- `NaturalLanguageSearchEngineTest`: Inject `FakeTimeProvider`, test "today"/"yesterday"/"this week"/"last month" resolve correctly at different fake times.
- `DateFormatterUtils`: Verify format methods produce correct output for known timestamps.
- Test `NaturalLanguageSearchEngine` around midnight: fake time at 23:59 vs 00:01 should produce different "today".

**Done when**:
- No direct `Instant.now()`, `LocalDate.now()`, or `LocalDateTime.now()` in `DateFormatterUtils`, `NaturalLanguageSearchEngine`, `AccountingExportRepository`, or `DashboardBriefingPromptFormatter`.
- All 13 `DateFormatterUtils` methods accept a timestamp parameter.
- `NaturalLanguageSearchEngine` is testable with `FakeTimeProvider`.

**Validation**:
```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest --tests "*NaturalLanguage*"
./gradlew.bat :app:testDebugUnitTest --tests "*DateFormatter*"
```

---

### PR 3 — Entity Timestamp Default Cleanup (Batch A — Core Entities)

**Purpose**: Remove `System.currentTimeMillis()` defaults from the most critical entity classes. This is split into two batches to manage compile fan-out.

**Strategy**: Replace `= System.currentTimeMillis()` with `= 0L` (sentinel) + KDoc. The `0L` sentinel is detectable at the repository layer. Full removal of defaults is deferred to the TransactionLifecycleCoordinator phase.

**Files — Batch A (core, high-traffic entities)**:
1. `data/database/entity/Expense.kt` — `createdAt: Long = 0L`
2. `data/database/entity/Budget.kt` — `createdAt: Long = 0L`
3. `data/database/entity/PlannedExpense.kt` — `createdAt: Long = 0L`
4. `data/database/entity/SavingsGoal.kt` — `createdAt: Long = 0L`
5. `data/database/entity/SpendingChallengeEntity.kt` — `createdAt`, `updatedAt: Long = 0L`
6. `data/database/entity/ExchangeRate.kt` — `lastUpdated: Long = 0L`
7. `data/database/entity/SavingsSweepPlan.kt` — `computedAt: Long = 0L`
8. `data/database/entity/StressForecastSnapshot.kt` — `computedAt: Long = 0L`

**Actions**:
1. For each entity:
   - Change `= System.currentTimeMillis()` to `= 0L`
   - Add KDoc: `/** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */`
   - Ensure `createdAt` is NOT a Room `@ColumnInfo(defaultValue = ...)` — it's a Kotlin default only

2. For each entity's primary creation site (repository/use-case), ensure `createdAt = timeProvider.now()` is explicitly passed. Find these sites:
   - `Expense` creation: `ManualExpenseRepository.addManualExpense()`, `NotificationProcessingPipeline`, imports → ensure they pass `createdAt`
   - `Budget` creation: `BudgetRepository` → ensure `createdAt` is passed
   - `PlannedExpense`: creation sites in `AddExpenseViewModel` / planned expense repos
   - `SavingsGoal`: creation sites in savings repos
   - `SpendingChallengeEntity`: creation site
   - `ExchangeRate`: `MultiCurrencyRepository` / `CurrencySettingsRepositoryImpl`
   - `SavingsSweepPlan`: `MonthlySavingsSweepUseCase`
   - `StressForecastSnapshot`: `FinancialStressForecastEngine`

3. **Add a runtime check in debug builds** at the DAO insertion layer:
   ```kotlin
   // In ExpenseDao or repository insert function (debug only):
   require(expense.createdAt > 0L) { "createdAt was not set! Must pass timeProvider.now()." }
   ```

**Tests**: Update entity creation in affected tests to pass explicit timestamps via `FakeTimeProvider.now()`.

**Done when**:
- 8 core entities no longer have `System.currentTimeMillis()` in their defaults.
- All creation sites explicitly pass `timeProvider.now()`.
- Compile and tests pass.
- Room schema is unchanged (no migration needed — these are Kotlin defaults, not `@ColumnInfo(defaultValue)`).

**Validation**:
```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest --tests "*Expense*"
./gradlew.bat :app:testDebugUnitTest --tests "*Budget*"
```

---

### PR 4 — Entity Timestamp Default Cleanup (Batch B — Remaining Entities)

**Purpose**: Finish removing `System.currentTimeMillis()` from all remaining entities.

**Files — Batch B (all remaining entities from audit Section 5)**:
1. `BudgetForecast.kt` (createdAt)
2. `BudgetAdjustmentRecommendation.kt` (generatedAt, appliedAt)
3. `PendingReview.kt` (createdAt)
4. `UserCorrection.kt` (createdAt)
5. `BankConnection.kt` (createdAt)
6. `MerchantLocationCorrection.kt` (createdAt)
7. `MerchantAlias.kt` (createdAt)
8. `MerchantCanonical.kt` (createdAt, updatedAt)
9. `ManualRecurringExpense.kt` (createdAt)
10. `Warranty.kt` (createdAt, updatedAt)
11. `SubscriptionCandidate.kt` (createdAt, updatedAt)
12. `SplitItemAssignment.kt` (createdAt)
13. `SplitTemplate.kt` (createdAt, updatedAt)
14. `ScannedReceipt.kt` (createdAt)
15. `ReceiptItemCategorization.kt` (createdAt, updatedAt)
16. `PromptState.kt` (createdAt)
17. `MileageTracking.kt` (createdAt)
18. `InvestmentValue.kt` (timestamp)
19. `Investment.kt` (createdAt)
20. `ExpenseGroup.kt` (createdAt)
21. `AiChatSessionEntity.kt` (createdAt, updatedAt)
22. `AiChatMessageEntity.kt` (createdAt)
23. `AnalyticsModels.kt` — `generatedAt: Long = 0L`

**Actions**: Same as PR 3 — replace `= System.currentTimeMillis()` with `= 0L`, add KDoc, ensure creation sites pass `timeProvider.now()`.

**Tests**: Update all affected test fixtures to pass explicit timestamps.

**Done when**:
- No entity in main source has `System.currentTimeMillis()` as a constructor default.
- Room schema unchanged.
- All tests updated and passing.

**Validation**:
```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest
rg "System.currentTimeMillis\(\)" app/src/main/java/ --include="*.kt" | grep -v SystemTimeProvider | grep -v AppDatabase | grep -v nanoTime | grep -v "//"
# Should show only whitelisted files or perf timing
```

---

### PR 5 — Calendar vs. Rolling Period Semantics (Bug Fix)

**Purpose**: Fix the most impactful semantic bug: "This Month" showing last 30 rolling days instead of the calendar month. Also fix raw millis day math and week-end calculation bugs.

**Files**:
- **modify**: `ui/screens/transactions/TransactionsViewModel.kt`
- **modify**: `ui/screens/analytics/AnalyticsViewModel.kt`
- **modify**: `domain/analytics/AdvancedAnalyticsEngine.kt`
- **modify**: `domain/analytics/SpendingPaceCalculator.kt`
- **modify**: `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- **modify**: `domain/budget/BudgetForecastingEngine.kt`

**Actions**:

1. **`TransactionsViewModel.kt` — Fix `TransactionTab` period resolution** (lines 760-773):
   ```kotlin
   // OLD:
   TransactionTab.MONTH -> getLastNDaysRange(now, 30)
   TransactionTab.QUARTER -> getLastNDaysRange(now, 90)
   TransactionTab.YEAR -> getLastNDaysRange(now, 365)

   // NEW:
   TransactionTab.MONTH -> getMonthRange(now)
   TransactionTab.QUARTER -> getQuarterRange(now)
   TransactionTab.YEAR -> getYearRange(now)
   ```

2. **`AnalyticsViewModel.kt` — Fix `TimePeriod` resolution** (lines 793-805):
   Same fix — MONTH/QUARTER/YEAR → `getMonthRange`/`getQuarterRange`/`getYearRange`.

3. **`AnalyticsViewModel.kt` — Fix `fullWindowStart`** (line 281):
   ```kotlin
   // OLD: else -> TimePeriodUtils.getLastNDaysRange(now, 365).first
   // NEW: else -> TimePeriodUtils.getYearRange(now).first  // or getStartOfYear(now)
   ```

4. **`AdvancedAnalyticsEngine.kt` — Fix week-end calculation** (line 86):
   ```kotlin
   // OLD: val end = start + (7 * TimePeriodUtils.DAY_IN_MILLIS)
   // NEW: val end = TimePeriodUtils.getEndOfWeek(referenceDate)
   ```

5. **`AdvancedAnalyticsEngine.kt` — Fix raw day math** (lines 516, 740, 752):
   ```kotlin
   // OLD: periodDays = ((period.endMs - period.startMs) / DAY_IN_MILLIS).toInt()
   // NEW: periodDays = daysBetween(period.startMs, period.endMs)
   ```
   Same for lines 740, 752.

6. **`ComputeMoneyRadarUseCase.kt` — Fix raw millis division** (line 231):
   ```kotlin
   // OLD: ((now - alert.alertedAt) / ONE_DAY_MS).toInt()
   // NEW: daysBetween(alert.alertedAt, now)
   ```

7. **`BudgetForecastingEngine.kt` — Fix remaining days calculation** (line 58):
   ```kotlin
   // OLD: remainingForecastDays = ((periodEnd - elapsedEnd).coerceAtLeast(0L) / MILLIS_PER_DAY)
   // NEW: remainingForecastDays = daysBetween(elapsedEnd, periodEnd).coerceAtLeast(0)
   ```

8. **`SpendingPaceCalculator.kt`** — Review `daysBetween(currentMonthStart, currentWindowEnd) + 1` pattern. Ensure it's correct with half-open conventions.

**Important note**: The `TransactionTab` enum still has `daysBack: Int?` constructor parameter (lines 53-60). We do NOT change the enum definition in this PR — we only change how it's resolved to ranges. The `daysBack` field becomes unused for MONTH/QUARTER/YEAR after this change, but removing it would require changing all enum usages across the UI (tab labels, etc.). Defer enum cleanup to Phase 10.

**Tests**:
- April 15, 2026 "This Month" tab → expenses from April 1 to May 1, not March 16 to April 15.
- Quarter tab in May → April 1 to July 1.
- Year tab → January 1 to next January 1.
- WEEK tab → Monday-start week, not rolling 7 days.
- DST week → week is still 7 calendar days (not 6 or 8).
- Sparkline for current month includes today.
- `AdvancedAnalyticsEngine` week range covers Monday 00:00 to next Monday 00:00 (not 23:59:59 leak).

**Done when**:
- Calendar-labeled tabs use calendar ranges.
- Rolling-labeled uses (`BudgetRepository`'s "90 days", etc.) still work correctly.
- No raw millis division for logical days in the listed files.
- Week-end is calendar-aware.

**Validation**:
```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest --tests "*TransactionsViewModel*"
./gradlew.bat :app:testDebugUnitTest --tests "*AnalyticsViewModel*"
./gradlew.bat :app:testDebugUnitTest --tests "*AdvancedAnalytics*"
./gradlew.bat :app:testDebugUnitTest --tests "*SpendingPace*"
./gradlew.bat :app:testDebugUnitTest --tests "*MoneyRadar*"
```

---

### PR 6 — Parser / Import Date Determinism

**Purpose**: Ensure all parsers and importers produce deterministic dates — no fallback to today, no `Calendar.getInstance().get(Calendar.YEAR)` for "current year".

**Files**:
- **modify**: `util/CsvExpenseImporter.kt`
- **modify**: `domain/receipt/ReceiptParser.kt`
- **modify**: `domain/parser/AppParserRegistry.kt`
- **modify**: `domain/parser/GenericTransactionParser.kt`
- **modify**: `domain/receipt/BankStatementParser.kt`
- **modify**: `domain/parser/DocumentParser.kt` (if applicable)
- **modify**: parser-related tests

**Actions**:

1. **`CsvExpenseImporter.kt`**:
   - Invalid date → row error, NOT today.
   - Preserve import count/error reporting.
   - If CSV has no date column, the importer should require a `defaultDate` parameter (NOT `System.currentTimeMillis()`).

2. **`ReceiptParser.kt` — Replace raw `daysDiff`** (line 734):
   ```kotlin
   // OLD: val daysDiff = (System.currentTimeMillis() - date) / (1000 * 60 * 60 * 24)
   // NEW: val daysDiff = TimePeriodUtils.daysBetween(date, timeProvider.now())
   ```

3. **`ReceiptParser.kt` — Replace `Calendar.getInstance().get(Calendar.YEAR)`** (line 599):
   Replace with year derived from `timeProvider.now()` via `TimePeriodUtils.getYear(timeProvider.now())`.

4. **`GenericTransactionParser.kt` — Future date validation** (line 308):
   ```kotlin
   // OLD: if (ts in 1..(System.currentTimeMillis() + 86_400_000)) return ts
   // NEW: val now = timeProvider.now()
   //      val tomorrow = TimePeriodUtils.addDays(now, 1)
   //      if (ts in 1..tomorrow) return ts
   ```

5. **`AppParserRegistry.kt`** (line 66):
   ```kotlin
   // OLD: require(it <= System.currentTimeMillis() + 86_400_000)
   // NEW: require(it <= timeProvider.now() + 86_400_000)  // or use calendar-aware
   ```

6. **`BankStatementParser.kt` — Replace `Calendar.getInstance().get(Calendar.YEAR)`** (lines 686, 734):
   Use `timeProvider.now()` → year extraction.

7. **`BankStatementParser.kt` — Replace `SimpleDateFormat` patterns** with `DateTimeFormatter` where possible (graceful — not a hard requirement for this PR).

**Tests**:
- CSV invalid date → error, not today.
- Same input + same `receivedAt` = same parsed timestamp (deterministic).
- Year-less receipt around Dec/Jan resolves using the reference timestamp.
- Future-date rejection uses fake time (set `FakeTimeProvider` to Jan 1, feed date Dec 31).
- Receipt age across DST boundary is correct.

**Done when**:
- No parser synthesizes unknown dates from wall-clock `now()`.
- Same input yields same output (determinism).
- Invalid dates are errors, not silent defaults.

**Validation**:
```bash
./gradlew.bat :app:testDebugUnitTest --tests "*Parser*"
./gradlew.bat :app:testDebugUnitTest --tests "*Receipt*"
./gradlew.bat :app:testDebugUnitTest --tests "*Import*"
./gradlew.bat :app:testDebugUnitTest --tests "*BankStatement*"
```

---

### PR 7 — DAO / Repository Period Alignment

**Purpose**: Verify and fix the data layer to use the same half-open boundary semantics as the domain layer.

**Files**:
- **modify**: `data/database/dao/ExpenseDao.kt` (audit only — it's already correct)
- **modify**: `data/database/dao/BudgetForecastDao.kt`
- **modify**: `data/database/dao/HealthScoreHistoryDao.kt`
- **modify**: `data/repository/ExpenseRepository.kt`
- **modify**: `data/repository/AnalyticsRepository.kt`
- **modify**: `domain/analytics/DayOfWeekAnalyzer.kt`
- **modify**: DAO boundary tests

**Actions**:

1. **Audit all DAO date filters**: Confirm every `WHERE date >= :start AND date < :end` uses half-open. From the audit, `ExpenseDao.kt` (line 154, 191, etc.) already does this correctly. Document this verification.

2. **Fix `BudgetForecastDao.kt`** (line 27):
   ```sql
   -- OLD (inclusive end): WHERE targetPeriodStart <= :date AND targetPeriodEnd >= :date
   -- NEW (half-open):     WHERE targetPeriodStart <= :date AND targetPeriodEnd > :date
   ```
   This changes `>=` to `>` for the end boundary.

3. **Fix `HealthScoreHistoryDao.kt`** period-overlap query (line 46):
   ```sql
   -- Should use half-open overlap:
   WHERE periodStart < :requestedEnd AND periodEnd > :requestedStart
   ```

4. **Fix `AnalyticsRepository.kt` day index calculations** (lines 78-79):
   Ensure `daysBetween()` is used, not raw division.

5. **`DayOfWeekAnalyzer.kt`** — Ensure Monday→Sunday chronological order. The audit notes this should be "sorted chronologically, not by spend." Verify and fix.

6. **`ExpenseRepository.kt` — Weekly aggregation**: Ensure that when DAOs return weekly aggregates, the repository converts keys to canonical Monday `[start, end)` ranges using `getCanonicalWeekRangeFromKey()`.

**Tests**:
- Weekly totals over Dec/Jan year boundary (week 2025-W53/2026-W00 → correct ranges).
- Empty week: DAO returns 0 results → handled gracefully.
- Boundary transaction exactly at Monday 00:00 → included in previous week, excluded from next week.
- Boundary transaction exactly at next Monday 00:00 → excluded from previous week (half-open contract).
- Budget forecast lookup with `targetPeriodEnd` exactly at query date → excluded (strict `>`).
- `InvestmentValueDao` already uses half-open — verify no regressions.

**Done when**:
- Data layer does not invent different boundary semantics than domain.
- All SQL date filters verified or fixed to `[start, end)`.
- Budget forecast period containment is correct.

**Validation**:
```bash
./gradlew.bat :app:testDebugUnitTest --tests "*Dao*"
./gradlew.bat :app:testDebugUnitTest --tests "*BoundaryConsistency*"
./gradlew.bat :app:testDebugUnitTest --tests "*DayOfWeek*"
```

---

### PR 8 — Recurrence & Forecast Model Cleanup (Targeted)

**Purpose**: Eliminate magic zero sentinels from the recurrence/forecast models. This is a targeted cleanup, not a full recurrence refactor.

**Files**:
- **modify**: `domain/model/RecurringPattern.kt` (RecurrenceFrequency)
- **modify**: `domain/model/FinancialForecast.kt` (ForecastHorizon)
- **modify**: `domain/logic/RecurrenceCalculator.kt`
- **modify**: `domain/logic/RecurringExpenseEngine.kt`
- **modify**: `domain/logic/SynthesisEngine.kt`
- **modify**: `domain/analytics/InsightsEngine.kt`
- **modify**: related tests

**Actions**:

1. **`RecurrenceFrequency` — Remove `days` from enum constructor**: The `days` parameter is already `@Deprecated`. Remove it from the constructor and make each enum entry define `fixedIntervalDays`/`calendarMonths`/`isIrregular` explicitly. The `days` property becomes a deprecated computed property (not a constructor parameter).

   **Current**: `WEEKLY(7), BIWEEKLY(14), MONTHLY(30), ...`
   **Target**: Each entry defines `calendarMonths` or `fixedIntervalDays`. `days` is retained as `@Deprecated` computed property for backward compat.

2. **`RecurrenceFrequency.intervalInMs`** — Already `@Deprecated`. Add explicit KDoc: "Avoid — uses raw 86_400_000 millis per day. Use RecurrenceCalculator for calendar-safe arithmetic."

3. **`ForecastHorizon.REST_OF_MONTH`** — Its `days` getter throws `UnsupportedOperationException` (already fixed). Verify no production code catches this exception silently.

4. **`RecurrenceCalculator`** — Already uses calendar-aware arithmetic for MONTHLY/QUARTERLY/etc. Verify the `addFrequencyInterval` function (line 100) handles all frequencies correctly. The WEEKLY/BIWEEKLY cases already use `addDays` (calendar-aware). This is good — just audit.

5. **`RecurringExpenseEngine.kt` — `determineFrequency`** (lines 202-233): Already uses ranges of days for detection. This is acceptable for detection (heuristic), but add a comment noting that the detected frequency should use `RecurrenceCalculator` for advancement, not raw day multiplication.

6. **`InsightsEngine.kt` — Raw day mapping** (lines 486-491):
   ```kotlin
   // OLD: RecurrenceFrequency.MONTHLY -> 30
   // This is used for normalization. Add comment: "Approximate normalization factor; not used for calendar arithmetic."
   ```
   Acceptable as an approximation factor, not calendar math.

7. **`SynthesisEngine.kt`** — Capture `now` once (lines 85, 265 already do this via `timeProvider.now()`). Verify `IRREGULAR → 0.0` doesn't propagate zero sentinels.

**Tests**:
- No production logic interprets `0` as "rest of month" or "irregular".
- Monthly recurrence from Jan 31 → behavior is consistent and documented.
- Yearly recurrence from Feb 29 → has explicit behavior (Feb 28 in non-leap years).
- Synthesis midnight race regression test.

**Done when**:
- `days` is removed from `RecurrenceFrequency` constructor.
- `intervalInMs` is clearly documented as deprecated.
- Magic zero sentinels are not propagated through the system.
- Calendar recurrence advancement uses calendar helpers, not approximate millis.

**Validation**:
```bash
./gradlew.bat :app:testDebugUnitTest --tests "*Recurrence*"
./gradlew.bat :app:testDebugUnitTest --tests "*Recurring*"
./gradlew.bat :app:testDebugUnitTest --tests "*Synthesis*"
./gradlew.bat :app:testDebugUnitTest --tests "*Insights*"
```

---

### PR 9 — Reactive Current-Period Flows + Screen Direct-Now Cleanup

**Purpose**: Make long-lived flows rollover-safe and clean up the remaining direct `System.currentTimeMillis()` calls in UI/screen code.

**Files**:
- **modify**: `data/repository/DashboardContractsAdapter.kt`
- **modify**: `data/repository/BudgetRepository.kt`
- **modify**: `ui/screens/home/HomeScreen.kt`
- **modify**: `ui/screens/home/HomeViewModel.kt`
- **modify**: `ui/screens/budget/BudgetScreen.kt`
- **modify**: `ui/screens/budget/BudgetViewModel.kt`
- **modify**: `ui/screens/analytics/AnalyticsScreen.kt`
- **modify**: `ui/components/analytics/StatisticalVisualizations.kt`
- **modify**: `ui/screens/subscription/SubscriptionManagementScreen.kt`
- **modify**: `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt`
- **modify**: `ui/screens/map/SpendingMapScreen.kt`
- **modify**: `ui/screens/recurring/RecurringExpensesScreen.kt`

**Actions**:

1. **`DashboardContractsAdapter.observeDashboardExpenses()`** — Inject `TimeProvider`. Recalculate month range on each boundary tick using `TimeBoundaryTicker.dayBoundaryTicks().flatMapLatest { ... }`.

2. **`BudgetRepository`** — Recalculate `twentyFiveMonthsAgo` and end range after rollover. Preserve public `Flow<List<BudgetStatus>>`.

3. **`HomeScreen.kt`** — Replace 5 `System.currentTimeMillis()` calls (lines 409, 520, 707, 732, 1151, plus line 101's `Calendar.getInstance().get(YEAR)`):
   - Line 101: Use `TimeProvider.getYear()` via ViewModel
   - Lines 409, 520, 707, 732: These are likely display formatting or period queries — pass `now` from ViewModel state
   - Line 1151: Pass reference timestamp

4. **`HomeViewModel.kt`** — Already has `TimeProvider` injected (line 121). Replace line 728's `SimpleDateFormat(Date(now))` with `DateFormatterUtils.formatTimestampJavaTime(now, pattern)`.

5. **`BudgetScreen.kt`** — Replace 3 `System.currentTimeMillis()` calls (lines 480, 750, 892). Accept `referenceNowMs` from ViewModel.

6. **`BudgetViewModel.kt`** — Replace `System.currentTimeMillis()` with `timeProvider.now()` (lines 242, 325, 343).

7. **`AnalyticsScreen.kt`** — Replace line 558's raw day math (`(it - System.currentTimeMillis()) / (1000*60*60*24)`) with `TimePeriodUtils.daysBetween(now, it)`.

8. **`StatisticalVisualizations.kt`** — Replace line 534's same raw day math pattern.

9. **`SubscriptionManagementScreen.kt`** — Replace line 850's `System.currentTimeMillis()` default with explicit timestamp from ViewModel.

10. **`ManualRecurringExpenseScreen.kt`** — Replace lines 338, 463 with `timeProvider.now()`.

11. **`SpendingMapScreen.kt`** — Replace line 243's `val now = System.currentTimeMillis()` with `timeProvider.now()`.

12. **`RecurringExpensesScreen.kt`** — Replace line 102's `365L * 24 * 60 * 60 * 1000` with `TimePeriodUtils.addYears(timeProvider.now(), -1)` or similar calendar-aware approach.

13. **`FinancialHealthScoreV2.kt`** (lines 82, 189) and **`FinancialStressForecastEngine.kt`** (lines 66, 134): These use `System.currentTimeMillis()` for performance timing. **Keep as-is** — this is allowed perf timing.

**Tests**:
- Start flow before midnight; after fake ticker advance, range updates.
- Start flow before month end; after next month starts, dashboard/budget queries update.
- No resubscription required — ticker drives automatic refresh.

**Done when**:
- All remaining production `System.currentTimeMillis()` calls are either migrated or whitelisted (perf timing, system clock provider, old migrations).
- Current-period UI/data flows are rollover-safe (ticker + flatMapLatest).
- Screens do not call `System.currentTimeMillis()` directly.

**Validation**:
```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest
rg "System.currentTimeMillis\(\)" app/src/main/java/ --include="*.kt" -l | grep -v SystemTimeProvider | grep -v AppDatabase | grep -v nanoTime | grep -v FinancialHealthScoreV2 | grep -v FinancialStressForecastEngine
# Should produce minimal/empty output (only whitelisted files)
```

---

### PR 10 — Test Infrastructure, Audit Closeout & Cleanup

**Purpose**: Fix test utilities, update test fixtures, add scan checks, and close out the audit.

**Files**:
- **modify**: `TestUtils.kt` (endOfMonth fix)
- **modify**: `FakeTimeProvider.kt` (enhancements)
- **modify**: ~50 test files with `System.currentTimeMillis()` fixtures
- **modify**: `docs/development/TIME_SEMANTICS.md` (update)
- **modify**: `docs/analyses and debug master/time-usage-audit.md` (mark resolved items)

**Actions**:

1. **Fix `TestUtils.endOfMonth()`** (line 233-241):
   ```kotlin
   // OLD: returns 23:59:59.999999999 of last day (INCLUSIVE — WRONG)
   // NEW: returns start of NEXT month (EXCLUSIVE — matches TimePeriodUtils)
   fun endOfMonth(year: Int, month: Int): Long {
       return LocalDate.of(year, month, 1)
           .plusMonths(1)
           .atStartOfDay(ZoneId.systemDefault())
           .toInstant()
           .toEpochMilli()
   }

   // OLD helper renamed for backward compat:
   @Deprecated("Use endOfMonth(). This returns exclusive end matching half-open convention.")
   fun endOfMonthInclusive(year: Int, month: Int): Long { ... }
   ```

2. **Update all test files calling `endOfMonth()`** to use the new half-open semantics. If any test was asserting inclusive-end behavior, update the assertion:
   - `HealthScoreGoldenTest.kt`
   - `HealthScoreEdgeCaseTest.kt`
   - `FinancialHealthCalculatorBoundaryTest.kt`
   - `FinancialHealthCalculatorTransactionTypeTest.kt`
   - And any other test using `startOfMonth()`/`endOfMonth()`

3. **Replace logical fixture `System.currentTimeMillis()` with `FakeTimeProvider`** in time-heavy tests:
   - `InsightsEngineTest.kt` (lines 34, 57, 72, 84)
   - `InsightsEngineEdgeCaseTest.kt` (lines 35, 136, 176)
   - `BudgetRepositoryStressTest.kt` (lines 64, 90, 108, 162+)
   - `ExpenseRepositoryStressTest.kt` (lines 109, 111, 160+)
   - `HomeViewModelStressTest.kt` (lines 364-419)
   - `BudgetViewModelStressTest.kt` (lines 96, 121, 145+)
   - `AnalyticsStateStressTest.kt` (lines 151-152)
   - `CanonicalMultiCurrencyFixture.kt` (lines 284, 344)
   - `TimePeriodUtilsStressTest.kt` (lines 635, 787)
   - `SpendingMapViewModelStressTest.kt` (lines 317, 437)
   - `ReceiptScanViewModelStressTest.kt` (line 90)
   - `TransactionsViewModelStressTest.kt` (line 252)

   **Leave performance/stress timing alone** if clearly using `System.nanoTime()` for perf measurement — these are whitelisted.

4. **Enhance `FakeTimeProvider.kt`**:
   - Add `forDate(year, month, day)` convenience that returns a `FakeTimeProvider` with midnight of that day
   - Add `forDateTime(year, month, day, hour, minute)` 
   - Improve KDoc with examples

5. **Add scan/grep checks to CI or build script** (suggested, not mandatory for this PR):
   - Production `System.currentTimeMillis` (outside whitelist)
   - `Instant.now` / `LocalDate.now` / `LocalDateTime.now` (outside whitelist)
   - Raw `/ DAY_IN_MILLIS` or `/ 86_400_000` (outside whitelist)
   - `23:59:59` (inclusive end anti-pattern)
   - `365L * 24 * 60 * 60 * 1000` (hard-coded year)

6. **Mark audit rows as resolved** in the time-usage-audit.md — only for verified fixes.

**Done when**:
- `TestUtils.endOfMonth()` is half-open.
- All test fixtures use `FakeTimeProvider` (not raw `System.currentTimeMillis()`).
- All time-heavy tests pass deterministically.
- No new test failures introduced.

**Validation**:
```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest
rg "endOfMonth\(" app/src/test/ --include="*.kt" -A2
# Verify all usages are updated to half-open
rg "System.currentTimeMillis\(\)" app/src/test/ --include="*.kt" -l
# Verify remaining usages are perf timing only
```

---

## 4. Risk Assessment

### High Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Entity constructor fan-out** (PR 3, 4) | Removing `System.currentTimeMillis()` default breaks all 80+ call sites that don't pass `createdAt`. The `= 0L` sentinel approach mitigates this, but still requires updating creation sites. | Batch entities by dependency (core first, leaf entities later). Use `= 0L` sentinel. Add debug assertions. |
| **`DateFormatterUtils` signature change** (PR 2) | Changing 13 method signatures from zero-arg to timestamp-accepting breaks ALL call sites (estimated 30-50 locations across screens, engines, repos). | Do this as its own isolated PR (PR 2). Search-and-replace with IDE assistance. Each call site replacement is mechanical. |
| **Rolling → Calendar migration** (PR 5) | `TransactionsViewModel` and `AnalyticsViewModel` period resolution changes user-visible behavior. Users will see different transaction sets for "Month" after this change. | Add explicit UI labels if needed. Consider a transitional flag (but probably unnecessary — the new behavior is what the label says). |

### Medium Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| **`BudgetForecastDao` query change** (PR 7) | Changing `>=` to `>` for end boundary could change forecast results — a budget forecast ending exactly at the query time would no longer match. | Review the actual data — `targetPeriodEnd` is likely start-of-day for the next period. The inclusive end was probably a bug. |
| **Reactivating flows** (PR 9) | Adding `flatMapLatest` to flows changes subscription behavior. Cold flows become hot — potential for duplicate emissions or subscription leaks. | Use `SharingStarted.WhileSubscribed(5000)` consistently. Test cancellation. |
| **`RecurrenceFrequency` constructor change** (PR 8) | Removing `days` from constructor changes serialization if enum ordinals or names are used anywhere. | Keep enum entries in same order. Verify no external storage of enum ordinals. |

### Low Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| **`TestUtils.endOfMonth()` fix** (PR 10) | Tests using old inclusive-end semantics will fail. | Audit all call sites during PR 10. Tests should be updated to expect half-open. |
| **New `TimePeriodUtils` helpers** (PR 1) | New code, no existing callers. | Add thorough tests including edge cases. |
| **Parser determinism** (PR 6) | None — this is a pure improvement. | Existing tests should still pass; add new tests for the new behavior. |

---

## 5. Dependencies

```
PR 0 (Baseline + Types)
  └── PR 1 (TimePeriodUtils new helpers)
        ├── PR 2 (DateFormatterUtils + NLSearch engine)
        ├── PR 3 (Entity Batch A)
        │     └── PR 4 (Entity Batch B)
        ├── PR 5 (Calendar vs Rolling fix)
        │     └── PR 9 (Screen cleanup) — depends on PR 5 having correct semantics
        ├── PR 6 (Parser determinism) — independent, can parallelize
        ├── PR 7 (DAO alignment) — independent, can parallelize
        └── PR 8 (Recurrence cleanup) — independent, can parallelize
              └── PR 10 (Test + audit closeout) — depends on all above

PRs 2, 6, 7, 8 are independently parallelizable after PR 1.
PR 3→4 must be sequential.
PR 5→9 should be sequential (screen cleanup after semantics fix).
PR 10 always comes last.
```

**Recommended execution order** (respecting dependencies but maximizing parallelism):
```
0 → 1 → 2 (parallel with 3) → 3 → 4 (parallel with 5) → 5 → 6,7,8 (parallel) → 9 → 10
```

---

## 6. Validation Commands per PR

After each PR, run:

```bash
# Compile (must pass)
./gradlew.bat :app:compileDebugKotlin

# Unit tests (must pass)
./gradlew.bat :app:testDebugUnitTest

# Targeted tests for changed modules
./gradlew.bat :app:testDebugUnitTest --tests "*<ModuleName>*"
```

After PR 10 (closeout), run the full suite and scan:

```bash
./gradlew.bat :app:testDebugUnitTest

# Grep scan for remaining violations
# Should show only whitelisted files
rg "System.currentTimeMillis\(\)" app/src/main/java/ --include="*.kt" -l | grep -v SystemTimeProvider | grep -v AppDatabase
rg "Instant\.now\(\)" app/src/main/java/ --include="*.kt" -l
rg "LocalDate\.now\(\)" app/src/main/java/ --include="*.kt" -l
rg "LocalDateTime\.now\(\)" app/src/main/java/ --include="*.kt" -l
rg "23:59:59" app/src/main/java/ --include="*.kt" -l
```

---

## 7. Acceptance Criteria (Phase 2 Complete)

- [ ] PR 0: `PeriodRange`, `PeriodKind` compile. Baseline documented. `TIME_SEMANTICS.md` exists.
- [ ] PR 1: All new helpers exist and are tested. `getLastNDaysRange` deprecated. Half-open contract verified.
- [ ] PR 2: `DateFormatterUtils` methods all accept timestamps. `NaturalLanguageSearchEngine` uses `TimeProvider`. Zero direct `Instant.now()` in these files.
- [ ] PR 3-4: No entity has `System.currentTimeMillis()` default. All creation sites explicitly pass `timeProvider.now()`. Room schema unchanged.
- [ ] PR 5: "This Month" = calendar month, not rolling 30 days. Week-end uses `getEndOfWeek()` not `+ 7 * DAY_IN_MILLIS`. No raw millis division in target files.
- [ ] PR 6: Parsers are deterministic. Invalid dates are errors not fallbacks. No `Calendar.getInstance().get(YEAR)` for current year in parsers.
- [ ] PR 7: All DAO date filters verified or fixed to half-open. Budget forecast uses strict `>` for end boundary.
- [ ] PR 8: `RecurrenceFrequency.days` removed from constructor. `intervalInMs` documented as deprecated. Magic zero sentinels eliminated from propagation.
- [ ] PR 9: Remaining `System.currentTimeMillis()` in UI/screens replaced. Flows are rollover-safe via `TimeBoundaryTicker`.
- [ ] PR 10: `TestUtils.endOfMonth()` is half-open. Test fixtures use `FakeTimeProvider`. All tests pass deterministically.
- [ ] Overall: `./gradlew.bat :app:compileDebugKotlin` passes.
- [ ] Overall: `./gradlew.bat :app:testDebugUnitTest` passes.
- [ ] Overall: Production grep for `System.currentTimeMillis()` (outside whitelist) returns empty.
- [ ] Overall: Production grep for `Instant.now()` / `LocalDate.now()` / `LocalDateTime.now()` (outside whitelist) returns empty.
- [ ] Overall: No entity has live `System.currentTimeMillis()` default.
- [ ] Overall: No file uses `23:59:59` or `23:59:59.999` for period end boundaries.
- [ ] Overall: No file uses `365L * 24 * 60 * 60 * 1000` for year calculations.

---

## 8. Whitelist (Allowed Clock Sources)

Files/methods that are **explicitly allowed** to use raw system clocks:

| File | Usage | Reason |
|------|-------|--------|
| `SystemTimeProvider.kt` | `System.currentTimeMillis()` | Single production clock implementation |
| `TimeProvider.kt` | `nowFormatted()` default impl | Delegates to `now()` |
| `AppDatabase.kt` | `System.currentTimeMillis()` in old migrations | Do NOT touch old migration code |
| `NotificationCaptureService.kt` | `SystemClock.elapsedRealtime()` | Android elapsed realtime scheduling — allowed |
| `SettlementCalculator.kt` | `System.nanoTime()` | Performance timeout — allowed |
| `FinancialHealthScoreV2.kt` | `System.currentTimeMillis()` for perf timing | Performance measurement — allowed |
| `FinancialStressForecastEngine.kt` | `System.currentTimeMillis()` for perf timing | Performance measurement — allowed |
| All WorkManager `PeriodicWorkRequestBuilder` | Duration scheduling | Allowed — infrastructure scheduling, not logical time |
| `AppConstants.kt` | `DUPLICATE_DETECTION = 300_000L` | Duration constant, not calendar period — allowed |
| Any `System.nanoTime()` for performance measurement | Perf timing | Allowed |

---

## 9. Files NOT Touched in Phase 2

These files are explicitly excluded to minimize blast radius:

| File/Group | Reason |
|------------|--------|
| `AppDatabase.kt` old migrations | Risk of corrupting existing user databases |
| `NotificationCaptureService.kt` | Android system scheduling — different concern |
| WorkManager workers (`*Worker.kt`) | Scheduling infrastructure — Phase 8 concern |
| `BootReceiver.kt`, `ServiceRestartReceiver.kt` | Android system integration |
| UI component files not listed in PR 9 | Only screen-level files with direct `System.currentTimeMillis()` are included |
| `CsvExpenseImporter.kt` (beyond date fallback fix) | Full importer rewrite out of scope |

---

## 10. Summary of Changes from Template Plan

| Template PR | This Plan | Change |
|-------------|-----------|--------|
| PR 0 — Baseline | PR 0 — Baseline + Foundation Types | Added `PeriodRange`/`PeriodKind` introduction |
| PR 1 — TimePeriodUtils | PR 1 — same | Added 3 new explicit helpers, `parseMonthKeyToRange`, KDoc hardening |
| PR 2A — Entity timestamps | PR 3 + PR 4 (split) | Split 30+ entities into two batches. Use `= 0L` sentinel instead of removing defaults. |
| PR 2B — Utilities/repos/UI | PR 2 (DateFormatterUtils + NLSearch) + PR 9 (Screens) | Split into two PRs for manageability |
| PR 3 — Parser determinism | PR 6 — same | Minor reorder (independent of other PRs) |
| PR 4 — Calendar vs Rolling | PR 5 — same | Corrected dependency: this needs PR 1's new helpers |
| PR 5 — DAO alignment | PR 7 — same | Minor reorder |
| PR 6 — Recurrence cleanup | PR 8 — same | Minor reorder |
| PR 7 — Reactive flows | PR 9 (merged with screen cleanup) | Combined with remaining System.currentTimeMillis() cleanup since both touch UI/screen files |
| PR 8 — Date formatting modernization | Distributed across PRs 2, 9 | `SimpleDateFormat` migration is out of scope for Phase 2 except where we touch those files |
| PR 9 — Test infrastructure | PR 10 — same | Added scan/grep checks |

---

*End of Implementation Plan*

@orchestrator The Advanced Technical Plan is ready. Please begin execution of Batch 1.
