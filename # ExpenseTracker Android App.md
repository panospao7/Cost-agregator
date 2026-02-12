
# 🟠 **CATEGORY 2: DUPLICATIONS**

## **DUP-001: Duplicate Widget ID Mapping**
**Files:** `HomeScreen.kt` (lines ~14504-14515) AND `HomeViewModel.kt` (lines ~15210-15221)
```kotlin
// In HomeScreen.kt
private fun getWidgetId(widget: DashboardWidget): String = when (widget) {
    is DashboardWidget.SafeToSpend -> "safe_to_spend"
    // ... repeated mapping
}

// In HomeViewModel.kt
private fun getWidgetId(widget: DashboardWidget): String = when (widget) {
    is DashboardWidget.SafeToSpend -> "safe_to_spend"
    // ... identical mapping
}
```
**Proposed Fix:** Move to a single location, ideally in a companion object of `DashboardWidget`:
```kotlin
sealed class DashboardWidget {
    abstract val id: String
    
    data class SafeToSpend(...) : DashboardWidget() {
        override val id = "safe_to_spend"
    }
    // ...
}
```

---

## **DUP-002: Date Formatting Duplicated Across Screens**
**Files:** Multiple files use the same date formatting pattern:
- `ReviewScreen.kt`
- `HomeScreen.kt`  
- `AnalyticsScreen.kt`
- `TransactionsScreen.kt`

```kotlin
// Repeated in multiple files:
val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
val dateFormat2 = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
```
**Proposed Fix:** Create a centralized `DateFormatters` object:
```kotlin
object DateFormatters {
    val shortDate: SimpleDateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    val dateTime: SimpleDateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    // ThreadLocal for thread safety
}
```

---

## **DUP-003: Category Color Parsing Duplicated**
**Files:** `ExpenseWithCategory.kt`, `CategoryItem.kt` (AnalyticsScreen), `CategorySpendingRow.kt` (HomeScreen)

All have similar color parsing logic with try-catch.

**Proposed Fix:**
```kotlin
// In a ColorUtils.kt file
fun parseCategoryColor(colorHex: String?, fallback: Int = Color.GRAY): Int {
    return colorHex?.let {
        try { android.graphics.Color.parseColor(it) } 
        catch (e: Exception) { fallback }
    } ?: fallback
}
```

---

## **DUP-004: Currency Symbol Helper Duplicated**
**Files:** `ReceiptScanScreen.kt` and `AddExpenseSheet.kt` both have:
```kotlin
private fun getCurrencySymbol(currencyCode: String?): String {
    return try { Currency.getInstance(currencyCode ?: "EUR").symbol } catch(e: Exception) { "€" }
}
```
**Proposed Fix:** Move to `CurrencyUtils.kt` or companion object.

---
# 🟠 **ADDITIONAL DUPLICATIONS**

## **DUP-005: Amount Extraction Patterns Duplicated**
**Files:** `GenericTransactionParser.kt`, `GoogleWalletParser.kt`, `RevolutParser.kt`, `SmsParser.kt`, `GreekBankParser.kt`

All have similar `amountPattern` regex definitions:
```kotlin
// In GenericTransactionParser
Pattern.compile("""([€$£])\s*(\d+(?:[.,]\d{2})?)|...""")

// In GoogleWalletParser
Pattern.compile("""([€$£])\s*(\d+[.,]\d{2})|...""")

// In SmsParser
Pattern.compile("""(\d+[.,]\d{2})\s*(EUR|€|...)...""")
```
**Proposed Fix:** Create a shared `AmountExtractor` utility class.

---

## **DUP-006: Merchant Extraction Logic Duplicated**
**Files:** Multiple parsers have similar merchant extraction logic:
```kotlin
// Pattern repeated across parsers
for (prefix in MERCHANT_PREFIXES) {
    val index = normalized.indexOf(prefix, ignoreCase = true)
    if (index != -1) {
        val after = normalized.substring(index + prefix.length).trim()
        return merchantCleaner.clean(after)
    }
}
```
**Proposed Fix:** Move to shared `MerchantExtractor` class.

