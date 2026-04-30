# Time Usage Audit — Full Codebase Inventory

## Summary Statistics
- **Total files with time-related code**: 180+ files across main and test sources
- **Direct `System.currentTimeMillis()` callers**: ~120+ locations across 40+ files (main source: ~30 files test source: ~50+ files)
- **Direct `LocalDate.now()` / `Instant.now()` callers**: 21 locations in main source (DateFormatterUtils, NaturalLanguageSearchEngine, AccountingExportRepository)
- **Time utility files**: 5 (TimePeriodUtils, DateFormatterUtils, TimeProvider, SystemTimeProvider, TimeBoundaryTicker)
- **Time injection/setup files**: 2 (TimeModule.kt, FakeTimeProvider.kt)
- **Entity/DTO files with time fields**: 35+ entity files with Long timestamp fields
- **DAO files with time-filtered queries**: 20+ DAO files
- **Scheduling/Worker files**: 6 worker/service files
- **DateFormat/SimpleDateFormat usages**: 25+ files using various date format patterns

---

## 1. Time Utility Classes

### 1.1 `TimePeriodUtils.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`
**Lines**: 699 lines
**Summary**: The canonical owner of all shared calendar boundary math. Uses `java.util.Calendar` extensively (NOT `java.time`). Follows half-open `[start, end)` contract.

**Key functions**:
| Function | Lines | Description |
|---|---|---|
| `DAY_IN_MILLIS` | 50 | `24L * 60L * 60L * 1000L` — warns to prefer calendar-aware helpers |
| `isInRange()` | 65-67 | Half-open containment check |
| `getStartOfDay()` | 77-85 | Uses `Calendar.getInstance()` |
| `getEndOfDay()` | 94-99 | Start of next day, `Calendar.add(DAY_OF_MONTH, 1)` |
| `getDayRange()` | 108-112 | Pair wrapper |
| `getStartOfWeek()` | 124-138 | Monday-start, locale-independent via delta logic |
| `getEndOfWeek()` | 146-151 | Calendar-add 7 days |
| `getWeekRange()` | 163-189 | With weekOffset support |
| `getCanonicalWeekRangeFromKey()` | 201-217 | From SQLite week key |
| `getStartOfMonth()` | 243-252 | Calendar-set DAY_OF_MONTH=1 |
| `getEndOfMonth()` | 260-265 | Calendar-add 1 month |
| `getMonthRange(timestamp)` | 274-284 | With monthOffset |
| `getMonthRange(year, month)` | 293-304 | Year/month overload |
| `formatMonthKey()` | 309-323 | `yyyy-MM` format |
| `buildMonthKeyRange()` | 342-369 | Inclusive month key range |
| `getStartOfQuarter()` | 379-391 | Month/3*3 logic |
| `getEndOfQuarter()` | 397-402 | Calendar-add 3 months |
| `getQuarterRange()` | 411-421 | With quarterOffset |
| `getStartOfYear()` | 430-440 | Calendar-set JANUARY 1 |
| `getEndOfYear()` | 446-451 | Calendar-add 1 year |
| `getYearRange()` | 460-470 | With yearOffset |
| `getLastNDaysRange()` | 504-509 | Calendar-add -days, start is day-aligned |
| `getDaysRemainingInMonth()` | 518-523 | Calendar DAY_OF_MONTH calc |
| `getDayOfMonth()` | 528-531 | Calendar get |
| `getDaysInMonth()` | 536-539 | Calendar getActualMaximum |
| `getDayIndexFromMonthStart()` | 545-548 | DAY_OF_MONTH - 1 |
| `isSameMonth()` | 553-558 | Year+Month comparison |
| `addMonths()` | 568-572 | Calendar.add(MONTH) |
| `addDays()` | 578-582 | Calendar.add(DAY_OF_MONTH) |
| `addYears()` | 587-591 | Calendar.add(YEAR) |
| `getYear()` | 600-603 | Calendar.get(YEAR) |
| `getMonth()` | 608-611 | Calendar.get(MONTH) — 0-based! |
| `getWeekOfYear()` | 632-639 | Monday-firstDay, minDaysInFirstWeek=1 |
| `getWeekBasedYear()` | 657-662 | ISO week-based year via ZoneId |
| `getDayOfWeek()` | 667-670 | Calendar.get(DAY_OF_WEEK) |
| `getHourOfDay()` | 675-678 | Calendar.get(HOUR_OF_DAY) |
| `daysBetween()` | 693-698 | DST-safe via LocalDate |

**Anti-patterns found**:
- Uses `Calendar.getInstance()` in EVERY function (no DI, call creates new instance)
- Mixes `java.util.Calendar` (legacy) with `java.time` (ZoneId, Instant, LocalDate)
- `getMonth()` returns 0-based month (Calendar.JANUARY=0) — callers must +1
- `getLastNDaysRange()` end is raw `now`, not day-aligned (asymmetric with start)
- `getWeekOfYear()` with `minimalDaysInFirstWeek=1` differs from ISO 8601 (min 4 days)
- No `TimeProvider` injection — always uses real system clock internally via `Calendar.getInstance()`

### 1.2 `DateFormatterUtils.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/util/DateFormatterUtils.kt`
**Lines**: 60 lines
**Summary**: Thread-safe cached DateTimeFormatter utility. Provides convenience format methods.

**Key functions**:
| Function | Lines | Description |
|---|---|---|
| `javaTime()` | 31-38 | Creates/caches DateTimeFormatter with ZoneId.systemDefault() |
| `javaTimeMonthDay()` | 41 | `Instant.now().atZone(ZoneId.systemDefault())` — **Direct Instant.now() call!** |
| `javaTimeMonthDayShort()` | 42 | Same pattern |
| `javaTimeFullDate()` | 43 | Same pattern |
| ... | 44-53 | **ALL 13 convenience methods call `Instant.now()` directly** |
| `formatTimestampJavaTime()` | 55-59 | Format arbitrary timestamp |

**Anti-patterns found**:
- **All 13 convenience methods call `Instant.now()` directly** — not injectable, not testable
- Hard-coded `ZoneId.systemDefault()` in formatter creation
- Should accept a `TimeProvider` or be parameterized

### 1.3 `TimeProvider.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/util/TimeProvider.kt`
**Lines**: 30 lines
**Summary**: Interface to abstract system time. Production impl is `SystemTimeProvider`.

```kotlin
interface TimeProvider {
    fun now(): Long
    fun nowFormatted(): String {
        return DateFormatterUtils.formatTimestampJavaTime(now(), "yyyy-MM-dd HH:mm")
    }
}
```

### 1.4 `SystemTimeProvider.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/util/SystemTimeProvider.kt`
**Lines**: 13 lines
**Summary**: Production impl — delegates to `System.currentTimeMillis()`.

```kotlin
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
```

### 1.5 `TimeBoundaryTicker.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/util/TimeBoundaryTicker.kt`
**Lines**: 74 lines
**Summary**: Cold Flow that emits at day boundaries for reactive time updates.

