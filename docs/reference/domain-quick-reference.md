# Quick Reference Guide - Domain Layer

## Find What You Need

### By Feature Area

| Feature | Key Files | Entry Point |
|---------|-----------|------------|
| **Dashboard** | `engine/DashboardFollowThroughEngine.kt` | `ComputeDashboardWidgetsUseCase` |
| **Analytics** | `analytics/InsightsEngine.kt` | `InsightsEngine.generateInsights()` |
| **Budget** | `budget/BudgetCalculator.kt` | `CalculateBudgetStatusUseCase` |
| **Categorization** | `categorization/CategorizationEngine.kt` | `CategorizeExpenseUseCase` |
| **Receipt Processing** | `receipt/ReceiptOcrService.kt` | `ProcessReceiptUseCase` |
| **AI Features** | `ai/usecase/*` | Feature-specific use case |
| **Forecasting** | `forecasting/FinancialStressForecastEngine.kt` | `CalculateFinancialForecastUseCase` |
| **Groups/Sharing** | `groups/GroupTransactionCoordinator.kt` | `AddGroupExpenseUseCase` |
| **Location** | `location/LocationInsightsEngine.kt` | Dashboard + Analytics |
| **Savings** | `savings/SmartSavingsEngine.kt` | `LifestyleSavingsPromptUseCase` |
| **Transaction Lifecycle** | `transaction/lifecycle/TransactionLifecycleCoordinator.kt` | `coordinator.createExpense(request)` |

### By Problem

**"How do I..."**

| Problem | Solution |
|---------|----------|
| ...create an expense? | `TransactionLifecycleCoordinator.createExpense(request)` — handles validate → normalize → dedupe → insert → event log → side effects |
| ...update an expense? | `TransactionLifecycleCoordinator.updateExpense(expense)` — writes UPDATED event + persists |
| ...delete an expense? | `TransactionLifecycleCoordinator.deleteExpense(expenseId)` — writes DELETED event + deletes |
| ...track expense origin? | `ExpenseSource` enum on each expense + `transaction_events` audit log |
| ...detect duplicate transactions? | Built into `TransactionLifecycleCoordinator.createExpense()` + `DetectDuplicateExpenseUseCase` |
| ...get spending insights? | `InsightsEngine.generateInsights()` |
| ...calculate a budget period? | `BudgetCalculator.calculatePeriodRange()` |
| ...categorize an expense? | `CategorizeExpenseUseCase` + `CategorizationEngine` |
| ...process a receipt? | `ProcessReceiptUseCase` (end-to-end) |
| ...detect anomalies? | `InsightsEngine.findAnomalies()` (dual-path) |
| ...find recurring expenses? | `RecurringExpenseEngine.getPatterns()` |
| ...make AI recommendations? | Feature-specific input builder → `ExecuteFinancialQueryUseCase` |
| ...split an expense? | `EnhancedSplitManager` |
| ...convert currency? | `CurrencyConverter` |

## Key Types

### `PeriodRange` (`domain/core/time/`)

Typed half-open period model replacing raw `Pair<Long, Long>`:

```kotlin
data class PeriodRange(
    val kind: PeriodKind,
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val label: String = ""
) {
    fun contains(timestamp: Long): Boolean
    val durationMillis: Long
    val isCalendarPeriod: Boolean  // true for TODAY, THIS_WEEK, THIS_MONTH, etc.
}
```

**Contract:** `timestamp >= startInclusiveMillis && timestamp < endExclusiveMillis`

### `PeriodKind` (`domain/core/time/`)

Semantic period classification:

| Calendar periods | Rolling windows |
|-----------------|-----------------|
| `TODAY`, `THIS_WEEK`, `LAST_WEEK` | `LAST_7_DAYS` |
| `THIS_MONTH`, `LAST_MONTH` | `LAST_30_DAYS` |
| `THIS_QUARTER`, `LAST_QUARTER` | |
| `THIS_YEAR`, `LAST_YEAR` | `CUSTOM` |

**Rule:** Calendar labels **must** use calendar helpers (`getMonthRange`, `getWeekRange`). Rolling labels **must** use rolling helpers (`getLastNCalendarDaysRange`). Never mix them.

### `ExpenseSource` (`domain/transaction/`)

Enum tracking the origin of every expense:

