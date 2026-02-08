# Comprehensive Performance & Optimization Audit

After exhaustively reviewing your entire codebase, I've identified issues across **database**, **CPU/battery**, and **UI smoothness** categories. Here's everything:

---

## 🔴 CRITICAL ISSUES

### 1. **AnalyticsViewModel: Full dataset recomputation on EVERY expense change**
**File:** `AnalyticsViewModel.kt`
**Impact:** SEVERE lag on analytics screen, excessive CPU

```kotlin
// PROBLEM: This triggers on EVERY single expense change, recomputing everything
init {
    viewModelScope.launch {
        combine(
            repository.getAllExpenses(), // ← Emits ALL expenses as Flow
            categoryRepository.allCategories
        ) { expenses, categories ->
            Pair(expenses, categories)
        }.collect { (expenses, categories) ->
            computeAnalytics(expenses, categories, _state.value.selectedPeriod)
            // ↑ calls insightsEngine.generateInsights() which makes 8+ DB queries
            // ↑ calls insightsEngine.detectRecurring() which iterates all expenses
            // ↑ filters, groups, sorts the ENTIRE expense list multiple times
        }
    }
}
```

**And inside `computeAnalytics`:**
```kotlin
// This calls generateInsights which internally does:
// - expenseDao.getTotalForPeriod (DB query)
// - expenseDao.getCountForPeriod (DB query)
// - expenseDao.getCategoryTotalsForPeriod x2 (DB queries)
// - expenseDao.getAllMerchantStats (DB query - full table scan)
// - expenseDao.getMerchantStats (DB query)
// - expenseDao.getTopMerchantsForPeriod (DB query)
// - expenseDao.getLargestExpenseForPeriod (DB query)
// - expenseDao.getRecurringCandidates (DB query)
// - expenseDao.getDayOfWeekPattern (DB query)
// PLUS in-memory: calculateCategoryMonthlyAverages iterates ALL expenses
// PLUS in-memory: buildMerchantInsights iterates ALL expenses
// PLUS: detectRecurring iterates ALL purchases, groups, sorts
val insightsSnapshot = insightsEngine.generateInsights(categories, allExpenses)
val insights = insightsEngine.getLegacyInsights(insightsSnapshot)
val recurring = insightsEngine.detectRecurring(purchases)
```

