

# 🐛 BUG ANALYSIS REPORT

## Category 1: Memory Leaks & Resource Management

### Issue 1.1: ViewModel Scoped to Activity Instead of Composable
**File:** `MainActivity.kt:76`
```kotlin
private val mainViewModel: MainViewModel by viewModels()
```
**Problem:** The `mainViewModel` is scoped to the Activity, but you also get another instance via `hiltViewModel()` inside `MainScreen()` at line 118. This creates TWO instances of `MainViewModel` - one scoped to Activity and one scoped to the Composable navigation graph.

**Risk:** Medium
**Proposed Fix:** Use only one ViewModel instance:
```kotlin
// Remove the activity-scoped viewModels() at line 76
// Keep only the hiltViewModel() inside MainScreen()
// Pass navigation events via callbacks instead
```

---

### Issue 1.2: SharedFlow Never Collected on First Launch
**File:** `MainActivity.kt:121-125`
```kotlin
LaunchedEffect(Unit) {
    mainViewModel.navigationRequest.collect { tabIndex ->
        selectedTab = tabIndex
    }
}
```
**Problem:** `SharedFlow` with no replay buffer will miss events emitted before collection starts. If `handleIntent()` is called before the LaunchedEffect starts running, the navigation event is lost.

**Risk:** High
**Proposed Fix:**
```kotlin
// In MainViewModel, change to StateFlow or add replay buffer:
private val _navigationRequest = MutableSharedFlow<Int>(replay = 1)
val navigationRequest = _navigationRequest.asSharedFlow()
// Or use:
private val _navigationRequest = MutableStateFlow<Int?>(null)
```

---

### Issue 1.3: Service Scope Not Properly Cancelled
**File:** `NotificationCaptureService.kt:256-257`
```kotlin
private val serviceJob = SupervisorJob()
private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
```
**Problem:** While `serviceJob.cancel()` is called in `onDestroy()`, the scope uses `Dispatchers.IO` which can have leaked coroutines if they're blocked on I/O operations.

**Risk:** Low
**Proposed Fix:** Add timeout handling:
```kotlin
override fun onDestroy() {
    serviceScope.coroutineContext.cancelChildren() // Cancel children first
    runBlocking {
        withTimeout(1000) {
            serviceJob.cancelAndJoin()
        }
    }
    // ... rest of cleanup
}
```

---

## Category 2: State Management Issues

### Issue 2.1: Missing State Hoisting for Dialog State
**File:** `MainActivity.kt:145-148`
```kotlin
var showAddExpense by remember { mutableStateOf(false) }
var showScanReceipt by remember { mutableStateOf(false) }
var showRecurringExpenses by remember { mutableStateOf(false) }
var isFabExpanded by remember { mutableStateOf(false) }
```
**Problem:** These states are not remembered with keys, meaning they won't survive configuration changes (rotation, theme change) and will reset unexpectedly.

**Risk:** Medium
**Proposed Fix:** Use `rememberSaveable`:
```kotlin
var showAddExpense by rememberSaveable { mutableStateOf(false) }
var showScanReceipt by rememberSaveable { mutableStateOf(false) }
var showRecurringExpenses by rememberSaveable { mutableStateOf(false) }
var isFabExpanded by rememberSaveable { mutableStateOf(false) }
```

---

### Issue 2.2: Race Condition in Clipboard Reading
**File:** `MainActivity.kt:248-255`
```kotlin
LaunchedEffect(Unit) {
    val text = clipboardManager.getText()?.text ?: ""
    val regex = Regex("""(\d+[\.,]\d{2})""")
    val match = regex.find(text)
    if (match != null) {
        initialAmount = match.value
    }
}
```
**Problem:** There's a race between the LaunchedEffect and the user interaction. If the user opens the AddExpenseSheet while clipboard is being read, `initialAmount` might be updated after the sheet is already displayed.

**Risk:** Medium
**Proposed Fix:** Add a loading state or ensure atomicity:
```kotlin
var initialAmount by remember { mutableStateOf<String?>(null) }
var clipboardChecked by remember { mutableStateOf(false) }

LaunchedEffect(Unit) {
    // ... clipboard logic
    clipboardChecked = true
}

if (showAddExpense && clipboardChecked) {
    AddExpenseSheet(...)
}
```

---

## Category 3: Business Logic Errors

