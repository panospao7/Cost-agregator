# COMPREHENSIVE MASTER REMEDIATION PLAN
## ExpenseTracker Codebase - Validated Issues Roadmap

**Version:** 1.0  
**Date:** February 2026  
**Issues Validated:** 27 Critical/High/Medium  
**Estimated Effort:** 6-8 weeks  
**Risk Level:** Medium (requires careful testing)

---

## EXECUTIVE SUMMARY

This master plan addresses **27 validated critical issues** across the ExpenseTracker codebase, organized into 5 implementation phases. The plan prioritizes data integrity and stability fixes first, followed by architectural improvements.

### Quick Stats:
- **Phase 1 (Critical):** 5 issues - 1 week
- **Phase 2 (High):** 7 issues - 2 weeks  
- **Phase 3 (Medium):** 15 issues - 3 weeks
- **Phase 4 (Refactoring):** Architecture improvements - 2 weeks
- **Phase 5 (Optimization):** Performance tuning - 1 week

**Total Estimated Effort:** 160-200 hours

---

## PHASE 1: CRITICAL DATA INTEGRITY FIXES
**Timeline:** Week 1  
**Priority:** 🔴 CRITICAL  
**Risk if Delayed:** Data corruption, app crashes, security issues

### 1.1 Add Entity Validation (Category.kt)
**Issue:** Category entity accepts empty names, invalid colors, unlimited icon length
**Effort:** 2 hours  
**Files:** `data/database/entity/Category.kt`

```kotlin
// CURRENT (BROKEN):
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,        // Can be ""
    val icon: String,        // Unlimited length
    val color: String,       // Can be "invalid"
    val isDefault: Boolean = false
)

// FIXED:
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val color: String,
    val isDefault: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Category name cannot be blank" }
        require(name.length <= 50) { "Category name too long (max 50 chars)" }
        require(icon.length <= 10) { "Icon too long (max 10 chars)" }
        require(color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) { 
            "Color must be valid hex code (e.g., #FF5733)" 
        }
    }
}
```

**Testing:**
```kotlinn@Test(expected = IllegalArgumentException::class)
fun `empty category name throws exception`() {
    Category(name = "", icon = "📦", color = "#FF5733")
}

@Test(expected = IllegalArgumentException::class)
fun `invalid color throws exception`() {
    Category(name = "Food", icon = "🍔", color = "invalid")
}
```

**Database Migration:**
```kotlinn// Add migration to clean invalid existing data
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Clean empty names
        db.execSQL("UPDATE categories SET name = 'Unnamed' WHERE name = '' OR name IS NULL")
        // Fix invalid colors to default
        db.execSQL("UPDATE categories SET color = '#607D8B' WHERE color NOT REGEXP '^#[0-9A-Fa-f]{6}$'")
    }
}
```

---

### 1.2 Add Validation to ParsedTransaction
**Issue:** ParsedTransaction accepts invalid amounts, dates, confidence scores
**Effort:** 3 hours  
**Files:** `domain/parser/AppParserRegistry.kt` (where ParsedTransaction is defined)

```kotlinn// ADD VALIDATION:
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val type: TransactionType,
    val confidence: Float,
    val date: Long? = null
) {
    init {
        require(amount.isFinite() && amount > 0) { 
            "Amount must be positive and finite: $amount" 
        }
        require(amount <= 1_000_000) { 
            "Amount exceeds maximum: $amount" 
        }
        require(confidence in 0f..1f) { 
            "Confidence must be between 0 and 1: $confidence" 
        }
        require(merchant.isNotBlank()) { 
            "Merchant cannot be blank" 
        }
        require(currency.matches(Regex("^[A-Z]{3}$"))) { 
            "Currency must be ISO 4217 code (e.g., EUR, USD): $currency" 
        }
        date?.let {
            require(it > 0) { "Date must be positive timestamp" }
            require(it <= System.currentTimeMillis() + 86_400_000) { 
                "Date cannot be in the future" 
            }
        }
    }
}
```