**FIX:**
```kotlin
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val insightsEngine: InsightsEngine
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    private var computeJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                repository.getAllExpenses(),
                categoryRepository.allCategories
            ) { expenses, categories ->
                Pair(expenses, categories)
            }
            .debounce(300) // ← Debounce rapid changes
            .collectLatest { (expenses, categories) -> // ← collectLatest cancels previous
                computeAnalytics(expenses, categories, _state.value.selectedPeriod)
            }
        }
    }

    fun selectPeriod(period: TimePeriod) {
        _state.update { it.copy(selectedPeriod = period, isLoading = true) }
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            val expenses = repository.getAllExpenses().first()
            val categories = categoryRepository.allCategories.first()
            computeAnalytics(expenses, categories, period)
        }
    }

    private suspend fun computeAnalytics(
        allExpenses: List<Expense>,
        categories: List<Category>,
        period: TimePeriod
    ) {
        // Move heavy work off main thread
        withContext(Dispatchers.Default) {
            val purchases = allExpenses.filter { it.transactionType == TransactionType.PURCHASE }
            val now = System.currentTimeMillis()
            val categoryMap = categories.associateBy { it.id }
            val (currentStart, currentEnd) = getPeriodRange(period, now)
            val periodLength = currentEnd - currentStart
            val previousStart = currentStart - periodLength
            val previousEnd = currentStart

            val currentExpenses = purchases.filter { it.date in currentStart..currentEnd }
            val previousExpenses = purchases.filter { it.date in previousStart..previousEnd }

            val currentTotal = currentExpenses.sumOf { it.amount }
            val previousTotal = previousExpenses.sumOf { it.amount }
            val changePercent = if (previousTotal > 0) {
                ((currentTotal - previousTotal) / previousTotal * 100).toFloat()
            } else null

            val categoryBreakdown = currentExpenses
                .groupBy { it.categoryId }
                .mapNotNull { (catId, exps) ->
                    val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                    CategoryBreakdown(
                        category = cat,
                        total = exps.sumOf { it.amount },
                        count = exps.size,
                        percentage = if (currentTotal > 0)
                            (exps.sumOf { it.amount } / currentTotal * 100).toFloat()
                        else 0f
                    )
                }
                .sortedByDescending { it.total }

            val merchantBreakdown = currentExpenses
                .groupBy { it.merchant.uppercase() }
                .map { (_, exps) ->
                    val total = exps.sumOf { it.amount }
                    MerchantBreakdown(
                        name = exps.first().merchant,
                        totalSpent = total,
                        transactionCount = exps.size,
                        averageTransaction = total / exps.size,
                        categoryId = exps.firstOrNull()?.categoryId
                    )
                }
                .sortedByDescending { it.totalSpent }

            val chartDays = when (period) {
                TimePeriod.TODAY -> 1
                TimePeriod.WEEK -> 7
                TimePeriod.MONTH -> 30
                TimePeriod.YEAR -> 365
                TimePeriod.ALL -> {
                    val oldest = purchases.minOfOrNull { it.date } ?: now
                    ((now - oldest) / 86_400_000L).toInt().coerceIn(7, 365)
                }
            }
            val dailyTotals = insightsEngine.buildDailyTotals(currentExpenses, chartDays)

            // Only generate insights if we have meaningful data
            val insights = if (purchases.size >= 5) {
                try {
                    val insightsSnapshot = insightsEngine.generateInsights(categories, allExpenses)
                    insightsEngine.getLegacyInsights(insightsSnapshot)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            val recurring = if (purchases.size >= 10) {
                insightsEngine.detectRecurring(purchases)
            } else emptyList()

            _state.update {
                it.copy(
                    selectedPeriod = period,
                    currentTotal = currentTotal,
                    previousTotal = if (previousTotal > 0) previousTotal else null,
                    changePercent = changePercent,
                    transactionCount = currentExpenses.size,
                    categoryBreakdown = categoryBreakdown,
                    merchantBreakdown = merchantBreakdown,
                    dailyTotals = dailyTotals,
                    insights = insights,
                    recurring = recurring,
                    isLoading = false
                )
            }
        }
    }
    // ... rest unchanged
}
```

---

### 2. **HomeViewModel: Heavy computation on every expense emission**
**File:** `HomeViewModel.kt`
**Impact:** Home screen stutters when data changes

```kotlin
// PROBLEM: Every time ANY expense changes, this re-processes ALL expenses
val dashboard: StateFlow<DashboardState> = combine(
    repository.getAllExpenses(), // ← Full list, every emission
    categoryRepository.allCategories
) { expenses, categories ->
    // Iterates ALL expenses multiple times:
    // 1. filter for purchases
    // 2. sumOf for totalSpent
    // 3. groupBy categoryId
    // 4. mapNotNull + sortedByDescending
    // 5. filter for today
    // 6. filter for week
    // 7. filter for month
    // All on the MAIN thread within combine!
```

**FIX:**
```kotlin
val dashboard: StateFlow<DashboardState> = combine(
    repository.getAllExpenses(),
    categoryRepository.allCategories
) { expenses, categories ->
    expenses to categories
}
.debounce(200) // Don't recompute on rapid changes
.map { (expenses, categories) ->
    withContext(Dispatchers.Default) { // Off main thread
        computeDashboard(expenses, categories)
    }
}
.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

private fun computeDashboard(expenses: List<Expense>, categories: List<Category>): DashboardState {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis

    val tempCal = cal.clone() as Calendar
    tempCal.firstDayOfWeek = Calendar.MONDAY
    tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    if (tempCal.timeInMillis > todayStart) tempCal.add(Calendar.DAY_OF_YEAR, -7)
    val weekStart = tempCal.timeInMillis

    cal.set(Calendar.DAY_OF_MONTH, 1)
    val monthStart = cal.timeInMillis

    val categoryMap = categories.associateBy { it.id }

    // Single pass through expenses instead of multiple filters
    var totalSpent = 0.0
    var todaySpent = 0.0
    var weekSpent = 0.0
    var monthSpent = 0.0
    val categoryTotals = mutableMapOf<Long, Double>()
    val recentList = mutableListOf<Expense>()

    for (expense in expenses) {
        if (expense.transactionType != TransactionType.PURCHASE) continue
        val amount = expense.amount
        totalSpent += amount
        if (expense.date >= todayStart) todaySpent += amount
        if (expense.date >= weekStart) weekSpent += amount
        if (expense.date >= monthStart) monthSpent += amount
        expense.categoryId?.let { catId ->
            categoryTotals[catId] = (categoryTotals[catId] ?: 0.0) + amount
        }
        if (recentList.size < 5) recentList.add(expense)
    }

    val topCategories = categoryTotals.entries
        .mapNotNull { (catId, catTotal) ->
            val cat = categoryMap[catId] ?: return@mapNotNull null
            CategorySpending(cat, catTotal, if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f)
        }
        .sortedByDescending { it.total }
        .take(5)

    return DashboardState(
        totalSpent = totalSpent,
        todaySpent = todaySpent,
        weekSpent = weekSpent,
        monthSpent = monthSpent,
        transactionCount = expenses.count { it.transactionType == TransactionType.PURCHASE },
        topCategories = topCategories,
        recentExpenses = recentList
    )
}
```