**Key code**:
```kotlin
fun dayBoundaryTicks(): Flow<Long> = flow {
    while (currentCoroutineContext().isActive) {
        val now = timeProvider.now()
        emit(now)
        val nextDayStart = TimePeriodUtils.getEndOfDay(now)
        val sleepMs = (nextDayStart - now + MARGIN_MS).coerceAtLeast(1L)
        delay(sleepMs)
    }
}
```
- Uses `TimeProvider` (injectable) ✓
- Uses `TimePeriodUtils.getEndOfDay()` for DST-safe boundary ✓

### 1.6 `TimeModule.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/di/TimeModule.kt`
**Lines**: 22 lines
**Summary**: Dagger/Hilt module binding `TimeProvider` to `SystemTimeProvider`.

### 1.7 `AppConstants.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/util/AppConstants.kt`
**Lines**: 27 lines
**Summary**: Time window constants:
- `DUPLICATE_DETECTION = 300_000L` (5 min)
- `NOTIFICATION_LRU_MAX_AGE = 30 * 60 * 1000L` (30 min)

### 1.8 `FakeTimeProvider.kt` (Test Only)
**Path**: `app/src/test/java/com/yourname/expensetracker/domain/util/FakeTimeProvider.kt`
**Lines**: 45 lines
**Summary**: Controllable test double for TimeProvider with `setTime()`, `advanceTime()`, and `forDate()` factory.

---

## 2. Direct `System.currentTimeMillis()` Callers

### Main Source Files (Production Code)

| # | File | Lines | Code |
|---|---|---|---|
| 1 | `SystemTimeProvider.kt` | 12 | `override fun now(): Long = System.currentTimeMillis()` |
| 2 | `Expense.kt` | 68 | `val createdAt: Long = System.currentTimeMillis()` |
| 3 | `Budget.kt` | 52 | `val createdAt: Long = System.currentTimeMillis()` |
| 4 | `SpendingChallengeEntity.kt` | 42, 44 | `createdAt/updatedAt: Long = System.currentTimeMillis()` |
| 5 | `SavingsGoal.kt` | 20 | `val createdAt: Long = System.currentTimeMillis()` |
| 6 | `PlannedExpense.kt` | 35 | `val createdAt: Long = System.currentTimeMillis()` |
| 7 | `ExchangeRate.kt` | 33 | `val lastUpdated: Long = System.currentTimeMillis()` |
| 8 | `BudgetForecast.kt` | 60 | `val createdAt: Long = System.currentTimeMillis()` |
| 9 | `BudgetAdjustmentRecommendation.kt` | 59, 100 | `generatedAt/appliedAt: Long = System.currentTimeMillis()` |
| 10 | `SavingsSweepPlan.kt` | 76 | `val computedAt: Long = System.currentTimeMillis()` |
| 11 | `StressForecastSnapshot.kt` | 84 | `val computedAt: Long = System.currentTimeMillis()` |
| 12 | `PendingReview.kt` | 60 | `val createdAt: Long = System.currentTimeMillis()` |
| 13 | `UserCorrection.kt` | 45 | `val createdAt: Long = System.currentTimeMillis()` |
| 14 | `BankConnection.kt` | 58 | `val createdAt: Long = System.currentTimeMillis()` |
| 15 | `MerchantLocationCorrection.kt` | 68 | `val createdAt: Long = System.currentTimeMillis()` |
| 16 | `MerchantAlias.kt` | 33 | `val createdAt: Long = System.currentTimeMillis()` |
| 17 | `MerchantCanonical.kt` | 35-36 | `createdAt/updatedAt: Long = System.currentTimeMillis()` |
| 18 | `ManualRecurringExpense.kt` | 26 | `val createdAt: Long = System.currentTimeMillis()` |
| 19 | `Warranty.kt` | 49-50 | `createdAt/updatedAt: Long = System.currentTimeMillis()` |
| 20 | `SubscriptionCandidate.kt` | 64, 67 | `createdAt/updatedAt: Long = System.currentTimeMillis()` |
| 21 | `SplitItemAssignment.kt` | 34 | `val createdAt: Long = System.currentTimeMillis()` |
| 22 | `SplitTemplate.kt` | 23-24 | `createdAt/updatedAt: Long = System.currentTimeMillis()` |
| 23 | `ScannedReceipt.kt` | 56 | `val createdAt: Long = System.currentTimeMillis()` |
| 24 | `ReceiptItemCategorization.kt` | 52-53 | `createdAt/updatedAt: Long = System.currentTimeMillis()` |
| 25 | `PromptState.kt` | 23 | `val createdAt: Long = System.currentTimeMillis()` |
| 26 | `MileageTracking.kt` | 63 | `val createdAt: Long = System.currentTimeMillis()` |
| 27 | `InvestmentValue.kt` | 31 | `val timestamp: Long = System.currentTimeMillis()` |
| 28 | `Investment.kt` | 47 | `val createdAt: Long = System.currentTimeMillis()` |
| 29 | `ExpenseGroup.kt` | 26 | `val createdAt: Long = System.currentTimeMillis()` |
| 30 | `AiChatSessionEntity.kt` | 17-18 | `createdAt/updatedAt: Long = System.currentTimeMillis()` |
| 31 | `AiChatMessageEntity.kt` | 33 | `val createdAt: Long = System.currentTimeMillis()` |
| 32 | `AppDatabase.kt` | 438, 1254 | `val now = System.currentTimeMillis()` (migration code) |
| 33 | `AnalyticsModels.kt` | 151 | `val generatedAt: Long = System.currentTimeMillis()` |
| 34 | `ReceiptParser.kt` | 734 | `val daysDiff = (System.currentTimeMillis() - date) / (1000 * 60 * 60 * 24)` |
| 35 | `AppParserRegistry.kt` | 66 | `require(it <= System.currentTimeMillis() + 86_400_000)` |
| 36 | `GenericTransactionParser.kt` | 308 | `if (ts in 1..(System.currentTimeMillis() + 86_400_000)) return ts` |
| 37 | `DocumentParser.kt` | (likely) | Date validation against current time |
| 38 | `FinancialHealthScoreV2.kt` | 82, 189 | `System.currentTimeMillis()` for performance timing |
| 39 | `FinancialStressForecastEngine.kt` | 66, 134 | `System.currentTimeMillis()` for performance timing |
| 40 | `SettlementCalculator.kt` | 169, 178 | `System.nanoTime()` for DFS timeout |
| 41 | `AccountantReportPdfExporter.kt` | 42 | `formatTimestamp(System.currentTimeMillis())` |
| 42 | `HomeScreen.kt` | 409, 520, 707, 732, 1151 | Multiple `System.currentTimeMillis()` calls |
| 43 | `BudgetScreen.kt` | 480, 750, 892 | `System.currentTimeMillis()` for period UI |
| 44 | `AnalyticsScreen.kt` | 558 | `(it - System.currentTimeMillis()) / (1000*60*60*24)` — raw millis days-until |
| 45 | `StatisticalVisualizations.kt` | 534 | Same raw millis days-until pattern |
| 46 | `SubscriptionManagementScreen.kt` | 850 | `System.currentTimeMillis()` as default date |
| 47 | `ManualRecurringExpenseScreen.kt` | 338, 463 | `System.currentTimeMillis()` for date comparisons |
| 48 | `SpendingMapScreen.kt` | 243 | `val now = System.currentTimeMillis()` |
| 49 | `RecurringExpensesScreen.kt` | 102 | `System.currentTimeMillis() - (365L * 24 * 60 * 60 * 1000)` — raw year math |
| 50 | `BudgetViewModel.kt` | 242, 325, 343 | `generatedAt = System.currentTimeMillis()` |
| 51 | `HomeScreen.kt` | 101 | `Calendar.getInstance().get(Calendar.YEAR)` |
| 52 | `ReceiptParser.kt` | 599 | `Calendar.getInstance().get(Calendar.YEAR)` |
| 53 | `BankStatementParser.kt` | 686, 734 | `Calendar.getInstance().get(Calendar.YEAR)` |
| 54 | `BudgetForecastingEngine.kt` | 58 | `((periodEnd - elapsedEnd) / MILLIS_PER_DAY)` — raw millis division for days |