---

## **DUP-007: Currency Normalization Duplicated**
**Files:** Every parser calls `currencyNormalizer.normalize()` with similar fallback logic.
**Proposed Fix:** Make the normalizer handle null/empty inputs with a default return value.

---

# 🟡 **CATEGORY 3: BAD LOGIC**

## **LOGIC-001: Incorrect Week Start Calculation**
**File:** `HomeViewModel.kt` (lines 14983-14985)
```kotlin
val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
val daysToMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
val weekStart = cal.timeInMillis - (daysToMonday * 86400000L)
```
**Issue:** This calculates Monday-based week start but doesn't reset the time to midnight. The weekStart includes the current time component, making period calculations inconsistent.

**Proposed Fix:**
```kotlin
val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
val daysToMonday = when (dayOfWeek) {
    Calendar.SUNDAY -> 6
    Calendar.MONDAY -> 0
    else -> dayOfWeek - Calendar.MONDAY
}
cal.add(Calendar.DAY_OF_MONTH, -daysToMonday)
cal.set(Calendar.HOUR_OF_DAY, 0)
cal.set(Calendar.MINUTE, 0)
cal.set(Calendar.SECOND, 0)
cal.set(Calendar.MILLISECOND, 0)
val weekStart = cal.timeInMillis
```

---

## **LOGIC-002: Duplicate Detection Window Too Narrow**
**File:** `ReceiptRepository.kt` (lines 4603-4609)
```kotlin
val isDuplicate = expenseDao.isDuplicate(
    amount = amount,
    merchant = normalizedMerchant,
    date = date,
    windowMs = 60000 // 1 minute window
)
```
**Issue:** A 1-minute window is extremely narrow. OCR processing delays can cause legitimate duplicates to be saved if the user scans the same receipt twice within minutes.

**Proposed Fix:**
```kotlin
// Use a larger window (5-10 minutes) OR compare by amount+merchant only (ignoring small time differences)
val isDuplicate = expenseDao.isDuplicate(
    amount = amount,
    merchant = normalizedMerchant,
    date = date,
    windowMs = 300000 // 5 minutes
)
```

---

## **LOGIC-003: Incorrect Recurring Frequency Detection Thresholds**
**File:** `RecurringExpenseEngine.kt` (lines 7537-7544)
```kotlin
val frequency = when (mode) {
    in 5..9 -> RecurrenceFrequency.WEEKLY      // 7 days ±2
    in 11..17 -> RecurrenceFrequency.BIWEEKLY  // 14 days ±3
    in 25..35 -> RecurrenceFrequency.MONTHLY   // 30 days ±5
    in 80..100 -> RecurrenceFrequency.QUARTERLY // 90 days ±10
    // ...
}
```
**Issue:** These ranges have gaps! A 10-day interval falls through to IRREGULAR, but could be a shifted weekly or a specific pattern. Similarly, 18-24 days could be "monthly, paid early/late."

**Proposed Fix:**
```kotlin
val frequency = when (mode) {
    in 5..10 -> RecurrenceFrequency.WEEKLY      // Expanded range
    in 11..18 -> RecurrenceFrequency.BIWEEKLY   // Expanded range
    in 19..38 -> RecurrenceFrequency.MONTHLY    // Expanded to handle early/late payments
    // ...
}
```

---

## **LOGIC-004: Confidence Threshold Inconsistency**
**File:** `ConfidenceRouter.kt` (inferred from usage)
The code uses different confidence thresholds in different places:
- `HybridExpenseClassifier`: 0.85f for high confidence
- `MerchantNormalizer`: 0.80f for fuzzy match
- `PendingReview`: Various thresholds

**Issue:** Inconsistent thresholds lead to unpredictable routing behavior.

**Proposed Fix:** Centralize thresholds:
```kotlin
object ConfidenceThresholds {
    const val HIGH_CONFIDENCE = 0.90f
    const val MEDIUM_CONFIDENCE = 0.75f
    const val LOW_CONFIDENCE = 0.50f
    const val AUTO_APPROVE = 0.95f
}
```

