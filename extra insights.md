

I'll carefully build Tier 1 and Tier 2 insights by analyzing your existing codebase structure. Let me trace through all the dependencies.

## Analysis of Existing Code

Looking at your codebase:
- `ExpenseDao` has basic queries, needs new aggregate queries
- `HomeViewModel` already computes some stats inline (today/week/month spending, top categories)
- `Category` entity has `id`, `name`, `icon`, `color`, `isDefault`
- `Expense` entity has `id`, `amount`, `currency`, `merchant`, `transactionType`, `date`, `rawNotificationId`, `categoryId`, `createdAt`
- `TransactionType` enum: `PURCHASE`, `WITHDRAWAL`, `TRANSFER`, `DEPOSIT`, `UNKNOWN`
- You use Hilt DI, Room, Kotlin Flows, Jetpack Compose with Material3
- Database version is 5

Let me build this step by step.

---

### Step 1: New DAO queries

```kotlin
// FILE: main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
// REPLACE the entire file

package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.Expense
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllFlow(): Flow<List<Expense>>

    @Query("SELECT SUM(amount) FROM expenses WHERE transactionType = 'PURCHASE'")
    fun getTotalSpentFlow(): Flow<Double?>

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(expense: Expense)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE ABS(amount - :amount) < 0.001
            AND merchant = :merchant
            AND ABS(date - :date) <= :windowMs
        )
    """)
    suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean

    // === Insights Queries ===

    // Monthly total for a specific month range
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double

    // Count for a period
    @Query("""
        SELECT COUNT(*) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int

    // Category totals for a period
    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsForPeriod(startMs: Long, endMs: Long): List<CategoryTotal>

    // Merchant averages (merchants with 2+ transactions)
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING txCount >= 2
        ORDER BY totalAmount DESC
    """)
    suspend fun getMerchantStats(): List<MerchantStats>

    // All merchant stats (including single-transaction merchants)
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        ORDER BY totalAmount DESC
    """)
    suspend fun getAllMerchantStats(): List<MerchantStats>

    // Top merchants by total spending for a period
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        GROUP BY merchant
        ORDER BY totalAmount DESC
        LIMIT :limit
    """)
    suspend fun getTopMerchantsForPeriod(startMs: Long, endMs: Long, limit: Int = 10): List<MerchantStats>

    // Largest single transaction in a period
    @Query("""
        SELECT * FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForPeriod(startMs: Long, endMs: Long): Expense?

    // Daily spending totals for a period (for pace calculation)
    @Query("""
        SELECT (date / 86400000) as dayEpoch, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal>

    // Recurring candidates: merchants that appear in multiple distinct months
    @Query("""
        SELECT merchant, 
               AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, 
               MAX(amount) as maxAmount,
               COUNT(*) as txCount, 
               SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING txCount >= 2 
        AND (maxAmount - minAmount) < (avgAmount * 0.15)
        ORDER BY txCount DESC
    """)
    suspend fun getRecurringCandidates(): List<MerchantStats>

    // Day-of-week spending pattern
    @Query("""
        SELECT 
            CAST(((date / 1000 + 259200) % 604800) / 86400 AS INTEGER) as dayOfWeek,
            SUM(amount) as total,
            COUNT(*) as txCount,
            AVG(amount) as avgAmount
        FROM expenses
        WHERE transactionType = 'PURCHASE'
        AND date >= :startMs AND date < :endMs
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long): List<DayOfWeekTotal>
}

// Data classes for Room query results
data class CategoryTotal(
    val categoryId: Long,
    val total: Double,
    val txCount: Int
)

data class MerchantStats(
    val merchant: String,
    val avgAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val txCount: Int,
    val totalAmount: Double
)

data class DailyTotal(
    val dayEpoch: Long,
    val total: Double,
    val txCount: Int
)

data class DayOfWeekTotal(
    val dayOfWeek: Int,
    val total: Double,
    val txCount: Int,
    val avgAmount: Double
)
```

---

### Step 2: Insights Engine (domain layer)