| Source | Meaning |
|--------|---------|
| `MANUAL_ENTRY` | User manually entered via Add Expense form |
| `NOTIFICATION_AUTO_ACCEPT` | Auto-accepted from a notification |
| `REVIEW_APPROVAL` | Approved through the review queue |
| `RECEIPT_SCAN` | Created from a scanned receipt |
| `RECEIPT_BATCH_REVIEW` | Batch-reviewed from receipt scans |
| `BANK_STATEMENT_REVIEW` | Created from bank statement parsing |
| `CSV_IMPORT` | Imported via CSV file |
| `EMAIL_RECEIPT` | Ingestion from email receipt |
| `GROUP_EXPENSE` | Shared/group expense |
| `BANK_API_SYNC` | Synced from bank API connection |
| `RECURRING_GENERATED` | Auto-generated recurring expense |
| `DEBUG_TOOL` | Created via debug screen |
| `MIGRATION` | Backfilled during database migration |
| `UNKNOWN` | Origin not identified |

### `CreateExpenseRequest` / `CreateExpenseResult` (`domain/transaction/`)

Source-neutral creation request and sealed result for the `TransactionLifecycleCoordinator`:

```kotlin
// Build a request
val request = CreateExpenseRequest(
    merchant = "Starbucks",
    amount = 5.50,
    currency = "EUR",
    date = timeProvider.now(),
    transactionType = TransactionType.PURCHASE,
    source = ExpenseSource.MANUAL_ENTRY,
    categoryId = 42L,
    notes = "Morning coffee"
)

// Send through the coordinator
val result = coordinator.createExpense(request)
when (result) {
    is CreateExpenseResult.Created -> { /* expenseId = result.expenseId */ }
    is CreateExpenseResult.DuplicateSkipped -> { /* existingExpenseId, reason */ }
    is CreateExpenseResult.ValidationFailed -> { /* errors list */ }
    is CreateExpenseResult.InsertConflict -> { /* dedupeKey */ }
    is CreateExpenseResult.Error -> { /* exception */ }
}
```

### `TransactionLifecycleCoordinator` (`domain/transaction/lifecycle/`)

**Single entry point for ALL expense creation, update, and delete.** Injected via `@Singleton` into all consumer classes.

```kotlin
class MyViewModel @Inject constructor(
    private val coordinator: TransactionLifecycleCoordinator
) {
    fun addExpense() {
        viewModelScope.launch {
            val request = CreateExpenseRequest(
                merchant = name,
                amount = amount,
                currency = currency,
                date = date,
                transactionType = TransactionType.PURCHASE,
                source = ExpenseSource.MANUAL_ENTRY
            )
            when (val result = coordinator.createExpense(request)) {
                is CreateExpenseResult.Created -> { /* success */ }
                is CreateExpenseResult.ValidationFailed -> { /* show errors */ }
                else -> { /* handle other cases */ }
            }
        }
    }
}
```

**Lifecycle pipeline:** `validate → normalize → dedupe → insert atomic → event log → side effects`

Side effects (dispatched by `TransactionSideEffectDispatcher`):
1. Budget check via `BudgetMonitor.checkBudgets()`
2. Anomaly alert via `AnomalyAlertOrchestrator.checkAndAlert()`
3. Merchant-category pattern learning via `MerchantCategoryRepository.learnPattern()`

### `TimeProvider` (`domain/util/`)

Single source of "now" for the entire app:

```kotlin
interface TimeProvider {
    fun now(): Long  // epoch milliseconds
}
```

**Usage in a ViewModel:**
```kotlin
class MyViewModel @Inject constructor(
    private val timeProvider: TimeProvider
) {
    fun loadMonth() {
        val now = timeProvider.now()               // ✅ single capture
        val range = TimePeriodUtils.getMonthRange(now)
        // ...
    }
}
```

**In tests:**
```kotlin
val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
val range = TimePeriodUtils.getMonthRange(timeProvider.now())
// range = [April 1 00:00, May 1 00:00)
```

---

## Key Concepts

### The Insights Snapshot Pattern

InsightsEngine is the hub for analytics. It returns an `InsightsSnapshot` containing:

```kotlin
InsightsSnapshot(
    currentMonth: MonthPeriod,
    monthlyComparison: MonthlyComparison,      // vs previous
    categoryInsights: List<CategoryInsight>,    // top categories
    topMerchants: List<MerchantInsight>,       // top spending
    spendingPace: SpendingPace,                // daily rate projection
    anomalies: List<AnomalyTransaction>,       // outliers
    recurringExpenses: List<RecurringExpense>, // detected subscriptions
    dayOfWeekPattern: List<DayOfWeekInsight>,  // 7-day breakdown
    largestTransaction: Expense?,              // max this month
    averageTransactionSize: Double,            // avg amount
    medianTransactionSize: Double,             // median amount
    totalMonthsOfData: Int                     // historical depth
)
```