---

### 3. **TransactionsViewModel: Mapping ALL expenses with category on every change**
**File:** `TransactionsViewModel.kt`
**Impact:** Transactions list lag with many items

```kotlin
// PROBLEM: Every time expenses OR categories change, maps ALL expenses
val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
    repository.getAllExpenses(), // ALL expenses
    categoryRepository.allCategories
) { expenses, categories ->
    val categoryMap = categories.associateBy { it.id }
    expenses.map { expense -> // Maps EVERY expense
        ExpenseWithCategory(expense, expense.categoryId?.let { categoryMap[it] })
    }
}
```

**FIX:**
```kotlin
val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
    repository.getAllExpenses(),
    categoryRepository.allCategories
) { expenses, categories ->
    expenses to categories
}
.debounce(150)
.map { (expenses, categories) ->
    withContext(Dispatchers.Default) {
        val categoryMap = categories.associateBy { it.id }
        expenses.map { expense ->
            ExpenseWithCategory(expense, expense.categoryId?.let { categoryMap[it] })
        }
    }
}
.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

---

### 4. **TransactionsScreen: Missing LazyColumn item keys stability + no pagination**
**File:** `TransactionsScreen.kt`
**Impact:** Scrolling jank with many transactions

The list has keys but creates a new `SimpleDateFormat` per composition and parses color on every recomposition of items not using `remember`:

```kotlin
// PROBLEM: dateFormat created on every recomposition of the screen
val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
// This is OK at screen level, but the format.format() call inside items is fine

// However TransactionItem creates color parsing inline:
// The fix is already partially there with remember(category?.color), which is good
```

**The bigger issue is no pagination.** With hundreds of transactions, LazyColumn renders all items' states:

**FIX: Add pagination to the DAO and ViewModel:**
```kotlin
// In ExpenseDao.kt - add:
@Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit OFFSET :offset")
suspend fun getExpensesPaged(limit: Int, offset: Int): List<Expense>