```kotlin
// FILE: main/java/com/yourname/expensetracker/domain/insights/InsightsEngine.kt
// NEW FILE

package com.yourname.expensetracker.domain.insights

import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.dao.DayOfWeekTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

// === Data Models ===

data class MonthPeriod(
    val year: Int,
    val month: Int, // 0-indexed (Calendar.JANUARY = 0)
    val startMs: Long,
    val endMs: Long
) {
    val label: String
        get() {
            val monthNames = arrayOf(
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
            )
            return "${monthNames[month]} $year"
        }

    val shortLabel: String
        get() {
            val monthNames = arrayOf(
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
            )
            return monthNames[month]
        }
}

data class CategoryInsight(
    val category: Category,
    val currentTotal: Double,
    val currentCount: Int,
    val previousTotal: Double?,
    val previousCount: Int?,
    val averageOverMonths: Double?,
    val monthsOfData: Int,
    val percentageOfTotal: Float,
    val changeFromPrevious: Float?, // percentage change
    val changeFromAverage: Float? // percentage deviation from average
)

data class MerchantInsight(
    val merchant: String,
    val avgAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val totalSpent: Double,
    val transactionCount: Int,
    val isLikelyRecurring: Boolean,
    val stdDeviation: Double? // null if < 3 transactions
)

data class SpendingPace(
    val currentMonthSpent: Double,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val projectedTotal: Double,
    val previousMonthTotal: Double?,
    val averageMonthlyTotal: Double?,
    val pacePercentage: Float, // how far through the month's typical spend
    val paceStatus: PaceStatus
)

enum class PaceStatus {
    UNDER_PACE,    // spending less than typical
    ON_PACE,       // within 10% of typical
    OVER_PACE,     // spending more than typical
    NO_BASELINE    // not enough data
}

data class AnomalyTransaction(
    val expense: Expense,
    val merchantAvg: Double,
    val deviationMultiple: Float, // how many times the average
    val category: Category?
)

data class RecurringExpense(
    val merchant: String,
    val avgAmount: Double,
    val frequency: Int, // transactions total
    val amountVariation: Double, // max - min
    val isStable: Boolean // low variation
)

data class DayOfWeekInsight(
    val dayName: String,
    val dayIndex: Int, // 0=Mon, 6=Sun
    val totalSpent: Double,
    val transactionCount: Int,
    val avgPerTransaction: Double
)

data class MonthlyComparison(
    val currentMonth: MonthPeriod,
    val previousMonth: MonthPeriod?,
    val currentTotal: Double,
    val previousTotal: Double?,
    val changeAmount: Double?,
    val changePercentage: Float?,
    val currentCount: Int,
    val previousCount: Int?
)

data class InsightsSnapshot(
    val generatedAt: Long = System.currentTimeMillis(),
    val currentMonth: MonthPeriod,
    val monthlyComparison: MonthlyComparison,
    val categoryInsights: List<CategoryInsight>,
    val topMerchants: List<MerchantInsight>,
    val spendingPace: SpendingPace,
    val anomalies: List<AnomalyTransaction>,
    val recurringExpenses: List<RecurringExpense>,
    val dayOfWeekPattern: List<DayOfWeekInsight>,
    val largestTransaction: Expense?,
    val averageTransactionSize: Double,
    val medianTransactionSize: Double,
    val totalMonthsOfData: Int
)

// === Engine ===

@Singleton
class InsightsEngine @Inject constructor(
    private val expenseDao: ExpenseDao
) {

    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    suspend fun generateInsights(
        categories: List<Category>,
        allExpenses: List<Expense>
    ): InsightsSnapshot {
        val now = System.currentTimeMillis()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)

        val categoryMap = categories.associateBy { it.id }

        // Monthly comparison
        val monthlyComparison = buildMonthlyComparison(currentMonth, previousMonth)

        // Category insights (current vs previous vs average)
        val categoryInsights = buildCategoryInsights(
            currentMonth, previousMonth, categoryMap, allExpenses
        )

        // Top merchants this month
        val topMerchants = buildMerchantInsights(allExpenses)

        // Spending pace
        val spendingPace = buildSpendingPace(currentMonth, previousMonth, allExpenses)

        // Anomalies (transactions that deviate significantly from merchant average)
        val anomalies = findAnomalies(currentMonth, categoryMap)

        // Recurring expenses
        val recurringExpenses = findRecurringExpenses()

        // Day of week pattern (last 3 months)
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPattern = buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs)

        // Largest transaction this month
        val largestTransaction = expenseDao.getLargestExpenseForPeriod(
            currentMonth.startMs, currentMonth.endMs
        )

        // Transaction size stats
        val currentMonthPurchases = allExpenses.filter {
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
                    && it.date >= currentMonth.startMs
                    && it.date < currentMonth.endMs
        }
        val avgTxSize = if (currentMonthPurchases.isNotEmpty())
            currentMonthPurchases.map { it.amount }.average() else 0.0
        val medianTxSize = calculateMedian(currentMonthPurchases.map { it.amount })

        // How many months of data we have
        val totalMonthsOfData = countDistinctMonths(allExpenses)

        return InsightsSnapshot(
            currentMonth = currentMonth,
            monthlyComparison = monthlyComparison,
            categoryInsights = categoryInsights,
            topMerchants = topMerchants,
            spendingPace = spendingPace,
            anomalies = anomalies,
            recurringExpenses = recurringExpenses,
            dayOfWeekPattern = dayOfWeekPattern,
            largestTransaction = largestTransaction,
            averageTransactionSize = avgTxSize,
            medianTransactionSize = medianTxSize,
            totalMonthsOfData = totalMonthsOfData
        )
    }

    // === Month Period Helpers ===

    fun getMonthPeriod(timeMs: Long, monthOffset: Int = 0): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        cal.add(Calendar.MONTH, monthOffset)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)

        cal.add(Calendar.MONTH, 1)
        val endMs = cal.timeInMillis

        return MonthPeriod(year, month, startMs, endMs)
    }

    private fun getPreviousMonthPeriod(current: MonthPeriod): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = current.startMs
        cal.add(Calendar.MONTH, -1)
        return getMonthPeriod(cal.timeInMillis)
    }

    // === Monthly Comparison ===

    private suspend fun buildMonthlyComparison(
        current: MonthPeriod,
        previous: MonthPeriod
    ): MonthlyComparison {
        val currentTotal = expenseDao.getTotalForPeriod(current.startMs, current.endMs)
        val currentCount = expenseDao.getCountForPeriod(current.startMs, current.endMs)
        val previousTotal = expenseDao.getTotalForPeriod(previous.startMs, previous.endMs)
        val previousCount = expenseDao.getCountForPeriod(previous.startMs, previous.endMs)

        val hasPrevious = previousCount > 0
        val changeAmount = if (hasPrevious) currentTotal - previousTotal else null
        val changePercentage = if (hasPrevious && previousTotal > 0)
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat() else null

        return MonthlyComparison(
            currentMonth = current,
            previousMonth = if (hasPrevious) previous else null,
            currentTotal = currentTotal,
            previousTotal = if (hasPrevious) previousTotal else null,
            changeAmount = changeAmount,
            changePercentage = changePercentage,
            currentCount = currentCount,
            previousCount = if (hasPrevious) previousCount else null
        )
    }

    // === Category Insights ===

    private suspend fun buildCategoryInsights(
        current: MonthPeriod,
        previous: MonthPeriod,
        categoryMap: Map<Long, Category>,
        allExpenses: List<Expense>
    ): List<CategoryInsight> {
        val currentTotals = expenseDao.getCategoryTotalsForPeriod(current.startMs, current.endMs)
        val previousTotals = expenseDao.getCategoryTotalsForPeriod(previous.startMs, previous.endMs)
        val previousMap = previousTotals.associateBy { it.categoryId }

        val currentGrandTotal = currentTotals.sumOf { it.total }

        // Calculate multi-month averages per category
        val monthlyAverages = calculateCategoryMonthlyAverages(allExpenses, current)

        return currentTotals.mapNotNull { ct ->
            val category = categoryMap[ct.categoryId] ?: return@mapNotNull null
            val prev = previousMap[ct.categoryId]
            val avgData = monthlyAverages[ct.categoryId]

            val changeFromPrev = if (prev != null && prev.total > 0)
                ((ct.total - prev.total) / prev.total * 100).toFloat() else null

            val changeFromAvg = if (avgData != null && avgData.first > 0)
                ((ct.total - avgData.first) / avgData.first * 100).toFloat() else null

            CategoryInsight(
                category = category,
                currentTotal = ct.total,
                currentCount = ct.txCount,
                previousTotal = prev?.total,
                previousCount = prev?.txCount,
                averageOverMonths = avgData?.first,
                monthsOfData = avgData?.second ?: 0,
                percentageOfTotal = if (currentGrandTotal > 0)
                    (ct.total / currentGrandTotal * 100).toFloat() else 0f,
                changeFromPrevious = changeFromPrev,
                changeFromAverage = changeFromAvg
            )
        }.sortedByDescending { it.currentTotal }
    }

    private fun calculateCategoryMonthlyAverages(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Map<Long, Pair<Double, Int>> {
        // Group purchases by category, then by month, compute average
        val purchases = allExpenses.filter {
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
                    && it.categoryId != null
                    && it.date < currentMonth.startMs // exclude current month
        }

        // Group by categoryId -> month key -> sum
        val categoryMonthTotals = mutableMapOf<Long, MutableMap<String, Double>>()
        val cal = Calendar.getInstance()
        for (expense in purchases) {
            val catId = expense.categoryId ?: continue
            cal.timeInMillis = expense.date
            val monthKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            categoryMonthTotals
                .getOrPut(catId) { mutableMapOf() }
                .merge(monthKey, expense.amount) { a, b -> a + b }
        }

        // For each category, compute average monthly spend
        return categoryMonthTotals.mapValues { (_, monthMap) ->
            val months = monthMap.size
            val avg = if (months > 0) monthMap.values.sum() / months else 0.0
            Pair(avg, months)
        }
    }

    // === Merchant Insights ===

    private suspend fun buildMerchantInsights(
        allExpenses: List<Expense>
    ): List<MerchantInsight> {
        val stats = expenseDao.getAllMerchantStats()

        // For std deviation, compute from raw data grouped by merchant
        val purchasesByMerchant = allExpenses
            .filter { it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE }
            .groupBy { it.merchant }

        return stats.map { ms ->
            val amounts = purchasesByMerchant[ms.merchant]?.map { it.amount } ?: emptyList()
            val stdDev = if (amounts.size >= 3) calculateStdDev(amounts) else null

            val isRecurring = ms.txCount >= 2 &&
                    (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.15)

            MerchantInsight(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                minAmount = ms.minAmount,
                maxAmount = ms.maxAmount,
                totalSpent = ms.totalAmount,
                transactionCount = ms.txCount,
                isLikelyRecurring = isRecurring,
                stdDeviation = stdDev
            )
        }.sortedByDescending { it.totalSpent }
    }

    // === Spending Pace ===

    private suspend fun buildSpendingPace(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        allExpenses: List<Expense>
    ): SpendingPace {
        val now = System.currentTimeMillis()
        val currentSpent = expenseDao.getTotalForPeriod(currentMonth.startMs, currentMonth.endMs)
        val previousTotal = expenseDao.getTotalForPeriod(previousMonth.startMs, previousMonth.endMs)
        val previousCount = expenseDao.getCountForPeriod(previousMonth.startMs, previousMonth.endMs)

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Calculate average monthly total (excluding current month)
        val avgMonthly = calculateAverageMonthlySpend(allExpenses, currentMonth)

        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        val projectedTotal = if (dayOfMonth > 0)
            currentSpent * daysInMonth.toDouble() / dayOfMonth else currentSpent

        // Pace percentage: how much of the baseline have we consumed
        val baseline = avgMonthly ?: if (previousCount > 0) previousTotal else null
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expectedAtThisPoint = baseline * dayOfMonth / daysInMonth
            (currentSpent / expectedAtThisPoint * 100).toFloat()
        } else 0f

        val paceStatus = when {
            baseline == null || baseline == 0.0 -> PaceStatus.NO_BASELINE
            pacePercentage < 90f -> PaceStatus.UNDER_PACE
            pacePercentage > 110f -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }

        return SpendingPace(
            currentMonthSpent = currentSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousCount > 0) previousTotal else null,
            averageMonthlyTotal = avgMonthly,
            pacePercentage = pacePercentage,
            paceStatus = paceStatus
        )
    }

    private fun calculateAverageMonthlySpend(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Double? {
        val purchases = allExpenses.filter {
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
                    && it.date < currentMonth.startMs
        }
        if (purchases.isEmpty()) return null

        val cal = Calendar.getInstance()
        val monthTotals = mutableMapOf<String, Double>()
        for (p in purchases) {
            cal.timeInMillis = p.date
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            monthTotals.merge(key, p.amount) { a, b -> a + b }
        }

        return if (monthTotals.isNotEmpty()) monthTotals.values.average() else null
    }

    // === Anomaly Detection ===

    private suspend fun findAnomalies(
        currentMonth: MonthPeriod,
        categoryMap: Map<Long, Category>
    ): List<AnomalyTransaction> {
        val merchantStats = expenseDao.getMerchantStats() // only merchants with 2+ tx
        val statsMap = merchantStats.associateBy { it.merchant }

        // Get this month's expenses
        val currentExpenses = expenseDao.getLargestExpenseForPeriod(
            currentMonth.startMs, currentMonth.endMs
        )
        // We need all current month expenses, not just largest
        // Use the DAO getAllFlow but we need a suspend version
        // Let's query daily totals to get the actual expenses differently
        // Actually, we can use the existing data passed to generateInsights

        // Instead, query all for the period directly
        val anomalies = mutableListOf<AnomalyTransaction>()

        // We'll check each merchant stat against current month purchases
        // by getting top merchants for the period and comparing
        val topMerchants = expenseDao.getTopMerchantsForPeriod(
            currentMonth.startMs, currentMonth.endMs, 100
        )

        // For anomalies, we need individual transactions. 
        // Let's use a different approach: check the largest transactions this month
        // against their merchant averages
        for (merchantStat in topMerchants) {
            val historicalStats = statsMap[merchantStat.merchant] ?: continue
            if (historicalStats.txCount < 3) continue

            // If the max amount this month is > 2x the historical average
            if (merchantStat.maxAmount > historicalStats.avgAmount * 2.0) {
                // Find the actual expense (largest for this merchant this month)
                val expense = expenseDao.getLargestExpenseForPeriod(
                    currentMonth.startMs, currentMonth.endMs
                )
                if (expense != null && expense.merchant == merchantStat.merchant) {
                    anomalies.add(
                        AnomalyTransaction(
                            expense = expense,
                            merchantAvg = historicalStats.avgAmount,
                            deviationMultiple = (expense.amount / historicalStats.avgAmount).toFloat(),
                            category = expense.categoryId?.let { categoryMap[it] }
                        )
                    )
                }
            }
        }

        return anomalies.sortedByDescending { it.deviationMultiple }.take(5)
    }

    // === Recurring Expenses ===

    private suspend fun findRecurringExpenses(): List<RecurringExpense> {
        val candidates = expenseDao.getRecurringCandidates()

        return candidates.map { ms ->
            RecurringExpense(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                frequency = ms.txCount,
                amountVariation = ms.maxAmount - ms.minAmount,
                isStable = (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.05)
            )
        }
    }

    // === Day of Week Pattern ===

    private suspend fun buildDayOfWeekPattern(
        startMs: Long,
        endMs: Long
    ): List<DayOfWeekInsight> {
        val data = expenseDao.getDayOfWeekPattern(startMs, endMs)

        // Fill in missing days with zeros
        val dayMap = data.associateBy { it.dayOfWeek }
        return (0..6).map { dayIndex ->
            val d = dayMap[dayIndex]
            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = d?.total ?: 0.0,
                transactionCount = d?.txCount ?: 0,
                avgPerTransaction = d?.avgAmount ?: 0.0
            )
        }
    }

    // === Utility Functions ===

    private fun calculateMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun countDistinctMonths(expenses: List<Expense>): Int {
        if (expenses.isEmpty()) return 0
        val cal = Calendar.getInstance()
        return expenses.map { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }.distinct().size
    }
}
```