**Usage:** Fetch once, use for all dashboard metrics.

### The Budget Period Calculation

BudgetCalculator handles 5 period types:

```
DAILY    → 24-hour window from start time
WEEKLY   → 7 days, aligned to anchor weekday
MONTHLY  → Calendar month from anchor day
QUARTERLY → 3-month window (Q1, Q2, Q3, Q4)
YEARLY   → 365 days from anniversary
```

**Usage:** All budget logic depends on period range accuracy.

### The Recommendation Pipeline

```
Transaction Event
    ↓
DashboardFollowThroughEngine.generateRecommendations()
    ↓
Applies 4 deterministic rules:
    1. High-amount (adaptive threshold)
    2. Category-specific
    3. Merchant-specific
    4. Recent transactions (7-day)
    ↓
Returns up to 5 recommendations (priority-sorted)
    ↓
AI artifact provides summary text (optional)
```

### The Dual Anomaly Detection

**Path 1: Merchant-level (DB-backed)**
- Compares max vs historical average
- Multiplier: 5x (few), 4x (5-10), 3x (10+)
- Precise, historical context

**Path 2: Statistical (in-memory)**
- IQR, MAD, contextual analysis
- Fires on new merchants
- Catches distribution outliers

**Result:** Merged + deduplicated list (top 10)

## Common Patterns

### Pattern 1: Async Parallel Execution

```kotlin
// InsightsEngine.generateInsights()
val deferred1 = async { buildMonthlyComparison(...) }
val deferred2 = async { buildCategoryInsights(...) }
// ... more in parallel ...
val result1 = deferred1.await()  // wait for all
```

**When to use:** Heavy computations, independent queries

### Pattern 2: Error Resilience

```kotlin
val result = try {
    buildCategoryInsights(...)
} catch (e: Exception) {
    // Return safe default instead of crashing
    emptyList()
}
```

**When to use:** Optional analytics features that shouldn't crash dashboard

### Pattern 3: Period Range Pair

```kotlin
// Instead of separate start/end params:
val period: Pair<Long, Long> = Pair(startMs, endMs)

// Used everywhere:
expenseRepository.getTotalForPeriod(period.first, period.second)
```

**When to use:** Enforce start < end, keep paired values together

### Pattern 4: Coordinator Pattern (Transaction Lifecycle)

```kotlin
// All expense creation routes through a single coordinator
class MyService @Inject constructor(
    private val coordinator: TransactionLifecycleCoordinator
) {
    suspend fun createFromSource(...): CreateExpenseResult {
        val request = CreateExpenseRequest(
            merchant = ...,
            amount = ...,
            currency = ...,
            date = timeProvider.now(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MY_SOURCE  // each source identifies itself
        )
        return coordinator.createExpense(request)
    }
}
```

**When to use:** Any code that creates, updates, or deletes an expense must go through the coordinator. Direct `expenseDao.insertAtomic()` is forbidden outside grandfathered files.

### Pattern 5: Normalization → Classification

```kotlin
// CategorizationEngine pipeline:
merchant = MerchantCanonicalizer.normalize(merchant)  // "Starbucks" ← "STARBUCKS S.A."
keywords = CategoryKeywords.getKeywords(category)
if (SemanticKeywordMatcher.matches(merchant, keywords)) {
    return category
}
// Fallback: ML classifier
```

**When to use:** Multi-layered classifiers with increasing complexity

## Dependency Injection Patterns

### Standard Injection

```kotlin
@Singleton
class MyEngine @Inject constructor(
    private val repository: MyRepository,
    private val helper: HelperService,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher
) { ... }
```

**Scope:** Most engines use @Singleton

### Dispatcher Qualifiers

```kotlin
@DefaultDispatcher   // Default coroutine dispatcher (CPU-bound)
@IoDispatcher        // IO-bound tasks (file, network)
@MainDispatcher      // Main thread (Android-only)
```

**Usage:** InsightsEngine uses @DefaultDispatcher for parallel work

## Performance Tips

### 1. InsightsEngine Caching
- It's expensive (~750 lines, 8+ db queries, 7 sub-engines)
- Cache result for 5-10 minutes
- Re-compute only on new transaction or manual refresh

### 2. Category/Merchant Lists
- Load once at app startup
- Cache in memory (both are small datasets)
- Category → keywords can be pre-indexed

### 3. Anomaly Detection
- Statistical path (in-memory) is fast
- Merchant path (DB-backed) queries for all merchants
- Limit to top 100 merchants to reduce DB load