**Integration Points:**
- All parsers must catch validation exceptions and return null
- NotificationRepository should handle validation failures gracefully
- Log validation failures for debugging

---

### 1.3 Fix AmountUtils Edge Case Bug
**Issue:** Invalid formats like "1,23,456" parsed as 123456 instead of null
**Effort:** 2 hours  
**Files:** `domain/util/AmountUtils.kt`

```kotlinn// CURRENT (BUGGY):
hasComma -> {
    val parts = cleaned.split(",")
    if (parts.size == 2 && parts[1].length <= 2) {
        cleaned.replace(",", ".")
    } else {
        cleaned.replace(",", "")  // "1,23,456" -> "123456" (WRONG!)
    }
}

// FIXED:
hasComma -> {
    val parts = cleaned.split(",")
    when {
        parts.size == 2 && parts[1].length <= 2 -> {
            // Likely decimal: "1,50" -> "1.50"
            cleaned.replace(",", ".")
        }
        parts.size >= 2 && parts.all { it.isNotEmpty() && it.all { c -> c.isDigit() } } -> {
            // Thousand separators: "1,234,567" -> "1234567"
            cleaned.replace(",", "")
        }
        else -> {
            // Ambiguous/invalid: "1,23,456" - reject
            Timber.w("Ambiguous amount format: $amountStr")
            return null
        }
    }
}
```

**Test Cases:**
```kotlinn@Test
fun `valid formats parse correctly`() {
    assertEquals(1234.56, AmountUtils.parseAmount("1,234.56"))  // US
    assertEquals(1234.56, AmountUtils.parseAmount("1.234,56"))  // European
    assertEquals(1234567.0, AmountUtils.parseAmount("1,234,567"))  // Thousands
    assertEquals(1.5, AmountUtils.parseAmount("1,50"))  // Decimal
}

@Test
fun `invalid formats return null`() {
    assertNull(AmountUtils.parseAmount("1,23,456"))  // Ambiguous
    assertNull(AmountUtils.parseAmount("abc"))  // Non-numeric
    assertNull(AmountUtils.parseAmount(""))  // Empty
}
```

---

### 1.4 Fix BudgetCalculator MONTHLY Period Logic
**Issue:** February 29th/31st edge cases cause incorrect period calculation
**Effort:** 4 hours  
**Files:** `domain/budget/BudgetCalculator.kt`

```kotlinn// PROBLEM CASE:
// Anchor: January 31st
// Current: February 15th
// Expected: Jan 31 - Feb 28 (or 29)
// Actual: Feb 28 - Mar 28 (WRONG!)

// SOLUTION: Track anchor day separately, don't coerce to current month
BudgetPeriod.MONTHLY -> {
    val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
    val currentMonthStart = cal.apply { 
        set(Calendar.DAY_OF_MONTH, 1) 
    }.timeInMillis
    
    // Find the most recent occurrence of anchor day
    val calStart = Calendar.getInstance().apply {
        timeInMillis = currentMonthStart
        // Try to set anchor day in current month
        val daysInMonth = getActualMaximum(Calendar.DAY_OF_MONTH)
        val actualAnchorDay = anchorDay.coerceAtMost(daysInMonth)
        set(Calendar.DAY_OF_MONTH, actualAnchorDay)
        
        // If anchor day hasn't occurred yet this month, go to previous month
        if (timeInMillis > evaluationTime) {
            add(Calendar.MONTH, -1)
            val prevDays = getActualMaximum(Calendar.DAY_OF_MONTH)
            set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(prevDays))
        }
    }
    
    val start = calStart.timeInMillis
    
    // End is start of next period
    val calEnd = Calendar.getInstance().apply {
        timeInMillis = start
        add(Calendar.MONTH, 1)
        val nextDays = getActualMaximum(Calendar.DAY_OF_MONTH)
        set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(nextDays))
    }
    
    PeriodRange(start, calEnd.timeInMillis)
}
```

