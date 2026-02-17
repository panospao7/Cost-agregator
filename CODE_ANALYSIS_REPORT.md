# ExpenseTracker Android App - Code Analysis Report

## Executive Summary

**Total Issues Found:** 90+ items across 9 categories

| Priority | Count |
|----------|-------|
| 🔴 CRITICAL | 31 |
| 🟠 HIGH | 30 |
| 🟡 MEDIUM | 29 |

---

## 🔴 CRITICAL (Immediate Action Required)

### 1. Architecture Violations - Domain Layer Accessing Database

The domain layer is directly injecting and using DAOs - a severe clean architecture violation:

| File | Line | Issue |
|------|------|-------|
| `domain/analytics/InsightsEngine.kt` | 20 | `expenseDao: ExpenseDao` injected |
| `domain/analytics/AdvancedAnalyticsEngine.kt` | 31-33 | `expenseDao`, `categoryDao`, `budgetDao` injected |
| `domain/budget/BudgetMonitor.kt` | 21-22 | `context: Context`, `budgetDao` injected |
| `domain/logic/RecurringExpenseEngine.kt` | 16-17 | `expenseDao`, `recurringExpenseDao` injected |
| `domain/intelligence/ml/MerchantNormalizer.kt` | 35-37 | `dao: MerchantNormalizationDao`, `context: Context` |
| `domain/intelligence/ml/HybridExpenseClassifier.kt` | 17-19 | `categoryDao`, `context: Context` |
| `domain/intelligence/ConfidenceRouter.kt` | 3-5 | `SourceStatsDao`, `UserCorrectionDao` |
| `domain/categorization/CategorizationEngine.kt` | 3-4 | `MerchantCategoryDao` |
| `domain/intelligence/TransactionClassifier.kt` | 26-27 | `context: Context`, `UserCorrectionDao` |
| `domain/receipt/ReceiptOcrService.kt` | 44 | `context: Context` |

### 2. Memory Leaks - Custom CoroutineScopes Never Cleaned

| File | Line | Issue |
|------|------|-------|
| `data/repository/NotificationRepository.kt` | 45-53 | `repositoryScope` with `shareIn` never cleaned up |
| `data/repository/FinancialWeatherRepository.kt` | 240-244 | Unbounded `CoroutineScope` in `stateIn` |
| `domain/intelligence/TransactionClassifier.kt` | 29-36 | Custom `scope` never cancelled, `cleanup()` never called |
| `domain/budget/BudgetMonitor.kt` | 27-31 | `serviceScope` never cancelled |

### 3. Bad Logic - BudgetCalculator Wrong Timestamp

**File:** `domain/budget/BudgetCalculator.kt`

```kotlin
// Line 26 - Sets anchorCal correctly from anchorDate
val anchorCal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
anchorCal.timeInMillis = anchorDate

// Line 30 - Uses evaluationTime correctly here
val startOfDay = TimePeriodUtils.getStartOfDay(evaluationTime)

// Line 32-33 - IGNORES evaluationTime! Uses current time instead!
val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
```

**Impact:** Period window calculation is incorrect when `evaluationTime` differs from current time.

### 4. Bad Logic - Overlapping Time Slot Ranges

**File:** `domain/analytics/AdvancedAnalyticsEngine.kt` (Lines 523-530)

```kotlin
private fun hourToTimeSlot(hour: Int): TimeSlot {
    return when (hour) {
        in 6..9 -> TimeSlot.EARLY_MORNING
        in 9..12 -> TimeSlot.MORNING        // Hour 9 is in BOTH!
        in 12..17 -> TimeSlot.AFTERNOON     // Hour 12 is in BOTH!
        in 17..21 -> TimeSlot.EVENING       // Hour 17 is in BOTH!
        in 21..24 -> TimeSlot.NIGHT
        else -> TimeSlot.LATE_NIGHT
    }
}
```

### 5. Security - Unencrypted Sensitive Data