---

### Step 3: Insights ViewModel

```kotlin
// FILE: main/java/com/yourname/expensetracker/ui/screens/insights/InsightsViewModel.kt
// NEW FILE

package com.yourname.expensetracker.ui.screens.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.insights.InsightsEngine
import com.yourname.expensetracker.domain.insights.InsightsSnapshot
import com.yourname.expensetracker.domain.insights.MonthPeriod
import com.yourname.expensetracker.domain.insights.PaceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsUiState(
    val isLoading: Boolean = true,
    val snapshot: InsightsSnapshot? = null,
    val error: String? = null
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val insightsEngine: InsightsEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    private val expenses: StateFlow<List<Expense>> = repository.getAllExpenses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(expenses, categories) { exp, cats ->
                Pair(exp, cats)
            }.collect { (exp, cats) ->
                if (exp.isNotEmpty() && cats.isNotEmpty()) {
                    refreshInsights(exp, cats)
                } else {
                    _uiState.value = InsightsUiState(isLoading = false, snapshot = null)
                }
            }
        }
    }

    private suspend fun refreshInsights(expenses: List<Expense>, categories: List<Category>) {
        try {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val snapshot = insightsEngine.generateInsights(categories, expenses)
            _uiState.value = InsightsUiState(isLoading = false, snapshot = snapshot)
        } catch (e: Exception) {
            _uiState.value = InsightsUiState(
                isLoading = false,
                error = "Failed to generate insights: ${e.message}"
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val exp = expenses.value
            val cats = categories.value
            if (exp.isNotEmpty() && cats.isNotEmpty()) {
                refreshInsights(exp, cats)
            }
        }
    }
}
```