**Test Cases:**
```kotlinn@Test
fun `monthly period with month-end anchor`() {
    // Anchor: Jan 31
    val jan31 = createTimestamp(2024, 1, 31)
    val feb15 = createTimestamp(2024, 2, 15)
    
    val period = calculator.calculatePeriodWindowForTime(
        BudgetPeriod.MONTHLY, jan31, feb15
    )
    
    // Should be Jan 31 - Feb 29 (2024 is leap year)
    assertEquals(createTimestamp(2024, 1, 31), period.start)
    assertEquals(createTimestamp(2024, 2, 29), period.end)
}
```

---

### 1.5 Implement BudgetMonitor Scope Cancellation
**Issue:** CoroutineScope never cancelled, causes memory leaks
**Effort:** 2 hours  
**Files:** `domain/budget/BudgetMonitor.kt`

```kotlinn// CURRENT (LEAKY):
@Singleton
class BudgetMonitor @Inject constructor(...) {
    private val serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    // No cleanup!
}

// FIXED:
@Singleton
class BudgetMonitor @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,  // Inject instead
    ...
) {
    // Use injected scope that follows app lifecycle
}

// ALTERNATIVE: Add cleanup method
@Singleton
class BudgetMonitor @Inject constructor(...) {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + ioDispatcher)
    
    // Call this from Application.onTerminate() or use @OnLifecycleEvent
    fun cleanup() {
        serviceJob.cancel()
    }
}

// In Application class:
override fun onTerminate() {
    super.onTerminate()
    budgetMonitor.cleanup()
}
```

**Hilt Configuration:**
```kotlinn// Add to AppModule
@Provides
@Singleton
@ApplicationScope
fun provideApplicationScope(): CoroutineScope {
    return CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

// Qualifier
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
```

---

## PHASE 1 CHECKPOINT

**Deliverables:**
- [ ] All 5 critical fixes implemented
- [ ] Unit tests written and passing
- [ ] Database migration tested
- [ ] No regression in existing functionality

**Success Criteria:**
- Category entity rejects invalid data
- Amount parsing handles edge cases correctly
- Budget periods calculate correctly for all edge cases
- No memory leaks detected in heap dumps

---

## PHASE 2: HIGH-PRIORITY ARCHITECTURE FIXES
**Timeline:** Weeks 2-3  
**Priority:** 🟠 HIGH  
**Risk if Delayed:** Technical debt accumulation, maintenance burden

### 2.1 Fix CategorizationEngine Double Database Query
**Issue:** getCache() and getPatternsSet() query DB separately
**Effort:** 3 hours  
**Files:** `domain/categorization/CategorizationEngine.kt`

```kotlinn// CURRENT (INEFFICIENT):
private suspend fun getCache(): List<MerchantCategory> {
    return cacheMutex.withLock {
        if (cachedMappings == null) {
            val all = merchantCategoryDao.getAll()  // Query #1
            cachedMappings = all.sortedByDescending { it.merchantPattern.length }
        }
        cachedMappings!!
    }
}

private suspend fun getPatternsSet(): Set<String> {
    return cacheMutex.withLock {
        if (cachedPatternsSet == null) {
            val all = merchantCategoryDao.getAll()  // Query #2 - REDUNDANT!
            cachedPatternsSet = all.map { it.merchantPattern.lowercase() }.toSet()
        }
        cachedPatternsSet!!
    }
}

// FIXED:
private data class CacheData(
    val mappings: List<MerchantCategory>,
    val patternsSet: Set<String>
)

private var cachedData: CacheData? = null

private suspend fun getCache(): List<MerchantCategory> {
    return getCacheData().mappings
}

private suspend fun getPatternsSet(): Set<String> {
    return getCacheData().patternsSet
}

private suspend fun getCacheData(): CacheData {
    return cacheMutex.withLock {
        val now = System.currentTimeMillis()
        if (cachedData == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
            val all = merchantCategoryDao.getAll()  // Single query!
            cachedData = CacheData(
                mappings = all.sortedByDescending { it.merchantPattern.length },
                patternsSet = all.map { it.merchantPattern.lowercase() }.toSet()
            )
            lastCacheTime = now
        }
        cachedData!!
    }
}
```