### Test Source Files (Representative Selection)
Tests use `System.currentTimeMillis()` extensively for fixture creation, mock setup, and stress testing. Key examples:

| File | Lines | Usage |
|---|---|---|
| `AnalyticsViewModelStressTest.kt` | 84, 88, 116, 203 | Mock setup + fixture creation |
| `InsightsEngineTest.kt` | 34, 57, 72, 84 | Mock setup + `daysAgo * dayMs` |
| `InsightsEngineEdgeCaseTest.kt` | 35, 136, 176 | `daysAgo * 86_400_000L` |
| `AnalyticsStressTest.kt` | 88, 92 | `System.nanoTime()` for perf |
| `BudgetRepositoryStressTest.kt` | 64, 90, 108, 162+ | Multiple `startDate = System.currentTimeMillis()` |
| `ExpenseRepositoryStressTest.kt` | 109, 111, 160+ | Fixture creation |
| `HomeViewModelStressTest.kt` | 364-419 | Multiple fixture dates |
| `BudgetViewModelStressTest.kt` | 96, 121, 145+ | `startDate = System.currentTimeMillis()` |
| `AnalyticsStateStressTest.kt` | 151-152 | `System.currentTimeMillis()` ± 30 days |
| `CanonicalMultiCurrencyFixture.kt` | 284, 344 | Fixture creation |
| `TimePeriodUtilsStressTest.kt` | 635, 787 | Random timestamp generation |
| `StringDistanceUtilsStressTest.kt` | 31, 33, 153, 161 | `System.nanoTime()` for perf |
| `CategorizationEngineStressTest.kt` | 500, 621 | `System.currentTimeMillis()` for perf |
| `TransactionRollbackTest.kt` | 454 | `System.currentTimeMillis()` for timeout |
| `SpendingMapViewModelStressTest.kt` | 317, 437 | Fixture dates |
| `ReceiptScanViewModelStressTest.kt` | 90 | Mock setup |
| `TransactionsViewModelStressTest.kt` | 252 | Date range setup |

---

## 3. Direct `LocalDate.now()` / `Instant.now()` Callers

### Main Source Files

| # | File | Lines | Code |
|---|---|---|---|
| 1 | `DateFormatterUtils.kt` | 41-53 | **ALL 13 convenience methods** call `Instant.now()` directly |
| 2 | `AccountingExportRepository.kt` | 79 | `LocalDateTime.now()` |
| 3 | `NaturalLanguageSearchEngine.kt` | 25, 39, 54-56, 66, 328 | Multiple `LocalDate.now()` calls |
| 4 | `DashboardBriefingPromptFormatter.kt` | (uses Instant.now) | Date formatting for AI prompts |
| 5 | `HomeViewModel.kt` | 728 | `SimpleDateFormat(...).format(Date(now))` — using current time |

### Test Source Files

| File | Lines | Usage |
|---|---|---|
| `AdvancedAnalyticsEngineDeepTest.kt` | 238 | `LocalDate.of(2025,5,1).plusMonths(idx)` |
| `MonthlyComparisonCalculatorTest.kt` | 5, 23+ | `startOfMonth()` utility that uses LocalDate |
| `DayOfWeekAnalyzerTest.kt` | 5, 19+ | `startOfMonth()` utility |
| `CategoryInsightEngineTest.kt` | 6, 27+ | `startOfMonth()` utility |
| `FinancialHealthCalculatorTransactionTypeTest.kt` | 38 | `LocalDate.of().atZone(ZoneId.systemDefault())` |
| `FinancialHealthCalculatorBoundaryTest.kt` | 38 | `LocalDate.of().atZone(ZoneId.systemDefault())` |
| `AdvancedAnalyticsEngineDeepTest.kt` | 301-304 | `LocalDate.of().atStartOfDay(ZoneId.systemDefault()).toEpochMilli()` |

---

## 4. Period / Date Range Calculation Code

### 4.1 `TimePeriodUtils.kt` (Central)
Already covered in Section 1.1. All period boundaries are calendar-aware via `java.util.Calendar`.

### 4.2 `BudgetCalculator.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt`
**Lines**: 165 lines
**Summary**: TWO period calculation modes:
- **ROLLING**: Uses anchor-based arithmetic with `Calendar` for DAILY, WEEKLY, MONTHLY, YEARLY
- **CALENDAR**: Delegates to `TimePeriodUtils.getDayRange/getWeekRange/getMonthRange/getYearRange`

**Key anti-pattern**: Line 58 — `remainingForecastDays = ((periodEnd - elapsedEnd).coerceAtLeast(0L) / MILLIS_PER_DAY)` — raw division for days calculation (not DST-safe). This is in `BudgetForecastingEngine.kt` line 58.

### 4.3 `AdvancedAnalyticsEngine.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
**Lines**: ~940 lines
**Key functions**:
- Lines 85-86: `getStartOfWeek(referenceDate)` + `7 * DAY_IN_MILLIS` — NOT using `getEndOfWeek`!
- Line 516: `periodDays = ((period.endMs - period.startMs) / DAY_IN_MILLIS).toInt()` — raw division
- Line 740: `diff = (sorted[i] - sorted[i-1]) / DAY_IN_MILLIS` — raw division
- Line 752: Same pattern
- Lines 124-127: `addDays` / `addMonths` / `addYears` for period navigation
- Lines 96-114: Month/Quarter/Year range via TimePeriodUtils

**Anti-pattern**: Line 86 uses `7 * DAY_IN_MILLIS` instead of `getEndOfWeek()`, which is inconsistent with the half-open contract of TimePeriodUtils.