### Issue 3.1: Incorrect Recurring Detection Logic
**File:** `InsightsEngine.kt:797-802`
```kotlin
val avgInterval = intervals.average().toInt()

val isRecurring = avgInterval in 5..9 ||
        avgInterval in 12..16 ||
        avgInterval in 25..35 ||
        avgInterval in 350..380
```
**Problem:** The `average()` result is cast to `toInt()` which truncates instead of rounding. An average of 29.7 days becomes 29, which falls outside the `25..35` range. Weekly recurring (7 days) is detected, but bi-weekly (14 days) with slight variance may fail.

**Risk:** High
**Proposed Fix:**
```kotlin
val avgInterval = intervals.average().roundToInt()

// Also widen ranges to account for variance:
val isRecurring = avgInterval in 5..10 ||       // Weekly (± variance)
        avgInterval in 12..18 ||                 // Bi-weekly
        avgInterval in 25..35 ||                 // Monthly
        avgInterval in 85..95 ||                 // Quarterly (NEW - missing!)
        avgInterval in 350..380                  // Yearly
```

---

### Issue 3.2: Division by Zero in Pace Calculation
**File:** `InsightsEngine.kt:613-614`
```kotlin
val projectedTotal = if (dayOfMonth > 0)
    currentSpent * daysInMonth.toDouble() / dayOfMonth else currentSpent
```
**Problem:** While `dayOfMonth > 0` check prevents division by zero, on day 1 of the month, the projection is highly inflated (multiplying by 30x or 31x). This creates misleading projections early in the month.

**Risk:** Medium
**Proposed Fix:**
```kotlin
// Only project after day 3 to get meaningful data
val projectedTotal = if (dayOfMonth >= 3)
    currentSpent * daysInMonth.toDouble() / dayOfMonth 
else if (dayOfMonth > 0)
    currentSpent * 10  // Conservative estimate for first 2 days
else currentSpent
```

---

### Issue 3.3: Incorrect Median Calculation for Empty List
**File:** `InsightsEngine.kt:824-833`
```kotlin
private fun calculateMedian(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    // ...
}
```
**Problem:** Returning `0.0` for empty list is semantically wrong. Median of no values should be `null` or `NaN`, not `0.0`. This affects analytics displays.

**Risk:** Low
**Proposed Fix:**
```kotlin
private fun calculateMedian(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    // ... rest stays same
}
// Update caller to handle null:
val medianTxSize = calculateMedian(currentMonthPurchases.map { it.amount }) ?: 0.0
```

---

### Issue 3.4: Anomaly Detection Too Aggressive
**File:** `InsightsEngine.kt:682`
```kotlin
if (merchantStat.maxAmount > historicalStats.avgAmount * 3.0) {
```
**Problem:** Using 3x average for anomaly detection is too aggressive for merchants with low transaction counts. A merchant with 3 transactions of €10, €11, €32 would flag the €32 as anomaly.

**Risk:** Medium
**Proposed Fix:**
```kotlin
// Use standard deviation instead, or increase threshold for low sample sizes:
val multiplier = when {
    historicalStats.txCount < 5 -> 4.0  // Higher threshold for small samples
    historicalStats.txCount < 10 -> 3.5
    else -> 3.0
}
if (merchantStat.maxAmount > historicalStats.avgAmount * multiplier) {
```



---


---

## Category 5: Null Safety Issues

### Issue 5.1: Potential NPE in Category Lookup
**File:** `InsightsEngine.kt:498`
```kotlin
val category = categoryMap[ct.categoryId] ?: return@mapNotNull null
```
**Problem:** While this handles null, the `mapNotNull` discards the entire category insight. This silently drops data that might indicate a data integrity issue (expense with deleted category).

**Risk:** Medium
**Proposed Fix:** Log the data integrity issue:
```kotlin
val category = categoryMap[ct.categoryId]
if (category == null) {
    Log.w("InsightsEngine", "Category ${ct.categoryId} not found for expense")
    return@mapNotNull null
}
```

---



## Category 6: Database & Migration Issues



### Issue 6.2: Potential Index Missing After Migration 15→16
**File:** `AppDatabase.kt:354-355`
```kotlin
database.execSQL("DROP INDEX IF EXISTS index_expenses_transactionType_merchant")
database.execSQL("DROP INDEX IF EXISTS index_expenses_date_transactionType")
```
**Problem:** The index `index_expenses_transactionType_merchant` was created in MIGRATION_14_15 but dropped in MIGRATION_15_16. However, no replacement index with similar purpose is created, potentially degrading query performance for merchant lookups.