// Or better, use Paging 3:
@Query("SELECT * FROM expenses ORDER BY date DESC")
fun getExpensesPagingSource(): PagingSource<Int, Expense>
```

For a simpler immediate fix without Paging3, limit the initial load:
```kotlin
// In ExpenseDao - add:
@Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
fun getRecentExpensesFlow(limit: Int = 100): Flow<List<Expense>>
```

---

## 🟠 DATABASE OPTIMIZATION ISSUES

### 5. **Missing database indices for critical query patterns**
**File:** `Expense.kt`, `RawNotification.kt`, `PendingReview.kt`

```kotlin
// PROBLEM: The expenses table has these indices:
indices = [
    Index(value = ["rawNotificationId"]),
    Index(value = ["date"]),
    Index(value = ["categoryId"]),
    Index(value = ["amount", "merchant", "date"])
]
// MISSING: Index on transactionType - nearly EVERY query filters by it!
// Every analytics query has: WHERE transactionType = 'PURCHASE'
```

**FIX:**
```kotlin
@Entity(
    tableName = "expenses",
    foreignKeys = [/* ... same ... */],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["date"]),
        Index(value = ["categoryId"]),
        Index(value = ["amount", "merchant", "date"]),
        Index(value = ["transactionType"]),  // ← ADD THIS
        Index(value = ["transactionType", "date"]),  // ← ADD THIS (covers most analytics queries)
        Index(value = ["transactionType", "merchant"]),  // ← ADD THIS (covers merchant stats)
        Index(value = ["transactionType", "categoryId", "date"]),  // ← ADD THIS (covers category period queries)
    ]
)
```

Also for `raw_notifications`:
```kotlin
// Current: Index(value = ["packageName", "timestamp"])
// MISSING: Index on capturedAt (used in ORDER BY capturedAt DESC)
indices = [
    Index(value = ["packageName", "timestamp"]),
    Index(value = ["capturedAt"]),  // ← ADD THIS
    Index(value = ["isRelevant"]),  // ← ADD THIS (if queried)
]
```

For `pending_reviews`:
```kotlin
// Current: Index on rawNotificationId and status - this is OK
// But add compound index for the most common query:
indices = [
    Index(value = ["rawNotificationId"]),
    Index(value = ["status"]),
    Index(value = ["status", "createdAt"]),  // ← ADD THIS (covers getPendingFlow ORDER BY)
]
```

---

### 6. **InsightsEngine makes excessive sequential DB queries**
**File:** `InsightsEngine.kt`

```kotlin
// PROBLEM: generateInsights() makes 10+ separate DB queries sequentially
suspend fun generateInsights(...): InsightsSnapshot {
    val monthlyComparison = buildMonthlyComparison(currentMonth, previousMonth) // 4 DB queries
    val categoryInsights = buildCategoryInsights(...) // 2 DB queries
    val topMerchants = buildMerchantInsights(allExpenses) // 1 DB query
    val spendingPace = buildSpendingPace(...) // 2 DB queries
    val anomalies = findAnomalies(currentMonth, categoryMap) // 2 DB queries
    val recurringExpenses = findRecurringExpenses() // 1 DB query
    val dayOfWeekPattern = buildDayOfWeekPattern(...) // 1 DB query
    val largestTransaction = expenseDao.getLargestExpenseForPeriod(...) // 1 DB query
    // Total: ~14 sequential DB queries!
}
```

**FIX: Use async/coroutines to parallelize independent queries:**
```kotlin
suspend fun generateInsights(
    categories: List<Category>,
    allExpenses: List<Expense>
): InsightsSnapshot = coroutineScope {
    val now = System.currentTimeMillis()
    val currentMonth = getMonthPeriod(now)
    val previousMonth = getPreviousMonthPeriod(currentMonth)
    val categoryMap = categories.associateBy { it.id }

    // Parallel DB queries
    val monthlyComparisonDeferred = async { buildMonthlyComparison(currentMonth, previousMonth) }
    val categoryInsightsDeferred = async { buildCategoryInsights(currentMonth, previousMonth, categoryMap, allExpenses) }
    val merchantInsightsDeferred = async { buildMerchantInsights(allExpenses) }
    val spendingPaceDeferred = async { buildSpendingPace(currentMonth, previousMonth, allExpenses) }
    val anomaliesDeferred = async { findAnomalies(currentMonth, categoryMap) }
    val recurringDeferred = async { findRecurringExpenses() }
    val threeMonthsAgo = getMonthPeriod(now, -2)
    val dayOfWeekDeferred = async { buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs) }
    val largestDeferred = async { expenseDao.getLargestExpenseForPeriod(currentMonth.startMs, currentMonth.endMs) }

    // Await all
    val monthlyComparison = monthlyComparisonDeferred.await()
    val categoryInsights = categoryInsightsDeferred.await()
    val topMerchants = merchantInsightsDeferred.await()
    val spendingPace = spendingPaceDeferred.await()
    val anomalies = anomaliesDeferred.await()
    val recurringExpenses = recurringDeferred.await()
    val dayOfWeekPattern = dayOfWeekDeferred.await()
    val largestTransaction = largestDeferred.await()

    // ... rest of computation
}
```

---

### 7. **CategorizationEngine: DB query per word in merchant name**
**File:** `CategorizationEngine.kt`

```kotlin
// PROBLEM: For each word in merchant name, makes a separate DB query
val words = normalized.split(" ").filter { it.length >= 4 }
for (word in words) {
    val wordMatch = merchantCategoryDao.getCategoryForMerchant(word) // DB QUERY per word!
    if (wordMatch != null) return wordMatch.categoryId
}
```

This is called during `processAndSave` for every notification. With a 3-word merchant name, that's 3 DB queries just for categorization.

**FIX:** The cache already exists but the word-level matching bypasses it:
```kotlin
suspend fun categorize(merchant: String): Long? {
    val normalized = normalize(merchant)
    
    // 1. Exact match from cache first
    val allMappings = getMappings() // Uses cached mappings
    val exactMatch = allMappings.find { it.merchantPattern == normalized }
    if (exactMatch != null) return exactMatch.categoryId
    
    // 2. Substring match from cache (no DB query needed!)
    val sortedMappings = allMappings.sortedByDescending { it.merchantPattern.length }
    val paddedNormalized = " $normalized "
    for (mapping in sortedMappings) {
        if (mapping.merchantPattern.length >= 3) {
            val paddedPattern = " ${mapping.merchantPattern} "
            if (paddedNormalized.contains(paddedPattern)) {
                return mapping.categoryId
            }
        }
    }
    
    // 3. Word-level match FROM CACHE (not DB!)
    val words = normalized.split(" ").filter { it.length >= 4 }
    for (word in words) {
        val wordMatch = allMappings.find { it.merchantPattern == word }
        if (wordMatch != null) return wordMatch.categoryId
    }
    
    return null
}
```

---

### 8. **CategoryRepository.ensureDefaultCategories: Sequential DB inserts**
**File:** `CategoryRepository.kt`

```kotlin
// PROBLEM: Inserts merchant entities one by one
if (merchantEntities.isNotEmpty()) {
    // We need a bulk insert for speed
    merchantEntities.forEach { merchantCategoryDao.insert(it) } // N separate transactions!
}
```

**FIX:** Add bulk insert to MerchantCategoryDao:
```kotlin
// In MerchantCategoryDao.kt:
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertAll(merchantCategories: List<MerchantCategory>)