### 4.4 `FinancialHealthCalculator.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
- Line 117: `getDayRange(now)`
- Line 166: `getWeekRange(now)`
- Line 221: `getMonthRange(now)`

### 4.5 `SpendingPaceCalculator.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt`
- Line 28: `getEndOfMonth(currentMonthStart)`
- Line 30: `getDaysInMonth(currentMonthStart)`
- Line 31: `daysBetween(currentMonthStart, currentWindowEnd) + 1`

### 4.6 `ComputeDashboardWidgetsUseCase.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- Lines 534, 632, 812, 820, 838: Multiple `daysBetween()` and day-of-month calculations
- Lines 790-791, 820-821: Elapsed days this month via `Calendar.DAY_OF_MONTH`
- Lines 505-526: Series of Calendar instances for trend calculation

### 4.7 `ComputeMoneyRadarUseCase.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- Lines 192, 251: `getStartOfDay(now)`
- Lines 263, 387: `getMonthRange(now)`
- Lines 201, 231: `daysBetween()` for days-until and days-ago
- Line 231: `((now - alert.alertedAt) / ONE_DAY_MS).toInt()` — raw millis division

### 4.8 `SmartSavingsEngine.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt`
- Line 307: `endOfLastCompleteMonth = getStartOfMonth(now) - 1L` — subtracting 1ms from month start (slightly odd but works for half-open)
- Lines 553-556: Year/month extraction for iteration

### 4.9 `InsightsEngine.kt`
- Line 280: `getMonthRange(timeMs, monthOffset)`
- Lines 516, 523: Date key formatting
- Line 583: Format for display

### 4.10 `AnalyticsRepository.kt`
- Line 78: `daysBetween(start, end)`
- Line 79: `daysBetween(previousStart, previousEnd)`
- Lines 102, 108: Day index calculations

### 4.11 `BudgetRepository.kt`
- Line 358: `getEndOfDay(now)`
- Line 363: `getLastNDaysRange(now, 90)` 
- Line 369: `ChronoUnit.DAYS.between(startDate, endDateExclusive)` — Java Time usage

### 4.12 `SynthesisEngine.kt`
- Lines 122-143: Month range + day range for monthly synthesis
- Lines 294, 308, 330-331: Same pattern
- Lines 399, 441, 456: Day-of-month / quarter boundary checks

### 4.13 `ForecastInputAssembler.kt`
- Line 200: `daysBetween(monthStart, now)`
- Line 208: `daysBetween(monthStart, expense.date)`
- Line 226: `daysBetween(currentMonthStart, now) + 1`

### 4.14 `TestUtils.kt` (Test global utility)
**Path**: `app/src/test/java/com/yourname/expensetracker/TestUtils.kt`
- Lines 223-228: `startOfMonth(year, month)` — uses `LocalDate.of().atStartOfDay(ZoneId.systemDefault()).toEpochMilli()`
- Lines 233-239: `endOfMonth(year, month)` — uses `23:59:59.999999999` (LAST MILLISECOND — INCONSISTENT with half-open convention!)

**Anti-pattern**: `endOfMonth()` in TestUtils uses last millisecond of month, which is INCONSISTENT with `TimePeriodUtils`'s half-open `[start, end)` convention. This could cause off-by-one bugs in tests.

---

## 5. Entity/DTO Classes with Timestamp Fields

### 5.1 Main Entities with `date` field (primary event timestamp):
| Entity | Field Name | Type | Default |
|---|---|---|---|
| `Expense` | `date` | `Long` | (required) |
| `Expense` | `createdAt` | `Long` | `System.currentTimeMillis()` |
| `PlannedExpense` | `date` | `Long` | (required) |
| `PlannedExpense` | `createdAt` | `Long` | `System.currentTimeMillis()` |
| `Budget` | `startDate` | `Long` | (required, anchor date) |
| `Budget` | `createdAt` | `Long` | `System.currentTimeMillis()` |
| `Budget` | `lastWarningNotifiedAt` | `Long?` | null |
| `Budget` | `lastCriticalNotifiedAt` | `Long?` | null |
| `Budget` | `lastExceededNotifiedAt` | `Long?` | null |
| `BudgetForecast` | `createdAt` | `Long` | `System.currentTimeMillis()` |
| `BudgetAdjustmentRecommendation` | `generatedAt` | `Long` | `System.currentTimeMillis()` |
| `BudgetAdjustmentRecommendation` | `appliedAt` | `Long` | `System.currentTimeMillis()` |

### 5.2 Entities with `createdAt`/`updatedAt` timestamp fields:
| Entity | Fields |
|---|---|
| `SpendingChallengeEntity` | `startDate`, `endDate`, `createdAt`, `updatedAt` |
| `SavingsGoal` | `createdAt` |
| `SavingsSweepPlan` | `planMonth`, `computedAt`, `actionedAt` |
| `StressForecastSnapshot` | `computedAt` |
| `PendingReview` | `createdAt` |
| `UserCorrection` | `createdAt` |
| `BankConnection` | `createdAt`, `lastSync` |
| `MerchantLocationCorrection` | `createdAt` |
| `MerchantAlias` | `createdAt`, `lastUsedAt` |
| `MerchantCanonical` | `createdAt`, `updatedAt` |
| `ManualRecurringExpense` | `createdAt`, `nextDate` |
| `Warranty` | `createdAt`, `updatedAt`, `warrantyEndDate` |
| `SubscriptionCandidate` | `createdAt`, `updatedAt` |
| `ReturnWindow` | `createdAt`, `updatedAt`, `returnDeadline` |
| `SplitItemAssignment` | `paidAt`, `createdAt` |
| `SplitTemplate` | `createdAt`, `updatedAt` |
| `ScannedReceipt` | `createdAt` |
| `ReceiptItemCategorization` | `createdAt`, `updatedAt` |
| `PromptState` | `createdAt` |
| `MileageTracking` | `createdAt` |
| `InvestmentValue` | `timestamp` |
| `Investment` | `createdAt` |
| `ExpenseGroup` | `createdAt` |
| `AiChatSessionEntity` | `createdAt`, `updatedAt` |
| `AiChatMessageEntity` | `createdAt` |
| `AiArtifactEntity` | `createdAt`, `updatedAt` |
| `RecommendationEntity` | `createdAt`, `updatedAt` |
| `RawNotification` | `timestamp` |
| `ExchangeRate` | `lastUpdated` |
| `SpendingPersonalityProfileEntity` | `lastUpdated`, `periodStart`, `periodEnd` |

### 5.3 Entities with `periodStart`/`periodEnd` (range fields):
| Entity | Fields |
|---|---|
| `SpendingPersonalityProfileEntity` | `periodStart: Long`, `periodEnd: Long` |
| `HealthScoreHistory` | `periodStart`, `periodEnd`, `calculatedAt` |
| `BudgetForecast` | `targetPeriodStart`, `targetPeriodEnd` |

---

## 6. SQL Queries with Time Filtering