### 4. Async Best Practices
- Use `coroutineScope { async { ... } }` for true parallelism
- Don't spawn async for sequential work (use `withContext`)
- Always `await()` all async jobs before returning

## Testing Checklist

- [ ] Mock `ExpenseRepository` for use case tests
- [ ] Test edge cases: empty periods, null values, zero amounts
- [ ] Test period calculations for month-end (28, 29, 30, 31 days)
- [ ] Test anomaly detection with synthetic outliers
- [ ] Test categorization fallback chain
- [ ] Test concurrent async operations (InsightsEngine)
- [ ] Test UI text rendering (post-rendering in presentation, not domain)

## Clean Architecture Checklist

- [ ] No Android imports (except debug utilities)
- [ ] No UI framework imports (Compose, androidx.ui)
- [ ] No View/Activity references
- [ ] No Context in constructors (pass what you need, not Context)
- [ ] No SharedPreferences (use repository pattern)
- [ ] Return domain models, not entities
- [ ] Suspend functions for async (not callbacks)

## File Size Reference

Large files (potential refactor candidates):

| File | Lines | Reason |
|------|-------|--------|
| `analytics/InsightsEngine.kt` | 751 | Hub for all analytics, 7 sub-engines |
| `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt` | 222 | 6 query types × 2 paths each |
| `budget/BudgetCalculator.kt` | 153 | 5 period types × complex calendar math |
| `location/LocationInsightsEngine.kt` | ? | Geographic analytics |

**Refactor approach:** Extract sub-methods to dedicated utilities (already done for most)

## Common Gotchas

1. **Period Range Direction:** Always `start < end`. BudgetCalculator validates this.
2. **Half-Open Contract:** All period ranges are `[startInclusive, endExclusive)`. Never use `23:59:59.999` as an endpoint — use midnight of the next period.
3. **Calendar vs. Rolling:** Calendar labels ("This Month") must use calendar helpers (`getMonthRange`). Rolling labels ("Last 30 Days") must use rolling helpers (`getLastNCalendarDaysRange`). Confusing these was the #1 time bug.
4. **No Direct `now()`:** Never call `System.currentTimeMillis()`, `Instant.now()`, or `LocalDate.now()` in business logic. Inject `TimeProvider` and call `timeProvider.now()`.
5. **DST-Safe Day Math:** Do NOT use `(end - start) / 86_400_000` for day counts — use `TimePeriodUtils.daysBetween(start, end)`.
6. **ALWAYS use the Coordinator for writes:** Never call `expenseDao.insertAtomic()`, `expenseDao.update()`, or `expenseDao.delete()` directly outside the grandfathered list in `DAO_ACCESS_GUARDRAILS.md`. Bypassing the coordinator skips validation, deduplication, event logging, and side effects.
6. **Merchant Normalization:** Must use canonical key (`MerchantKeyGenerator.generate()`) for lookups.
7. **Category IDs:** Can be null. Treat as "Uncategorized" / "GENERAL".
8. **Timezone Handling:** All timestamps are UTC milliseconds. No timezone conversion in domain.
9. **Leap Year:** BudgetCalculator handles Feb 29 correctly.
10. **Empty Lists:** Analytics gracefully handle zero transactions (return empty insights, not crash).
11. **Null Safety:** Use `?.let { }` for optional fields (category, merchant, location).

## Integration Checklist

**Before adding new feature to domain:**

- [ ] Define domain model (data class in `model/`)
- [ ] Create use case (in `usecase/feature/`) or engine (in `feature/`)
- [ ] Depend on repositories (data layer), not UI
- [ ] Use `suspend fun` for async operations
- [ ] Inject via Dagger (mark @Inject + @Singleton if shared)
- [ ] Return domain model, wrapped in `Result<T>` if error-prone
- [ ] Handle null/empty gracefully
- [ ] Add Timber logging for debugging
- [ ] No hardcoded strings (use AppConstants or repository config)
- [ ] Test with mock repositories
- [ ] Document public methods with KDoc
- [ ] **If creating an expense:** use `TransactionLifecycleCoordinator.createExpense()`, **not** direct `expenseDao.insertAtomic()`
- [ ] **If updating/deleting an expense:** use `TransactionLifecycleCoordinator.updateExpense()` / `deleteExpense()`, **not** direct `expenseDao.update()` / `delete()`
- [ ] **Set `source` on every expense:** always pass the correct `ExpenseSource` enum value in the `CreateExpenseRequest`
- [ ] **Inject `TimeProvider` for timestamps:** never call `System.currentTimeMillis()`

---

**Last Updated:** April 4, 2026