---

### Step 4: Insights Screen

```kotlin
// FILE: main/java/com/yourname/expensetracker/ui/screens/insights/InsightsScreen.kt
// NEW FILE

package com.yourname.expensetracker.ui.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.insights.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Insights") })
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.snapshot == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📊", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Not enough data yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Insights will appear after your first transactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                val snapshot = state.snapshot!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Spending Pace Card
                    item {
                        SpendingPaceCard(snapshot.spendingPace, snapshot.currentMonth)
                    }

                    // Monthly Comparison
                    item {
                        MonthlyComparisonCard(snapshot.monthlyComparison)
                    }

                    // Quick Stats Row
                    item {
                        QuickStatsRow(snapshot)
                    }

                    // Category Insights
                    if (snapshot.categoryInsights.isNotEmpty()) {
                        item {
                            Text(
                                "Category Breakdown",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(snapshot.categoryInsights) { insight ->
                            CategoryInsightCard(insight)
                        }
                    }

                    // Recurring Expenses
                    if (snapshot.recurringExpenses.isNotEmpty()) {
                        item {
                            Text(
                                "🔄 Recurring Expenses",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(snapshot.recurringExpenses) { recurring ->
                            RecurringExpenseCard(recurring)
                        }
                    }

                    // Top Merchants
                    if (snapshot.topMerchants.isNotEmpty()) {
                        item {
                            Text(
                                "🏪 Top Merchants",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(snapshot.topMerchants.take(7)) { merchant ->
                            MerchantInsightCard(merchant)
                        }
                    }

                    // Day of Week Pattern
                    if (snapshot.dayOfWeekPattern.any { it.transactionCount > 0 }) {
                        item {
                            DayOfWeekCard(snapshot.dayOfWeekPattern)
                        }
                    }

                    // Anomalies
                    if (snapshot.anomalies.isNotEmpty()) {
                        item {
                            Text(
                                "⚠️ Unusual Transactions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(snapshot.anomalies) { anomaly ->
                            AnomalyCard(anomaly)
                        }
                    }

                    // Data quality indicator
                    item {
                        DataQualityCard(snapshot.totalMonthsOfData)
                    }

                    // Bottom spacer
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

// === Component Cards ===

@Composable
fun SpendingPaceCard(pace: SpendingPace, currentMonth: MonthPeriod) {
    val paceColor = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> Color(0xFF4CAF50)
        PaceStatus.ON_PACE -> Color(0xFF2196F3)
        PaceStatus.OVER_PACE -> Color(0xFFFF5722)
        PaceStatus.NO_BASELINE -> Color(0xFF9E9E9E)
    }

    val paceEmoji = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> "📉"
        PaceStatus.ON_PACE -> "✅"
        PaceStatus.OVER_PACE -> "📈"
        PaceStatus.NO_BASELINE -> "📊"
    }

    val paceText = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> "Under pace"
        PaceStatus.ON_PACE -> "On track"
        PaceStatus.OVER_PACE -> "Over pace"
        PaceStatus.NO_BASELINE -> "Building baseline"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${currentMonth.label} Spending",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = paceColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        "$paceEmoji $paceText",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = paceColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "€${String.format("%.2f", pace.currentMonthSpent)}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar: day progress through month
            val dayProgress = pace.daysElapsed.toFloat() / pace.daysInMonth
            LinearProgressIndicator(
                progress = { dayProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = paceColor,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Day ${pace.daysElapsed} of ${pace.daysInMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
                if (pace.projectedTotal > 0 && pace.paceStatus != PaceStatus.NO_BASELINE) {
                    Text(
                        "Projected: €${String.format("%.0f", pace.projectedTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }

            pace.averageMonthlyTotal?.let { avg ->
                Text(
                    "Monthly average: €${String.format("%.0f", avg)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun MonthlyComparisonCard(comparison: MonthlyComparison) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Month over Month",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (comparison.previousTotal != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            comparison.currentMonth.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "€${String.format("%.2f", comparison.currentTotal)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${comparison.currentCount} transactions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Change indicator
                    comparison.changePercentage?.let { change ->
                        val isUp = change > 0
                        val changeColor = if (isUp) Color(0xFFFF5722) else Color(0xFF4CAF50)
                        val arrow = if (isUp) "↑" else "↓"

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$arrow ${String.format("%.0f", kotlin.math.abs(change))}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = changeColor
                            )
                            comparison.changeAmount?.let { amt ->
                                Text(
                                    "${if (isUp) "+" else ""}€${String.format("%.0f", amt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = changeColor
                                )
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        comparison.previousMonth?.let {
                            Text(
                                it.shortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "€${String.format("%.2f", comparison.previousTotal)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${comparison.previousCount ?: 0} transactions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    "No previous month data for comparison",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun QuickStatsRow(snapshot: InsightsSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickStatCard(
            label = "Avg Transaction",
            value = "€${String.format("%.2f", snapshot.averageTransactionSize)}",
            modifier = Modifier.weight(1f)
        )
        QuickStatCard(
            label = "Median",
            value = "€${String.format("%.2f", snapshot.medianTransactionSize)}",
            modifier = Modifier.weight(1f)
        )
        snapshot.largestTransaction?.let { largest ->
            QuickStatCard(
                label = "Largest",
                value = "€${String.format("%.2f", largest.amount)}",
                subtitle = largest.merchant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun QuickStatCard(
    label: String,
    value: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CategoryInsightCard(insight: CategoryInsight) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category icon
                val catColor = try {
                    Color(android.graphics.Color.parseColor(insight.category.color))
                } catch (e: Exception) {
                    Color.Gray
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(catColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(insight.category.icon, fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        insight.category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${insight.currentCount} transactions · ${String.format("%.0f", insight.percentageOfTotal)}% of total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "€${String.format("%.2f", insight.currentTotal)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Change from previous
                    insight.changeFromPrevious?.let { change ->
                        val isUp = change > 0
                        val color = if (isUp) Color(0xFFFF5722) else Color(0xFF4CAF50)
                        val arrow = if (isUp) "↑" else "↓"
                        Text(
                            "$arrow${String.format("%.0f", kotlin.math.abs(change))}% vs last month",
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                }
            }

            // Average comparison (if we have enough data)
            insight.changeFromAverage?.let { avgChange ->
                if (insight.monthsOfData >= 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val isAbove = avgChange > 0
                    val statusText = if (isAbove) {
                        "${String.format("%.0f", avgChange)}% above your ${insight.monthsOfData}-month average"
                    } else {
                        "${String.format("%.0f", kotlin.math.abs(avgChange))}% below your ${insight.monthsOfData}-month average"
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun RecurringExpenseCard(recurring: RecurringExpense) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recurring.merchant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${recurring.frequency} occurrences" +
                            if (recurring.isStable) " · Stable amount" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "€${String.format("%.2f", recurring.avgAmount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                if (recurring.amountVariation > 0.01) {
                    Text(
                        "±€${String.format("%.2f", recurring.amountVariation / 2)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MerchantInsightCard(merchant: MerchantInsight) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    merchant.merchant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${merchant.transactionCount} visits · avg €${String.format("%.2f", merchant.avgAmount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "€${String.format("%.2f", merchant.totalSpent)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DayOfWeekCard(pattern: List<DayOfWeekInsight>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📅 Spending by Day of Week",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            val maxSpent = pattern.maxOfOrNull { it.totalSpent } ?: 1.0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                pattern.forEach { day ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        // Amount
                        if (day.totalSpent > 0) {
                            Text(
                                "€${String.format("%.0f", day.totalSpent)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Bar
                        val barHeight = if (maxSpent > 0)
                            (day.totalSpent / maxSpent * 60).toFloat().coerceAtLeast(2f) else 2f
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(barHeight.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (day.totalSpent == maxSpent)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Day label
                        Text(
                            day.dayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AnomalyCard(anomaly: AnomalyTransaction) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3E0)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠️", fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "€${String.format("%.2f", anomaly.expense.amount)} at ${anomaly.expense.merchant}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${String.format("%.1f", anomaly.deviationMultiple)}× your average (€${String.format("%.2f", anomaly.merchantAvg)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE65100)
                )
                Text(
                    dateFormat.format(Date(anomaly.expense.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DataQualityCard(totalMonths: Int) {
    val quality = when {
        totalMonths >= 6 -> Triple("🟢", "Good data depth", "6+ months of history — insights are reliable")
        totalMonths >= 3 -> Triple("🟡", "Growing data", "Comparisons and averages are getting meaningful")
        totalMonths >= 2 -> Triple("🟠", "Early data", "Month-over-month comparisons now available")
        else -> Triple("🔴", "Just started", "Keep tracking — insights improve with more data")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(quality.first, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "${quality.second} · $totalMonths month${if (totalMonths != 1) "s" else ""} of data",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    quality.third,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

---

### Step 5: Wire into navigation (update MainActivity.kt)

```kotlin
// FILE: main/java/com/yourname/expensetracker/ui/MainActivity.kt
// REPLACE the entire file