### 6.1 `ExpenseDao.kt` — Most extensive time-filtered queries
| Line | Query Pattern |
|---|---|
| 154 | `WHERE date >= :startMs AND date < :endMs ORDER BY date DESC` |
| 191 | `WHERE date >= :startDate AND date < :endDate AND isNotMine = 0` |
| 197 | With transactionType filter |
| 207 | Date range only |
| 213 | With transactionType |
| 219 | `WHERE date >= :since` |
| 897 | Paged date range |
| 900 | ASC order variant |
| 903 | `COUNT(*)` for date range |
| 919-925 | Typed variants with date range |
| 1221 | `SELECT MIN(date) FROM expenses` (oldest date) |
| 1447-1454 | Deposit-specific date range queries |
| 1555 | `latitude IS NULL ORDER BY date DESC` |

### 6.2 Other DAOs with time queries:
| DAO | Key Queries |
|---|---|
| `WarrantyDao` | `WHERE warrantyEndDate < :currentTime AND status = 'ACTIVE'` (line 56) |
| `ReturnWindowDao` | `WHERE returnDeadline < :currentTime AND status = 'RETURNABLE'` (line 55) |
| `InvestmentValueDao` | `WHERE timestamp >= :startDate AND timestamp < :endDate` (lines 27, 30) |
| `InvestmentValueDao` | `WHERE timestamp < :olderThan` (line 33 — DELETE) |
| `InvestmentValueDao` | `WHERE timestamp >= :startDate` (lines 36, 39, 42 — AVG/MIN/MAX) |
| `BudgetDao` | `UPDATE budgets SET lastWarningNotifiedAt = :timestamp` (line 165) |
| `BudgetForecastDao` | `WHERE targetPeriodStart <= :date AND targetPeriodEnd >= :date` (line 27) |
| `ManualRecurringExpenseDao` | `WHERE nextDate <= :date AND isActive = 1` (lines 68, 71) |
| `SavingsSweepPlanDao` | `WHERE status = 'PENDING' AND planMonth <= :targetMonth` (line 93+) |
| `PromptStateDao` | `WHERE createdAt < :olderThanTimestamp` (line 63) |
| `SpendingPersonalityProfileDao` | `ORDER BY lastUpdated DESC` multiple |
| `BankConnectionDao` | `UPDATE ... SET lastSync = :timestamp` (line 39) |
| `SplitItemAssignmentDao` | `UPDATE ... SET isPaid = 1, paidAt = :timestamp` (line 30) |
| `SplitTemplateDao` | `UPDATE ... SET useCount = useCount + 1, updatedAt = :timestamp` (line 27) |
| `MerchantNormalizationDao` | `UPDATE ... SET totalOccurrences = totalOccurrences + 1, ... updatedAt = :timestamp` (line 39) |
| `ScannedReceiptDao` | `WHERE createdAt >= :since ORDER BY createdAt DESC LIMIT :limit` (line 52) |
| `HealthScoreHistoryDao` | Complex period query at line 46 |

---

## 7. Time-Related Settings / Preferences

### 7.1 `CurrencySettingsRepositoryImpl.kt`
- Line 56: `prefs[LAST_RATE_UPDATE_KEY] = timestamp` — SharedPreferences timestamp tracking

### 7.2 `AutomatedSavingsRuleStateRepository.kt`
- Lines 33, 128, 140, 162: `pruneState(readState(preferences), timeProvider.now())` — DataStore pruning by time

### 7.3 `SavingsContributionHistoryRepository.kt`
- Lines 51, 66: `pruneState(readState(preferences), timeProvider.now())` — DataStore pruning by time

### 7.4 No explicit timezone or date-format user preferences found in the codebase.
Timezone is always `ZoneId.systemDefault()` — implicitly from the device/JVM.

---

## 8. Date Formatting and Display

### 8.1 `SimpleDateFormat` usages (legacy Android):
| File | Pattern | Lines |
|---|---|---|
| `HomeViewModel.kt` | `"yyyy-MM-dd"` (dateKeyFormat), `"MMM yyyy"` (monthLabel) | 148, 728 |
| `SubscriptionManagementScreen.kt` | `"MMM dd"`, `"MMM dd, yyyy"` | 439, 713 |
| `SavingsGoalsScreen.kt` | `"MMM yyyy"`, `"dd MMM yyyy"`, `"MMM d"` | 40, 377, 705 |
| `BillRemindersScreen.kt` | `"MMM dd"` | 122 |
| `ManualRecurringExpenseScreen.kt` | `"MMM dd"`, `"MMM dd, yyyy"` | 337, 521 |
| `SharedExpenseGroupsScreen.kt` | `"MMM dd"` | 589 |
| `CashFlowCalendarScreen.kt` | `"MMMM yyyy"`, `"EEE, MMM d, yyyy"` | 39-40 |
| `BudgetScreen.kt` | `"MMMM yyyy"` | 450 |
| `AnalyticsViewModel.kt` | `"MMM dd, yyyy HH:mm"`, `"EEE MMM dd"` | 488, 677 |
| `AnalyticsScreen.kt` | `"MMM dd"`, `"MMM dd, HH:mm"`, `"MMM dd"` | 1245, 1548, 1690 |
| `MoneyRadarWidget.kt` | `"MMM d"` | 432 |
| `FinancialStressForecastCard.kt` | `"MMM d"` | 372 |
| `ReceiptParser.kt` | `"dd/MM/yyyy"` | 589 |
| `BankStatementParser.kt` | `"MMM d yyyy"`, `"dd/MM/yyyy HH:mm:ss"`, `"dd/MM/yyyy"` | 377, 471, 500, 683, 731 |
| `HomeScreen.kt` | `"MMM dd"` | 1091, 1128 |
| `TransactionsScreen.kt` | (import) | 70 |

### 8.2 `DateTimeFormatter` usages (java.time):
| File | Pattern | Lines |
|---|---|---|
| `DateFormatterUtils.kt` | `"MMM dd"`, `"MMM d"`, `"EEE, dd MMM yyyy"`, `"MMM dd, HH:mm"`, `"MMMM yyyy"`, `"HH:mm"`, `"HH:mm:ss"`, `"HH:mm:ss dd/MM"`, `"dd/MM/yyyy"`, `"dd/MM/yyyy HH:mm"`, `"yyyy-MM-dd'T'HH:mm:ss"`, `"yyyy-MM-dd"`, `"EEEE, MMMM d, yyyy"` | 41-53 |
| `PriceProtectionScreen.kt` | `"MMM dd"`, `"MMM dd, yyyy"` | 232, 477 |
| `NaturalLanguageSearchScreen.kt` | `"MMM d"`, `"MMM d, yyyy"`, `"MMM d, yyyy"` | 225, 313, 380 |
| `AddExpenseSheet.kt` | `"EEE, dd MMM yyyy, HH:mm"` | 800 |
| `RecurringExpensesScreen.kt` | `"MMM dd, yyyy"`, `"MMM dd"` | 356, 441 |
| `AccountingExporters.kt` | `"MM/dd/yyyy"`, `"dd/MM/yyyy"`, `"yyyy-MM-dd"` | 14, 53, 112 |
| `AccountantReportPdfExporter.kt` | `"yyyy-MM-dd"`, `"MMM yyyy"`, `"yyyy-MM-dd HH:mm"` | 261-263 |
| `DashboardBriefingPromptFormatter.kt` | `"yyyy-MM-dd"` | 31 |
| `GenericTransactionParser.kt` | `"uuuu-MM-dd"`, `"dd/MM/uuuu"`, `"dd MMM uuuu"` | 257, 266, 292 |
| `ExpenseRepository.kt` | `DateTimeFormatter.ISO_LOCAL_DATE` | 822 |