| Location | Issue |
|----------|-------|
| `data/database/entity/Expense.kt` | `amount`, `merchant`, `notes` stored in plain text |
| `data/database/entity/RawNotification.kt` | Raw banking notifications stored |
| `data/database/entity/PendingReview.kt` | Complete transaction details stored |
| `data/database/entity/ScannedReceipt.kt` | OCR text with financial data |

### 6. Security - Sensitive Data in Logs

| File | Lines | Issue |
|------|-------|-------|
| `data/repository/NotificationRepository.kt` | 110, 224, 384, 392, 467 | Logs amounts, merchant names, transaction types |
| `service/NotificationCaptureService.kt` | 264 | Logs full notification content |

---

## 🟠 HIGH PRIORITY

### 7. Duplication - CalendarUtils vs TimePeriodUtils

Both files have identical `getStartOfDay()` and `getStartOfMonth()` functions.

| File | Status |
|------|--------|
| `domain/util/CalendarUtils.kt` | **Should be removed** - duplicate |
| `domain/util/TimePeriodUtils.kt` | **Canonical source** - keep |

### 8. Duplication - Amount String Normalization

Pattern: `string.replace(",", ".")`

| File | Line |
|------|------|
| `GreekBankParser.kt` | 102 |
| `SmsParser.kt` | 76 |
| `RevolutParser.kt` | 67, 73, 79 |
| `GoogleWalletParser.kt` | 70 |
| `GenericTransactionParser.kt` | 98 |
| `BankStatementParser.kt` | 224, 262 |
| `MainActivity.kt` | 237, 297 |
| `AddExpenseViewModel.kt` | 179 |
| `ReceiptScanViewModel.kt` | 254 |
| `ReviewScreen.kt` | 698 |

**Recommendation:** Create centralized utility:
```kotlin
fun normalizeAmountString(amountStr: String): Double? = 
    amountStr.replace(",", ".").toDoubleOrNull()
```

### 9. Duplication - Amount Validation

Pattern: `amount < min || amount > max`

| File | Line |
|------|------|
| `GreekBankParser.kt` | 103 |
| `SmsParser.kt` | 80 |
| `GoogleWalletParser.kt` | 73 |
| `NotificationRepository.kt` | 109, 223, 383 |
| `AddExpenseViewModel.kt` | 186 |

### 10. Missing Error Handling - ViewModel Launches

| File | Lines |
|------|-------|
| `ui/screens/categories/CategoryViewModel.kt` | 24-26, 30-32 |
| `ui/screens/recurring/RecurringExpensesScreen.kt` | 70-83, 87-98, 103-107 |
| `ui/MainViewModel.kt` | 28-31 |
| `ui/screens/home/HomeViewModel.kt` | 120-124, 477-488 |
| `ui/screens/budget/BudgetViewModel.kt` | 138-143 |

### 11. Missing Error Handling - Parser Pipeline

| File | Lines |
|------|-------|
| `domain/parser/AppParserRegistry.kt` | 64-79 |
| `domain/receipt/ReceiptParser.kt` | 105-148 |

### 12. Missing Error Handling - Repository Operations

| File | Lines |
|------|-------|
| `data/repository/NotificationRepository.kt` | 184, 216-220, 805 |
| `data/repository/BudgetRepository.kt` | 104, 117 |

### 13. Performance - Missing LazyColumn Keys

| File | Lines |
|------|-------|
| `ui/screens/recurring/RecurringExpensesScreen.kt` | 163, 190 |
| `ui/screens/debug/DebugScreen.kt` | 227, 259 |
| `ui/screens/debug/DebugViewerScreen.kt` | 499, 514, 529 |
| `ui/screens/categories/CategoryScreen.kt` | 53 |
| `ui/screens/analytics/AnalyticsScreen.kt` | 255 |
| `ui/screens/transactions/TransactionsScreen.kt` | 824, 923 |

### 14. God Objects - Files Over 1000 Lines