---

## **LOGIC-005: Day 1 Pace Dampening Creates False Sense**
**File:** `HomeViewModel.kt` (lines 15055-15059)
```kotlin
if (dayOfMonth == 1) {
    if (calculated > 110f) 110f else if (calculated < 90f) 90f else calculated
}
```
**Issue:** Artificially capping pace at 90-110% on day 1 hides overspending warnings. If a user makes a large purchase on day 1, they should see the warning.

**Proposed Fix:**
```kotlin
// Show actual pace but add a "Day 1 - too early to tell" badge
// Or weight the baseline more heavily rather than capping
```

---
# 🟡 **ADDITIONAL BAD LOGIC**

## **LOGIC-006: Calendar Instance Creation in Loop**
**File:** `InsightsEngine.kt` (lines 5469-5481)
```kotlin
private suspend fun buildDayOfWeekPattern(...): List<DayOfWeekInsight> {
    val timeZoneOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
    val data = expenseDao.getDayOfWeekPattern(startMs, endMs, timeZoneOffset)
    // ...
}
```
**Issue:** `TimeZone.getDefault().getOffset()` is called every time. Should be cached or passed from caller.
**Proposed Fix:**
```kotlin
// Cache at class level
private val cachedTimeZoneOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis())
```

---

## **LOGIC-007: Hardcoded "30 days" for Monthly Recurring**
**File:** `SynthesisEngine.kt` (lines 7621-7629)
```kotlin
val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
    when (pattern.frequency) {
        RecurrenceFrequency.WEEKLY -> pattern.averageAmount * (30.0 / 7.0)
        RecurrenceFrequency.BIWEEKLY -> pattern.averageAmount * (30.0 / 14.0)
        RecurrenceFrequency.MONTHLY -> pattern.averageAmount
        // ...
    }
}
```
**Issue:** Uses 30 days as month, but should use actual days in current month.
**Proposed Fix:**
```kotlin
val daysInCurrentMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
    when (pattern.frequency) {
        RecurrenceFrequency.WEEKLY -> pattern.averageAmount * (daysInCurrentMonth / 7.0)
        // ...
    }
}
```

---

## **LOGIC-008: Confidence Score Not Propagated in Statement Import**
**File:** `ReceiptRepository.kt` (lines 4720-4734)
```kotlin
val review = PendingReview(
    // ...
    confidence = tx.confidence,  // From parser
    // ...
)
```
**Issue:** The `confidence` from `StatementParser` is always `0.7f` (hardcoded). Different transaction types should have different confidences.
**Proposed Fix:** Calculate confidence based on:
- Amount extraction confidence
- Merchant extraction confidence
- Date presence

---

## **LOGIC-009: Category Insights Uses Wrong Filter**
**File:** `InsightsEngine.kt` (lines 5305-5308)
```kotlin
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
    && it.categoryId != null
    && it.date < currentMonth.startMs // exclude current month
}
```
**Issue:** Filters out expenses without categoryId, skewing category averages. Should use all purchases and group by category when present.
**Proposed Fix:**
```kotlin
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
    && it.date < currentMonth.startMs
}
// Then filter for category-specific calculations
```

---

# 🔵 **CATEGORY 4: INSUFFICIENCIES**

## **INS-001: No Validation for Budget Amount**
**File:** `BudgetRepository.kt` / `BudgetViewModel.kt`
No validation exists for budget amounts:
```kotlin
// No check for:
// - Negative amounts
// - Unrealistically high amounts (> 1M)
// - Zero amounts
```
**Proposed Fix:**
```kotlin
fun validateBudgetAmount(amount: Double): String? {
    return when {
        amount <= 0 -> "Amount must be greater than zero"
        amount > 1_000_000 -> "Amount exceeds maximum limit"
        else -> null
    }
}
```

---