### 8.3 Custom format utilities
| File | Function | Lines |
|---|---|---|
| `UiTextArg.kt` | `"dd/MM/yyyy"` pattern | 15 |
| `DateFormatterUtils.kt` | `formatTimestampJavaTime(timestamp, pattern)` | 55-59 |
| `AdvancedAnalyticsEngine.kt` | `"MMM dd, yyyy HH:mm"` | 525 |

---

## 9. Scheduling / Worker Code

### 9.1 `NotificationCaptureService.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`
- Lines 151-166: Uses `AlarmManager.setRepeating(ELAPSED_REALTIME)` for service restart
- Line 163: `SystemClock.elapsedRealtime()` for alarm trigger

### 9.2 `WarrantyExpirationWorker.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt`
- Lines 79-84: `PeriodicWorkRequestBuilder` with 1-day interval via WorkManager

### 9.3 `ReceiptMatchingWorker.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt`
- Lines 106-111: `PeriodicWorkRequestBuilder` with 2-hour interval
- Lines 125-126: `OneTimeWorkRequestBuilder` for immediate execution

### 9.4 `LocationBackfillWorker.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt`
- Lines 147-159: `PeriodicWorkRequestBuilder` (period unspecified but periodic)

### 9.5 `MerchantKeyBackfillWorker.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt`
- Lines 107-115: `OneTimeWorkRequestBuilder` for one-shot backfill

### 9.6 `AiWorkSchedulerImpl.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt`
- Lines 21-26: `PeriodicWorkRequestBuilder<DailyBriefingWorker>` (period not shown in excerpt)