| File | Lines | Type |
|------|-------|------|
| `ui/screens/transactions/TransactionsScreen.kt` | 1040 | UI Screen (God Object) |
| `domain/analytics/AdvancedAnalyticsEngine.kt` | 913 | Domain Engine |

### 15. Large Files - 500+ Lines

| File | Lines |
|------|-------|
| `data/repository/NotificationRepository.kt` | 816 |
| `domain/analytics/InsightsEngine.kt` | 649 |
| `ui/screens/home/HomeViewModel.kt` | 537 |
| `data/repository/ReceiptRepository.kt` | 500 |
| `ui/screens/transactions/TransactionsViewModel.kt` | 416 |
| `domain/logic/SynthesisEngine.kt` | 358 |

---

## 🟡 MEDIUM PRIORITY

### 16. Bad Logic - Velocity Calculation Excludes Middle Element

**File:** `domain/analytics/AdvancedAnalyticsEngine.kt` (Lines 619-628)

```kotlin
private fun calculateVelocity(expenses: List<Expense>): Double {
    if (expenses.size < 2) return 0.0
    
    val sorted = expenses.sortedBy { it.date }
    val midPoint = sorted.size / 2
    
    val firstHalfTotal = sorted.take(midPoint).sumOf { it.amount }
    val secondHalfTotal = sorted.takeLast(midPoint).sumOf { it.amount }
    
    return secondHalfTotal - firstHalfTotal
}
```

**Issue:** For odd-sized lists (e.g., 5 items), the middle transaction is excluded from BOTH halves.

### 17. Bad Logic - Month Pro-rating Ignores Month Lengths

**File:** `domain/logic/SynthesisEngine.kt` (Lines 73-83)

```kotlin
val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
    when (pattern.frequency) {
        RecurrenceFrequency.WEEKLY -> pattern.averageAmount * (daysInMonth.toDouble() / 7.0)
        RecurrenceFrequency.BIWEEKLY -> pattern.averageAmount * (daysInMonth.toDouble() / 14.0)
        RecurrenceFrequency.MONTHLY -> pattern.averageAmount
        RecurrenceFrequency.QUARTERLY -> pattern.averageAmount / 3.0
        RecurrenceFrequency.SEMI_ANNUALLY -> pattern.averageAmount / 6.0
        RecurrenceFrequency.ANNUALLY -> pattern.averageAmount / 12.0
        else -> 0.0
    }
}
```

**Issue:** Doesn't account for variable month lengths (February vs March).

### 18. Bad Logic - Redundant Calculation

**File:** `domain/logic/RecurringExpenseEngine.kt` (Lines 155-169)

```kotlin
val days = ((dates[i + 1] - dates[i]) / 86400000.0).roundToInt()
// ... code that seems to do the same calculation but differently ...
cal1.timeInMillis = dates[i]
cal2.timeInMillis = dates[i + 1]
val diffDays = ((cal2.timeInMillis - cal1.timeInMillis) / 86400000.0).roundToInt()
```

**Issue:** `days` is calculated but never used - only `diffDays` is used.

### 19. Bad Logic - Interval Boundary Edge Cases

**File:** `domain/logic/RecurringExpenseEngine.kt` (Lines 181-189)

```kotlin
val frequency = when (mode) {
     in 5..10 -> RecurrenceFrequency.WEEKLY
     in 11..23 -> RecurrenceFrequency.BIWEEKLY
     in 24..37 -> RecurrenceFrequency.MONTHLY
     in 80..110 -> RecurrenceFrequency.QUARTERLY
}
```

**Issue:** Gap between WEEKLY's max (10) and MONTHLY's min (24) - day 11-23 is covered, but what about exactly 28 days?

### 20. Performance - Multiple collectAsState Calls

| File | Lines |
|------|-------|
| `ui/screens/transactions/TransactionsScreen.kt` | 67-75 (9 states) |
| `ui/screens/review/ReviewScreen.kt` | 52-70 |
| `ui/screens/debug/DebugScreen.kt` | 42-45, 200-201, 245 |