// In CategoryRepository:
if (merchantEntities.isNotEmpty()) {
    merchantCategoryDao.insertAll(merchantEntities)
}
```

---

### 9. **SourceStatsDao.upsert uses IGNORE strategy - broken upsert**
**File:** `SourceStatsDao.kt`

```kotlin
// PROBLEM: This inserts with IGNORE, not a true upsert
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun upsert(stats: SourceStats)
// If a row exists with the same packageName, the insert is silently ignored!
// This means ensureSourceStats works, but the name "upsert" is misleading
// and any fields meant to be updated on conflict won't be.
```

This is actually OK for the current usage in `ensureSourceStats`, but rename it:
```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertIfNotExists(stats: SourceStats) // ← Renamed for clarity
```

---

## 🟡 CPU / BATTERY ISSUES

### 10. **NotificationCaptureService: processAndSave runs full pipeline on IO thread**
**File:** `NotificationCaptureService.kt`, `NotificationRepository.kt`

```kotlin
// In processAndSave, for EVERY notification:
// 1. exists() check - DB query
// 2. dao.insert() - DB write
// 3. ensureSourceStats() - DB query + possible write
// 4. incrementTotal() - DB write
// 5. classifier.initialize() - possible disk I/O + DB query
// 6. parserRegistry.parse() - CPU (regex parsing)
// 7. merchantNormalizer.applyUserCorrections() - DB query
// 8. confidenceRouter.route() - multiple DB queries
//    - classifier.predict() - CPU
//    - sourceStatsDao.getByPackage() - DB query
//    - getMerchantRejectionRate() - 2 DB queries
//    - getPackageRejectionRate() - 2 DB queries
//    - hasPreviousApprovals() - DB query
// 9. categorize() - DB queries (potentially multiple)
// 10. expenseDao.isDuplicate() - DB query
// 11. expenseDao.insert() OR pendingReviewDao.insert() - DB write
// 12. classifier.train() - CPU + scheduled disk I/O
//
// Total: 15-20+ DB operations per notification!
```

**FIX:** Batch some operations and use `@Transaction` more effectively:
```kotlin
@Transaction
suspend fun processAndSave(notification: RawNotification) {
    // Already has @Transaction, but the individual DB calls within
    // confidenceRouter.route() are NOT part of this transaction
    // because they go through different DAOs
    
    // Quick exit: Check block list in memory cache instead of DB
    // ... (see fix #12 below)
}
```

### 11. **TransactionClassifier.initialize() called on every notification**
**File:** `NotificationRepository.kt` line in processAndSave

```kotlin
// PROBLEM: Called for every notification
classifier.initialize() // checks isLoaded flag, but acquires mutex every time
```

The `initialize()` method does a `mutex.withLock` check even when already loaded. The `@Volatile` flag should be checked before acquiring the mutex:

```kotlin
// Already has early return: if (isLoaded) return
// But the suspend function overhead + volatile read on every notification is unnecessary
// The current implementation is actually fine - the volatile check is O(1)
// Just ensure it's not calling userCorrectionDao.getCount() every time
```

Actually looking more closely, the issue is:
```kotlin
suspend fun initialize() {
    if (isLoaded) return // Fast path - OK
    mutex.withLock {
        if (isLoaded) return // Double-check - OK
        // ... loads from disk
        val correctionCount = userCorrectionDao.getCount() // DB QUERY every init!
        if (correctionCount > lastTrainingCount && ...) {
            retrainFromCorrectionsInternal() // Can be VERY expensive
        }
        isLoaded = true
    }
}
```

The fix is: once loaded, it stays loaded. The current code is correct but `retrainFromCorrectionsInternal` could be deferred:
```kotlin
suspend fun initialize() {
    if (isLoaded) return
    mutex.withLock {
        if (isLoaded) return
        if (loadFromDisk()) {
            isLoaded = true
            // Defer retrain check to background
            scope.launch {
                val correctionCount = userCorrectionDao.getCount()
                if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                    mutex.withLock { retrainFromCorrectionsInternal() }
                }
            }
            return
        }
        isLoaded = true
    }
}
```

---

### 12. **ConfidenceRouter.route(): 5-7 sequential DB queries per notification**
**File:** `ConfidenceRouter.kt`

```kotlin
suspend fun route(...): RoutingResult {
    // 1. classifier.predict() - CPU
    // 2. sourceStatsDao.getByPackage() - DB
    // 3. getMerchantRejectionRate() → 2 DB queries
    // 4. getPackageRejectionRate() → 2 DB queries  
    // 5. hasPreviousApprovals() → 1 DB query
    // Total: 6 DB queries per notification routing!
}
```

**FIX:** Cache source stats and correction rates:
```kotlin
@Singleton
class ConfidenceRouter @Inject constructor(
    private val sourceStatsDao: SourceStatsDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val classifier: TransactionClassifier
) {
    // In-memory caches
    private val sourceStatsCache = ConcurrentHashMap<String, Pair<SourceStats, Long>>()
    private val merchantRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val packageRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val approvalCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    
    private val CACHE_TTL = 60_000L // 1 minute

    private suspend fun getCachedSourceStats(packageName: String): SourceStats? {
        val cached = sourceStatsCache[packageName]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL) {
            return cached.first
        }
        val stats = sourceStatsDao.getByPackage(packageName)
        if (stats != null) {
            sourceStatsCache[packageName] = stats to System.currentTimeMillis()
        }
        return stats
    }
    
    // Similar for other cached lookups...
}
```

---

### 13. **MerchantNormalizer: DB query on every merchant normalization**
**File:** `MerchantNormalizer.kt`

```kotlin
suspend fun applyUserCorrections(merchant: String): String {
    val normalized = normalize(merchant)
    for ((key, canonical) in KNOWN_ALIASES) {
        if (normalized.contains(key)) return canonical
    }
    // DB query for EVERY notification!
    val corrected = userCorrectionDao.getMostCommonMerchantCorrection(normalized)
    return corrected ?: toTitleCase(normalized)
}
```

**FIX:** Cache corrections:
```kotlin
private val correctionCache = ConcurrentHashMap<String, String?>()
private var lastCorrectionCacheTime = 0L