## **INS-002: Missing Error Recovery in OCR Processing**
**File:** `ReceiptRepository.kt` (lines 4531-4543)
```kotlin
} catch (e: Exception) {
    android.util.Log.e("ReceiptRepository", "Failed to process receipt", e)
    return saveManualReceiptRecord(imageUri).let { (receipt, parsed) ->
        val failedReceipt = receipt.copy(
            rawOcrText = "Scan Failed: ${e.message}", 
            confidence = 0f
        )
        // ...
    }
}
```
**Issue:** Only logs the error message. No categorization of failure types (no text found, image too dark, language not supported, etc.) to help users correct the issue.

**Proposed Fix:**
```kotlin
sealed class OcrError {
    object NoTextDetected : OcrError()
    object ImageTooBlurry : OcrError()
    object UnsupportedLanguage : OcrError()
    data class Unknown(val message: String) : OcrError()
}

// Then provide user-facing suggestions
```

---

## **INS-003: No Handling for Currency Mismatch**
**Files:** `ReceiptRepository.kt`, `AddExpenseViewModel.kt`
The app defaults to EUR everywhere:
```kotlin
currency = "EUR" // Hardcoded throughout
```
**Issue:** No handling for multi-currency scenarios. If a user travels or has cards in different currencies, all amounts are treated as EUR.

**Proposed Fix:**
```kotlin
// Add currency field to Expense entity and use CurrencyNormalizer
// Implement currency conversion for analytics
```

---

## **INS-004: Missing Index on MerchantName**
**File:** `Expense.kt` / DAO queries
```kotlin
@Index("merchant") // Not present
```
**Issue:** Many queries search by merchant name, but there's no index on it:
```kotlin
@Query("SELECT * FROM expenses WHERE merchant = :merchant")
```
This causes full table scans on large datasets.

**Proposed Fix:** Add index to Expense entity:
```kotlin
@Entity(indices = [
    Index("merchant"),
    // ... existing indices
])
```

---

## **INS-005: No Rate Limiting on Notification Processing**
**File:** `NotificationCaptureService.kt`
```kotlin
private val processedNotifications = ConcurrentHashMap<String, Long>()
private const val DEDUP_WINDOW_MS = 5000L
```
**Issue:** The deduplication cache grows unbounded until cleanup threshold. A flood of notifications could cause memory pressure.

**Proposed Fix:**
```kotlin
// Use LRU cache with fixed size
private val processedNotifications = LinkedHashMap<String, Long>(100, 0.75f, true)
```

---

## **INS-006: No Input Sanitization for Merchant Names**
**File:** `MerchantNormalizer.kt` (line 7194-7205)
```kotlin
fun cleanMerchantName(rawName: String): String {
    var cleaned = rawName.trim()
    cleaned = LOCATION_PATTERN.replace(cleaned, "")
    // ...
}
```
**Issue:** Doesn't handle:
- SQL injection characters (though Room parameterizes)
- Emoji in merchant names
- Extremely long names (DoS)

**Proposed Fix:**
```kotlin
fun cleanMerchantName(rawName: String): String {
    var cleaned = rawName.trim()
    if (cleaned.length > 100) cleaned = cleaned.take(100)
    cleaned = cleaned.filter { it.isLetterOrDigit() || it.isWhitespace() }
    // ...
}
```

--
# 🔵 **ADDITIONAL INSUFFICIENCIES**

## **INS-007: No Input Validation in Bank Statement Parser**
**File:** `BankStatementParser.kt`
```kotlin
fun parse(blocks: List<TextBlock>): List<ParsedTransaction> {
    if (blocks.isEmpty()) return emptyList()
    // No validation of block content
}
```
**Issue:** No validation that blocks contain actual transaction data. Could process garbage text.
**Proposed Fix:** Add minimum validation:
```kotlin
if (blocks.isEmpty() || blocks.all { it.text.length < 5 }) return emptyList()
```

---