### 21. Performance - Flow.first() Usage

| File | Line |
|------|------|
| `data/repository/BudgetRepository.kt` | 165 |
| `data/repository/CategoryRepository.kt` | 33, 53 |
| `ui/screens/analytics/AnalyticsViewModel.kt` | 92-93 |
| `domain/budget/BudgetMonitor.kt` | 34 |
| `ui/screens/debug/DebugViewModel.kt` | 127 |

### 22. Dead Code - Unused FiveData Class

| File | Lines |
|------|-------|
| `ui/screens/home/HomeViewModel.kt` | 507-513 |

### 23. Dead Code - Unused Repository Methods

| File | Lines |
|------|-------|
| `NotificationRepository.kt` | 91-93, 166-168 |
| `ReceiptRepository.kt` | 278-280, 287-289 |
| `RecurringExpenseRepository.kt` | 17, 19, 21, 46, 48 |
| `PlannedExpenseRepository.kt` | 17-19, 29-31 |
| `MerchantCategoryRepository.kt` | 32-35 |

### 24. Dead Code - Unused Model Fields

| File | Field |
|------|-------|
| `domain/model/PeriodRange.kt` | `duration`, `contains()` |
| `domain/model/RecurringPattern.kt` | `intervalInMs` |
| `domain/model/FinancialForecast.kt` | `horizon`, `generatedAt`, `confidence`, `actionableInsights` |
| `data/repository/BudgetRepository.kt` | `allBudgets`, `activeBudgets` |
| `data/repository/ReceiptRepository.kt` | `allReceipts` |

### 25. Dead Code - Commented-Out Code

| File | Lines |
|------|-------|
| `data/repository/ReceiptRepository.kt` | 21 |
| `data/repository/NotificationRepository.kt` | 703 |
| `data/repository/BudgetRepository.kt` | 107, 125, 146 |
| `domain/analytics/InsightsEngine.kt` | 593-599 |

### 26. Overlap - Two Analytics Engines

| File | Issue |
|------|-------|
| `domain/analytics/AdvancedAnalyticsEngine.kt` | Full analytics engine |
| `domain/analytics/InsightsEngine.kt` | Overlapping functionality |

Both calculate spending by day of week, percentiles, trends.

### 27. Overlap - Three Categorization Pipelines

| File | Purpose |
|------|---------|
| `domain/intelligence/TransactionClassifier.kt` | Transaction detection (Naive Bayes ML) |
| `domain/intelligence/ml/HybridExpenseClassifier.kt` | Category classification (rules + ML) |
| `domain/categorization/CategorizationEngine.kt` | Category assignment (merchant patterns) |

### 28. Security - Debug Screens Expose Data

| File | Lines |
|------|-------|
| `ui/screens/debug/DebugScreen.kt` | 319-335, 388-409, 436-449 |
| `ui/screens/debug/DebugViewerScreen.kt` | 107-190, 272-363 |
| `ui/screens/debug/DebugDataStorage.kt` | 25 |

---

## ✅ What's Done Well

- **Clean Architecture separation** exists (data/domain/ui folders)
- **MVVM pattern** implemented correctly in most ViewModels
- **Hilt DI** properly used
- **Room database** with proper migrations
- **No SQL injection** - parameterized queries used throughout
- **No hardcoded credentials**
- **Coroutines** used appropriately in most places

---

## 📋 Recommended Priority Order

1. **Fix memory leaks** (custom CoroutineScopes in singletons)
2. **Fix bad logic bugs** (BudgetCalculator, time slots, velocity)
3. **Remove Context from domain layer**
4. **Consolidate duplicate utilities** (CalendarUtils, amount normalization)
5. **Add missing error handling** in ViewModels
6. **Remove dead code** (52+ items)
7. **Fix performance issues** (LazyColumn keys, Flow collection)
8. **Address security concerns** (encryption, logging)

---

## Report Generated: February 17, 2026