suspend fun applyUserCorrections(merchant: String): String {
    val normalized = normalize(merchant)
    for ((key, canonical) in KNOWN_ALIASES) {
        if (normalized.contains(key)) return canonical
    }
    
    // Check cache first
    if (correctionCache.containsKey(normalized)) {
        return correctionCache[normalized] ?: toTitleCase(normalized)
    }
    
    val corrected = userCorrectionDao.getMostCommonMerchantCorrection(normalized)
    correctionCache[normalized] = corrected
    return corrected ?: toTitleCase(normalized)
}

fun invalidateCache() {
    correctionCache.clear()
}
```

---

### 14. **DebugViewModel: simulateMassData processes sequentially**
**File:** `DebugViewModel.kt`

```kotlin
// PROBLEM: Processes 500 notifications one by one, each with 15+ DB queries
fun simulateMassData(count: Int) {
    viewModelScope.launch {
        _isSimulating.value = true
        val notifications = seeder.generate(count)
        notifications.forEach { notification ->
            repository.processAndSave(notification) // 15+ DB ops EACH
        }
        _isSimulating.value = false
    }
}
```

With 500 notifications × 15 DB ops = 7,500 DB operations sequentially. This would freeze the UI.

**FIX:**
```kotlin
fun simulateMassData(count: Int) {
    viewModelScope.launch {
        _isSimulating.value = true
        withContext(Dispatchers.IO) {
            val notifications = seeder.generate(count)
            // Process in batches to avoid overwhelming the DB
            notifications.chunked(10).forEach { batch ->
                batch.forEach { notification ->
                    repository.processAndSave(notification)
                }
                yield() // Allow other coroutines to run
            }
        }
        _isSimulating.value = false
    }
}
```

---

## 🟡 UI SMOOTHNESS ISSUES

### 15. **DebugScreen: Nested scrollable containers**
**File:** `DebugScreen.kt`

```kotlin
// The DebugScreen has a LazyColumn as root, and inside items it has:
// - LazyRow for filters (OK - different scroll direction)
// - LazyRow for blocked apps (OK - different scroll direction)
// But the notification cards expand/collapse which causes full list relayout
```

The main issue here is that every notification card expansion triggers recomposition of the entire list. Also, blocked apps and filter chips in `item {}` blocks containing `LazyRow` work but are inefficient.

**FIX:** Use `animateContentSize()` for smooth expansion:
```kotlin
Card(
    modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .animateContentSize() // ← Add smooth animation
) {
```

### 16. **ReviewScreen: No item animation for approval/rejection**
**File:** `ReviewScreen.kt`

When items are approved/rejected, they disappear abruptly from the list.

**FIX:**
```kotlin
LazyColumn(...) {
    items(pendingReviews, key = { it.id }) { review ->
        AnimatedVisibility(
            visible = true,
            exit = shrinkVertically() + fadeOut()
        ) {
            ReviewCard(...)
        }
    }
}
```

### 17. **Color parsing on every recomposition in multiple screens**
**Files:** `HomeScreen.kt`, `CategoryScreen.kt`, `AnalyticsScreen.kt`

```kotlin
// In HomeScreen's CategorySpendingRow:
val color = try {
    Color(android.graphics.Color.parseColor(item.category.color))
} catch (e: Exception) { Color.Gray }
// This is NOT remembered! Parses the hex string on EVERY recomposition

// In CategoryScreen's CategoryItem:
val color = try {
    Color(android.graphics.Color.parseColor(category.color))
} catch (e: Exception) { Color.Gray }
// Same issue
```

**FIX:** Wrap in `remember`:
```kotlin
// HomeScreen CategorySpendingRow:
val categoryColor = remember(item.category.color) {
    try { Color(android.graphics.Color.parseColor(item.category.color)) }
    catch (e: Exception) { Color.Gray }
}

// CategoryScreen CategoryItem:
val color = remember(category.color) {
    try { Color(android.graphics.Color.parseColor(category.color)) }
    catch (e: Exception) { Color.Gray }
}
```

Note: `TransactionsScreen.kt` and `AnalyticsScreen.kt` already have `remember` for color - good!

### 18. **MainScreen: ReviewViewModel instantiated at top level**
**File:** `MainActivity.kt`

```kotlin
@Composable
fun MainScreen() {
    val reviewViewModel: ReviewViewModel = hiltViewModel()
    val pendingCount by reviewViewModel.pendingCount.collectAsState()
    // This ViewModel is created even when we're on the Home tab
    // And it starts collecting the Flow immediately
```

This is actually needed for the badge count, so it's somewhat unavoidable. But the `errorMessage` LaunchedEffect could be moved to only the Review tab.

### 19. **AnalyticsScreen chart: entryModelOf called on every recomposition**
**File:** `AnalyticsScreen.kt`

```kotlin
@Composable
fun AnalyticsChart(state: AnalyticsState) {
    // PROBLEM: Creates new model objects on every recomposition
    val entries = state.dailyTotals.values.map { it.toFloat() }
    val chartEntryModel = entryModelOf(*entries.toTypedArray())
    // This allocates arrays and creates model objects every time
}
```

**FIX:**
```kotlin
@Composable
fun AnalyticsChart(state: AnalyticsState) {
    Card(...) {
        Column(...) {
            Text("Daily Spending", ...)
            Spacer(modifier = Modifier.height(16.dp))
            if (state.dailyTotals.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data for this period", color = Color.Gray)
                }
            } else {
                val chartEntryModel = remember(state.dailyTotals) {
                    val entries = state.dailyTotals.values.map { it.toFloat() }
                    entryModelOf(*entries.toTypedArray())
                }
                Chart(
                    chart = columnChart(),
                    model = chartEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
```

---

### 20. **DebugScreen: SimpleDateFormat created per notification card**
**File:** `DebugScreen.kt`

```kotlin
@Composable
fun NotificationCard(...) {
    // PROBLEM: Each card instance creates its own SimpleDateFormat
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }
    // 'remember' helps but with 200 notification cards, that's 200 instances
```

**FIX:** Create it once at the screen level and pass it down:
```kotlin
// In DebugScreen:
val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }

// Pass to NotificationCard:
NotificationCard(
    notification = notification,
    dateFormat = dateFormat, // ← Pass shared instance
    ...
)
```

---

## 🟢 ADDITIONAL OPTIMIZATIONS

### 21. **Flow emissions: getAllExpenses() used in 4+ places simultaneously**
Multiple ViewModels observe `repository.getAllExpenses()`:
- HomeViewModel
- TransactionsViewModel  
- AnalyticsViewModel
- DebugViewModel (indirectly)

Each creates a separate Room Flow that independently queries the database.

**FIX:** Use `shareIn` in the repository:
```kotlin
@Singleton
class NotificationRepository @Inject constructor(...) {
    // Shared Flow - single DB query, multiple collectors
    private val _allExpenses: SharedFlow<List<Expense>> = expenseDao.getAllFlow()
        .shareIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )
    
    fun getAllExpenses(): Flow<List<Expense>> = _allExpenses
}
```

### 22. **Room database: No WAL mode explicitly set**
**File:** `AppModule.kt`

Room uses WAL by default on API 16+, but explicitly setting it with journal mode ensures optimal concurrent read/write:

```kotlin
fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
    return Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "expense_tracker_db"
    )
    .fallbackToDestructiveMigration()
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING) // Explicit WAL
    .build()
}
```

### 23. **Regex compilation in parsers: Lazy is good but verify**
The parsers use `by lazy` for regex patterns, which is correct. However, `GenericTransactionParser` uses `java.util.regex.Pattern` while other places use Kotlin `Regex`. Both are fine, but consistency would help maintenance.

---

## Summary of Impact by Priority

| Priority | Issue | Impact | Fix Effort |
|----------|-------|--------|-----------|
| 🔴 Critical | AnalyticsVM full recompute | Major lag on analytics | Medium |
| 🔴 Critical | HomeVM multi-pass computation | Home screen stutter | Medium |
| 🔴 Critical | Missing transactionType index | ALL analytics queries slow | Low |
| 🔴 Critical | 14 sequential DB queries in InsightsEngine | Analytics load time | Medium |
| 🟠 High | TransactionsVM mapping all expenses | List lag | Low |
| 🟠 High | ConfidenceRouter 6 DB queries/notification | Battery drain | Medium |
| 🟠 High | MerchantNormalizer DB query per notification | Battery drain | Low |
| 🟠 High | CategorizationEngine word-level DB queries | Battery drain | Low |
| 🟠 High | No debounce on Flow combines | Excessive recomputation | Low |
| 🟡 Medium | Chart model recreation | Analytics jank | Low |
| 🟡 Medium | Color parsing without remember | Recomposition waste | Low |
| 🟡 Medium | Sequential merchant category inserts | First-run slow | Low |
| 🟡 Medium | Multiple getAllExpenses() flows | Redundant DB queries | Medium |
| 🟡 Medium | No pagination for transactions | Memory with large lists | Medium |
| 🟢 Low | SimpleDateFormat per card | Minor memory | Low |
| 🟢 Low | Mass simulation sequential processing | Debug only | Low |