## **INS-008: Missing Index on `date` Column**
**File:** `Expense` entity
```kotlin
@Entity(
    indices = [/* existing indices */]
)
```
**Issue:** The `date` column is used in every period-based query but lacks an index.
**Proposed Fix:**
```kotlin
@Entity(
    indices = [
        Index("date"),
        Index("merchant"),
        // ...
    ]
)
```

---

## **INS-009: No Rate Limiting on Recurring Pattern Detection**
**File:** `RecurringExpenseEngine.kt`
```kotlin
suspend fun getPatterns(): List<RecurringPattern> {
    val allExpenses = expenseDao.getAll() // Loads ALL expenses
    // ...
}
```
**Issue:** Loads all expenses into memory for pattern detection. With 10,000+ transactions, this could cause memory pressure.
**Proposed Fix:** Use streaming or limit to last 12 months:
```kotlin
val oneYearAgo = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
val recentExpenses = expenseDao.getExpensesBetween(oneYearAgo, System.currentTimeMillis())
```

---

## **INS-010: No Error Handling in Category Default Creation**
**File:** `CategoryRepository.kt` (inferred)
```kotlin
suspend fun ensureDefaultCategories() {
    // Creates default categories if not exist
}
```
**Issue:** No error handling if category creation fails mid-way. Could leave inconsistent state.
**Proposed Fix:**
```kotlin
suspend fun ensureDefaultCategories() = withContext(Dispatchers.IO) {
    try {
        // Use transaction
        database.withTransaction {
            DEFAULT_CATEGORIES.forEach { category ->
                if (categoryDao.getByName(category.name) == null) {
                    categoryDao.insert(category)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("CategoryRepo", "Failed to ensure categories", e)
        // Report to crash analytics
    }
}
```

---


| 15 | Logic | LOGIC-001 | Week start incorrect | 🟠 High | HomeViewModel.kt |
| 16 | Logic | LOGIC-002 | Duplicate window too narrow | 🟡 Medium | ReceiptRepository.kt |
| 17 | Logic | LOGIC-003 | Frequency detection gaps | 🟡 Medium | RecurringExpenseEngine.kt |
| 18 | Logic | LOGIC-004 | Confidence threshold inconsistency | 🟡 Medium | Multiple files |
| 19 | Logic | LOGIC-005 | Day 1 pace dampening | 🟢 Low | HomeViewModel.kt |
| 20 | Logic | LOGIC-006 | Calendar instance in loop | 🟢 Low | InsightsEngine.kt |
| 21 | Logic | LOGIC-007 | Hardcoded 30 days | 🟡 Medium | SynthesisEngine.kt |
| 22 | Logic | LOGIC-008 | Confidence not propagated | 🟢 Low | ReceiptRepository.kt |
| 23 | Logic | LOGIC-009 | Category filter excludes nulls | 🟡 Medium | InsightsEngine.kt |
| 24 | Dup | DUP-001-004 | Duplicate code patterns | 🟢 Low | Multiple files |
| 25 | Dup | DUP-005-007 | Parser code duplications | 🟢 Low | Parser files |
| 26 | Ins | INS-001 | No budget validation | 🟡 Medium | BudgetRepository.kt |
| 27 | Ins | INS-002 | No OCR error recovery | 🟡 Medium | ReceiptRepository.kt |
| 28 | Ins | INS-003 | No currency support | 🟡 Medium | Multiple files |
| 29 | Ins | INS-004 | Missing merchant index | 🟠 High | Expense.kt |
| 30 | Ins | INS-005 | No rate limiting | 🟡 Medium | NotificationCaptureService.kt |
| 31 | Ins | INS-006 | No input sanitization | 🟡 Medium | MerchantNormalizer.kt |
| 32 | Ins | INS-007 | No statement validation | 🟢 Low | BankStatementParser.kt |
| 33 | Ins | INS-008 | Missing date index | 🟠 High | Expense.kt |
| 34 | Ins | INS-009 | No rate limit recurring | 🟡 Medium | RecurringExpenseEngine.kt |
| 35 | Ins | INS-010 | No error in category creation | 🟢 Low | CategoryRepository.kt |


---