### 9.7 `DailyBriefingWorker.kt`
**Path**: `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
- Line 26: Periodic WorkManager worker for daily AI briefing

### 9.8 `AppStartupCoordinator.kt` / `AppStartupDelegate.kt`
- Likely schedule initial work on app startup

---

## 10. Anti-Patterns and Issues Found

### 10.1 MIXING Calendar and Rolling Periods

**Issue**: `TransactionsViewModel.TransactionTab` and `AnalyticsViewModel` use `getLastNDaysRange()` for MONTH/QUARTER/YEAR, which is a **rolling 30/90/365-day window**, NOT a calendar month/quarter/year.

| Location | Code | Problem |
|---|---|---|
| `TransactionsViewModel.kt:768` | `getLastNDaysRange(now, 30)` | Rolling 30 days != calendar month |
| `TransactionsViewModel.kt:769` | `getLastNDaysRange(now, 90)` | Rolling 90 days != calendar quarter |
| `TransactionsViewModel.kt:770` | `getLastNDaysRange(now, 365)` | Rolling 365 days != calendar year |
| `AnalyticsViewModel.kt:800` | `getLastNDaysRange(now, 30)` | Same — rolling, not calendar |
| `AnalyticsViewModel.kt:801` | `getLastNDaysRange(now, 90)` | Same |
| `AnalyticsViewModel.kt:802` | `getLastNDaysRange(now, 365)` | Same |

**Impact**: A "This Month" view on April 15 shows expenses from March 16 to April 15, not April 1-30. This is likely a bug.

### 10.2 Raw Millis Math Without Calendar Awareness

**Issue**: Multiple locations divide millisecond differences by `86_400_000` or `24*60*60*1000` for day calculations, which breaks during DST transitions.

| Location | Code |
|---|---|
| `ReceiptParser.kt:734` | `(System.currentTimeMillis() - date) / (1000 * 60 * 60 * 24)` |
| `AnalyticsScreen.kt:558` | `(it - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)` |
| `StatisticalVisualizations.kt:534` | `(date - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)` |
| `AnalyticsScreen.kt:561` | Same pattern |
| `InsightsEngineTest.kt:57` | `System.currentTimeMillis() - daysAgo * dayMs` |
| `InsightsEngineEdgeCaseTest.kt:176` | `System.currentTimeMillis() - daysAgo * 86_400_000L` |
| `AnalyticsStateStressTest.kt:151` | `System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)` |
| `BudgetForecastingEngine.kt:58` | `(periodEnd - elapsedEnd) / MILLIS_PER_DAY` |
| `AdvancedAnalyticsEngine.kt:516` | `(period.endMs - period.startMs) / DAY_IN_MILLIS` |
| `AdvancedAnalyticsEngine.kt:740` | `(sorted[i] - sorted[i-1]) / DAY_IN_MILLIS` |
| `ComputeMoneyRadarUseCase.kt:231` | `(now - alert.alertedAt) / ONE_DAY_MS` |

**Note**: `TimePeriodUtils.daysBetween()` exists and is DST-safe, but it's not used in these locations.

### 10.3 `Calendar.getInstance()` Without Dependency Injection

**Issue**: `TimePeriodUtils` creates `Calendar.getInstance()` on every call — always reads real system time. While this is mostly OK for pure date math (since Calendar uses zone+locale, not the instant), it means:
- Cannot be faked in tests (tests mock at higher level instead)
- Creates garbage objects on every call (performance concern in hot paths)

### 10.4 Asymmetric Period Ranges

**Issue**: `getLastNDaysRange(now, days)` uses `getStartOfDay()` for the start but raw `now` for the end:
```kotlin
fun getLastNDaysRange(now: Long, days: Int): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    cal.add(Calendar.DAY_OF_MONTH, -days)
    val start = getStartOfDay(cal.timeInMillis)
    return start to now  // <- end is raw now, not aligned!
}
```

**Impact**: The end boundary is a specific time-of-day, while the start is midnight. Over time this means the window grows/shrinks by time of day.

### 10.5 `getEndOfMonth()` vs `endOfMonth()` TestUtils Inconsistency

**Issue**: `TimePeriodUtils.getEndOfMonth()` returns exclusive start of next month (half-open). But `TestUtils.endOfMonth()` (line 233-239) returns `23:59:59.999999999` of the last day (inclusive end).

This test utility could hide bugs by making inclusive-end assertions pass when they shouldn't.

### 10.6 `DateFormatterUtils` Instant.now() Proliferation

**Issue**: All 13 convenience methods in `DateFormatterUtils` (lines 41-53) call `Instant.now()` directly with no way to inject a `TimeProvider`. This means:
- Displayed "current time" cannot be faked in UI tests
- Any formatting function that should show "current time" is fixed to real wall clock

### 10.7 Entity `createdAt` Defaults

**Issue**: 30+ entity files use `System.currentTimeMillis()` as default value for `createdAt`. These are evaluated at object creation time in Kotlin, NOT at database INSERT time. If the JVM clock changes between object creation and database insert, timestamps will be wrong.

### 10.8 Hard-Coded Year `365L * 24 * 60 * 60 * 1000`

**Issue**: `RecurringExpensesScreen.kt:102` uses `365L * 24 * 60 * 60 * 1000` for a "one year ago" filter. This ignores leap years.

### 10.9 `SimpleDateFormat` Thread Safety

**Issue**: Multiple `SimpleDateFormat` instances are created as local variables (OK), but some are `remember`'d in composables (`SubscriptionManagementScreen.kt:713`, `SavingsGoalsScreen.kt:40`, `CashFlowCalendarScreen.kt:39`). Since these are used in UI thread only, this is probably safe, but SimpleDateFormat is not thread-safe if accessed from multiple threads.

### 10.10 NaturalLanguageSearchEngine uses `LocalDate.now()` Directly

**Issue**: `NaturalLanguageSearchEngine.kt` (lines 25-66) uses `LocalDate.now()` directly for parsing "today", "yesterday", "this week", etc. This is not injectable and cannot be tested with fake time. Should use `TimeProvider`.

---

## 11. Recommended Migration Order

### Phase 1 — Critical (Replace all `System.currentTimeMillis()` in production code)
1. **Entity defaults** — 30+ entity files with `System.currentTimeMillis()` as default for `createdAt`. Migrate to accept a timestamp parameter or use a DAO-level time provider.
2. **`DateFormatterUtils.kt`** — Replace all `Instant.now()` calls with `TimeProvider.now()` parameter.
3. **`NaturalLanguageSearchEngine.kt`** — Replace `LocalDate.now()` with `TimeProvider`.
4. **`Activity/ViewModel/Screen` files** — Replace `System.currentTimeMillis()` in:
   - `HomeScreen.kt` (5 call sites)
   - `BudgetScreen.kt` (3 call sites)
   - `AnalyticsScreen.kt` (1 call site)
   - `AnalyticsViewModel.kt`
   - `SubscriptionManagementScreen.kt`
   - `ManualRecurringExpenseScreen.kt`
   - `SpendingMapScreen.kt`
   - `BudgetViewModel.kt`
   - `RecurringExpensesScreen.kt`

### Phase 2 — Period Boundary Corrections
5. **`TransactionsViewModel.kt`** — Replace `getLastNDaysRange(now, 30/90/365)` with `getMonthRange()`/`getQuarterRange()`/`getYearRange()`.
6. **`AnalyticsViewModel.kt`** — Same replacement for ANALYTICS period tab.
7. **`AdvancedAnalyticsEngine.kt` line 86** — Replace `7 * DAY_IN_MILLIS` with `getEndOfWeek()`.
8. **`ComputeMoneyRadarUseCase.kt` line 231** — Replace raw millis division with `daysBetween()`.

### Phase 3 — DST-Safe Day Calculations
9. **`ReceiptParser.kt:734`** — Replace `(now - date) / (1000*60*60*24)` with `daysBetween()`.
10. **`AnalyticsScreen.kt:558`** — Same.
11. **`StatisticalVisualizations.kt:534`** — Same.
12. **`BudgetForecastingEngine.kt:58`** — Same.
13. **All test files** with raw millis math (InsightsEngineTest, InsightsEngineEdgeCaseTest, etc.)

### Phase 4 — Test Infrastructure
14. **`TestUtils.endOfMonth()`** — Fix to use half-open convention matching TimePeriodUtils.
15. **Ensure all relevant tests use `FakeTimeProvider`** instead of `System.currentTimeMillis()`.

### Phase 5 — Calendar.getInstance() Reduction
16. **`TimePeriodUtils.kt`** — Consider injecting `TimeProvider` to avoid creating `Calendar.getInstance()` instances. Or at minimum, consolidate Calendar creation patterns.

### Phase 6 — SimpleDateFormat → DateTimeFormatter Migration
17. **Migrate remaining `SimpleDateFormat` usages** to `java.time.format.DateTimeFormatter` for thread safety and modern API.

---

## Appendix: Files with Time-Related Code — Full Inventory

### Main Source Files (A-Z):
- `data/ai/provider/DashboardBriefingPromptFormatter.kt`
- `data/ai/worker/AiWorkSchedulerImpl.kt`
- `data/ai/worker/DailyBriefingWorker.kt`
- `data/database/AppDatabase.kt` (migration code)
- `data/database/dao/BudgetDao.kt`
- `data/database/dao/BudgetForecastDao.kt`
- `data/database/dao/ExpenseDao.kt`
- `data/database/dao/HealthScoreHistoryDao.kt`
- `data/database/dao/InvestmentValueDao.kt`
- `data/database/dao/ManualRecurringExpenseDao.kt`
- `data/database/dao/MerchantNormalizationDao.kt`
- `data/database/dao/PromptStateDao.kt`
- `data/database/dao/ReturnWindowDao.kt`
- `data/database/dao/SavingsSweepPlanDao.kt`
- `data/database/dao/ScannedReceiptDao.kt`
- `data/database/dao/SpendingPersonalityProfileDao.kt`
- `data/database/dao/SplitItemAssignmentDao.kt`
- `data/database/dao/SplitTemplateDao.kt`
- `data/database/dao/WarrantyDao.kt`
- `data/database/entity/*.kt` (35+ entity files)
- `data/location/LocationBackfillWorker.kt`
- `data/location/MerchantKeyBackfillWorker.kt`
- `data/repository/AccountingExportRepository.kt`
- `data/repository/AnalyticsRepository.kt`
- `data/repository/AutomatedSavingsRuleStateRepository.kt`
- `data/repository/BudgetRepository.kt`
- `data/repository/CurrencySettingsRepositoryImpl.kt`
- `data/repository/DashboardContractsAdapter.kt`
- `data/repository/ExpenseRepository.kt`
- `data/repository/SavingsContributionHistoryRepository.kt`
- `di/TimeModule.kt`
- `domain/analytics/AdvancedAnalyticsDashboard.kt`
- `domain/analytics/AdvancedAnalyticsEngine.kt`
- `domain/analytics/AdvancedAnalyticsModels.kt` (AnalyticsPeriod enum)
- `domain/analytics/AnalyticsModels.kt` (TimePeriod enum)
- `domain/analytics/AnomalyDetector.kt`
- `domain/analytics/DayOfWeekAnalyzer.kt`
- `domain/analytics/InsightsEngine.kt`
- `domain/analytics/SpendingPaceCalculator.kt`
- `domain/analytics/SpendingPersonalityClassifier.kt`
- `domain/budget/BudgetCalculator.kt`
- `domain/budget/BudgetForecastingEngine.kt`
- `domain/budget/BudgetHistorySeriesBuilder.kt`
- `domain/budget/BudgetMonitor.kt`
- `domain/carbon/CarbonFootprintCalculator.kt`
- `domain/cashflow/CashFlowCalculator.kt`
- `domain/export/AccountantReportPdfExporter.kt`
- `domain/export/AccountingExporters.kt`
- `domain/forecasting/ForecastInputAssembler.kt`
- `domain/forecasting/MonteCarloSpendingSimulator.kt`
- `domain/forecasting/FinancialStressForecastEngine.kt`
- `domain/health/FinancialHealthCalculator.kt`
- `domain/health/FinancialHealthScoreV2.kt`
- `domain/income/RecurringIncomeTracker.kt`
- `domain/logic/RecurringExpenseEngine.kt`
- `domain/logic/SynthesisEngine.kt`
- `domain/naturallanguage/NaturalLanguageSearchEngine.kt`
- `domain/parser/AppParserRegistry.kt`
- `domain/parser/GenericTransactionParser.kt`
- `domain/receipt/BankStatementParser.kt`
- `domain/receipt/ReceiptParser.kt`
- `domain/savings/AutomatedSavingsRuleEngine.kt`
- `domain/savings/SavingsGamificationEngine.kt`
- `domain/savings/SmartSavingsEngine.kt`
- `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `domain/util/AppConstants.kt`
- `domain/util/DateFormatterUtils.kt`
- `domain/util/SystemTimeProvider.kt`
- `domain/util/TimeBoundaryTicker.kt`
- `domain/util/TimePeriodUtils.kt`
- `domain/util/TimeProvider.kt`
- `receiver/BootReceiver.kt`
- `receiver/ServiceRestartReceiver.kt`
- `service/NotificationCaptureService.kt`
- `service/receiptmatching/ReceiptMatchingWorker.kt`
- `service/warranty/WarrantyExpirationWorker.kt`
- `ui/screens/addexpense/AddExpenseSheet.kt`
- `ui/screens/analytics/AnalyticsScreen.kt`
- `ui/screens/analytics/AnalyticsViewModel.kt`
- `ui/screens/budget/BudgetScreen.kt`
- `ui/screens/budget/BudgetViewModel.kt`
- `ui/screens/carbon/CarbonFootprintViewModel.kt`
- `ui/screens/cashflow/CashFlowCalendarScreen.kt`
- `ui/screens/home/HomeScreen.kt`
- `ui/screens/home/HomeViewModel.kt`
- `ui/screens/map/SpendingMapScreen.kt`
- `ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt`
- `ui/screens/price/PriceProtectionScreen.kt`
- `ui/screens/recurring/RecurringExpensesScreen.kt`
- `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt`
- `ui/screens/reminder/BillRemindersScreen.kt`
- `ui/screens/savings/SavingsGoalsScreen.kt`
- `ui/screens/subscription/SubscriptionManagementScreen.kt`
- `ui/screens/transactions/TransactionsScreen.kt`
- `ui/screens/transactions/TransactionsViewModel.kt`
- `ui/components/BudgetBlockPartyCard.kt`
- `ui/components/FinancialStressForecastCard.kt`
- `ui/components/FinancialWeatherCard.kt`
- `ui/components/MoneyRadarWidget.kt`
- `ui/components/RetroBudgetBlockPartyCard.kt`
- `ui/components/analytics/StatisticalVisualizations.kt`
- `ui/components/dashboard/MoneyRadarWidget.kt`
- `util/CsvExpenseImporter.kt`
- `ui/text/UiTextArg.kt`

### Test Source Files (Representative Selection):
- `TestUtils.kt` (startOfMonth, endOfMonth)
- `domain/util/FakeTimeProvider.kt`
- `domain/util/TimePeriodUtilsTest.kt`
- `domain/util/TimePeriodUtilsStressTest.kt`
- `domain/util/TimePeriodUtilsValidationTest.kt`
- `metrics/TimePeriodAlignmentTest.kt`
- `metrics/TimePeriodAlignmentStressTest.kt`
- `consistency/TemporalConsistencyTest.kt`
- `consistency/TimePeriodAnalyticsAlignmentTest.kt`
- `e2e/DateBoundaryFlowTest.kt`
- `e2e/MonthlyTotalFlowTest.kt`
- `e2e/AnalyticsPipelineTest.kt`
- `data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt`
- `data/database/model/ExpenseWithCategoryFormattedTimeTest.kt`
- `data/repository/BudgetRolloverTest.kt`
- `data/repository/BudgetRepositoryTruncationTest.kt`
- `data/repository/BudgetRepositoryStressTest.kt`
- `data/repository/BudgetRepositoryHistoricalStatusTest.kt`
- `domain/budget/BudgetCalculatorBoundaryTest.kt`
- `domain/budget/BudgetCalculatorGoldenTest.kt`
- `domain/budget/BudgetForecastingEngineTest.kt`
- `domain/budget/BudgetTrendBoundaryTest.kt`
- `domain/budget/SharedBudgetManagerTest.kt`
- `domain/health/HealthScoreGoldenTest.kt`
- `domain/health/HealthScoreEdgeCaseTest.kt`
- `domain/health/FinancialHealthScoreV2Test.kt`
- `domain/health/FinancialHealthCalculatorTransactionTypeTest.kt`
- `domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt`
- `domain/health/FinancialHealthCalculatorBoundaryTest.kt`
- `domain/analytics/SpendingPaceCalculatorDeepTest.kt`
- `domain/analytics/SpendingPaceBoundaryTest.kt`
- `domain/analytics/SpendingPaceGoldenTest.kt`
- `domain/analytics/AnomalyDetectorTest.kt`
- `domain/analytics/InsightsEngineTest.kt`
- `domain/analytics/InsightsEngineEdgeCaseTest.kt`
- `domain/analytics/InsightsEngineValidationTest.kt`
- `domain/analytics/InsightsEngineDeepTest.kt`
- `domain/analytics/AdvancedAnalyticsEngineDeepTest.kt`
- `domain/analytics/AdvancedAnalyticsDashboardTest.kt`
- `domain/analytics/MonthlyComparisonCalculatorTest.kt`
- `domain/analytics/DayOfWeekAnalyzerTest.kt`
- `domain/analytics/CategoryInsightEngineTest.kt`
- `domain/savings/SmartSavingsEngineTest.kt`
- `domain/savings/SavingsGamificationEngineTest.kt`
- `domain/savings/AutomatedSavingsRuleEngineTest.kt`
- `domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt`
- `domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt`
- `domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt`
- `domain/groups/SettlementCalculatorStressTest.kt`
- `data/repository/ExpenseRepositoryStressTest.kt`
- `currency/CanonicalMultiCurrencyFixture.kt`
- `AnalyticsEngineTestBase.kt`
- `AnalyticsTestCompat.kt`

---

*Generated by Scout on 2026-05-01. This audit covers 180+ files across main and test sources.*