**Performance Impact:** 50% reduction in DB queries on cache miss

---

### 2.2 Add File Type Validation to ReceiptOcrService
**Issue:** Accepts any non-PDF file as image
**Effort:** 2 hours  
**Files:** `domain/receipt/ReceiptOcrService.kt`

```kotlinncompanion object {
    private val ALLOWED_IMAGE_TYPES = setOf(
        "image/jpeg",
        "image/png", 
        "image/webp",
        "image/heic"
    )
    private const val MAX_FILE_SIZE = 20 * 1024 * 1024  // 20MB
}

suspend fun processUri(uri: Uri): OcrResult {
    val mimeType = context.contentResolver.getType(uri) ?: ""
    
    // Validate file type
    if (mimeType == "application/pdf") {
        return processPdf(uri)
    } else if (mimeType in ALLOWED_IMAGE_TYPES) {
        return processImage(uri)
    } else {
        throw IllegalArgumentException(
            "Unsupported file type: $mimeType. " +
            "Supported types: ${ALLOWED_IMAGE_TYPES.joinToString()}, application/pdf"
        )
    }
}

private suspend fun processImage(imageUri: Uri): OcrResult {
    // Validate file size
    val fileSize = context.contentResolver.openFileDescriptor(imageUri, "r")?.use {
        it.statSize
    } ?: 0
    
    if (fileSize > MAX_FILE_SIZE) {
        throw IllegalArgumentException(
            "File too large: ${fileSize / 1024 / 1024}MB. Maximum: ${MAX_FILE_SIZE / 1024 / 1024}MB"
        )
    }
    
    // ... rest of processing
}
```

**Security Benefit:** Prevents processing of malicious files (XML bombs, SVG scripts)

---

### 2.3 Add Flow Error Handling
**Issue:** Individual flow errors not caught before combine
**Effort:** 4 hours  
**Files:** `data/repository/FinancialWeatherRepository.kt`, `ui/screens/home/HomeViewModel.kt`

```kotlinn// CURRENT (FRAGILE):
fun getFinancialWeather(): Flow<FinancialWeather> = combine(
    expenseRepository.getAllExpenses(),
    budgetRepository.getBudgetStatuses(),
    recurringExpenseRepository.getAllFlow(),
    plannedExpenseRepository.getAllPlannedExpenses(),
    savingsGoalRepository.getAllGoals()
) { ... }.catch { e -> ... }

// FIXED (ROBUST):
fun getFinancialWeather(): Flow<FinancialWeather> = combine(
    expenseRepository.getAllExpenses()
        .catch { e -> 
            Timber.e(e, "Error loading expenses")
            emit(emptyList()) 
        },
    budgetRepository.getBudgetStatuses()
        .catch { e -> 
            Timber.e(e, "Error loading budgets")
            emit(emptyList()) 
        },
    recurringExpenseRepository.getAllFlow()
        .catch { e -> 
            Timber.e(e, "Error loading recurring")
            emit(emptyList()) 
        },
    plannedExpenseRepository.getAllPlannedExpenses()
        .catch { e -> 
            Timber.e(e, "Error loading planned")
            emit(emptyList()) 
        },
    savingsGoalRepository.getAllGoals()
        .catch { e -> 
            Timber.e(e, "Error loading goals")
            emit(emptyList()) 
        }
) { expenses, budgets, recurring, planned, goals ->
    // Now individual failures don't break the whole flow
    calculateWeather(expenses, budgets, recurring, planned, goals)
}.catch { e ->
    Timber.e(e, "Error calculating financial weather")
    emit(defaultWeather())
}
```

**Pattern to Apply:** All repository combine() calls should catch on individual flows

---