**Risk:** Medium (Performance)
**Proposed Fix:**
```kotlin
// Add a new composite index that serves both purposes:
database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_merchant_date ON expenses (transactionType, merchant, date)")
```

---

### Issue 6.3: Duplicate Detection Query Logic Flaw
**File:** `ExpenseDao.kt:721-728`
```kotlin
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM expenses 
        WHERE (ABS(amount - :amount) < 0.01 OR ABS(amount - :amount) / amount < 0.001)
        AND merchant = :merchant 
        AND ABS(date - :date) <= :windowMs
    )
""")
suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean
```
**Problem:** The duplicate detection uses OR logic which is flawed:
- `ABS(amount - :amount) < 0.01` - This catches exact amounts within 1 cent
- `ABS(amount - :amount) / amount < 0.001` - This catches amounts within 0.1%

But the division by `amount` (existing row's amount) can cause:
1. Division by zero if `amount = 0`
2. Skewed results for very small amounts (€0.50 / 0.001 = 0.0005 threshold is too tight)

**Risk:** High
**Proposed Fix:**
```kotlin
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM expenses 
        WHERE merchant = :merchant 
        AND ABS(amount - :amount) < :amountThreshold
        AND ABS(date - :date) <= :windowMs
    )
""")
suspend fun isDuplicate(
    amount: Double, 
    merchant: String, 
    date: Long, 
    windowMs: Long = 300000,
    amountThreshold: Double = 0.01  // 1 cent tolerance
): Boolean
```

---

## Category 7: Parser Issues

### Issue 7.1: Regex Pattern Can Match Years as Amounts
**File:** `MainActivity.kt:250`
```kotlin
val regex = Regex("""(\d+[\.,]\d{2})""")
```
**Problem:** This regex matches ANY number with two decimal places, including years like "20.24" or version numbers. The clipboard could contain "Version 20.24" and that would be parsed as an amount.

**Risk:** Medium
**Proposed Fix:**
```kotlin
// Require currency symbol or context
val regex = Regex("""(?:€|$|EUR)?\s*(\d{1,6}[\.,]\d{2})\s*(?:€|$|EUR)?""")
// And validate the captured value is reasonable:
if (match != null) {
    val value = match.value.replace(",", ".").toDoubleOrNull()
    if (value != null && value in 0.01..100000.0) {
        initialAmount = match.value
    }
}
```

---



## Category 8: Concurrency Issues



### Issue 8.2: BKTree Rebuild Race Condition
**File:** `MerchantNormalizer.kt:2823-2833`
```kotlin
private suspend fun getOrBuildTree(): StringBKTree {
    return treeMutex.withLock {
        val now = System.currentTimeMillis()
        if (bkTree == null || now - lastTreeRebuild > TREE_REBUILD_INTERVAL) {
            // ... build tree
            bkTree = tree
            lastTreeRebuild = now
        }
        bkTree!!
    }
}
```
**Problem:** While mutex-protected, the `bkTree!!` assertion can fail if the tree is set to null by `invalidateTreeCache()` between the check and the return.

**Risk:** Medium
**Proposed Fix:**
```kotlin
private suspend fun getOrBuildTree(): StringBKTree {
    return treeMutex.withLock {
        val now = System.currentTimeMillis()
        val currentTree = bkTree
        if (currentTree == null || now - lastTreeRebuild > TREE_REBUILD_INTERVAL) {
            val tree = StringBKTree.create()
            dao.getTopMerchants(1000).forEach { tree.insert(it.searchKey) }
            bkTree = tree
            lastTreeRebuild = now
            tree
        } else {
            currentTree
        }
    }
}
```

---



## Category 10: UI/UX Issues

### Issue 10.1: HeroBentoCard Gradient Never Applied
**File:** `BentoCard.kt:550-557`
```kotlin
val heroGradient = remember {
    Brush.linearGradient(...)
}
// But this gradient is NEVER USED anywhere!
BentoCard(
    modifier = modifier,
    containerColor = Color.Transparent, // This is wrong - should use gradient
    // ...
)
```
**Problem:** The `heroGradient` is computed but never applied. The card ends up with a transparent background that doesn't show the intended gradient effect.

**Risk:** Low (Visual bug)
**Proposed Fix:**
```kotlin
BentoCard(
    modifier = modifier.background(heroGradient, RoundedCornerShape(28.dp)),
    containerColor = Color.Transparent,
    // ...
)
```

---