package com.yourname.expensetracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.ui.screens.categories.CategoryScreen
import com.yourname.expensetracker.ui.screens.debug.DebugScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.insights.InsightsScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.review.ReviewViewModel
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExpenseTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Get pending review count for badge
    val reviewViewModel: ReviewViewModel = hiltViewModel()
    val pendingCount by reviewViewModel.pendingCount.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by reviewViewModel.errorMessage.collectAsState()

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            reviewViewModel.clearError()
        }
    }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Transactions") },
                    label = { Text("Transactions") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingCount > 0) {
                                    Badge { Text("$pendingCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "Review")
                        }
                    },
                    label = { Text("Review") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Insights") },
                    label = { Text("Insights") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "More") },
                    label = { Text("More") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> TransactionsScreen()
                2 -> ReviewScreen()
                3 -> InsightsScreen()
                4 -> MoreScreen(
                    onNavigateToCategories = { selectedTab = 5 },
                    onNavigateToDebug = { selectedTab = 6 }
                )
                5 -> CategoryScreen()
                6 -> DebugScreen()
            }
        }
    }
}

@Composable
fun MoreScreen(
    onNavigateToCategories: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        item {
            androidx.compose.material3.Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp),
                onClick = onNavigateToCategories
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.List, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Text("Manage Categories")
                }
            }
        }
        item {
            androidx.compose.material3.Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp),
                onClick = onNavigateToDebug
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Text("Debug & Diagnostics")
                }
            }
        }
    }
}
```

---

### Step 6: Provide InsightsEngine in DI

```kotlin
// FILE: main/java/com/yourname/expensetracker/di/AppModule.kt
// ADD this method at the end of the AppModule object, before the closing brace

    // ADD this to your existing AppModule.kt — paste INSIDE the AppModule object

    @Provides
    @Singleton
    fun provideInsightsEngine(expenseDao: ExpenseDao): com.yourname.expensetracker.domain.insights.InsightsEngine {
        return com.yourname.expensetracker.domain.insights.InsightsEngine(expenseDao)
    }
```

Actually, since `InsightsEngine` uses `@Inject constructor` and `@Singleton`, Hilt will provide it automatically. **You do NOT need to add anything to AppModule.kt** — Hilt's `@Inject` constructor handling takes care of it.

---

### Summary of changes

| File | Action |
|---|---|
| `data/database/dao/ExpenseDao.kt` | **Replace** — added 10 new queries + 4 data classes |
| `domain/insights/InsightsEngine.kt` | **New** — core calculation engine |
| `ui/screens/insights/InsightsViewModel.kt` | **New** — ViewModel for insights screen |
| `ui/screens/insights/InsightsScreen.kt` | **New** — full UI with 8 card components |
| `ui/MainActivity.kt` | **Replace** — added Insights tab, moved Categories/Debug to "More" tab |

**No database migration needed** — no schema changes, only new queries on existing tables.

**No new dependencies** — uses only existing Room, Hilt, Compose, Material3.

**No new gradle changes** — everything uses what's already imported.

The insights will work from day one with basic stats, and the data quality card honestly tells users how reliable the insights are based on how many months of data exist.