### 2.4 Fix Race Condition in NotificationRepository
**Issue:** TOCTOU (Time-of-Check-Time-of-Use) in notification processing
**Effort:** 3 hours  
**Files:** `data/repository/NotificationRepository.kt`

```kotlinn// CURRENT (RACE CONDITION):
suspend fun processAndSaveInternal(notification: RawNotification) {
    // Check outside transaction
    if (dao.exists(...)) return
    
    // Heavy work
    val parsed = parserRegistry.parse(...)  // 50-100ms
    
    database.withTransaction {
        // Check inside transaction
        if (dao.exists(...)) return@withTransaction
        dao.insert(notification)  // May still conflict!
    }
}

// FIXED (ATOMIC):
suspend fun processAndSaveInternal(notification: RawNotification) {
    // Skip initial check, rely on transaction + INSERT OR IGNORE
    
    val parsed = parserRegistry.parse(...)
    
    database.withTransaction {
        // Use INSERT OR IGNORE pattern
        val rawId = dao.insertOrIgnore(notification)
        if (rawId == -1L) {
            // Already exists
            return@withTransaction
        }
        
        // Process normally
        ...
    }
}

// DAO changes:
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertOrIgnore(notification: RawNotification): Long
```

**Alternative (if unique constraint exists):**
```kotlinn@Query("INSERT OR IGNORE INTO raw_notifications ...")
suspend fun insertOrIgnore(notification: RawNotification): Long
```

---

### 2.5-2.7 God Object Refactoring (Overview)
**Effort:** 20 hours total  
**Files:** Multiple

#### 2.5 HomeViewModel Refactoring
**Strategy:** Extract Use Cases

```kotlinn// Extract to UseCase classes:
class CalculateFinancialForecastUseCase(...)
class CalculateSpendingPaceUseCase(...)
class GenerateNaturalLanguageInsightUseCase(...)
class CalculateFinancialRunwayUseCase(...)

// ViewModel becomes:
class HomeViewModel @Inject constructor(
    private val calculateForecast: CalculateFinancialForecastUseCase,
    private val calculatePace: CalculateSpendingPaceUseCase,
    private val generateInsight: GenerateNaturalLanguageInsightUseCase,
    private val calculateRunway: CalculateFinancialRunwayUseCase,
    private val dashboardRepository: DashboardRepository
) : ViewModel() {
    // Now < 200 lines
}
```

#### 2.6 MainActivity Refactoring
**Strategy:** Extract composables

```kotlinn// New files:
ui/screens/main/MainNavigation.kt
ui/screens/main/MainFab.kt
ui/screens/main/PermissionHandler.kt

// MainActivity becomes:
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContent {
            MainScreen()
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { MainBottomNavigation(navController) },
        floatingActionButton = { MainFab() }
    ) { padding ->
        MainNavHost(navController, padding)
    }
}
```

#### 2.7 FinancialWeatherRepository Layer Fix
**Strategy:** Move domain logic to Use Case

```kotlinn// NEW Use Case:
class GenerateFinancialWeatherUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val insightsEngine: InsightsEngine,
    private val synthesisEngine: SynthesisEngine,
    private val narrativeGenerator: NarrativeGenerator
) {
    operator fun invoke(): Flow<FinancialWeather> {
        // Orchestrate repositories and engines here
    }
}

// Repository becomes:
class FinancialWeatherRepository @Inject constructor(
    private val generateWeather: GenerateFinancialWeatherUseCase
) {
    fun getFinancialWeather(): Flow<FinancialWeather> = generateWeather()
}
```

---

## PHASE 3: MEDIUM-PRIORITY IMPROVEMENTS
**Timeline:** Weeks 4-6  
**Priority:** 🟡 MEDIUM  
**Risk if Delayed:** Code maintainability issues

### 3.1 Centralize Date Calculations
**Effort:** 6 hours  
**Strategy:** Extend TimePeriodUtils

```kotlinnobject TimePeriodUtils {
    // Existing functions...
    
    // ADD these to replace duplicated logic:
    fun getDayOfMonth(timestamp: Long): Int
    fun getDaysInMonth(timestamp: Long): Int
    fun getDaysRemainingInMonth(timestamp: Long): Int
    fun getDayIndexFromMonthStart(timestamp: Long): Int
    fun getPeriodRange(period: BudgetPeriod, anchorDate: Long, evaluationTime: Long): PeriodRange
    fun addMonths(timestamp: Long, months: Int): Long
    fun isSameMonth(timestamp1: Long, timestamp2: Long): Boolean
}
```

**Migration Plan:**
1. Add new functions to TimePeriodUtils
2. Replace usages in SynthesisEngine, InsightsEngine, HomeViewModel
3. Deprecate old inline calculations
4. Test all affected features

---

### 3.2 Centralize Currency Formatting
**Effort:** 4 hours  
**Files:** New `domain/util/CurrencyFormatter.kt`

```kotlinnobject CurrencyFormatter {
    fun format(amount: Double, currencyCode: String = "EUR", showCents: Boolean = true): String {
        val symbol = getCurrencySymbol(currencyCode)
        return if (showCents) {
            "$symbol${String.format("%.2f", amount)}"
        } else {
            "$symbol${String.format("%.0f", amount)}"
        }
    }
    
    fun formatCompact(amount: Double, currencyCode: String = "EUR"): String {
        return when {
            amount >= 1_000_000 -> "${getCurrencySymbol(currencyCode)}${amount / 1_000_000}M"
            amount >= 1_000 -> "${getCurrencySymbol(currencyCode)}${amount / 1_000}K"
            else -> format(amount, currencyCode)
        }
    }
    
    private fun getCurrencySymbol(currencyCode: String): String {
        return try {
            java.util.Currency.getInstance(currencyCode).symbol
        } catch (e: Exception) {
            "€"
        }
    }
}

// Extension function for convenience:
fun Double.toCurrency(currencyCode: String = "EUR"): String = 
    CurrencyFormatter.format(this, currencyCode)
```

**Replace in:**
- BudgetBlockPartyCard.kt
- FinancialRunwayCard.kt
- ReceiptScanScreen.kt
- All other UI components

---

### 3.3 Centralize Regex Patterns
**Effort:** 5 hours  
**Files:** New `domain/util/AmountExtractionUtils.kt`

```kotlinnobject AmountExtractionUtils {
    // Amount patterns
    val AMOUNT_PATTERN = Regex("""(\d{1,10}(?:[.,\s]\d{3})*[.,]\d{2})""")
    val CURRENCY_PATTERN = Regex("""[€\$£¥]|EUR|USD|GBP""")
    val POSITIVE_AMOUNT_PATTERN = Regex("""(?:paid|sent|purchased|charged)[\s:]*[€\$£]?\s*([\d.,]+)""", RegexOption.IGNORE_CASE)
    
    // Merchant patterns
    val MERCHANT_CLEANUP_PATTERN = Regex("""[^\w\s-]""")
    
    // Date patterns
    val DATE_DDMMYYYY_PATTERN = Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})""")
    val DATE_MMDDYYYY_PATTERN = Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})""")
    
    fun extractAmount(text: String): Pair<Double, String>? {
        // Centralized extraction logic
    }
}
```

**Refactor all parsers to use these patterns**

---

### 3.4 Make Thresholds Configurable
**Effort:** 4 hours  
**Files:** New `domain/config/AppConfig.kt`

```kotlinnobject AppConfig {
    // Amount limits
    const val MAX_TRANSACTION_AMOUNT = 1_000_000.0
    const val MAX_RECEIPT_AMOUNT = 50_000.0
    
    // Recurring detection thresholds
    const val RECURRING_AMOUNT_VARIANCE_THRESHOLD = 0.35
    const val RECURRING_CONFIDENCE_THRESHOLD = 0.50
    const val RECURRING_MIN_OCCURRENCES = 3
    
    // Cache expiry
    const val MERCHANT_CACHE_EXPIRY_MS = 300_000L  // 5 minutes
    const val SOURCE_STATS_CACHE_EXPIRY_MS = 300_000L
    
    // Notification cooldowns
    const val DAILY_NOTIFICATION_COOLDOWN_MS = 6 * 60 * 60 * 1000L
    const val WEEKLY_NOTIFICATION_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    
    // Flow timeouts
    const val FLOW_SUBSCRIPTION_TIMEOUT_MS = 5000L
    const val DEBOUNCE_DELAY_MS = 300L
    
    // Forecasting
    const val LIKELY_EXPENSE_WEIGHT = 0.7
    const val DEFAULT_HORIZON_DAYS = 31
    
    // OCR
    const val MAX_OCR_IMAGE_DIMENSION = 1024
    const val MAX_OCR_FILE_SIZE_MB = 20
}
```

**Replace all hardcoded magic numbers with these constants**

---

## PHASE 4: ARCHITECTURAL REFACTORING
**Timeline:** Weeks 7-8  
**Priority:** 🟢 ARCHITECTURE  
**Risk:** Medium (requires regression testing)

### 4.1 Split AppModule
**Effort:** 6 hours  
**Files:** Create new module files

```kotlinn// di/DatabaseModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase { ... }
}

// di/DaoModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DaoModule {
    @Provides
    @Singleton
    fun provideExpenseDao(db: AppDatabase) = db.expenseDao()
    // ... other DAOs
}

// di/ServiceModule.kt
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {
    @Provides
    @Singleton
    fun provideNotificationService(...) = ...
}
```

---

### 4.2 Implement Clean Architecture Separation
**Effort:** 20 hours  
**Strategy:** Create Use Case layer

```
domain/
  usecase/
    forecast/
      CalculateFinancialForecastUseCase.kt
      GenerateForecastNarrativeUseCase.kt
    budget/
      CalculateBudgetStatusUseCase.kt
      CheckBudgetAlertsUseCase.kt
    expense/
      CategorizeExpenseUseCase.kt
      DetectDuplicatesUseCase.kt
    receipt/
      ProcessReceiptUseCase.kt
      ExtractReceiptDataUseCase.kt
```

**Example Use Case:**
```kotlinnclass ProcessReceiptUseCase @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val merchantNormalizer: MerchantNormalizer,
    private val categoryRepository: CategoryRepository
) {
    suspend operator fun invoke(imageUri: Uri): Result<ProcessedReceipt> {
        return try {
            // 1. OCR
            val ocrResult = ocrService.processUri(imageUri)
            
            // 2. Parse
            val parsed = receiptParser.parse(ocrResult.fullText)
            
            // 3. Normalize
            val normalizedMerchant = merchantNormalizer.normalize(
                parsed.merchantName ?: ""
            )
            
            // 4. Categorize
            val category = categoryRepository.categorize(normalizedMerchant)
            
            Result.Success(ProcessedReceipt(
                merchant = normalizedMerchant,
                amount = parsed.total,
                category = category,
                date = parsed.date
            ))
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
```

---

## PHASE 5: OPTIMIZATION & POLISH
**Timeline:** Week 9  
**Priority:** 🔵 OPTIMIZATION

### 5.1 Performance Optimizations
- Optimize RecyclerView/Flow performance
- Add image caching
- Implement database query optimization
- Add lazy loading for large lists

### 5.2 Documentation
- Add KDoc to all public APIs
- Create architecture decision records (ADRs)
- Document complex business logic

### 5.3 Testing
- Achieve >70% code coverage
- Add integration tests for critical flows
- Add UI tests for main user journeys

---

## TESTING STRATEGY

### Unit Tests (All Phases)
- Every fix must have accompanying unit test
- Test edge cases explicitly
- Mock external dependencies

### Integration Tests (Phases 2-4)
- Test repository-database integration
- Test Use Case orchestration
- Test ViewModel-StateFlow integration

### UI Tests (Phase 5)
- Critical user journeys
- Error handling scenarios
- Accessibility testing

---

## RISK MITIGATION

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Breaking changes | Medium | High | Feature flags, gradual rollout |
| Performance regression | Low | Medium | Benchmark tests before/after |
| Data migration issues | Low | High | Backup before migration, test on staging |
| Scope creep | High | Medium | Strict adherence to plan, weekly reviews |

---

## SUCCESS METRICS

### Code Quality
- [ ] Zero CRITICAL issues remaining
- [ ] < 5 HIGH issues remaining
- [ ] Test coverage > 70%
- [ ] No new lint violations

### Performance
- [ ] App startup < 2 seconds
- [ ] Screen transitions < 300ms
- [ ] No ANR (Application Not Responding) errors
- [ ] Memory usage stable (no leaks)

### Stability
- [ ] Crash-free rate > 99.5%
- [ ] No data corruption incidents
- [ ] All edge cases handled gracefully

---

## WEEKLY CHECKPOINTS

### Week 1 Checkpoint
- [ ] All Phase 1 issues resolved
- [ ] Unit tests passing
- [ ] Manual testing complete
- [ ] Code review approved

### Week 3 Checkpoint
- [ ] Phase 2 issues resolved
- [ ] Integration tests passing
- [ ] Performance baseline established
- [ ] Documentation updated

### Week 6 Checkpoint
- [ ] Phase 3 issues resolved
- [ ] No regression in existing features
- [ ] Code coverage report generated
- [ ] Stakeholder demo completed

### Week 9 Checkpoint (Final)
- [ ] All phases complete
- [ ] Full regression test passed
- [ ] Performance benchmarks met
- [ ] Production deployment ready

---

## APPENDIX: ISSUE REFERENCE MATRIX

| Issue ID | Description | Phase | Effort | Status |
|----------|-------------|-------|--------|--------|
| C-001 | Category entity validation | 1 | 2h | ✅ DONE |
| C-002 | ParsedTransaction validation | 1 | 3h | ✅ DONE |
| C-003 | AmountUtils edge case | 1 | 2h | ✅ DONE |
| C-004 | BudgetCalculator period logic | 1 | 4h | ✅ DONE |
| C-005 | BudgetMonitor scope leak | 1 | 2h | ✅ DONE |
| H-001 | CategorizationEngine double query | 2 | 3h | ✅ DONE |
| H-002 | File type validation | 2 | 2h | ✅ DONE |
| H-003 | Flow error handling | 2 | 4h | ✅ DONE |
| H-004 | Race condition fix | 2 | 3h | ✅ DONE |
| H-005 | HomeViewModel refactoring | 2 | 8h | ✅ DONE (DashboardDataProvider) |
| H-006 | MainActivity refactoring | 2 | 6h | ✅ DONE (Extracted components) |
| H-007 | FinancialWeatherRepository layer | 2 | 4h | ⏸ PENDING |
| M-001 | Date calculations centralization | 3 | 6h | ✅ DONE |
| M-002 | Currency formatting | 3 | 4h | ✅ DONE |
| M-003 | Regex patterns | 3 | 5h | ✅ DONE |
| M-004 | Configurable thresholds | 3 | 4h | ✅ DONE |
| M-005+ | Other medium issues | 3 | 15h | ✅ DONE |
| A-001 | AppModule split | 4 | 6h | ✅ DONE |
| A-002 | Use Case layer | 4 | 20h | ✅ DONE |
| O-001+ | Optimizations | 5 | 20h | ✅ DONE |

---

## DOCUMENTATION UPDATES

The following documentation files have been updated to reflect the new architecture:

- **ARCHITECTURE.md**: Updated DI section, added Use Cases segment, added new utilities
- **CODEBASE_SEGMENTS.md**: Added Segments 14 (Use Cases), 15 (Performance), 16 (Configuration), updated file counts

---

*This master plan provides a comprehensive roadmap for systematically improving the ExpenseTracker codebase. Each phase builds upon the previous, ensuring stability while addressing technical debt.*
