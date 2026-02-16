# 2 Domain Layer

## Table of Contents
1. [app\src\main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt](#appsrcmainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt)
2. [app\src\main\java\com\yourname\expensetracker\domain\analytics\InsightsEngine.kt](#appsrcmainjavacomyournameexpensetrackerdomainanalyticsinsightsenginekt)
3. [app\src\main\java\com\yourname\expensetracker\domain\budget\BudgetModels.kt](#appsrcmainjavacomyournameexpensetrackerdomainbudgetbudgetmodelskt)
4. [app\src\main\java\com\yourname\expensetracker\domain\budget\BudgetMonitor.kt](#appsrcmainjavacomyournameexpensetrackerdomainbudgetbudgetmonitorkt)
5. [app\src\main\java\com\yourname\expensetracker\domain\categorization\CategorizationEngine.kt](#appsrcmainjavacomyournameexpensetrackerdomaincategorizationcategorizationenginekt)
6. [app\src\main\java\com\yourname\expensetracker\domain\debug\NotificationSeeder.kt](#appsrcmainjavacomyournameexpensetrackerdomaindebugnotificationseederkt)
7. [app\src\main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt](#appsrcmainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt)
8. [app\src\main\java\com\yourname\expensetracker\domain\intelligence\TransactionClassifier.kt](#appsrcmainjavacomyournameexpensetrackerdomainintelligencetransactionclassifierkt)
9. [app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseCategoryClassifier.kt](#appsrcmainjavacomyournameexpensetrackerdomainintelligencemlexpensecategoryclassifierkt)
10. [app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseClassifier.kt](#appsrcmainjavacomyournameexpensetrackerdomainintelligencemlexpenseclassifierkt)
11. [app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\FeatureExtractor.kt](#appsrcmainjavacomyournameexpensetrackerdomainintelligencemlfeatureextractorkt)
12. [app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\HybridExpenseClassifier.kt](#appsrcmainjavacomyournameexpensetrackerdomainintelligencemlhybridexpenseclassifierkt)
13. [app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\MerchantNormalizer.kt](#appsrcmainjavacomyournameexpensetrackerdomainintelligencemlmerchantnormalizerkt)
14. [app\src\main\java\com\yourname\expensetracker\domain\logic\NarrativeGenerator.kt](#appsrcmainjavacomyournameexpensetrackerdomainlogicnarrativegeneratorkt)
15. [app\src\main\java\com\yourname\expensetracker\domain\logic\RecurringExpenseEngine.kt](#appsrcmainjavacomyournameexpensetrackerdomainlogicrecurringexpenseenginekt)
16. [app\src\main\java\com\yourname\expensetracker\domain\logic\SynthesisEngine.kt](#appsrcmainjavacomyournameexpensetrackerdomainlogicsynthesisenginekt)
17. [app\src\main\java\com\yourname\expensetracker\domain\model\FinancialForecast.kt](#appsrcmainjavacomyournameexpensetrackerdomainmodelfinancialforecastkt)
18. [app\src\main\java\com\yourname\expensetracker\domain\model\OperationResult.kt](#appsrcmainjavacomyournameexpensetrackerdomainmodeloperationresultkt)
19. [app\src\main\java\com\yourname\expensetracker\domain\model\PlannedExpense.kt](#appsrcmainjavacomyournameexpensetrackerdomainmodelplannedexpensekt)
20. [app\src\main\java\com\yourname\expensetracker\domain\model\RecurringPattern.kt](#appsrcmainjavacomyournameexpensetrackerdomainmodelrecurringpatternkt)
21. [app\src\main\java\com\yourname\expensetracker\domain\model\Result.kt](#appsrcmainjavacomyournameexpensetrackerdomainmodelresultkt)
22. [app\src\main\java\com\yourname\expensetracker\domain\model\SavingsGoal.kt](#appsrcmainjavacomyournameexpensetrackerdomainmodelsavingsgoalkt)
23. [app\src\main\java\com\yourname\expensetracker\domain\model\UpcomingItem.kt](#appsrcmainjavacomyournameexpensetrackerdomainmodelupcomingitemkt)
24. [app\src\main\java\com\yourname\expensetracker\domain\parser\AppParserRegistry.kt](#appsrcmainjavacomyournameexpensetrackerdomainparserappparserregistrykt)
25. [app\src\main\java\com\yourname\expensetracker\domain\parser\GenericTransactionParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainparsergenerictransactionparserkt)
26. [app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\GoogleWalletParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainparserparsersgooglewalletparserkt)
27. [app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\GreekBankParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainparserparsersgreekbankparserkt)
28. [app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\RevolutParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainparserparsersrevolutparserkt)
29. [app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\SmsParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainparserparserssmsparserkt)
30. [app\src\main\java\com\yourname\expensetracker\domain\receipt\BankStatementParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainreceiptbankstatementparserkt)
31. [app\src\main\java\com\yourname\expensetracker\domain\receipt\ReceiptOcrService.kt](#appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt)
32. [app\src\main\java\com\yourname\expensetracker\domain\receipt\ReceiptParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptparserkt)
33. [app\src\main\java\com\yourname\expensetracker\domain\util\AppConstants.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilappconstantskt)
34. [app\src\main\java\com\yourname\expensetracker\domain\util\BKTree.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilbktreekt)
35. [app\src\main\java\com\yourname\expensetracker\domain\util\CalendarUtils.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilcalendarutilskt)
36. [app\src\main\java\com\yourname\expensetracker\domain\util\CommonPatterns.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilcommonpatternskt)
37. [app\src\main\java\com\yourname\expensetracker\domain\util\CurrencyNormalizer.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilcurrencynormalizerkt)
38. [app\src\main\java\com\yourname\expensetracker\domain\util\MerchantCleaner.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilmerchantcleanerkt)
39. [app\src\main\java\com\yourname\expensetracker\domain\util\StatisticsUtils.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilstatisticsutilskt)
40. [app\src\main\java\com\yourname\expensetracker\domain\util\StringDistanceUtils.kt](#appsrcmainjavacomyournameexpensetrackerdomainutilstringdistanceutilskt)

---

## app\src\main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense

// === New Insights Models ===

data class MonthPeriod(
    val year: Int,
    val month: Int, // 0-indexed (Calendar.JANUARY = 0)
    val startMs: Long,
    val endMs: Long
) {
    companion object {
        val MONTH_NAMES = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
    }

    val label: String
        get() = "${MONTH_NAMES[month]} $year"

    val shortLabel: String
        get() = MONTH_NAMES[month]
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

// === Legacy / Existing Models ===

data class SpendingPeriod(
    val label: String,
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val previousTotal: Double?,         // For comparison
    val byCategory: List<CategoryBreakdown>,
    val byMerchant: List<MerchantBreakdown>,
    val dailyTotals: Map<String, Double>,   // "2024-01-15" → 45.60
    val transactionCount: Int
) {
    val changePercent: Float?
        get() = if (previousTotal != null && previousTotal > 0)
            ((total - previousTotal) / previousTotal * 100).toFloat()
        else null
}

data class CategoryBreakdown(
    val category: Category,
    val total: Double,
    val count: Int,
    val percentage: Float           // 0-100
)

data class MerchantBreakdown(
    val name: String,
    val totalSpent: Double,
    val transactionCount: Int,
    val averageTransaction: Double,
    val categoryId: Long?
)

data class SpendingInsight(
    val type: InsightType,
    val icon: String,
    val title: String,
    val description: String,
    val severity: Float             // 0-1, how important/urgent
)

enum class InsightType {
    SPENDING_INCREASE,
    SPENDING_DECREASE,
    UNUSUAL_TRANSACTION,
    RECURRING_DETECTED,
    CATEGORY_TREND,
    BUDGET_WARNING,
    MERCHANT_FREQUENCY,
    DAILY_AVERAGE,
    TOP_MERCHANT,
    STREAK
}

data class RecurringCandidate(
    val merchant: String,
    val amount: Double,
    val intervalDays: Int,
    val occurrences: Int,
    val nextExpectedDate: Long?,
    val confidence: Float = 0.0f
)

enum class TimePeriod {
    TODAY, WEEK, MONTH, YEAR, ALL
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\analytics\InsightsEngine.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainanalyticsinsightsenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

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
    ): InsightsSnapshot = coroutineScope {
        val now = System.currentTimeMillis()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)

        val categoryMap = categories.associateBy { it.id }

        // Start all independent queries in parallel
        val monthlyComparisonDeferred = async { buildMonthlyComparison(currentMonth, previousMonth) }
        val categoryInsightsDeferred = async { buildCategoryInsights(currentMonth, previousMonth, categoryMap, allExpenses) }
        val topMerchantsDeferred = async { buildMerchantInsights(allExpenses) }
        val spendingPaceDeferred = async { buildSpendingPace(currentMonth, previousMonth, allExpenses) }
        val anomaliesDeferred = async { findAnomalies(currentMonth, categoryMap) }
        val recurringExpensesDeferred = async { findRecurringExpenses() }

        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPatternDeferred = async { buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs) }
        val largestTransactionDeferred = async { 
            expenseDao.getLargestExpenseForPeriod(currentMonth.startMs, currentMonth.endMs) 
        }

        // Await all results
        val monthlyComparison = monthlyComparisonDeferred.await()
        val categoryInsights = categoryInsightsDeferred.await()
        val topMerchants = topMerchantsDeferred.await()
        val spendingPace = spendingPaceDeferred.await()
        val anomalies = anomaliesDeferred.await()
        val recurringExpenses = recurringExpensesDeferred.await()
        val dayOfWeekPattern = dayOfWeekPatternDeferred.await()
        val largestTransaction = largestTransactionDeferred.await()

        // Transaction size stats
        val currentMonthPurchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.date >= currentMonth.startMs
                    && it.date < currentMonth.endMs
        }
        val avgTxSize = if (currentMonthPurchases.isNotEmpty())
            currentMonthPurchases.map { it.amount }.average() else 0.0
        val medianTxSize = calculateMedian(currentMonthPurchases.map { it.amount })

        // How many months of data we have
        val totalMonthsOfData = countDistinctMonths(allExpenses)

        InsightsSnapshot(
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

    // === Legacy Compatibility ===

    fun getLegacyInsights(snapshot: InsightsSnapshot): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()

        // 1. Monthly Comparison (Spending Increase/Decrease)
        val comparison = snapshot.monthlyComparison
        if (comparison.changePercentage != null) {
            if (comparison.changePercentage > 20) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_INCREASE, "📈",
                        "Spending up ${comparison.changePercentage.toInt()}%",
                        "€${fmt(comparison.currentTotal)} this month vs €${fmt(comparison.previousTotal ?: 0.0)} last month",
                        (comparison.changePercentage / 100).coerceAtMost(1.0f).toFloat()
                    )
                )
            } else if (comparison.changePercentage < -15) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_DECREASE, "📉",
                        "Good job! Spending down ${(-comparison.changePercentage).toInt()}%",
                        "You saved €${fmt((comparison.previousTotal ?: 0.0) - comparison.currentTotal)} compared to last month",
                        0.3f
                    )
                )
            }
        }

        // 2. Spending Pace
        val pace = snapshot.spendingPace
        if (pace.paceStatus == PaceStatus.OVER_PACE) {
             insights.add(
                SpendingInsight(
                    InsightType.BUDGET_WARNING, "⚠️",
                    "Spending Pace Warning",
                    "You're at ${pace.daysElapsed} days but spent ${pace.pacePercentage.toInt()}% of typical month",
                    0.8f
                )
            )
        }

        // 3. Category Trends
        snapshot.categoryInsights.take(3).forEach { catInsight ->
            if (catInsight.changeFromPrevious != null) {
                 if (catInsight.changeFromPrevious > 40 && catInsight.currentTotal > 50) {
                    insights.add(
                        SpendingInsight(
                            InsightType.CATEGORY_TREND, catInsight.category.icon,
                            "${catInsight.category.name} up ${catInsight.changeFromPrevious.toInt()}%",
                            "€${fmt(catInsight.currentTotal)} vs €${fmt(catInsight.previousTotal ?: 0.0)}",
                            0.7f
                        )
                    )
                 }
            }
        }

        // 4. Recurring
        snapshot.recurringExpenses.take(3).forEach { recurring ->
             insights.add(
                SpendingInsight(
                    InsightType.RECURRING_DETECTED, "🔄",
                    "Recurring: ${recurring.merchant}",
                    "€${fmt(recurring.avgAmount)} ~every ${30.0/recurring.frequency} days", // approx
                    0.5f
                )
            )
        }

        // 5. Largest Transaction
        val largest = snapshot.largestTransaction
        if (largest != null && largest.amount > 50) {
             insights.add(
                SpendingInsight(
                    InsightType.UNUSUAL_TRANSACTION, "⚡",
                    "Largest: ${largest.merchant}",
                    "€${fmt(largest.amount)} on ${formatDate(largest.date)}",
                    0.25f
                )
            )
        }

        return insights.sortedByDescending { it.severity }
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
    ): MonthlyComparison = coroutineScope {
        val currentTotalDeferred = async { expenseDao.getTotalForPeriod(current.startMs, current.endMs) }
        val currentCountDeferred = async { expenseDao.getCountForPeriod(current.startMs, current.endMs) }
        val previousTotalDeferred = async { expenseDao.getTotalForPeriod(previous.startMs, previous.endMs) }
        val previousCountDeferred = async { expenseDao.getCountForPeriod(previous.startMs, previous.endMs) }

        val currentTotal = currentTotalDeferred.await()
        val currentCount = currentCountDeferred.await()
        val previousTotal = previousTotalDeferred.await()
        val previousCount = previousCountDeferred.await()

        val hasPrevious = previousCount > 0
        val changeAmount = if (hasPrevious) currentTotal - previousTotal else null
        val changePercentage = if (hasPrevious && previousTotal > 0)
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat() else null

        MonthlyComparison(
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
    ): List<CategoryInsight> = coroutineScope {
        val currentTotalsDeferred = async { expenseDao.getCategoryTotalsForPeriod(current.startMs, current.endMs) }
        val previousTotalsDeferred = async { expenseDao.getCategoryTotalsForPeriod(previous.startMs, previous.endMs) }

        val currentTotals = currentTotalsDeferred.await()
        val previousTotals = previousTotalsDeferred.await()
        val previousMap = previousTotals.associateBy { it.categoryId }

        val currentGrandTotal = currentTotals.sumOf { it.total }

        // Calculate multi-month averages per category
        val monthlyAverages = calculateCategoryMonthlyAverages(allExpenses, current)

        currentTotals.mapNotNull { ct ->
            val category = categoryMap[ct.categoryId]
            if (category == null) {
                android.util.Log.w("InsightsEngine", "Category ${ct.categoryId} not found for expense integration")
                return@mapNotNull null
            }
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
            it.transactionType == TransactionType.PURCHASE
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
            .filter { it.transactionType == TransactionType.PURCHASE }
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
    ): SpendingPace = coroutineScope {
        val now = System.currentTimeMillis()

        val currentSpentDeferred = async { expenseDao.getTotalForPeriod(currentMonth.startMs, currentMonth.endMs) }
        val previousTotalDeferred = async { expenseDao.getTotalForPeriod(previousMonth.startMs, previousMonth.endMs) }
        val previousCountDeferred = async { expenseDao.getCountForPeriod(previousMonth.startMs, previousMonth.endMs) }

        val currentSpent = currentSpentDeferred.await()
        val previousTotal = previousTotalDeferred.await()
        val previousCount = previousCountDeferred.await()

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Calculate average monthly total (excluding current month)
        val avgMonthly = calculateAverageMonthlySpend(allExpenses, currentMonth)

        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        val projectedTotal = if (dayOfMonth >= 4) {
            currentSpent * daysInMonth.toDouble() / dayOfMonth
        } else if (dayOfMonth > 0) {
            // Conservative estimate for first 3 days to avoid massive multipliers
            currentSpent * (daysInMonth.toDouble() / 10.0).coerceAtLeast(1.0)
        } else {
            currentSpent
        }

        // Pace percentage: how much of the baseline have we consumed
        val baseline = avgMonthly ?: if (previousCount > 0) previousTotal else null
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expectedAtThisPoint = baseline * dayOfMonth.coerceAtLeast(1) / daysInMonth
            (currentSpent / expectedAtThisPoint * 100).toFloat()
        } else 0f

        val paceStatus = when {
            baseline == null || baseline == 0.0 -> PaceStatus.NO_BASELINE
            pacePercentage < 90f -> PaceStatus.UNDER_PACE
            pacePercentage > 110f -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }

        SpendingPace(
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
            it.transactionType == TransactionType.PURCHASE
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
    ): List<AnomalyTransaction> = coroutineScope {
        val merchantStats = expenseDao.getMerchantStats() // only merchants with 2+ tx
        val statsMap = merchantStats.associateBy { it.merchant }

        // Check top merchants this month for outliers
        val topMerchants = expenseDao.getTopMerchantsForPeriod(
            currentMonth.startMs, currentMonth.endMs, 100
        )

        val deferredAnomalies: List<kotlinx.coroutines.Deferred<AnomalyTransaction?>> = topMerchants.mapNotNull { merchantStat ->
            val historicalStats = statsMap[merchantStat.merchant] ?: return@mapNotNull null
            if (historicalStats.txCount < 3) return@mapNotNull null

            // Dynamic threshold based on sample size
            val multiplier = when {
                historicalStats.txCount < 5 -> 5.0
                historicalStats.txCount < 10 -> 4.0
                else -> 3.0
            }

            // If the max amount this month is > X times the historical average
            if (merchantStat.maxAmount > historicalStats.avgAmount * multiplier) {
                async {
                    expenseDao.getLargestExpenseForMerchant(
                        merchantStat.merchant, currentMonth.startMs, currentMonth.endMs
                    )?.let { expense ->
                        AnomalyTransaction(
                            expense = expense,
                            merchantAvg = historicalStats.avgAmount,
                            deviationMultiple = (expense.amount / historicalStats.avgAmount).toFloat(),
                            category = expense.categoryId?.let { categoryMap[it] }
                        )
                    }
                }
            } else null
        }

        deferredAnomalies.awaitAll().filterNotNull()
            .sortedByDescending { it.deviationMultiple }
            .take(5)
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
        val timeZoneOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val data = expenseDao.getDayOfWeekPattern(startMs, endMs, timeZoneOffset)

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

    fun buildDailyTotals(expenses: List<Expense>, days: Int): Map<String, Double> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val result = LinkedHashMap<String, Double>()
        val dateKeyFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        // Initialize all days with 0
        for (i in days - 1 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val key = dateKeyFormat.format(cal.time)
            result[key] = 0.0
        }

        // Fill in actual values - Optimized: reuse Date object
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val dateObj = java.util.Date()
        for (expense in purchases) {
            dateObj.time = expense.date
            val key = dateKeyFormat.format(dateObj)
            if (result.containsKey(key)) {
                result[key] = (result[key] ?: 0.0) + expense.amount
            }
        }

        return result
    }

    // Make detectRecurring available for ViewModel compatibility if needed
    // But it's better to use the snapshot.
    // We already have findRecurringExpenses internally.

    // Legacy helper for detections from list - RE-ADDED FOR UI COMPATIBILITY
    // Legacy helper for detections from list - REMOVED (Use RecurringExpenseEngine)
    // fun detectRecurring(expenses: List<Expense>): List<RecurringCandidate> { ... }

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
        return com.yourname.expensetracker.domain.util.StatisticsUtils.calculateStdDev(values)
    }

    private fun countDistinctMonths(expenses: List<Expense>): Int {
        if (expenses.isEmpty()) return 0
        val cal = Calendar.getInstance()
        return expenses.map { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }.distinct().size
    }

    // === Exposed Suspend Functions for Repository Usage ===

    suspend fun getSpendingPaceSuspend(expenses: List<Expense>? = null): SpendingPace {
        val now = System.currentTimeMillis()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)

        // Use provided expenses or fetch from DB if null
        val recentExpenses = expenses ?: run {
            val sixMonthsAgo = getMonthPeriod(now, -6).startMs
            expenseDao.getExpensesBetween(sixMonthsAgo, now)
        }

        return buildSpendingPace(currentMonth, previousMonth, recentExpenses)
    }

    private fun fmt(amount: Double): String = String.format(java.util.Locale.US, "%.2f", amount)

    private fun formatDate(dateMs: Long): String {
         val format = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
         return format.format(java.util.Date(dateMs))
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\budget\BudgetModels.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainbudgetbudgetmodelskt"></a>
```kotlin
package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Category

data class BudgetStatus(
    val budget: Budget,
    val category: Category?,
    val spentAmount: Double,
    val remainingAmount: Double,
    val percentUsed: Float,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long
)

enum class BudgetHealthStatus {
    ON_TRACK,   // Spent < warning threshold
    WARNING,    // Spent >= warning threshold
    CRITICAL,   // Spent >= critical threshold
    EXCEEDED    // Spent >= 100%
}

data class BudgetSuggestion(
    val categoryId: Long?,
    val categoryName: String,
    val categoryIcon: String,
    val suggestedAmount: Double,
    val basedOnMonths: Int,
    val reason: String
)

enum class BudgetAlertLevel {
    WARNING,
    CRITICAL,
    EXCEEDED
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\budget\BudgetMonitor.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainbudgetbudgetmonitorkt"></a>
```kotlin
package com.yourname.expensetracker.domain.budget

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val budgetDao: BudgetDao,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun checkBudgets() {
        serviceScope.launch {
            try {
                val activeBudgets = budgetDao.getActiveBudgets()
                if (activeBudgets.isEmpty()) return@launch

                // 1. Pre-fetch categories for notifications (Avoid N+1 in sendNotification)
                val categoryIds = activeBudgets.mapNotNull { it.categoryId }.distinct()
                val categoryMap = if (categoryIds.isNotEmpty()) {
                    categoryDao.getByIds(categoryIds).associateBy { it.id }
                } else emptyMap()

                // 2. Group budgets by their period window to batch spending queries
                val now = System.currentTimeMillis()
                val budgetsByWindow = activeBudgets.groupBy { 
                    calculatePeriodWindow(it.period, it.startDate)
                }

                for ((window, budgets) in budgetsByWindow) {
                    val startMs = window.first
                    val endMs = window.second

                    // Bulk query category totals for this window
                    val categoryTotals = expenseDao.getCategoryTotalsForPeriod(startMs, endMs)
                        .associateBy { it.categoryId }

                    // If any budget is overall (no category), query total spent for the period
                    val totalSpent = if (budgets.any { it.categoryId == null }) {
                        expenseDao.getTotalForPeriod(startMs, endMs)
                    } else null

                    for (budget in budgets) {
                        val spent = if (budget.categoryId != null) {
                            categoryTotals[budget.categoryId]?.total ?: 0.0
                        } else {
                            totalSpent ?: 0.0
                        }

                        val categoryName = budget.categoryId?.let { categoryMap[it]?.name } ?: "Overall"
                        processBudgetWithSpent(budget, spent, now, startMs, categoryName)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BudgetMonitor", "Error in checkBudgets: ${e.message}", e)
            }
        }
    }

    private suspend fun processBudgetWithSpent(
        budget: Budget, 
        spent: Double, 
        now: Long, 
        periodStart: Long,
        categoryName: String
    ) {
        if (spent <= 0 || budget.amount <= 0) return

        val percent = (spent / budget.amount).toFloat()

        when {
            percent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Budget Exceeded!", categoryName)
                    budgetDao.updateExceededNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Critical Budget Warning", categoryName)
                    budgetDao.updateCriticalNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtWarning -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now, periodStart)) {
                    sendNotificationDirect(budget, spent, "Budget Warning", categoryName)
                    budgetDao.updateWarningNotification(budget.id, now)
                }
            }
        }
    }

    private fun shouldNotify(lastNotified: Long?, now: Long, periodStart: Long): Boolean {
        if (lastNotified == null) return true

        // Reset cooldown if we entered a new period (BUG-7 Fix)
        if (lastNotified < periodStart) return true

        // Cooldown: only notify once every 12 hours for the same budget level
        val cooldown = 12 * 60 * 60 * 1000L
        return now - lastNotified > cooldown
    }

    private fun sendNotificationDirect(budget: Budget, spent: Double, title: String, categoryName: String) {
        val percent = (spent / budget.amount * 100).toInt()
        val content = "You've spent €${String.format(java.util.Locale.US, "%.2f", spent)} ($percent%) of your $categoryName budget."

        val builder = NotificationCompat.Builder(context, "budget_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(budget.id.toInt(), builder.build())
    }

    fun calculatePeriodWindow(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        return calculatePeriodWindowForTime(period, anchorDate, System.currentTimeMillis())
    }

    fun getPreviousPeriodWindow(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        val currentWindow = calculatePeriodWindow(period, anchorDate)
        // To get previous, we can just subtract a small amount from the start of current and recalculate
        // This is safer than date math which might miss (e.g. variable month lengths)
        // If current start is Nov 1. Nov 1 - 1ms = Oct 31.
        // Calculate window for Oct 31. It will be Oct 1 - Nov 1.
        return calculatePeriodWindowForTime(period, anchorDate, currentWindow.first - 1000)
    }

    private fun calculatePeriodWindowForTime(period: BudgetPeriod, anchorDate: Long, evaluationTime: Long): Pair<Long, Long> {
        val anchorCal = Calendar.getInstance()
        anchorCal.timeInMillis = anchorDate

        val cal = Calendar.getInstance()
        cal.timeInMillis = evaluationTime

        // Reset time components to start of day
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return when (period) {
            BudgetPeriod.DAILY -> {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.WEEKLY -> {
                // Find the most recent occurrence of the anchor weekday
                val anchorDayOfWeek = anchorCal.get(Calendar.DAY_OF_WEEK)
                while (cal.get(Calendar.DAY_OF_WEEK) != anchorDayOfWeek) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.MONTHLY -> {
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)

                // Set to start of current month first
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val currentMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(currentMonthMax))

                if (evaluationTime < cal.timeInMillis) {
                    // If evaluation time is before the start of this month's cycle, the cycle started last month
                    cal.add(Calendar.MONTH, -1)
                    val prevMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(prevMonthMax))
                }

                val start = cal.timeInMillis

                // To find the end, go to the start of the next cycle
                cal.add(Calendar.MONTH, 1)
                val nextMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(nextMonthMax))

                val end = cal.timeInMillis
                Pair(start, end)
            }
            BudgetPeriod.YEARLY -> {
                val anchorMonth = anchorCal.get(Calendar.MONTH)
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)

                val currentMonth = cal.get(Calendar.MONTH)
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)

                // Check if we passed the anniversary this year
                var passed = false
                if (currentMonth > anchorMonth) passed = true
                else if (currentMonth == anchorMonth && currentDay >= anchorDay) passed = true

                if (!passed) {
                    cal.add(Calendar.YEAR, -1)
                }

                cal.set(Calendar.MONTH, anchorMonth)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))

                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                val end = cal.timeInMillis
                Pair(start, end)
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\categorization\CategorizationEngine.kt <a name="appsrcmainjavacomyournameexpensetrackerdomaincategorizationcategorizationenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantCategoryDao: MerchantCategoryDao,
    private val merchantNormalizer: NewMerchantNormalizer
) {
    private val cacheMutex = Mutex()
    private var cachedMappings: List<MerchantCategory>? = null
    private var cachedMappingsMap: Map<String, MerchantCategory>? = null
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000 // 5 minutes

    // Regex moved to MerchantNormalizer

    suspend fun categorize(merchant: String): Long? {
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = false)
        val normalized = lookupResult.canonical.normalizedName.lowercase()

        // Ensure cache is loaded
        val (sortedMappings, mappingsMap) = getCache()

        // 1. Exact match (from cache)
        mappingsMap[normalized]?.let { return it.categoryId }

        // 2. Substring match
        val paddedNormalized = " $normalized "

        for (mapping in sortedMappings) {
            if (mapping.merchantPattern.length >= 5) {
                if (paddedNormalized.contains(mapping.merchantPattern)) {
                    return mapping.categoryId
                }
            }
        }

        // 3. Word-level match
        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .filter { it !in listOf("the", "and", "for", "inc", "ltd", "com") }

        if (words.isNotEmpty()) {
            for (word in words) {
                val match = mappingsMap[word]
                if (match != null) return match.categoryId
            }
        }

        return null
    }

    suspend fun normalize(merchant: String): String {
        return merchantNormalizer.normalize(merchant, autoCreate = false).canonical.normalizedName
    }

    private suspend fun getCache(): Pair<List<MerchantCategory>, Map<String, MerchantCategory>> {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedMappings == null || cachedMappingsMap == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
                val all = merchantCategoryDao.getAll()
                cachedMappings = all.map { it.copy(merchantPattern = " ${it.merchantPattern} ") }
                    .sortedByDescending { it.merchantPattern.length }

                cachedMappingsMap = all.associateBy { it.merchantPattern }
                lastCacheTime = now
            }
            return Pair(cachedMappings!!, cachedMappingsMap!!)
        }
    }

    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedMappings = null
            cachedMappingsMap = null
            lastCacheTime = 0
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\debug\NotificationSeeder.kt <a name="appsrcmainjavacomyournameexpensetrackerdomaindebugnotificationseederkt"></a>
```kotlin
package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.data.database.entity.RawNotification
import javax.inject.Inject
import kotlin.random.Random

class NotificationSeeder @Inject constructor() {

    val categories = mapOf(
        "Groceries" to listOf("AB Vassilopoulos", "Sklavenitis", "Lidl", "Masoutis", "My Market"),
        "Transport" to listOf("Uber", "Beat", "OASA", "Shell", "EKO", "Aegean Airlines"),
        "Utilities" to listOf("DEI", "EYDAP", "Vodafone", "Cosmote", "Wind"),
        "Entertainment" to listOf("Netflix", "Spotify", "Village Cinemas", "Steam", "PlayStation"),
        "Shopping" to listOf("Amazon", "Skroutz", "Zara", "H&M", "Public", "Plaisio"),
        "Food" to listOf("Goody's", "Wolt", "E-Food", "Starbucks", "Gregorys")
    )

    private val spamTemplates = listOf(
        "You won 1000 euros! Claim now at link.com",
        "Your OTP code is 123456. Do not share it.",
        "Limited time offer! 50% off on all items.",
        "Missed call from +306912345678",
        "Your package is out for delivery.",
        "Verify your account by clicking here."
    )

    private val unknownSources = listOf(
        "Unknown Sender", "+306900000000", "InfoSMS", "Alert", "Notice"
    )

    fun generate(count: Int): List<RawNotification> {
        val notifications = mutableListOf<RawNotification>()
        val now = System.currentTimeMillis()
        val twoMonthsMs = 60L * 24 * 60 * 60 * 1000

        for (i in 0 until count) {
            val type = Random.nextInt(100)
            val notification = when {
                type < 5 -> generateSpam(now, twoMonthsMs) // 5% Spam
                type < 10 -> generateUnknown(now, twoMonthsMs) // 5% Unknown
                type < 15 -> generateRecurring(i, now) // 5% Recurring candidates
                else -> generateTransaction(now, twoMonthsMs) // 85% Normal Transactions
            }
            notifications.add(notification)
        }
        return notifications
    }

    private fun generateTransaction(now: Long, rangeMs: Long): RawNotification {
        val categoryEntry = categories.entries.random()
        val merchant = categoryEntry.value.random()
        val amount = Random.nextDouble(5.0, 150.0)
        val date = now - Random.nextLong(rangeMs)

        // Randomize source slightly to test normalization
        val sources = listOf("Revolut", "Piraeus", "Eurobank", "Alpha Bank")
        val source = sources.random()

        val text = when (source) {
            "Revolut" -> "Spent €${"%.2f".format(amount)} at $merchant."
            "Piraeus" -> "Agora €${"%.2f".format(amount)} me karta ... sto $merchant"
            else -> "Purchase of €${"%.2f".format(amount)} at $merchant completed."
        }

        return RawNotification(
            packageName = "com.simulation.$source".lowercase(),
            appName = source,
            title = "Transaction Alert",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }

    private val recurringTemplates = listOf(
        Pair("Netflix", 13.99),
        Pair("Spotify", 7.99),
        Pair("Cosmote", 35.00),
        Pair("DEI", 45.50),
        Pair("iCloud", 2.99),
        Pair("YouTube Premium", 11.99)
    )

    private fun generateRecurring(index: Int, now: Long): RawNotification {
        val (merchant, amount) = recurringTemplates.random()
        // Random date within last 60 days
        val date = now - Random.nextLong(60L * 24 * 60 * 60 * 1000)

        return RawNotification(
            packageName = "com.simulation.revolut",
            appName = "Revolut",
            title = "Recurring Payment",
            text = "Spent €$amount at $merchant.",
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }

    private fun generateSpam(now: Long, rangeMs: Long): RawNotification {
        val text = spamTemplates.random()
        val date = now - Random.nextLong(rangeMs)
        return RawNotification(
            packageName = "com.android.mms",
            appName = "Messages",
            title = unknownSources.random(),
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }

    private fun generateUnknown(now: Long, rangeMs: Long): RawNotification {
        val amount = Random.nextDouble(10.0, 50.0)
        val date = now - Random.nextLong(rangeMs)
        val text = "Payment of €${"%.2f".format(amount)} to Unknown Merchant."
        return RawNotification(
            packageName = "com.unknown.app",
            appName = "Unknown App",
            title = "Payment Notification",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class RoutingDecision {
    AUTO_ACCEPT,    // High confidence → create expense immediately
    NEEDS_REVIEW,   // Medium confidence → add to review queue
    AUTO_REJECT     // Low confidence → silently drop
}

data class RoutingResult(
    val decision: RoutingDecision,
    val adjustedConfidence: Float,
    val reason: String
)

@Singleton
class ConfidenceRouter @Inject constructor(
    private val sourceStatsDao: SourceStatsDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val classifier: TransactionClassifier
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
        const val CACHE_TTL = 60_000L // 1 minute

        // ML Thresholds
        private const val ML_CONFIDENT_THRESHOLD = 0.8f
        private const val ML_LIKELY_THRESHOLD = 0.3f

        // ML Sample sizes for weight calculation
        private const val ML_SAMPLES_MIN = 20
        private const val ML_SAMPLES_LOW = 50
        private const val ML_SAMPLES_MED = 100
        private const val ML_SAMPLES_HIGH = 200

        // Trust Modifiers
        private const val TRUST_MOD_SPAM = 0.1f
        private const val TRUST_MOD_HIGH = 1.1f
        private const val TRUST_MOD_NEUTRAL = 1.0f
        private const val TRUST_MOD_LOW = 0.9f
        private const val TRUST_MOD_BAD = 0.5f

        // Trust Score Thresholds
        private const val TRUST_SCORE_HIGH = 0.8f
        private const val TRUST_SCORE_NEUTRAL = 0.4f
        private const val TRUST_SCORE_LOW = 0.15f

        // Source Stats Requirement
        private const val MIN_NOTIFICATIONS_FOR_TRUST = 10

        // Rejection Thresholds
        private const val MERCHANT_REJECTION_THRESHOLD = 0.5f
        private const val PACKAGE_REJECTION_THRESHOLD = 0.7f
        private const val PREVIOUS_APPROVAL_BOOST = 1.2f
        private const val AUTO_REJECT_PENALTY_PACKAGE = 0.3f
        private const val UNKNOWN_MERCHANT_PENALTY = 0.5f
    }

    // Caches with timestamp: Value -> Timestamp
    private val sourceStatsCache = ConcurrentHashMap<String, Pair<SourceStats?, Long>>()
    private val merchantRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val packageRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val approvalCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    private val MAX_CACHE_SIZE = 1000

    private fun checkCacheSize() {
        if (sourceStatsCache.size > MAX_CACHE_SIZE) sourceStatsCache.clear()
        if (merchantRejectionCache.size > MAX_CACHE_SIZE) merchantRejectionCache.clear()
        if (packageRejectionCache.size > MAX_CACHE_SIZE) packageRejectionCache.clear()
        if (approvalCache.size > MAX_CACHE_SIZE) approvalCache.clear()
    }

    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String,
        notificationText: String? = null
    ): RoutingResult {
        checkCacheSize()
        var adjustedConfidence = parsed.confidence
        val reasons = mutableListOf<String>()

        // 1. ML classifier prediction (if ready and needed)
        // Skip ML if parser is extremely confident (e.g. exact template match) to save resources
        if (notificationText != null && parsed.confidence < 1.0f) {
            val mlPrediction = classifier.predict(notificationText)
            val classifierStats = classifier.getStats()

            if (classifierStats.isReady) {
                // Blend parser confidence with ML prediction
                // Weight: 60% parser, 40% ML (ML gets more weight as it trains more)
                val mlWeight = calculateMlWeight(classifierStats)
                val parserWeight = 1.0f - mlWeight

                adjustedConfidence = parsed.confidence * parserWeight + mlPrediction * mlWeight

                if (mlPrediction < ML_LIKELY_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% likely transaction")
                } else if (mlPrediction > ML_CONFIDENT_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% confident")
                }
            }
        }

        // 2-5. Adjust based on source trust, merchant history, package history, and previous approvals
        coroutineScope {
            val sourceStatsDeferred = async { getCachedSourceStats(packageName) }
            val merchantRejectionRateDeferred = async { getCachedMerchantRejectionRate(parsed.merchant) }
            val packageRejectionRateDeferred = async { getCachedPackageRejectionRate(packageName) }
            val previouslyApprovedDeferred = async { getCachedHasPreviousApprovals(parsed.merchant, packageName) }

            // 2. Adjust based on source trust score
            val sourceStats = sourceStatsDeferred.await()
            if (sourceStats != null && sourceStats.totalNotifications > MIN_NOTIFICATIONS_FOR_TRUST) {
                val trustModifier = calculateTrustModifier(sourceStats)
                adjustedConfidence *= trustModifier
                if (trustModifier < TRUST_MOD_LOW) {
                    reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
                }
            }

            // 3. Adjust based on user correction history for this merchant
            val merchantRejectionRate = merchantRejectionRateDeferred.await()
            if (merchantRejectionRate > MERCHANT_REJECTION_THRESHOLD) {
                adjustedConfidence *= 0.5f // Keep simple multiplier or extract? Let's fix this one too.
                reasons.add("Merchant often rejected")
            }

            // 4. Package rejection rate
            val packageRejectionRate = packageRejectionRateDeferred.await()
            if (packageRejectionRate > PACKAGE_REJECTION_THRESHOLD) {
                adjustedConfidence *= AUTO_REJECT_PENALTY_PACKAGE
                reasons.add("Package mostly rejected")
            }

            // 5. Boost if user has previously approved similar transactions
            val previouslyApproved = previouslyApprovedDeferred.await()
            if (previouslyApproved) {
                adjustedConfidence = (adjustedConfidence * PREVIOUS_APPROVAL_BOOST).coerceAtMost(1.0f)
                reasons.add("Previously approved merchant")
            }
        }

        // 6. Penalty for Unknown merchant
        if (parsed.merchant.isBlank() || parsed.merchant.equals("Unknown", ignoreCase = true)) {
            adjustedConfidence *= UNKNOWN_MERCHANT_PENALTY
            reasons.add("Unknown merchant")
        }

        // Clamp
        adjustedConfidence = adjustedConfidence.coerceIn(0f, 1f)

        // Route
        val decision = when {
            adjustedConfidence >= AUTO_ACCEPT_THRESHOLD -> RoutingDecision.AUTO_ACCEPT
            adjustedConfidence >= REVIEW_THRESHOLD -> RoutingDecision.NEEDS_REVIEW
            else -> RoutingDecision.AUTO_REJECT
        }

        val reason = if (reasons.isEmpty()) {
            "Base confidence: ${(parsed.confidence * 100).toInt()}%"
        } else {
            reasons.joinToString("; ")
        }

        return RoutingResult(decision, adjustedConfidence, reason)
    }

    /**
     * ML weight increases with more training data
     */
    private fun calculateMlWeight(stats: ClassifierStats): Float {
        val totalSamples = stats.totalPositive + stats.totalNegative
        return when {
            totalSamples < ML_SAMPLES_MIN -> 0f       // Not ready
            totalSamples < ML_SAMPLES_LOW -> 0.2f     // Low confidence in ML
            totalSamples < ML_SAMPLES_MED -> 0.35f   // Growing confidence
            totalSamples < ML_SAMPLES_HIGH -> 0.5f    // Moderate
            else -> 0.6f                   // Max capped at 60% (LOG-007)
        }
    }

    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> TRUST_MOD_SPAM // LOG-014: Heavy penalty for spam
            stats.trustScore > TRUST_SCORE_HIGH -> TRUST_MOD_HIGH
            stats.trustScore > TRUST_SCORE_NEUTRAL -> TRUST_MOD_NEUTRAL // 40-80% is neutral
            stats.trustScore > TRUST_SCORE_LOW -> TRUST_MOD_LOW // 15-40% is slight penalty
            else -> TRUST_MOD_BAD // < 15% is heavy penalty
        }
    }

    // === Cached Data Access ===

    private suspend fun getCachedSourceStats(packageName: String): SourceStats? {
        val now = System.currentTimeMillis()
        val cached = sourceStatsCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }
        val stats = sourceStatsDao.getByPackage(packageName)
        sourceStatsCache[packageName] = Pair(stats, now)
        return stats
    }

    private suspend fun getCachedMerchantRejectionRate(merchant: String): Float {
        val now = System.currentTimeMillis()
        val key = merchant.lowercase()
        val cached = merchantRejectionCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        val total = userCorrectionDao.getMerchantTotalCorrections(merchant)
        val result = if (total < 3) 0f else {
            val rejections = userCorrectionDao.getMerchantRejectionCount(merchant)
            rejections.toFloat() / total
        }

        merchantRejectionCache[key] = Pair(result, now)
        return result
    }

    private suspend fun getCachedPackageRejectionRate(packageName: String): Float {
        val now = System.currentTimeMillis()
        val cached = packageRejectionCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        val total = userCorrectionDao.getTotalCorrections(packageName)
        val result = if (total < 5) 0f else {
            val rejections = userCorrectionDao.getRejectionCount(packageName)
            rejections.toFloat() / total
        }

        packageRejectionCache[packageName] = Pair(result, now)
        return result
    }

    private suspend fun getCachedHasPreviousApprovals(merchant: String, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "${merchant.lowercase()}|$packageName"
        val cached = approvalCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        val result = userCorrectionDao.hasPreviousApprovals(merchant, packageName)
        approvalCache[key] = Pair(result, now)
        return result
    }

    suspend fun ensureSourceStats(packageName: String) {
        // Optimistic check using cache first to avoid DB read
        val cached = sourceStatsCache[packageName]?.first
        if (cached != null) return

        val existing = sourceStatsDao.getByPackage(packageName)
        if (existing == null) {
            sourceStatsDao.insertIfNotExists(SourceStats(packageName = packageName))
        }
        // Update cache
        sourceStatsCache[packageName] = Pair(existing ?: SourceStats(packageName = packageName), System.currentTimeMillis())
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\intelligence\TransactionClassifier.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainintelligencetransactionclassifierkt"></a>
```kotlin
// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Lightweight on-device text classifier using Naive Bayes.
 * No TensorFlow needed. Learns from user corrections.
 */
@Singleton
open class TransactionClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userCorrectionDao: UserCorrectionDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var saveJob: Job? = null
    private var retrainJob: Job? = null

    fun cleanup() {
        saveJob?.cancel()
        retrainJob?.cancel()
    }

    companion object {
        private const val TAG = "TxClassifier"
        private const val MODEL_FILE = "naive_bayes_model.json"
        private const val MODEL_VERSION = 1
        private const val MIN_TRAINING_SAMPLES = 20
        private const val LAPLACE_SMOOTHING = 1.0

        private val regexNonAlphanumeric = Regex("[^a-zα-ωά-ώ0-9€$£ ]")
        private val regexWhitespace = Regex("\\s+")
        private val regexDecimalAmount = Regex("""\d+[.,]\d{2}""")
        private val regexCurrencySymbol = Regex("""[€$£]""")
        private val regexCurrencyCode = Regex("""(?i)(EUR|USD|GBP)""")
        private val regexPaymentKeyword = Regex("""(?i)(paid|payment|purchase|charged|debit)""")
        private val regexGreekPaymentKeyword = Regex("""(?i)(πληρωμ|αγορ|χρέωσ|συναλλαγ)""")
        private val regexPromoKeyword = Regex("""(?i)(offer|discount|promo|sale|free|δωρεάν|προσφορά|έκπτωση)""")
        private val regexOtpKeyword = Regex("""(?i)(otp|code|verify|κωδικός)""")
        private val regexBalanceKeyword = Regex("""(?i)(balance|υπόλοιπο)""")
    }

    private val mutex = Mutex()
    private val positiveWordCounts = mutableMapOf<String, Int>()
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    private val vocabulary = mutableSetOf<String>()
    private var vocabularySize = 0

    private val positiveBigramCounts = mutableMapOf<String, Int>()
    private val negativeBigramCounts = mutableMapOf<String, Int>()

    private val _stats = MutableStateFlow(
        ClassifierStats(0, 0, 0, false)
    )
    val stats: StateFlow<ClassifierStats> = _stats.asStateFlow()

    @Volatile
    private var isLoaded = false
    private var lastTrainingCount = 0

    suspend fun initialize() {
        if (isLoaded) return
        mutex.withLock {
            if (isLoaded) return

            if (loadFromDisk()) {
                isLoaded = true
                _stats.value = getStatsInternal()
                Log.d(TAG, "Loaded model from disk: +$totalPositive/-$totalNegative samples")
            }

            val correctionCount = userCorrectionDao.getCount()
            if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                retrainFromCorrectionsInternal()
            }

            isLoaded = true
        }
    }

    open suspend fun predict(text: String): Float {
        if (!isLoaded) initialize()

        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f 
        }

        val features = extractFeatures(text)
        return mutex.withLock {
            calculateProbability(features)
        }
    }

    suspend fun train(text: String, isTransaction: Boolean) {
        mutex.withLock {
            val features = extractFeatures(text)
            addTrainingSample(features, isTransaction)
            scheduleSave()
        }
    }

    fun retrainFromCorrections() {
        retrainJob?.cancel()
        retrainJob = scope.launch {
            delay(2000) // Debounce for 2 seconds
            mutex.withLock {
                retrainFromCorrectionsInternal()
            }
        }
    }

    private suspend fun retrainFromCorrectionsInternal() {
        val corrections = userCorrectionDao.getAll()
        if (corrections.size < MIN_TRAINING_SAMPLES) {
            Log.d(TAG, "Not enough corrections to train: ${corrections.size}/$MIN_TRAINING_SAMPLES")
            return
        }

        positiveWordCounts.clear()
        negativeWordCounts.clear()
        positiveBigramCounts.clear()
        negativeBigramCounts.clear()
        totalPositive = 0
        totalNegative = 0

        // LOG-012 Fix: Balance dataset
        val positiveCorrections = corrections.filter { it.wasApproved }
        val negativeCorrections = corrections.filter { it.wasRejected }

        // Cap negatives to 3x positives to prevent skew
        val maxNegatives = (positiveCorrections.size * 3).coerceAtLeast(MIN_TRAINING_SAMPLES)
        val selectedNegatives = negativeCorrections.shuffled().take(maxNegatives)

        val trainingSet = positiveCorrections + selectedNegatives

        for (correction in trainingSet) {
            val text = buildTrainingText(correction)
            if (text.isNotBlank()) {
                val features = extractFeatures(text)
                if (correction.wasRejected) {
                    addTrainingSample(features, isTransaction = false)
                } else if (correction.wasApproved) {
                    addTrainingSample(features, isTransaction = true)
                }
            }
        }

        vocabularySize = vocabulary.size
        lastTrainingCount = corrections.size

        scheduleSave()
        Log.d(TAG, "Retrained from ${corrections.size} corrections: +$totalPositive/-$totalNegative")
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            saveToDisk()
        }
    }

    // Internal helper to get stats without locking (caller must hold mutex)
    private fun getStatsInternal(): ClassifierStats {
        return ClassifierStats(
            totalPositive = totalPositive,
            totalNegative = totalNegative,
            vocabularySize = vocabularySize,
            isReady = totalPositive + totalNegative >= MIN_TRAINING_SAMPLES
        )
    }

    // Public suspend version that acquires lock
    open suspend fun getStats(): ClassifierStats {
        return mutex.withLock {
            getStatsInternal()
        }
    }

    private fun addTrainingSample(features: FeatureSet, isTransaction: Boolean) {
        if (isTransaction) {
            totalPositive++
            features.words.forEach {
                positiveWordCounts[it] = (positiveWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                positiveBigramCounts[it] = (positiveBigramCounts[it] ?: 0) + 1
            }
        } else {
            totalNegative++
            features.words.forEach {
                negativeWordCounts[it] = (negativeWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                negativeBigramCounts[it] = (negativeBigramCounts[it] ?: 0) + 1
            }
        }
        vocabularySize = vocabulary.size
        _stats.value = getStatsInternal()
    }

    private fun calculateProbability(features: FeatureSet): Float {
        val total = totalPositive + totalNegative
        if (total == 0) return 0.5f

        // Guard against ln(0) which returns -Infinity
        // If a class has 0 samples, we treat its prior probability as extremely low (-20.0 in log space ~= 2e-9)
        var logProbPos = if (totalPositive > 0) ln(totalPositive.toDouble() / total) else -20.0
        var logProbNeg = if (totalNegative > 0) ln(totalNegative.toDouble() / total) else -20.0

        val vocabSize = vocabularySize.coerceAtLeast(1)

        for (word in features.words) {
            val posCount = (positiveWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + vocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + vocabSize * LAPLACE_SMOOTHING

            logProbPos += ln(posCount / posDenom)
            logProbNeg += ln(negCount / negDenom)
        }

        val bigramWeight = 0.5
        val bigramVocabSize = (positiveBigramCounts.keys + negativeBigramCounts.keys).toSet().size.coerceAtLeast(1)

        for (bigram in features.bigrams) {
            val posCount = (positiveBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING

            logProbPos += bigramWeight * ln(posCount / posDenom)
            logProbNeg += bigramWeight * ln(negCount / negDenom)
        }

        val logOdds = logProbPos - logProbNeg
        val clampedLogOdds = logOdds.coerceIn(-20.0, 20.0)
        return (1.0 / (1.0 + Math.exp(-clampedLogOdds))).toFloat()
    }

    private fun extractFeatures(text: String): FeatureSet {
        val normalized = text.lowercase()
            .replace(regexNonAlphanumeric, " ")
            .replace(regexWhitespace, " ")
            .trim()

        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .toMutableList()

        if (regexDecimalAmount.containsMatchIn(text)) {
            words.add("__HAS_DECIMAL_AMOUNT__")
        }
        if (regexCurrencySymbol.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_SYMBOL__")
        }
        if (regexCurrencyCode.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_CODE__")
        }
        if (regexPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_PAYMENT_KEYWORD__")
        }
        if (regexGreekPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_GREEK_PAYMENT_KEYWORD__")
        }
        if (regexPromoKeyword.containsMatchIn(text)) {
            words.add("__HAS_PROMO_KEYWORD__")
        }
        if (regexOtpKeyword.containsMatchIn(text)) {
            words.add("__HAS_OTP_KEYWORD__")
        }
        if (regexBalanceKeyword.containsMatchIn(text)) {
            words.add("__HAS_BALANCE_KEYWORD__")
        }

        val actualWords = normalized.split(" ").filter { it.length >= 2 }
        val bigrams = if (actualWords.size >= 2) {
            actualWords.zipWithNext().map { (a, b) -> "${a}_$b" }
        } else {
            emptyList()
        }

        return FeatureSet(words, bigrams)
    }

    private fun buildTrainingText(
        correction: com.yourname.expensetracker.data.database.entity.UserCorrection
    ): String {
        return listOfNotNull(
            correction.notificationTitle,
            correction.notificationText,
            correction.originalMerchant
        ).joinToString(" ")
    }

    private suspend fun saveToDisk() {
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("version", MODEL_VERSION)
                    mutex.withLock {
                        put("totalPositive", totalPositive)
                        put("totalNegative", totalNegative)
                        put("vocabularySize", vocabularySize)
                        put("lastTrainingCount", lastTrainingCount)

                        put("positiveWords", JSONObject().apply {
                            positiveWordCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("negativeWords", JSONObject().apply {
                            negativeWordCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("positiveBigrams", JSONObject().apply {
                            positiveBigramCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("negativeBigrams", JSONObject().apply {
                            negativeBigramCounts.forEach { (k, v) -> put(k, v) }
                        })
                    }
                }

                File(context.filesDir, MODEL_FILE).writeText(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save model", e)
            }
        }
    }

    private fun loadFromDisk(): Boolean {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) return false

            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version != MODEL_VERSION) {
                Log.w(TAG, "Model version mismatch. Current: $MODEL_VERSION, Found: $version.")
                return false
            }

            totalPositive = json.getInt("totalPositive")
            totalNegative = json.getInt("totalNegative")
            vocabularySize = json.optInt("vocabularySize", 0)
            lastTrainingCount = json.optInt("lastTrainingCount", 0)

            val posWords = json.getJSONObject("positiveWords")
            positiveWordCounts.clear()
            posWords.keys().forEach { key ->
                val count = posWords.getInt(key)
                positiveWordCounts[key] = count
                vocabulary.add(key)
            }

            val negWords = json.getJSONObject("negativeWords")
            negativeWordCounts.clear()
            negWords.keys().forEach { key ->
                val count = negWords.getInt(key)
                negativeWordCounts[key] = count
                vocabulary.add(key)
            }

            json.optJSONObject("positiveBigrams")?.let { posBi ->
                positiveBigramCounts.clear()
                posBi.keys().forEach { key ->
                    positiveBigramCounts[key] = posBi.getInt(key)
                }
            }
            json.optJSONObject("negativeBigrams")?.let { negBi ->
                negativeBigramCounts.clear()
                negBi.keys().forEach { key ->
                    negativeBigramCounts[key] = negBi.getInt(key)
                }
            }

            vocabularySize = vocabulary.size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            false
        }
    }
}

data class FeatureSet(
    val words: List<String>,
    val bigrams: List<String>
)

data class ClassifierStats(
    val totalPositive: Int,
    val totalNegative: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)

```

---

## app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseCategoryClassifier.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainintelligencemlexpensecategoryclassifierkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Naive Bayes Classifier for Expense Categorization.
 * Uses multinomial Naive Bayes with Laplace smoothing.
 */
@Singleton
class ExpenseCategoryClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ExpenseCategoryNB"
        private const val MODEL_FILE = "expense_category_model.json"
        private const val SMOOTHING = 1.0
        private const val MIN_SAMPLES = 20
    }

    private var categoryCounts = mutableMapOf<Long, Int>()
    private var totalSamples = 0
    private var wordCounts = mutableMapOf<Long, MutableMap<String, Int>>()
    private var wordTotals = mutableMapOf<Long, Int>()
    private var vocabulary = mutableSetOf<String>()
    private var isLoaded = false

    suspend fun classify(features: ExpenseFeatures): List<CategoryScore> {
        if (!isLoaded) loadModel()

        if (totalSamples < MIN_SAMPLES || categoryCounts.isEmpty()) {
            return emptyList()
        }

        val scores = mutableMapOf<Long, Double>()
        categoryCounts.keys.forEach { categoryId ->
            scores[categoryId] = calculateLogProbability(features, categoryId)
        }

        // Softmax normalization
        val maxLog = scores.values.maxOrNull() ?: 0.0
        val expScores = scores.mapValues { Math.exp(it.value - maxLog).coerceAtLeast(1e-10) }
        val sumExp = expScores.values.sum()

        return expScores
            .map { (categoryId, expVal) ->
                CategoryScore(
                    categoryId = categoryId,
                    categoryName = "Category_$categoryId", // Resolved by HybridClassifier
                    score = (expVal / sumExp).toFloat()
                )
            }
            .filter { it.score > 0.01f }
            .sortedByDescending { it.score }
    }

    suspend fun train(features: ExpenseFeatures, categoryId: Long) {
        if (!isLoaded) loadModel()

        categoryCounts[categoryId] = (categoryCounts[categoryId] ?: 0) + 1
        totalSamples++

        val catWordCounts = wordCounts.getOrPut(categoryId) { mutableMapOf() }
        features.merchantTokens.forEach { token ->
            catWordCounts[token] = (catWordCounts[token] ?: 0) + 1
            vocabulary.add(token)
        }

        wordTotals[categoryId] = (wordTotals[categoryId] ?: 0) + features.merchantTokens.size
        saveModel()
    }

    private fun calculateLogProbability(features: ExpenseFeatures, categoryId: Long): Double {
        var logProb = Math.log(
            (categoryCounts[categoryId] ?: 1).toDouble() / 
            totalSamples.coerceAtLeast(1)
        )

        val catWordCounts = wordCounts[categoryId] ?: mutableMapOf()
        val catWordTotal = wordTotals[categoryId] ?: 0
        val vocabSize = vocabulary.size.coerceAtLeast(1)

        features.merchantTokens.forEach { token ->
            val wordCount = catWordCounts[token]?.toDouble() ?: 0.0
            val wordProb = (wordCount + SMOOTHING) / (catWordTotal + SMOOTHING * vocabSize)
            logProb += Math.log(wordProb.coerceAtLeast(1e-10))
        }

        return logProb
    }

    fun isReady(): Boolean = totalSamples >= MIN_SAMPLES

    fun getStats(): CategoryClassifierStats {
        return CategoryClassifierStats(
            totalSamples = totalSamples,
            categoryCount = categoryCounts.size,
            vocabularySize = vocabulary.size,
            isReady = isReady()
        )
    }

    private suspend fun saveModel() = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("totalSamples", totalSamples)
                put("vocabulary", JSONObject(vocabulary.associateWith { 1 }))

                val countsJson = JSONObject()
                categoryCounts.forEach { (id, count) -> countsJson.put(id.toString(), count) }
                put("categoryCounts", countsJson)

                val wordCountsJson = JSONObject()
                wordCounts.forEach { (catId, words) ->
                    val wordsJson = JSONObject()
                    words.forEach { (word, count) -> wordsJson.put(word, count) }
                    wordCountsJson.put(catId.toString(), wordsJson)
                }
                put("wordCounts", wordCountsJson)
            }
            File(context.filesDir, MODEL_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save model", e)
        }
    }

    private suspend fun loadModel() = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) {
                isLoaded = true
                return@withContext
            }

            val json = JSONObject(file.readText())
            totalSamples = json.getInt("totalSamples")

            categoryCounts.clear()
            val catCounts = json.getJSONObject("categoryCounts")
            catCounts.keys().forEach { key -> categoryCounts[key.toLong()] = catCounts.getInt(key) }

            vocabulary.clear()
            val vocab = json.getJSONObject("vocabulary")
            vocab.keys().forEach { vocabulary.add(it) }

            wordCounts.clear()
            val wc = json.getJSONObject("wordCounts")
            wc.keys().forEach { catId ->
                val wordsJson = wc.getJSONObject(catId)
                val words = mutableMapOf<String, Int>()
                wordsJson.keys().forEach { word -> words[word] = wordsJson.getInt(word) }
                wordCounts[catId.toLong()] = words
            }

            wordTotals.clear()
            wordCounts.forEach { (catId, words) -> wordTotals[catId] = words.values.sum() }

            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            isLoaded = true
        }
    }
}

data class CategoryClassifierStats(
    val totalSamples: Int,
    val categoryCount: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)

```

---

## app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\ExpenseClassifier.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainintelligencemlexpenseclassifierkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml

/**
 * Match type for categorization results.
 */
enum class MatchType {
    RULE_MATCH,        // Keyword based
    HISTORY_MATCH,     // Previous user choice
    ML_PREDICTION,     // Naive Bayes prediction
    FALLBACK,          // Default category
    EXACT_MATCH,       // Exact string match (for normalization)
    ALIAS_MATCH,       // Known alias (for normalization)
    FUZZY_MATCH,       // Fuzzy/string similarity (for normalization)
    PARTIAL_MATCH,     // Substring match
    USER_DEFINED,      // User explicitly linked
    NEW_MERCHANT       // No match found
}

/**
 * Score for a specific category prediction.
 */
data class CategoryScore(
    val categoryId: Long,
    val categoryName: String,
    val score: Float
)

/**
 * Result of the classification process.
 */
data class ClassificationResult(
    val categoryId: Long,
    val categoryName: String,
    val confidence: Float,
    val alternatives: List<CategoryScore> = emptyList(),
    val matchType: MatchType
)

/**
 * Features extracted for classification.
 */
data class ExpenseFeatures(
    val merchantName: String,
    val merchantTokens: List<String>,
    val notificationTitle: String?,
    val notificationText: String?,
    val allText: String,
    val amount: Double,
    val amountBucket: AmountBucket,
    val dayOfWeek: Int, // 0 = Monday, 6 = Sunday
    val hourOfDay: Int,
    val isWeekend: Boolean,
    val sourcePackage: String
)

/**
 * Amount buckets for qualitative amount features.
 */
enum class AmountBucket {
    TINY,   // < 5
    SMALL,  // 5 - 20
    MEDIUM, // 20 - 50
    LARGE,  // 50 - 200
    HUGE;   // > 200

    companion object {
        fun fromAmount(amount: Double): AmountBucket {
            return when {
                amount < 5.0 -> TINY
                amount < 20.0 -> SMALL
                amount < 50.0 -> MEDIUM
                amount < 200.0 -> LARGE
                else -> HUGE
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\FeatureExtractor.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainintelligencemlfeatureextractorkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.database.entity.Expense
import java.util.Calendar

/**
 * Extracts features from expenses and notifications for ML classification.
 */
class FeatureExtractor {

    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
            // Greek stop words
            "και", "το", "η", "τα", "του", "την", "των", "με", "σε", "για"
        )

        private val WORD_PATTERN = Regex("[a-zA-Zα-ωά-ώΑ-ΩΆ-Ώ]+")
    }

    /**
     * Extract features from an expense.
     */
    fun extractFromExpense(
        expense: Expense,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ExpenseFeatures {
        val calendar = Calendar.getInstance().apply { timeInMillis = expense.date }

        val allText = listOfNotNull(
            expense.merchant,
            notificationTitle,
            notificationText
        ).joinToString(" ")

        val tokens = tokenize(allText)

        return ExpenseFeatures(
            merchantName = expense.merchant,
            merchantTokens = tokens,
            notificationTitle = notificationTitle,
            notificationText = notificationText,
            allText = allText,
            amount = expense.amount,
            amountBucket = AmountBucket.fromAmount(expense.amount),
            dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7, // 0 = Monday
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            sourcePackage = packageName
        )
    }

    /**
     * Extract features from notification text (before expense is created).
     */
    fun extractFromNotification(
        title: String?,
        text: String?,
        packageName: String,
        amount: Double,
        merchant: String
    ): ExpenseFeatures {
        val calendar = Calendar.getInstance()

        val allText = listOfNotNull(title, text, merchant).joinToString(" ")
        val tokens = tokenize(allText)

        return ExpenseFeatures(
            merchantName = merchant,
            merchantTokens = tokens,
            notificationTitle = title,
            notificationText = text,
            allText = allText,
            amount = amount,
            amountBucket = AmountBucket.fromAmount(amount),
            dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7,
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            sourcePackage = packageName
        )
    }

    /**
     * Tokenize text into words.
     */
    fun tokenize(text: String): List<String> {
        return WORD_PATTERN.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toList()
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\HybridExpenseClassifier.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainintelligencemlhybridexpenseclassifierkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid Expense Classifier for CATEGORIZATION.
 * Strategy priority: Rule-based -> History (TBD) -> ML prediction.
 */
@Singleton
class HybridExpenseClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryDao: CategoryDao,
    private val nbClassifier: ExpenseCategoryClassifier
) {
    companion object {
        private const val TAG = "HybridClassifier"
        val RULE_CONFIDENCE = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RULE_BASED
        val ML_THRESHOLD = com.yourname.expensetracker.domain.util.AppConstants.Confidence.ML_PREDICTION

        private val CATEGORY_KEYWORDS: Map<String, String> = mapOf(
            "mcdonalds" to "Food", "starbucks" to "Food", "pizza" to "Food",
            "restaurant" to "Food", "cafe" to "Food", "coffee" to "Food",
            "supermarket" to "Groceries", "lidl" to "Groceries", "sklavenitis" to "Groceries",
            "βασιλόπουλος" to "Groceries", "σκλαβενίτης" to "Groceries", "μασούτης" to "Groceries",
            "γαλαξίας" to "Groceries", "κρητικός" to "Groceries", "φούρνος" to "Groceries",
            "uber" to "Transport", "taxi" to "Transport", "bolt" to "Transport",
            "fuel" to "Transport", "gas" to "Transport", "shell" to "Transport", "bp" to "Transport",
            "amazon" to "Shopping", "netflix" to "Entertainment", "spotify" to "Entertainment"
        )
    }

    private val featureExtractor = FeatureExtractor()
    private var categories: List<Category> = emptyList()
    private var categoryMap: Map<String, Category> = emptyMap()

    suspend fun initialize() {
        categories = categoryDao.getAll()
        categoryMap = categories.associateBy { it.name.lowercase() }
    }

    suspend fun classify(
        merchantName: String,
        amount: Double,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ClassificationResult = withContext(Dispatchers.Default) {

        if (categories.isEmpty()) initialize()

        val features = featureExtractor.extractFromNotification(
            title = notificationTitle,
            text = notificationText,
            packageName = packageName,
            amount = amount,
            merchant = merchantName
        )

        // 1. Rules
        val ruleResult = classifyWithRules(features)
        if (ruleResult != null && ruleResult.confidence >= RULE_CONFIDENCE) {
            return@withContext ruleResult
        }

        // 2. ML Prediction
        if (nbClassifier.isReady()) {
            val mlResults = nbClassifier.classify(features)
            if (mlResults.isNotEmpty()) {
                val best = mlResults.first()
                if (best.score >= ML_THRESHOLD) {
                    val category = categories.find { it.id == best.categoryId }
                    return@withContext ClassificationResult(
                        categoryId = best.categoryId,
                        categoryName = category?.name ?: "Unknown",
                        confidence = best.score,
                        alternatives = mlResults.take(3).map { res ->
                            res.copy(categoryName = categories.find { it.id == res.categoryId }?.name ?: "Unknown")
                        },
                        matchType = MatchType.ML_PREDICTION
                    )
                }
            }
        }

        // 3. Fallback (Improved for BUG-012)
        val defaultCategory = categories.find { it.name.contains("Groceries", ignoreCase = true) } 
            ?: categories.find { it.name.contains("Other", ignoreCase = true) }
            ?: categories.firstOrNull()

        ClassificationResult(
            categoryId = defaultCategory?.id ?: -1,
            categoryName = defaultCategory?.name ?: "Uncategorized",
            confidence = 0.0f,
            matchType = MatchType.FALLBACK
        )
    }

    private fun classifyWithRules(features: ExpenseFeatures): ClassificationResult? {
        val tokens = features.merchantTokens.map { it.lowercase() }
        for (token in tokens) {
            val catName = CATEGORY_KEYWORDS[token]
            if (catName != null) {
                val category = categoryMap[catName.lowercase()]
                if (category != null) {
                    return ClassificationResult(
                        categoryId = category.id,
                        categoryName = category.name,
                        confidence = 0.98f,
                        matchType = MatchType.RULE_MATCH
                    )
                }
            }
        }
        return null
    }

    suspend fun learnFromCorrection(
        merchantName: String,
        correctCategoryId: Long,
        amount: Double = 0.0,
        packageName: String = ""
    ) {
        val features = featureExtractor.extractFromNotification(
            title = null,
            text = null,
            packageName = packageName,
            amount = amount,
            merchant = merchantName
        )
        nbClassifier.train(features, correctCategoryId)
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\intelligence\ml\MerchantNormalizer.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainintelligencemlmerchantnormalizerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.util.StringBKTree
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a merchant lookup operation
 */
data class MerchantLookupResult(
    val canonical: MerchantCanonical,
    val alias: MerchantAlias?,
    val confidence: Float,
    val matchType: MatchType
)

/**
 * Advanced Merchant Name Normalization System.
 */
@Singleton
class MerchantNormalizer @Inject constructor(
    private val dao: MerchantNormalizationDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MerchantNormalizer"

        private val LOCATION_PATTERN = Regex(
            """\s*#[\dA-Za-z]+|""" +
            """\s*-\s*\d+\s*$|""" +
            """\s*Store\s*#?\s*\d+|""" +
            """\s*Branch\s*#?\s*\d+|""" +
            """\s*Unit\s*#?\s*\d+|""" +
            """\s*At\s+[A-Z][a-z]+|""" + // Matches " At Athens", " At London"
            """\s*\([\d\s]+\)"""
        )

        private val CORPORATE_SUFFIXES = listOf(
            "INC", "INC.", "LLC", "LTD", "LTD.", "CORP", "CORP.", "CORPORATION",
            "CO", "CO.", "COMPANY", "GMBH", "S.A.", "S.A.S", "B.V.", "A.G."
        )

        private val COMMON_IGNORE_WORDS = listOf("THE", "A", "AN", "OF", "AND", "OR", "&")
    }

    private var bkTree: StringBKTree? = null
    private val treeMutex = Mutex()
    private var lastTreeRebuild = 0L
    private val TREE_REBUILD_INTERVAL = 300_000L // 5 minutes

    suspend fun normalize(
        rawName: String,
        autoCreate: Boolean = true,
        categoryId: Long? = null
    ): MerchantLookupResult = withContext(Dispatchers.Default) {
        if (rawName.isBlank()) {
            return@withContext createPlaceholder("Unknown", "unknown", categoryId)
        }

        val cleaned = cleanMerchantName(rawName)
        val normalizedKey = createSearchKey(cleaned)

        // 1. Alias match
        dao.getAliasByNormalizedKey(normalizedKey)?.let { alias ->
            val canonical = dao.getCanonicalById(alias.canonicalId)
            if (canonical != null) {
                return@withContext MerchantLookupResult(
                    canonical = canonical,
                    alias = alias,
                    confidence = if (alias.isUserDefined) 1.0f else 0.95f,
                    matchType = if (alias.isUserDefined) MatchType.USER_DEFINED else MatchType.ALIAS_MATCH
                )
            }
        }

        // 2. Exact canonical match
        dao.getCanonicalBySearchKey(normalizedKey)?.let { canonical ->
            return@withContext MerchantLookupResult(
                canonical = canonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }

        // 3. Fuzzy matching
        val fuzzyResult = fuzzyMatch(cleaned, normalizedKey)
        if (fuzzyResult != null && fuzzyResult.confidence >= 0.80f) {
            dao.linkAliasToCanonical(rawName, fuzzyResult.canonical.id, isUserDefined = false)
            return@withContext fuzzyResult
        }

        // 4. Create new
        if (autoCreate) {
            val newCanonical = createNewMerchant(cleaned, normalizedKey, categoryId)
            dao.linkAliasToCanonical(rawName, newCanonical.id, isUserDefined = false)
            invalidateTreeCache()

            return@withContext MerchantLookupResult(
                canonical = newCanonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.NEW_MERCHANT
            )
        } else {
            return@withContext createPlaceholder(cleaned, normalizedKey, categoryId)
        }
    }

    /**
     * Learns a manual mapping from a cryptic POS name to a user-defined brand name.
     */
    suspend fun learnMerchantAlias(rawName: String, brandName: String) = withContext(Dispatchers.IO) {
        if (rawName.isBlank() || brandName.isBlank()) return@withContext
        if (rawName.equals(brandName, ignoreCase = true)) return@withContext

        // 1. Ensure the brand name exists as a canonical merchant
        val brandLookup = normalize(brandName, autoCreate = true)
        val brandId = brandLookup.canonical.id

        // 2. Link the original POS name to this brand ID
        dao.linkAliasToCanonical(rawName, brandId, isUserDefined = true)

        Log.i(TAG, "Learned alias: $rawName -> $brandName")
        invalidateTreeCache()
    }

    fun cleanMerchantName(rawName: String): String {
        var cleaned = rawName.trim()
        cleaned = LOCATION_PATTERN.replace(cleaned, "")

        val upper = cleaned.uppercase()
        for (suffix in CORPORATE_SUFFIXES) {
            if (upper.endsWith(" $suffix")) {
                cleaned = cleaned.dropLast(suffix.length + 1).trim()
            } else if (upper.endsWith(",$suffix")) {
                cleaned = cleaned.dropLast(suffix.length + 1).trim()
            }
        }

        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        cleaned = cleaned.trim { !it.isLetterOrDigit() }
        return cleaned.ifEmpty { rawName.trim() }
    }

    private fun createSearchKey(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9α-ωά-ώ]"), "")
            .trim()
    }

    private suspend fun fuzzyMatch(cleaned: String, normalizedKey: String): MerchantLookupResult? {
        val tree = getOrBuildTree()
        val maxDist = if (normalizedKey.length < 6) 1 else 2

        val matches = tree.search(normalizedKey, maxDist)
        if (matches.isEmpty()) return null

        val best = matches.first()
        val canonical = dao.getCanonicalBySearchKey(best.first) ?: return null
        val similarity = StringDistanceUtils.jaroWinklerSimilarity(normalizedKey, best.first)

        return MerchantLookupResult(
            canonical = canonical,
            alias = null,
            confidence = similarity.toFloat(),
            matchType = if (best.second == 0) MatchType.EXACT_MATCH else MatchType.FUZZY_MATCH
        )
    }

    private val creationMutex = Mutex()

    private suspend fun createNewMerchant(cleaned: String, key: String, catId: Long?): MerchantCanonical = creationMutex.withLock {
        // Double-check existence inside the lock to prevent redundant insertion attempts
        dao.getCanonicalBySearchKey(key)?.let { return it }

        val canonical = MerchantCanonical(
            normalizedName = formatDisplayName(cleaned),
            searchKey = key,
            categoryId = catId,
            totalOccurrences = 1,
            isVerified = false
        )

        val id = dao.insertCanonical(canonical)

        if (id == -1L) {
            // Insertion failed (likely already exists), retrieve the existing ID
            return dao.getCanonicalBySearchKey(key)
                ?: throw IllegalStateException("Failed to create or retrieve merchant: $key")
        }

        return canonical.copy(id = id)
    }

    private fun createPlaceholder(cleaned: String, key: String, catId: Long?): MerchantLookupResult {
        return MerchantLookupResult(
            canonical = MerchantCanonical(normalizedName = cleaned, searchKey = key, categoryId = catId),
            alias = null, confidence = 0.0f, matchType = MatchType.NEW_MERCHANT
        )
    }

    private fun formatDisplayName(name: String): String {
        return name.split(" ").joinToString(" ") { word ->
            if (word.uppercase() in COMMON_IGNORE_WORDS) word.lowercase()
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private suspend fun getOrBuildTree(): StringBKTree {
        return treeMutex.withLock {
            val now = System.currentTimeMillis()
            if (bkTree == null || now - lastTreeRebuild > TREE_REBUILD_INTERVAL) {
                val tree = StringBKTree.create()
                dao.getTopMerchants(1000).forEach { tree.insert(it.searchKey) }
                bkTree = tree
                lastTreeRebuild = now
            }
            bkTree!!
        }
    }

    private suspend fun invalidateTreeCache() = treeMutex.withLock { bkTree = null }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\logic\NarrativeGenerator.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainlogicnarrativegeneratorkt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.WeatherNarrative
import com.yourname.expensetracker.domain.model.NarrativeSection
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NarrativeGenerator @Inject constructor() {

    fun generate(
        forecast: FinancialForecast, 
        budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>
    ): WeatherNarrative {
        val components = forecast.components
        val risk = components.riskLevel
        val discretionary = components.discretionaryBudget

        val basic = when {
            risk == RiskLevel.LOW && discretionary > 100.0 -> WeatherNarrative(
                state = WeatherState.CLEAR_SKIES,
                icon = "☀️",
                headline = "Clear Skies",
                summary = "You have a comfortable buffer of €${String.format(java.util.Locale.US, "%.2f", discretionary)} for the rest of the month."
            )
            risk == RiskLevel.LOW || risk == RiskLevel.MEDIUM -> WeatherNarrative(
                state = WeatherState.PARTLY_CLOUDY,
                icon = "⛅",
                headline = "Partly Cloudy",
                summary = "Everything is on track, though discretionary buffer is moderate (€${String.format(java.util.Locale.US, "%.2f", discretionary)})."
            )
            risk == RiskLevel.HIGH && discretionary > 0 -> WeatherNarrative(
                state = WeatherState.CLOUDY,
                icon = "☁️",
                headline = "Cloudy Forecast",
                summary = "Spending is tight. You only have €${String.format(java.util.Locale.US, "%.2f", discretionary)} remaining for unpredicted expenses."
            )
            risk == RiskLevel.HIGH -> WeatherNarrative(
                state = WeatherState.RAINY,
                icon = "🌧️",
                headline = "Rainy Conditions",
                summary = "Over pace on budgets and high committed costs. Caution is highly advised."
            )
            risk == RiskLevel.CRITICAL -> WeatherNarrative(
                state = WeatherState.STORMY,
                icon = "⛈️",
                headline = "Stormy Weather",
                summary = "⚠️ Immediate action required. Budgets exceeded and no discretionary buffer remains."
            )
            else -> WeatherNarrative(
                state = WeatherState.UNKNOWN,
                icon = "❓",
                headline = "Mixed Signals",
                summary = "Not enough data to provide a clear outlook yet."
            )
        }

        return basic.copy(details = buildDetails(forecast, budgetStatuses))
    }

    private fun buildDetails(
        forecast: FinancialForecast,
        budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>
    ): List<NarrativeSection> {
        val sections = mutableListOf<NarrativeSection>()
        val components = forecast.components

        // 1. Budget Health Section (Momentum Engine)
        val criticalBudgets = budgetStatuses.filter { 
            it.healthStatus == com.yourname.expensetracker.domain.budget.BudgetHealthStatus.EXCEEDED ||
            it.healthStatus == com.yourname.expensetracker.domain.budget.BudgetHealthStatus.CRITICAL 
        }
        if (criticalBudgets.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = "Budget Alerts",
                    icon = "🚨",
                    items = criticalBudgets.map { 
                        val name = it.category?.name ?: "Total Budget"
                        "$name is ${it.healthStatus.name}: €${String.format(java.util.Locale.US, "%.0f", it.spentAmount)} spent"
                    }
                )
            )
        } else if (budgetStatuses.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = "Budget Health",
                    icon = "✅",
                    items = listOf("All active budgets are currently on track")
                )
            )
        }

        // 2. Goal Protection (Constraint)
        if (components.goalReserves > 0) {
            sections.add(
                NarrativeSection(
                    title = "Goal Reserves",
                    icon = "⛨",
                    items = listOf("€${String.format(java.util.Locale.US, "%.0f", components.goalReserves)} locked for high-priority savings")
                )
            )
        }

        // 3. Planned Intentions (Intention Engine)
        val importantPlans = components.plannedExpenses.filter { 
            it.priority == PlannedExpensePriority.MUST || 
            it.priority == PlannedExpensePriority.LIKELY 
        }
        if (importantPlans.isNotEmpty()) {
            sections.add(
                NarrativeSection(
                    title = "Committed Plans",
                    icon = "🎯",
                    items = importantPlans.map { 
                        val priorityLabel = if (it.priority == PlannedExpensePriority.MUST) "Must" else "Likely"
                        "${it.description}: €${String.format(java.util.Locale.US, "%.0f", it.amount)} ($priorityLabel)"
                    }
                )
            )
        }

        // 4. Predicted Habits (Behavioral)
        if (components.predictedDiscretionary > 0) {
            sections.add(
                NarrativeSection(
                    title = "Predicted Activity",
                    icon = "📈",
                    items = listOf(
                        "Habit-based forecast: €${String.format(java.util.Locale.US, "%.0f", components.predictedDiscretionary)} likely spending based on your typical month."
                    )
                )
            )
        }

        return sections
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\logic\RecurringExpenseEngine.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainlogicrecurringexpenseenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Singleton
class RecurringExpenseEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val recurringExpenseDao: RecurringExpenseDao
) {

    /**
     * Analyze all expenses to find recurring patterns and merge with manual overrides.
     * Returns a list of patterns sorted by confidence (Manual = 1.0).
     */
    suspend fun getPatterns(): List<RecurringPattern> {
        // Limit to last 12 months for performance - INS-009
        val twelveMonthsAgo = System.currentTimeMillis() - (365L * 24 * 60 * 60 * 1000)
        val allExpenses = expenseDao.getExpensesSince(twelveMonthsAgo)
        return getPatterns(allExpenses)
    }

    /**
     * Overload for when we already have the list of expenses (e.g. from Analytics).
     */
    suspend fun getPatterns(allExpenses: List<com.yourname.expensetracker.data.database.entity.Expense>): List<RecurringPattern> {
        // 1. Fetch Manual Overrides
        val manualExpenses = recurringExpenseDao.getAll()
        val manualMap = manualExpenses.associateBy { it.merchant.lowercase() }

        // Group by normalized merchant name
        val grouped = allExpenses.groupBy { it.merchant.lowercase().trim() }

        val detectedPatterns = mutableListOf<RecurringPattern>()

        for ((normalizedMerchant, expenses) in grouped) {
            // Use the most frequent original merchant name or the first one
            val actualMerchant = expenses.groupBy { it.merchant }
                .maxByOrNull { it.value.size }?.key ?: normalizedMerchant

            // If we already have a manual rule for this merchant, skip detection
            if (manualMap.containsKey(normalizedMerchant)) continue

            // Requirement: At least 3 occurrences to form a pattern
            if (expenses.size < 3) continue 

            // Sort by date ascending to calculate intervals
            val sorted = expenses.sortedBy { it.date }

            // 1. Amount Stability Check
            val amounts = sorted.map { it.amount }
            val avgAmount = amounts.average()
            val stdDevAmount = calculateStdDev(amounts)
            // Coefficient of variation: stdDev / mean
            val amountVariance = if (avgAmount > 0) stdDevAmount / avgAmount else 0.0

            // If amount varies by more than 35%, likely not a fixed subscription/bill
            if (amountVariance > 0.35) continue 

            // 2. Interval Analysis
            val dates = sorted.map { it.date }
            val intervals = calculateIntervals(dates)

            val (frequency, confidence, varianceDays) = determineFrequency(intervals, dates)

            // Thresholds: Must be a known frequency and have > 50% confidence (LOG-013 Relaxed further to catch varying bills)
            if (frequency != RecurrenceFrequency.IRREGULAR && confidence > 0.50) {

                // Predict next date
                // Predict next date (LOG-021 Fix: Use Calendar for proper Month/Year addition)
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = dates.last()
                when (frequency) {
                    RecurrenceFrequency.MONTHLY -> cal.add(java.util.Calendar.MONTH, 1)
                    RecurrenceFrequency.QUARTERLY -> cal.add(java.util.Calendar.MONTH, 3)
                    RecurrenceFrequency.SEMI_ANNUALLY -> cal.add(java.util.Calendar.MONTH, 6)
                    RecurrenceFrequency.ANNUALLY -> cal.add(java.util.Calendar.YEAR, 1)
                    else -> cal.add(java.util.Calendar.DAY_OF_YEAR, frequency.days)
                }
                val nextDate = cal.timeInMillis

                detectedPatterns.add(
                    RecurringPattern(
                        merchantName = actualMerchant,
                        averageAmount = avgAmount,
                        currency = sorted.first().currency,
                        frequency = frequency,
                        periodVarianceDays = varianceDays,
                        amountVariancePercent = amountVariance,
                        nextExpectedDate = nextDate,
                        confidence = confidence.toFloat(),
                        previousDates = dates.takeLast(5),
                        categoryId = sorted.first().categoryId
                    )
                )
            }
        }

        // 3. Convert Manual Entries to RecurringPattern
        val manualPatterns = manualExpenses.map { manual ->
            RecurringPattern(
                merchantName = manual.merchant,
                averageAmount = manual.amount,
                currency = manual.currency,
                frequency = manual.frequency,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = manual.nextDate,
                confidence = 1.0f, // Manual is 100% confident
                previousDates = emptyList(), // No history needed for display
                categoryId = null, // Manual entries don't have categoryId yet
                id = manual.id // Use DB ID
            )
        }

        return (manualPatterns + detectedPatterns).sortedByDescending { it.confidence }
    }

    private fun calculateIntervals(dates: List<Long>): List<Long> {
        if (dates.size < 2) return emptyList()
        val intervals = mutableListOf<Long>()
        for (i in 0 until dates.size - 1) {
            val diff = dates[i+1] - dates[i]
            intervals.add(diff)
        }
        return intervals
    }

    private fun calculateStdDev(values: List<Double>): Double {
        return com.yourname.expensetracker.domain.util.StatisticsUtils.calculateStdDev(values)
    }

    private fun determineFrequency(intervalsMs: List<Long>, dates: List<Long>): Triple<RecurrenceFrequency, Double, Int> {
        if (intervalsMs.isEmpty()) return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)

        // Fix (BUG-003): Use Calendar for proper day interval calculation across DST
        val intervalsDays = mutableListOf<Int>()
        val cal1 = java.util.Calendar.getInstance()
        val cal2 = java.util.Calendar.getInstance()

        for (i in 0 until dates.size - 1) {
            cal1.timeInMillis = dates[i]
            cal2.timeInMillis = dates[i + 1]

            val days = ((dates[i + 1] - dates[i]) / 86400000.0).roundToInt()
            // Validating logic: If the simple division is close to an integer, it's usually fine, 
            // but for extreme edge cases (DST), we coerced results already.
            // A more robust way in Android/Java is to clear time fields.
            cal1.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal1.set(java.util.Calendar.MINUTE, 0)
            cal1.set(java.util.Calendar.SECOND, 0)
            cal1.set(java.util.Calendar.MILLISECOND, 0)

            cal2.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal2.set(java.util.Calendar.MINUTE, 0)
            cal2.set(java.util.Calendar.SECOND, 0)
            cal2.set(java.util.Calendar.MILLISECOND, 0)

            val diffDays = ((cal2.timeInMillis - cal1.timeInMillis) / 86400000.0).roundToInt()
            intervalsDays.add(diffDays)
        }

        // Find Mode (most common interval)
        val frequencyMap = intervalsDays.groupingBy { it }.eachCount()
        val modeEntry = frequencyMap.maxByOrNull { it.value } 
            ?: return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)

        val mode = modeEntry.key

        // Map mode to known frequencies with expanded ranges (BUG-012, LOGIC-003)
        val frequency = when (mode) {
             in 5..10 -> RecurrenceFrequency.WEEKLY
             in 11..23 -> RecurrenceFrequency.BIWEEKLY // Expanded from 11..18 to bridge gap
             in 24..37 -> RecurrenceFrequency.MONTHLY // Covers month length variations and weekend shifts
             in 80..110 -> RecurrenceFrequency.QUARTERLY
             in 150..240 -> RecurrenceFrequency.SEMI_ANNUALLY
             in 340..390 -> RecurrenceFrequency.ANNUALLY
             else -> RecurrenceFrequency.IRREGULAR
        }

        if (frequency == RecurrenceFrequency.IRREGULAR) {
            return Triple(RecurrenceFrequency.IRREGULAR, 0.0, 0)
        }

        // Calculate Confidence
        // Score based on how many intervals are "close" to the mode (within ±20% or ±1 day)
        val tolerance = (mode * 0.2).coerceAtLeast(1.0)
        val matchingIntervals = intervalsDays.count { abs(it - mode) <= tolerance }
        val consistencyScore = matchingIntervals.toDouble() / intervalsDays.size

        // Calculate Average Deviation (days)
        val deviations = intervalsDays.map { abs(it - mode) }
        val avgDeviation = deviations.average()

        return Triple(frequency, consistencyScore, avgDeviation.roundToInt())
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\logic\SynthesisEngine.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainlogicsynthesisenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.*
import java.time.Instant
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SynthesisEngine @Inject constructor() {

    fun synthesize(
        pastSumDaily: List<Double>,
        recurringPatterns: List<RecurringPattern>,
        plannedExpenses: List<PlannedExpense>,
        savingsGoals: List<SavingsGoal>,
        budgetStatuses: List<BudgetStatus>,
        spendingPace: SpendingPace
    ): FinancialForecast {
        val now = System.currentTimeMillis()

        // Fix: Use single Calendar instance to avoid inconsistent dates if crossing midnight
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(1)

        val endOfMonthCal = (calendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, daysInMonth)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endOfMonth = endOfMonthCal.timeInMillis

        val startOfTodayCal = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = startOfTodayCal.timeInMillis

        // 1. Calculate Committed (Highly likely/Automated/Must happen)
        val committedUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth 
        }.sumOf { it.averageAmount }

        val committedPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.MUST && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }

        val totalCommitted = committedUpcomingBills + committedPlanned

        // 2. Calculate Likely (Probable behavior)
        // Fix: Confidence Interval Gap (0.89-0.90 was missing)
        val likelyUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.70f && it.confidence < 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth
        }.sumOf { it.averageAmount }

        val likelyPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.LIKELY && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }

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

        val typicalDailyDiscretionary = spendingPace.averageMonthlyTotal?.let { (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth } 
            ?: (spendingPace.previousMonthTotal?.let { (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth })
            ?: 0.0

        val predictedDiscretionary = typicalDailyDiscretionary * daysRemaining
        val totalLikely = likelyUpcomingBills + likelyPlanned

        // 3. Goal Reserves
        // Strict goals are subtracted from "Available"
        // 3. Goal Reserves (Pro-rated for strict goals - LOG-019)
        val goalReserves = savingsGoals
            .filter { it.protectionLevel == GoalProtectionLevel.STRICT }
            .sumOf { goal ->
                 val remaining = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
                 if (remaining <= 0) 0.0
                 else {
                     val targetDate = goal.targetDate
                     if (targetDate == null || targetDate <= now) remaining // Due now or past due
                     else {
                         val msRemaining = targetDate - now
                         val daysRemainingInGoal = (msRemaining / (24 * 60 * 60 * 1000.0)).coerceAtLeast(1.0)
                         val targetMonthsRemaining = (daysRemainingInGoal / daysInMonth.toDouble()).coerceAtLeast(1.0)
                         val remainingMonthly = remaining / targetMonthsRemaining
                         // For this month specifically
                         remainingMonthly
                     }
                 }
            }

        // 4. Calculate Projected Timeline Points
        val lastKnownTotal = pastSumDaily.lastOrNull() ?: 0.0
        val dailyProjectionRate = (totalLikely + predictedDiscretionary) / daysRemaining

        val projectedPoints = (1..daysRemaining).map { dayIndex ->
            lastKnownTotal + (dailyProjectionRate * dayIndex)
        }

        // 5. Calculate Discretionary (Available)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }?.budget?.amount ?: 0.0
        val categoryBudgetsSum = budgetStatuses.filter { it.budget.categoryId != null }.sumOf { it.budget.amount }
        val budgetLimit = if (overallBudget > 0) overallBudget else categoryBudgetsSum

        val spentSoFar = spendingPace.currentMonthSpent

        // Revised Formula: Limit - (Spent + Future Committed + Future Likely + Goal Reserves)
        // If budgetLimit is 0, we use a fallback or express "Unknown" state
        val projectedObligations = committedUpcomingBills + committedPlanned + likelyUpcomingBills + likelyPlanned

        val discretionaryBudget = if (budgetLimit > 0) {
            (budgetLimit - (spentSoFar + projectedObligations + goalReserves)).coerceAtLeast(0.0)
        } else {
            // If no budget is set, the discretionary "pool" isn't 0 (which looks like "No money left"),
            // it's effectively unlimited/unknown vs a goal. 
            // We'll return 0.0 for now but the RiskLevel will signal NO_BUDGET
            0.0
        }

        // 6. Determine Risk Level
        val riskLevel = determineRiskLevel(
            spendingPace, 
            budgetStatuses, 
            discretionaryBudget, 
            budgetLimit
        )

        // Dynamic Confidence Calculation based on data quality
        var forecastConfidence = 0.85
        // Reduce confidence if no budget or no baseline
        if (budgetLimit <= 0) forecastConfidence -= 0.15
        if (spendingPace.averageMonthlyTotal == null) forecastConfidence -= 0.10
        if (recurringPatterns.isEmpty()) forecastConfidence -= 0.05

        return FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = Instant.now(),
            confidence = forecastConfidence.coerceIn(0.1, 0.95), 
            components = ForecastComponents(
                recurringExpenses = recurringPatterns,
                plannedExpenses = plannedExpenses,
                goalReserves = goalReserves,
                pastSpendingPoints = pastSumDaily,
                projectedSpendingPoints = projectedPoints,
                totalCommitted = totalCommitted,
                totalLikely = totalLikely,
                predictedDiscretionary = predictedDiscretionary,
                discretionaryBudget = discretionaryBudget,
                riskLevel = riskLevel
            ),
            actionableInsights = buildInsights(riskLevel, budgetStatuses, spendingPace, plannedExpenses, savingsGoals)
        )
    }

    private fun determineRiskLevel(
        pace: SpendingPace,
        budgets: List<BudgetStatus>,
        discretionary: Double,
        limit: Double
    ): RiskLevel {
        val criticalBudgets = budgets.count { it.healthStatus == BudgetHealthStatus.CRITICAL || it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val overPace = pace.paceStatus == PaceStatus.OVER_PACE

        // Ratio of discretionary to total budget
        val bufferRatio = if (limit > 0) discretionary / limit else 0.0

        // If no budget is set, we can't really say it's CRITICAL based on ratio.
        // We should check if they are simply overspending their "pace"
        if (limit <= 0) {
            return if (overPace) RiskLevel.MEDIUM else RiskLevel.LOW
        }

        return when {
            // Priority 1: Critical Budget Issues or Severe Overspending with no buffer
            criticalBudgets > 0 -> RiskLevel.CRITICAL
            overPace && bufferRatio < 0.05 -> RiskLevel.CRITICAL

            // Priority 2: High Risk (Overspending or Low Buffer)
            overPace -> RiskLevel.HIGH // If overPace but buffer > 0.05
            bufferRatio < 0.1 -> RiskLevel.HIGH

            // Priority 3: Medium Risk
            bufferRatio < 0.2 -> RiskLevel.MEDIUM

            // Priority 4: Low Risk
            else -> RiskLevel.LOW
        }
    }

    private fun buildInsights(
        risk: RiskLevel,
        budgets: List<BudgetStatus>,
        pace: SpendingPace,
        planned: List<PlannedExpense>,
        goals: List<SavingsGoal>
    ): List<String> {
        val insights = mutableListOf<String>()
        if (pace.paceStatus == PaceStatus.OVER_PACE) insights.add("Spending pace is higher than usual.")
        val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        if (exceeded > 0) insights.add("$exceeded budgets exceeded.")

        val strictGoalCount = goals.count { it.protectionLevel == GoalProtectionLevel.STRICT }
        if (strictGoalCount > 0) insights.add("$strictGoalCount strict savings goals active.")

        val mustPlannedCount = planned.count { it.priority == PlannedExpensePriority.MUST }
        if (mustPlannedCount > 0) insights.add("$mustPlannedCount must-pay planned expenses this month.")

        return insights
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\model\FinancialForecast.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainmodelfinancialforecastkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model

import java.time.Instant

data class FinancialForecast(
    val horizon: ForecastHorizon,
    val generatedAt: Instant,
    val confidence: Double, // 0.0 - 1.0
    val components: ForecastComponents,
    val actionableInsights: List<String>
)

enum class ForecastHorizon(val days: Int, val displayName: String) {
    NEXT_7_DAYS(7, "Next 7 Days"),
    NEXT_30_DAYS(30, "Next 30 Days"),
    REST_OF_MONTH(0, "Rest of Month") // 0 means calculate based on calendar
}

data class ForecastComponents(
    val recurringExpenses: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense> = emptyList(), // Manual intentions
    val goalReserves: Double = 0.0, // Money locked in goals

    // Timeline Data
    val pastSpendingPoints: List<Double>, // Cumulative daily spend up to today
    val projectedSpendingPoints: List<Double>, // Projected cumulative daily spend for rest of month

    // Synthesis Metrics
    val totalCommitted: Double,        // High confidence (bills, manual)
    val totalLikely: Double,           // Medium confidence (patterns, manual)
    val predictedDiscretionary: Double, // Habit-based predicted spending
    val discretionaryBudget: Double,   // "Safe-to-Spend"
    val riskLevel: RiskLevel
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class WeatherNarrative(
    val state: com.yourname.expensetracker.data.repository.WeatherState,
    val icon: String,
    val headline: String,
    val summary: String,
    val details: List<NarrativeSection> = emptyList()
)

data class NarrativeSection(
    val title: String,
    val icon: String,
    val items: List<String>
)

```

---

## app\src\main\java\com\yourname\expensetracker\domain\model\OperationResult.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainmodeloperationresultkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model

sealed class OperationResult<out T> {
    data class Success<out T>(val data: T) : OperationResult<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : OperationResult<Nothing>()
    data object Duplicate : OperationResult<Nothing>()
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\model\PlannedExpense.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainmodelplannedexpensekt"></a>
```kotlin
package com.yourname.expensetracker.domain.model

data class PlannedExpense(
    val id: Long,
    val description: String,
    val amount: Double,
    val date: Long,
    val categoryId: Long?,
    val isRecurring: Boolean,
    val priority: PlannedExpensePriority
)

enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\model\RecurringPattern.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainmodelrecurringpatternkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model

import java.time.LocalDate

data class RecurringPattern(
    val merchantName: String,
    val averageAmount: Double,
    val currency: String,
    val frequency: RecurrenceFrequency,
    val periodVarianceDays: Int, // e.g. ±2 days
    val amountVariancePercent: Double, // e.g. 0.05 (5%)
    val nextExpectedDate: Long, // Epoch millis
    val confidence: Float, // 0.0 - 1.0
    val previousDates: List<Long>, // For debugging/UI visualization
    val categoryId: Long? = null,
    val id: Long? = null // ID of the underlying RecurringExpense rule, if any
)

enum class RecurrenceFrequency(val days: Int) {
    WEEKLY(7),
    BIWEEKLY(14),
    MONTHLY(30),
    QUARTERLY(90),
    SEMI_ANNUALLY(180),
    ANNUALLY(365),
    IRREGULAR(0);

    val intervalInMs: Long
        get() = days * 86_400_000L
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\model\Result.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainmodelresultkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model

/**
 * A generic class that holds a value with its loading status.
 * @param <T>
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable? = null, val message: String? = null) : Result<Nothing>()
    object Loading : Result<Nothing>()

    override fun toString(): String {
        return when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception, message=$message]"
            Loading -> "Loading"
        }
    }
}

val Result<*>.succeeded
    get() = this is Result.Success && data != null

```

---

## app\src\main\java\com\yourname\expensetracker\domain\model\SavingsGoal.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainmodelsavingsgoalkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model

data class SavingsGoal(
    val id: Long,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val targetDate: Long?,
    val protectionLevel: GoalProtectionLevel
)

enum class GoalProtectionLevel {
    STRICT,
    WARNING,
    TRACKING
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\model\UpcomingItem.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainmodelupcomingitemkt"></a>
```kotlin
package com.yourname.expensetracker.domain.model

sealed class UpcomingItem {
    abstract val id: String
    abstract val description: String
    abstract val amount: Double
    abstract val date: Long
    abstract val categoryId: Long?

    data class Recurring(
        val pattern: RecurringPattern
    ) : UpcomingItem() {
        override val id: String = "recurring_${pattern.merchantName}"
        override val description: String = pattern.merchantName
        override val amount: Double = pattern.averageAmount
        override val date: Long = pattern.nextExpectedDate
        override val categoryId: Long? = pattern.categoryId
    }

    data class Planned(
        val expense: PlannedExpense
    ) : UpcomingItem() {
        override val id: String = "planned_${expense.id}"
        override val description: String = expense.description
        override val amount: Double = expense.amount
        override val date: Long = expense.date
        override val categoryId: Long? = expense.categoryId
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\parser\AppParserRegistry.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainparserappparserregistrykt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * Result from an app-specific parser. Higher confidence = more certain it's a real transaction.
 */
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val type: TransactionType,
    val confidence: Float, // 0.0 to 1.0
    val date: Long? = null
)

/**
 * Interface for app-specific notification parsers.
 */
interface AppNotificationParser {
    /** Package names this parser handles */
    val supportedPackages: Set<String>

    /**
     * Try to parse. Return null if notification is NOT a transaction.
     * Should be strict — only return a result when confident.
     */
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction?
}

/**
 * Registry that routes notifications to the right parser.
 */
@Singleton
class AppParserRegistry @Inject constructor(
    private val greekBankParser: GreekBankParser,
    private val revolutParser: RevolutParser,
    private val smsParser: SmsParser,
    private val googleWalletParser: GoogleWalletParser,
    private val genericParser: GenericTransactionParser
) {
    private val parsers = mutableListOf<AppNotificationParser>()

    init {
        // Order matters: Specific parsers first
        parsers.add(greekBankParser)
        parsers.add(revolutParser)
        parsers.add(smsParser)
        parsers.add(googleWalletParser)
    }

    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // 1. Try app-specific parser first
        val specificParser = parsers.find { packageName in it.supportedPackages }
        if (specificParser != null) {
            return specificParser.parse(title, text, bigText, subText, packageName)
        }

        // 2. Fallback to generic parser with HIGH threshold
        return genericParser.parse(title, text, bigText, subText, packageName)
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\parser\GenericTransactionParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainparsergenerictransactionparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.regex.Pattern

/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject

/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
class GenericTransactionParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) {

    // Strong signals that this is a REAL transaction notification
    private val strongTransactionSignals by lazy {
        listOf(
            // English patterns that strongly indicate actual transactions
            Pattern.compile("""(?:you\s+)?paid\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""payment\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""charged?\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:debit|deducted)\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""transaction\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            // Greek patterns - using UNICODE_CASE and \p{L} for Greek letters
            Pattern.compile("""(?:πληρω|χρεω|αγορ[αά])[\p{L}]*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:στ[οη]|at)\s""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            // Greeklish patterns
            Pattern.compile("""(?:pliromi|xreosi|hreosi|agora)\w*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:sto|se|stin?)\s""", Pattern.CASE_INSENSITIVE),
        )
    }

    // NEGATIVE signals — if present, this is NOT a transaction
    // Using Regex to enforce word boundaries for English words to avoid "Coffee" matching "offer"
    private val negativeSignalsPattern by lazy {
        Pattern.compile(
            """\b(offer|discount|save\s+up\s+to|earn|free|up\s+to|starting\s+from|balance|otp|verification|code|unsubscribe|opt\s+out|sale|%\s+off|promo|your\s+order|tracking|shipped|delivered|reminder|rate\s+us|review|survey)\b|""" +
            """(προσφορά|έκπτωση|εξοικονομ|κέρδισε|δωρεάν|έως|από|υπόλοιπο|κωδικός|υπενθύμιση)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    private val amountPattern by lazy {
        com.yourname.expensetracker.domain.util.CommonPatterns.AMOUNT_REGEX
    }

    private val MERCHANT_PREFIXES = listOf(" at ", " to ", " σε ", " στον ", " στην ", " στο ", " για ", " sto ", " ston ", " stin ", " se ")

    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()

        // 1. Check negative signals first
        if (negativeSignalsPattern.matcher(lowerFull).find()) return null

        // 2. Require at least one STRONG transaction signal
        val hasStrongSignal = strongTransactionSignals.any { it.matcher(lowerFull).find() }
        if (!hasStrongSignal) return null

        // 3. Extract amount
        val amountResult = extractAmount(fullText) ?: return null

        // 4. Sanity check amount
        if (amountResult.first < 0.10 || amountResult.first > 25000) return null

        // 5. Extract merchant
        val merchant = extractMerchant(fullText, title)

        return ParsedTransaction(
            amount = amountResult.first,
            currency = amountResult.second,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.ML_PREDICTION // LOGIC-004
        )
    }

    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val currency = matcher.group(1) ?: matcher.group(3) ?: "€"
            val amountStr = matcher.group(2)?.replace(",", ".") ?: return null
            val amount = amountStr.toDoubleOrNull() ?: return null
            return Pair(amount, currencyNormalizer.normalize(currency))
        }
        return null
    }

    private fun extractMerchant(text: String, title: String?): String {
        val normalized = text.replace('\u00A0', ' ')
        for (prefix in MERCHANT_PREFIXES) {
            val index = normalized.indexOf(prefix, ignoreCase = true)
            if (index != -1) {
                val after = normalized.substring(index + prefix.length).trim()
                return merchantCleaner.clean(after)
            }
        }
        // Fallback to title if it's not a generic keyword
        if (!title.isNullOrBlank() && !isGenericTitle(title.lowercase())) {
            return merchantCleaner.clean(title)
        }
        return "Unknown"
    }

    private fun isGenericTitle(title: String): Boolean {
        val genericWords = listOf("payment", "purchase", "transaction", "alert", "notification",
            "πληρωμή", "αγορά", "συναλλαγή", "ειδοποίηση")
        return genericWords.any { title.contains(it) }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\GoogleWalletParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainparserparsersgooglewalletparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern

import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject

class GoogleWalletParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user"
    )

    private val amountPattern by lazy {
        Pattern.compile(
            """([€$£])\s*(\d+[.,]\d{2})|(\d+[.,]\d{2})\s*([€$£]|EUR|USD|GBP)""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val atPattern by lazy {
        Pattern.compile("""(?:at|to)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]+)""", Pattern.CASE_INSENSITIVE)
    }

    // Things that are NOT transactions
    private val REJECT_PATTERNS = listOf(
        "add a card", "set up", "tap to pay", "loyalty", "offer",
        "reward", "cashback available", "nearby", "suggest"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()

        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null

        // Extract amount from anywhere in the notification
        val amount = extractAmount(fullText) ?: return null

        // Extract merchant: usually the title IS the merchant, or text contains "at MERCHANT"
        val merchant = extractMerchant(title, text, bigText)

        return ParsedTransaction(
            amount = amount.first,
            currency = amount.second,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.90f
        )
    }

    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val prefixCurrency = matcher.group(1) ?: matcher.group(4)
            val amountStr = (matcher.group(2) ?: matcher.group(3))?.replace(",", ".") ?: return null
            val amount = amountStr.toDoubleOrNull() ?: return null
            // Filter unrealistic amounts
            if (amount < 0.01 || amount > 50000) return null
            return Pair(amount, currencyNormalizer.normalize(prefixCurrency))
        }
        return null
    }

    private fun extractMerchant(title: String?, text: String?, bigText: String?): String {
        // Check for "at MERCHANT" pattern in text
        val combinedText = listOfNotNull(text, bigText).joinToString(" ")
        val atMatcher = atPattern.matcher(combinedText)
        if (atMatcher.find()) {
            return merchantCleaner.clean(atMatcher.group(1))
        }

        // Title might be the merchant if it doesn't contain amount/payment keywords
        if (!title.isNullOrBlank()) {
            val lowerTitle = title.lowercase()
            val isAmount = amountPattern.matcher(title).find()
            val isKeyword = listOf("payment", "purchase", "paid", "transaction", "google wallet", "wallet").any { lowerTitle.contains(it) }

            if (!isAmount && !isKeyword) {
                return merchantCleaner.clean(title)
            }
        }

        return "Unknown"
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\GreekBankParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainparserparsersgreekbankparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Parser for Greek banking apps (NBG, Alpha, Eurobank, Piraeus).
 * These typically send very structured SMS-like notifications.
 */
class GreekBankParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf(
        "gr.nbg.mobilebanking",
        "mbanking.NBG",
        "gr.alpha.mobile",
        "com.eurobank.mobile",
        "com.winbank.mobile"
    )

    private val PURCHASE_PATTERNS = listOf(
        // "Αγορά 12,50 EUR στο MERCHANT" or "Πληρωμή €6.30 σε..."
        // Also handles: "Πληρώσατε €7,50 από την κάρτα *1554 σε BOX FOOD APP"
        Pattern.compile(
            """(?:αγορ[άα]|χρ[έε]ωσ|συναλλαγ[ήη]|πληρ[ώω]σ?(?:ατε|μ[ήη])?|payment|purchase)\s+(?:[€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:EUR|€|USD|GBP)?\s*(?:απ[όο]\s+τ[ηι]ν?\s+κ[άα]ρτ[αά]\s*[*0-9]*\s*)?(?:στ[οη]ν?|σε|at|-)?\s*(.+?)(?:\s*(?:με|with)\s*κ[άα]ρτ|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "€12.50 at MERCHANT" or "12,50€ MERCHANT"
        Pattern.compile(
            """([€$£])\s*(\d+[.,]\d{2})\s*(?:at|στ[οη]ν?|σε|-)\s+(.+?)(?:\s*(?:με|with)|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "MERCHANT 12,50 EUR"
        Pattern.compile(
            """(?:χρ[έε]ωσ[ηη]?\s*κ[άα]ρτ[αά]ς?\s*\*?\d*:?\s*)(\d+[.,]\d{2})\s*(EUR|€)?\s*[-–]\s*(.+)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Patterns to REJECT
    private val REJECT_PATTERNS = listOf(
        "υπόλοιπο", "balance", "otp", "κωδικός", "code",
        "ενεργοποί", "activate", "εγκρίθηκε η αίτηση",
        "προσφορά", "offer", "έκπτωση", "discount",
        "ενημέρωση", "update", "reminder"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()

        // Quick reject
        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null

        for (pattern in PURCHASE_PATTERNS) {
            val matcher = pattern.matcher(fullText)
            if (matcher.find()) {
                // Groups vary by pattern but we try to extract amount and merchant
                return tryExtract(matcher, fullText)
            }
        }

        return null
    }

    private fun tryExtract(matcher: java.util.regex.Matcher, fullText: String): ParsedTransaction? {
        // Try to find the amount (could be in group 1 or 2)
        var amountStr: String? = null
        var currency = "EUR"
        var merchant = "Unknown"

        for (i in 1..matcher.groupCount()) {
            val group = matcher.group(i) ?: continue

            // Fix (BUG-010): Use more specific currency check to avoid partial merchant matches
            if (group.matches(Regex("""^\d+[.,]\d{2}$"""))) {
                amountStr = group
            } else if (group.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
                currency = currencyNormalizer.normalize(group)
            } else if (group.length > 2 && merchant == "Unknown") {
                merchant = merchantCleaner.clean(group)
            }
        }

        val amount = amountStr?.replace(",", ".")?.toDoubleOrNull() ?: return null
        if (amount < 0.01 || amount > 50000) return null

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.92f
        )
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\RevolutParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainparserparsersrevolutparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern
import javax.inject.Inject

class RevolutParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf("com.revolut.revolut")

    // Revolut notifications typically look like:
    // Title: "Paid €12.50" or "💳 €12.50 at SKLAVENITIS"
    // Text: "💳 €12.50 at SKLAVENITIS" or "You paid €5.00 to John"
    // Also: "Received €100.00 from John"
    // Also: "ATM withdrawal: €50.00"
    // Ignore: "Your exchange rate...", "Weekly report", "Special offer"

    private val PAID_PATTERN = Pattern.compile(
        """(?:paid|sent|💳)\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:at|to)\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val RECEIVED_PATTERN = Pattern.compile(
        """received\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*from\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val ATM_PATTERN = Pattern.compile(
        """(?:atm|withdrawal)[:\s]*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})""",
        Pattern.CASE_INSENSITIVE
    )

    // Patterns to REJECT (not transactions)
    private val REJECT_PATTERNS = listOf(
        "exchange rate", "weekly report", "special offer", "cashback",
        "refer a friend", "upgrade", "verify", "security", "pin",
        "top-up reminder", "price alert", "savings vault"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Check each field individually to avoid "doubling" content which confuses greedy regex
        val candidates = listOfNotNull(title, text, bigText)

        for (content in candidates) {
            val lower = content.lowercase()
            if (REJECT_PATTERNS.any { lower.contains(it) }) return null

            // Try paid/purchase pattern
            val paidMatcher = PAID_PATTERN.matcher(content)
            val receivedMatcher = RECEIVED_PATTERN.matcher(content)
            val atmMatcher = ATM_PATTERN.matcher(content)

            if (paidMatcher.find()) {
                val amount = paidMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
                val currency = currencyNormalizer.normalize(paidMatcher.group(1))
                val merchant = merchantCleaner.clean(paidMatcher.group(3))

                return ParsedTransaction(amount, currency, merchant, TransactionType.PURCHASE, 0.95f)
            } else if (receivedMatcher.find()) {
                val amount = receivedMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
                val currency = currencyNormalizer.normalize(receivedMatcher.group(1))
                val merchant = merchantCleaner.clean(receivedMatcher.group(3))

                return ParsedTransaction(amount, currency, merchant, TransactionType.DEPOSIT, 0.90f)
            } else if (atmMatcher.find()) {
                val amount = atmMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
                val currency = currencyNormalizer.normalize(atmMatcher.group(1))
                return ParsedTransaction(amount, currency, "ATM", TransactionType.WITHDRAWAL, 0.95f)
            }
        }

        return null
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\parser\parsers\SmsParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainparserparserssmsparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern

/**
 * Handles SMS from banking apps (Google Messages, Samsung Messages, etc.)
 * These are forwarded notifications from SMS — needs very careful filtering
 * because messaging apps send ALL messages.
 */
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject

class SmsParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms"
    )

    // Known bank SMS sender IDs
    private val BANK_SENDERS = setOf(
        "nbg", "alpha", "eurobank", "piraeus", "winbank",
        "revolut", "paypal", "visa", "mastercard",
        "ethniki", "εθνική", "αλφα", "πειραιώς"
    )

    private val amountPattern by lazy {
        Pattern.compile(
            """(\d+[.,]\d{2})\s*(EUR|€|USD|\$|GBP|£)|(EUR|€|USD|\$|GBP|£)\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val TRANSACTION_KEYWORDS = listOf(
        "αγορ", "πληρωμ", "χρέωσ", "συναλλαγ",
        "purchase", "payment", "charged", "debit",
        "agora", "pliromi", "plirwmi", "hreosi", "xreosi", "synallagi"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Fix (BUG-011): Handle null title by looking for sender in body or skipping if body looks like bank SMS
        val sender = title?.lowercase() ?: ""
        val body = listOfNotNull(text, bigText).joinToString(" ")
        val lowerBody = body.lowercase()

        // Sender check - either from title or start of body (e.g., "From: NBG")
        val isBankSms = BANK_SENDERS.any { 
            sender.contains(it) || lowerBody.startsWith(it) || lowerBody.contains("from: $it")
        }

        if (!isBankSms) return null

        // Must contain transaction keywords
        val hasKeyword = TRANSACTION_KEYWORDS.any { lowerBody.contains(it) }
        if (!hasKeyword) return null

        // Extract amount
        val matcher = amountPattern.matcher(body)
        if (!matcher.find()) return null

        val amountStr = (matcher.group(1) ?: matcher.group(4))?.replace(",", ".") ?: return null
        val amount = amountStr.toDoubleOrNull() ?: return null
        val currency = currencyNormalizer.normalize(matcher.group(2) ?: matcher.group(3))

        if (amount < 0.10 || amount > 50000) return null

        // Try to extract merchant from the SMS body
        val merchant = extractMerchantFromSms(body)

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.85f
        )
    }

    private val merchantPatterns by lazy {
        listOf(
            // Logic: look for STO/AT/etc, then capture until common delimiters like "στις", "on", "at", or date-like patterns
            // Use non-greedy match to stop at the first delimiter.
            Pattern.compile("""(?:στ[οη]ν?|at|sto|stin?|ston?|se|sta)\s+(.+?)(?:\s+(?:στις|on|at|stis|athens|at-|\d{1,2}[/.-])|$)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""-\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE)
        )
    }

    private fun extractMerchantFromSms(body: String): String {
        for (p in merchantPatterns) {
            val m = p.matcher(body)
            if (m.find()) {
                val raw = m.group(1) ?: continue
                return merchantCleaner.clean(raw)
            }
        }
        return "Unknown"
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\receipt\BankStatementParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainreceiptbankstatementparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankStatementParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) {
    companion object {
        // Greek National Bank transaction line pattern
        // Format: DD/MM/YYYYHH:MM:SS Valeur Branch Merchant Χ/Π Amount Balance
        private val GREEK_NBG_TRANSACTION = Regex(
            """(\d{2}/\d{2}/\d{4})(\d{2}:\d{2}:\d{2})\s+""" +  // DateTime (concatenated)
            """(\d{2}/\d{2}/\d{4})\s+""" +                      // Valeur Date
            """(\d{3}\s+\d{3})\s+""" +                          // Branch Code
            """(.+?)\s+""" +                                     // Merchant (non-greedy)
            """([ΧΠ])\s+""" +                                   // Type indicator (Χ=Debit, Π=Credit)
            """(-?[\d.,]+)\s+""" +                             // Amount
            """([\d.,]+)"""                                     // Balance
        )

        // Header patterns for Greek National Bank
        private val ACCOUNT_NUMBER_PATTERN = Regex("""Κίνηση Λογαριασμού\s+(\d+)""")
        private val IBAN_PATTERN = Regex("""ΙΒΑΝ\s*Λογαριασμού[:\s]+(GR\d+)""")
        private val BALANCE_PATTERN = Regex("""Λογιστικό\s*Υπόλοιπο[:\s]+([\d.,]+)€""")
    }

    /**
     * Parse a list of text blocks (with spatial data) into multiple transactions.
     * Groups text into horizontal rows and then extracts data from each row.
     */
    fun parse(blocks: List<TextBlock>): List<ParsedTransaction> {
        if (blocks.isEmpty()) return emptyList()

        // 1. Group blocks into rows based on vertical proximity
        val rows = groupBlocksIntoRows(blocks)

        // 2. Try Greek NBG specific parsing first
        val greekNbgTransactions = rows.mapNotNull { rowText ->
            tryParseGreekNbgTransaction(rowText)
        }

        // If we got good results from Greek NBG parser, use those
        if (greekNbgTransactions.isNotEmpty()) {
            android.util.Log.d("BankStatementParser", "Parsed ${greekNbgTransactions.size} Greek NBG transactions")
            return greekNbgTransactions
        }

        // 3. Otherwise fall back to generic parsing
        return rows.mapNotNull { rowText ->
            extractTransactionFromRow(rowText)
        }
    }

    private fun groupBlocksIntoRows(blocks: List<TextBlock>): List<String> {
        // Sort by top coordinate to process top-to-bottom
        val sortedBlocks = blocks.sortedBy { it.top }
        val rows = mutableListOf<MutableList<TextBlock>>()

        for (block in sortedBlocks) {
            val lastRow = rows.lastOrNull()

            // If block overlaps vertically with the current row, add it to that row
            if (lastRow != null && isSameRow(lastRow.last(), block)) {
                lastRow.add(block)
            } else {
                // Otherwise, it belongs to a new row below
                rows.add(mutableListOf(block))
            }
        }

        // Within each row, sort blocks by left-to-right and join into a single string
        return rows.map { rowBlocks ->
            rowBlocks.sortedBy { it.left }.joinToString(" ") { it.text }
        }
    }

    /**
     * Heuristic to determine if two text blocks belong to the same horizontal row.
     */
    private fun isSameRow(lastBlock: TextBlock, currentBlock: TextBlock): Boolean {
        // Find the vertical overlap
        val overlapTop = maxOf(lastBlock.top, currentBlock.top)
        val overlapBottom = minOf(lastBlock.bottom, currentBlock.bottom)
        val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0)

        val lastHeight = lastBlock.bottom - lastBlock.top
        val currentHeight = currentBlock.bottom - currentBlock.top
        val minHeight = minOf(lastHeight, currentHeight)

        if (minHeight <= 0) return false

        // If they overlap by more than 50% of the smaller block's height, they are likely same row
        return overlapHeight.toDouble() / minHeight > 0.5
    }

    /**
     * Try to parse a Greek National Bank transaction line.
     * Format: 12/02/2026 15:22:23 11/02/2026 705 040 MASOUTIS Χ -4,00 1.602,57
     * 
     * Table columns:
     * - Ημερομηνία/Ώρα: 12/02/2026 15:22:23 (timestamp)
     * - Κατάστημα: 705 (store code - ignore)
     * - Σύν: 040 (transaction code - ignore)
     * - Περιγραφή: MASOUTIS (merchant name)
     * - Χ/Π: Χ (transaction type)
     * - Ποσό: -4,00 (amount)
     * - Λογιστικό Υπόλοιπο: 1.602,57 (balance - ignore)
     */
    private fun tryParseGreekNbgTransaction(rowText: String): ParsedTransaction? {
        val cleanRow = rowText.replace('\u00A0', ' ').trim()

        // Skip header rows and non-transaction rows
        if (cleanRow.contains("Ημερομηνία") || cleanRow.contains("Κατάστημα") || 
            cleanRow.contains("Περιγραφή") || cleanRow.isBlank()) {
            return null
        }

        try {
            // Split by whitespace
            val parts = cleanRow.split(Regex("\\s+"))
            if (parts.size < 6) return null

            // Find the Χ or Π indicator (this is our anchor point)
            val typeIndex = parts.indexOfFirst { it == "Χ" || it == "Π" }
            if (typeIndex < 0) return null

            // Extract transaction type
            val type = when (parts[typeIndex]) {
                "Χ" -> TransactionType.PURCHASE  // ΧΡΕΩΣΗ (debit)
                "Π" -> TransactionType.DEPOSIT   // ΠΙΣΤΩΣΗ (credit/transfer)
                else -> TransactionType.PURCHASE
            }

            // Extract amount (next part after Χ/Π)
            if (typeIndex + 1 >= parts.size) return null
            val amountStr = parts[typeIndex + 1]
            val amount = parseEuropeanNumber(amountStr)
            if (amount == null || amount == 0.0) return null

            // Extract timestamp (first two parts: date + time)
            if (parts.size < 2) return null
            val dateStr = parts[0]  // DD/MM/YYYY
            val timeStr = parts[1]  // HH:MM:SS
            val timestamp = parseGreekBankDateTime(dateStr, timeStr)

            // Extract merchant name
            // Everything between the timestamp/codes and the Χ/Π indicator
            // Skip: date (0), time (1), valeur date (2), store code (3), transaction code (4)
            // Start from index 5 (or first non-numeric after index 2) until typeIndex
            val merchantStartIndex = parts.drop(2).indexOfFirst { part ->
                // Find first part that's not a pure number (not 705, 040, etc.)
                !part.matches(Regex("\\d+")) && !part.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))
            } + 2

            if (merchantStartIndex < 2 || merchantStartIndex >= typeIndex) {
                android.util.Log.w("BankStatementParser", "Could not find merchant in: $cleanRow")
                return null
            }

            // Join all parts between merchant start and type indicator
            val merchantParts = parts.subList(merchantStartIndex, typeIndex)
            val merchant = merchantParts.joinToString(" ").trim()

            if (merchant.isBlank()) {
                android.util.Log.w("BankStatementParser", "Empty merchant in: $cleanRow")
                return null
            }

            // Clean merchant name
            val cleanedMerchant = merchantCleaner.clean(merchant)

            android.util.Log.d("BankStatementParser", "Parsed NBG: $cleanedMerchant €${kotlin.math.abs(amount)} ($type)")

            return ParsedTransaction(
                amount = kotlin.math.abs(amount),
                currency = "EUR",
                merchant = cleanedMerchant,
                type = type,
                confidence = 0.90f, // High confidence for structured format
                date = timestamp
            )
        } catch (e: Exception) {
            android.util.Log.w("BankStatementParser", "Failed to parse NBG transaction: ${e.message} | Row: $cleanRow")
            return null
        }
    }

    /**
     * Parse Greek bank date and time: DD/MM/YYYY HH:MM:SS
     */
    private fun parseGreekBankDateTime(dateStr: String, timeStr: String): Long? {
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
            sdf.isLenient = false
            sdf.parse("$dateStr $timeStr")?.time
        } catch (e: Exception) {
            // Fallback: try just the date
            parseGreekBankDate(dateStr)
        }
    }

    /**
     * Parse European number format: 1.602,57 -> 1602.57
     */
    private fun parseEuropeanNumber(numStr: String): Double? {
        return try {
            // Remove spaces, convert European format to US format
            val cleaned = numStr.trim().replace(" ", "")

            // Check if it's European format (comma as decimal separator)
            if (cleaned.contains(",")) {
                // European: 1.602,57 -> remove dots, replace comma with dot
                cleaned.replace(".", "").replace(",", ".").toDoubleOrNull()
            } else {
                // US format or integer
                cleaned.toDoubleOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse Greek bank date format: DD/MM/YYYY
     */
    private fun parseGreekBankDate(dateStr: String): Long? {
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            sdf.isLenient = false
            sdf.parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTransactionFromRow(rowText: String): ParsedTransaction? {
        // 1. Clean noise
        val cleanRow = rowText.replace('\u00A0', ' ').trim()

        // 2. Look for amount patterns (DUP-005)
        val amountMatcher = com.yourname.expensetracker.domain.util.CommonPatterns.AMOUNT_REGEX.matcher(cleanRow)

        if (!amountMatcher.find()) return null

        // Fix (BUG-009): Robust European & US decimal parsing
        val rawAmount = amountMatcher.group(2)?.replace(" ", "") ?: return null
        val lastSep = rawAmount.findLastAnyOf(listOf(".", ","))

        val amountStr = if (lastSep != null) {
            val (sepIndex, sepChar) = lastSep
            val integerPart = rawAmount.substring(0, sepIndex).replace(".", "").replace(",", "")
            val decimalPart = rawAmount.substring(sepIndex + 1)
            "$integerPart.$decimalPart"
        } else {
            rawAmount
        }

        val absAmount = kotlin.math.abs(amountStr.toDoubleOrNull() ?: return null)

        // Fix (BUG-010): Use more specific currency check
        var currency = "EUR" // Default currency
        val currencyGroup = amountMatcher.group(1) ?: amountMatcher.group(3)
        if (currencyGroup != null && currencyGroup.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
            currency = currencyNormalizer.normalize(currencyGroup)
        }

        // 3. Detect Transaction Type (ISSUE-008)
        val upperRow = cleanRow.uppercase()
        val isPurchase = upperRow.contains("ΑΓΟΡΑ") || upperRow.contains("PURCHASE") || 
                         upperRow.contains("ΧΡΕΩΣΗ") || upperRow.contains("DEBIT") ||
                         upperRow.contains("PAYMENT") || upperRow.contains("CARD")

        val isDeposit = upperRow.contains("ΚΑΤΑΘΕΣΗ") || upperRow.contains("DEPOSIT") ||
                        upperRow.contains("ΠΙΣΤΩΣΗ") || upperRow.contains("ΠΙΣΤΩΣH") || upperRow.contains("CREDIT") ||
                        upperRow.contains("REFUND") || upperRow.contains("MISTHODOSIA") ||
                        upperRow.contains("SALARY") || upperRow.contains("WAGES") || upperRow.contains("ΜΙΣΘΟΔΟΣΙΑ")

        val type = when {
            isDeposit -> TransactionType.DEPOSIT
            isPurchase -> TransactionType.PURCHASE
            amountStr.contains("-") -> TransactionType.PURCHASE
            else -> TransactionType.PURCHASE // Default to Purchase if ambiguous (Expense Tracker context) 
        }

        // 4. Extract logic for merchant (ISSUE-010)
        // Usually merchant is the text that is NOT the amount and NOT a date/time
        val dateValue = extractDate(cleanRow)

        var merchant = cleanRow.replace(amountMatcher.group(0)!!, "")
            .replace(Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?"""), "") // Date (for cleaning)
            .replace(Regex("""\d{2}:\d{2}(:\d{2})?"""), "") // Time
            // Remove common bank prefixes/suffixes
            .replace(Regex("""(?i)^(AGORA|ΑΓΟΡΑ|PURCHASE|PAYMENT)\s*[:\-]?\s*"""), "")
            .replace(Regex("""(?i)\s*(STO|ΣΤΟ|AT)\s*$"""), "")
            .replace(Regex("""\s{2,}"""), " ") // Double spaces
            .trim()

        // Basic validation: must have some letters to be a merchant
        if (merchant.isBlank() || !merchant.any { it.isLetter() }) {
            merchant = "Unknown Merchant"
        }

        return ParsedTransaction(
            amount = absAmount,
            currency = currency,
            merchant = merchantCleaner.clean(merchant),
            type = type,
            confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RECEIPT_FALLBACK,
            date = dateValue
        )
    }

    private fun extractDate(text: String): Long? {
        val datePatterns = listOf(
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](20\d{2})"""),
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{2})""")
        )

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                val yearInt = year.toIntOrNull() ?: 0

                if (yearInt in 2015..2035) {
                    try {
                        return sdf.parse("${d.padStart(2, '0')}/${m.padStart(2, '0')}/$year")?.time
                    } catch (e: Exception) {}
                }
            }
        }
        return null
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\receipt\ReceiptOcrService.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val savedImagePath: String
)

data class TextBlock(
    val text: String,
    val confidence: Float?,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

@Singleton
class ReceiptOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        // Initialize PDFBox for Android
        PDFBoxResourceLoader.init(context)
    }

    // Reverting to DEFAULT_OPTIONS as Builder might not be available in current dependency version
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Dispatcher that automatically routes URIs to the correct processor based on MIME type.
     */
    suspend fun processUri(uri: Uri): OcrResult {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        return if (mimeType == "application/pdf") {
            processPdf(uri)
        } else {
            processImage(uri)
        }
    }

    /**
     * Process an image URI and return OCR results.
     * Also saves a compressed copy of the image for future reference.
     */
    suspend fun processImage(imageUri: Uri): OcrResult {
        // 1. Load and prepare the image (throws if fail)
        val bitmap = loadAndCorrectBitmap(imageUri) ?: throw IllegalStateException("Failed to load and correct image: $imageUri")

        try {
            // 2. Save compressed copy
            val savedPath = saveReceiptImage(bitmap)

            // 3. Run ML Kit OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizeText(inputImage)

            // 4. Extract blocks with confidence filtering
            val blocks = visionText.textBlocks.mapNotNull { block ->
                val avgConfidence = block.lines.mapNotNull { it.confidence }.average().toFloat()
                // If confidence is available and very low (< 0.2), skip it.
                // Note: ML Kit often returns null confidence for Latin/Default models, so we default to 1.0 if null
                val safeConfidence = if (block.lines.firstOrNull()?.confidence != null) avgConfidence else 1.0f

                if (safeConfidence < 0.2f && block.text.length < 3) {
                    // Skip very low confidence noise (usually single characters)
                    null
                } else {
                    TextBlock(
                        text = block.text,
                        confidence = safeConfidence,
                        // lines argument removed as it's not in TextBlock definition
                        left = block.boundingBox?.left ?: 0,
                        top = block.boundingBox?.top ?: 0,
                        right = block.boundingBox?.right ?: 0,
                        bottom = block.boundingBox?.bottom ?: 0
                    )
                }
            }

            return OcrResult(
                fullText = blocks.joinToString("\n\n") { it.text },
                blocks = blocks,
                savedImagePath = savedPath
            )
        } finally {
            // CRITICAL: Prevent memory leaks during batch processing
            bitmap.recycle()
        }
    }

    /**
     * Process a PDF URI with intelligent routing:
     * 1. Try direct text extraction (fast for digital PDFs)
     * 2. Fall back to bitmap rendering + OCR (for scanned PDFs)
     */
    suspend fun processPdf(pdfUri: Uri): OcrResult {
        // First, try direct text extraction
        val extractedText = extractPdfText(pdfUri)

        // If we got substantial text (>100 chars), use it
        if (extractedText.length > 100) {
            android.util.Log.d("ReceiptOcrService", "Using direct PDF text extraction (${extractedText.length} chars)")
            return processPdfWithTextExtraction(pdfUri, extractedText)
        }

        // Otherwise, fall back to OCR
        android.util.Log.d("ReceiptOcrService", "PDF has minimal text, falling back to OCR")
        return processPdfWithOcr(pdfUri)
    }

    /**
     * Extract text directly from PDF using PDFBox (fast for digital PDFs).
     */
    private suspend fun extractPdfText(pdfUri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_pdf_extract_${System.nanoTime()}.pdf")
        var document: PDDocument? = null

        try {
            // Copy PDF to temp file
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ""

            // Load PDF and extract text
            document = PDDocument.load(tempFile)
            val stripper = PDFTextStripper()

            // Limit to first 5 pages for performance
            val pageLimit = minOf(document.numberOfPages, 5)
            stripper.startPage = 1
            stripper.endPage = pageLimit

            val text = stripper.getText(document)
            android.util.Log.d("ReceiptOcrService", "Extracted ${text.length} chars from $pageLimit pages")

            return@withContext text
        } catch (e: Exception) {
            android.util.Log.w("ReceiptOcrService", "PDF text extraction failed: ${e.message}")
            return@withContext ""
        } finally {
            try { document?.close() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Process PDF using direct text extraction (for digital PDFs).
     */
    private suspend fun processPdfWithTextExtraction(pdfUri: Uri, extractedText: String): OcrResult {
        // Save first page as thumbnail for UI
        val thumbnailPath = renderPdfFirstPageThumbnail(pdfUri)

        // Create text blocks from extracted text (simple line-based approach)
        val blocks = extractedText.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                TextBlock(
                    text = line.trim(),
                    confidence = 1.0f, // Direct extraction has perfect confidence
                    left = 0,
                    top = index * 20, // Approximate line height
                    right = 1000,
                    bottom = (index + 1) * 20
                )
            }

        return OcrResult(
            fullText = extractedText,
            blocks = blocks,
            savedImagePath = thumbnailPath
        )
    }

    /**
     * Render first page of PDF as thumbnail for UI preview.
     */
    private suspend fun renderPdfFirstPageThumbnail(pdfUri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_pdf_thumb_${System.nanoTime()}.pdf")
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            // Copy PDF to temp file
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ""

            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            val page = renderer.openPage(0)
            val scale = 1024f / page.width
            val bitmap = Bitmap.createBitmap(
                1024,
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val savedPath = saveReceiptImage(bitmap)
            bitmap.recycle()

            return@withContext savedPath
        } catch (e: Exception) {
            android.util.Log.w("ReceiptOcrService", "Thumbnail rendering failed: ${e.message}")
            return@withContext ""
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Process PDF by rendering pages to bitmaps and running OCR (for scanned PDFs).
     */
    private suspend fun processPdfWithOcr(pdfUri: Uri): OcrResult {
        val tempFile = File(context.cacheDir, "temp_pdf_${System.nanoTime()}.pdf")
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            // 1. Copy PDF to local file (PdfRenderer needs a ParcelFileDescriptor from a file or pipe)
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open PDF stream: $pdfUri")

            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            val allFullText = StringBuilder()
            val allBlocks = mutableListOf<TextBlock>()
            var savedThumbnailPath = ""

            // Limit to first 3-5 pages for performance (Rich functionality requirement)
            val pageLimit = 5 
            val pagesToProcess = minOf(renderer.pageCount, pageLimit)

            var verticalOffset = 0

            for (i in 0 until pagesToProcess) {
                val page = renderer.openPage(i)

                // Render page to high-quality Bitmap (OCR prefers ~200-300 DPI equivalent)
                // 1024 width is our standard for OCR in loadAndCorrectBitmap
                val scale = 1024f / page.width
                val bitmapWidth = 1024
                val bitmapHeight = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                try {
                    // Save first page as JPG for UI preview/record
                    if (i == 0) {
                        savedThumbnailPath = saveReceiptImage(bitmap)
                    }

                    // Run OCR on this page
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText = recognizeText(inputImage)

                    // Add full text
                    allFullText.append(visionText.text).append("\n\n")

                    // Add blocks with offset (Virtual Long Page strategy)
                    visionText.textBlocks.forEach { block ->
                        allBlocks.add(
                            TextBlock(
                                text = block.text,
                                confidence = block.lines.firstOrNull()?.confidence,
                                left = block.boundingBox?.left ?: 0,
                                top = (block.boundingBox?.top ?: 0) + verticalOffset,
                                right = block.boundingBox?.right ?: 0,
                                bottom = (block.boundingBox?.bottom ?: 0) + verticalOffset
                            )
                        )
                    }

                    verticalOffset += bitmapHeight

                } finally {
                    bitmap.recycle() // CRITICAL: Release memory immediately
                    page.close()
                }
            }

            return OcrResult(
                fullText = allFullText.toString().trim(),
                blocks = allBlocks,
                savedImagePath = savedThumbnailPath
            )

        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "PDF processing failed for $pdfUri", e)
            throw IllegalStateException("Failed to scan PDF: ${e.message}", e)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun recognizeText(
        image: InputImage
    ): com.google.mlkit.vision.text.Text {
        return kotlinx.coroutines.withTimeout(15000) { // Fix 4.17: 15s timeout
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        continuation.resume(text)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            }
        }
    }

    /**
     * Load bitmap from URI with EXIF rotation correction.
     * Copies to a temp file first to ensure reliable multi-read access.
     */
    private fun loadAndCorrectBitmap(uri: Uri): Bitmap? {
        val tempFile = File(context.cacheDir, "temp_ocr_${System.nanoTime()}.jpg")
        var decodedBitmap: Bitmap? = null
        try {
            // Copy URI to temp file
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open input stream for $uri")

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Temp file creation failed or empty for $uri")
            }

            // 1. Get dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)

            // Calculate sample size - Optimized: 1024 is plenty for OCR and saves memory/time
            val maxDimension = 1024
            var sampleSize = 1
            if (options.outWidth > 0 && options.outHeight > 0) {
                while (options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension
                ) {
                    sampleSize *= 2
                }
            }

            // 2. Decode actual bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                ?: throw IllegalStateException("Bitmap decode failed for $uri (Sample: $sampleSize)")
            decodedBitmap = bitmap

            // 3. Apply EXIF rotation
            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            var needsRotate = true
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> needsRotate = false
            }

            if (needsRotate) {
                try {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated != bitmap) {
                        bitmap.recycle() // Clean up original if rotated
                    }
                    return rotated
                } catch (e: Exception) {
                    bitmap.recycle() // CRITICAL: Recycle original if rotation fails (OOM similar)
                    throw e
                }
            } else {
                return bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "Error loading bitmap from $uri", e)
            if (decodedBitmap?.isRecycled == false) {
                decodedBitmap?.recycle()
            }
            throw IllegalStateException("Failed to load image: ${e.message}", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Save a compressed copy of the receipt image
     */
    private fun saveReceiptImage(bitmap: Bitmap): String {
        val receiptsDir = File(context.filesDir, "receipts")
        if (!receiptsDir.exists()) receiptsDir.mkdirs()

        val fileName = "receipt_${System.currentTimeMillis()}.jpg"
        val file = File(receiptsDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }

        return file.absolutePath
    }

    /**
     * Create a temporary URI for the camera to write to
     */
    fun createTempImageUri(): Uri {
        val cacheDir = File(context.cacheDir, "receipt_images")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")

        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Delete a saved receipt image
     */
    fun deleteImage(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\receipt\ReceiptParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import java.text.SimpleDateFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptParser @Inject constructor() {

    data class ParsedReceipt(
        val merchantName: String?,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?,
        val date: Long?,
        val currency: String,
        val lineItems: List<LineItem>,
        val confidence: Float
    )

    data class LineItem(
        val description: String,
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )

    // Total amount patterns (Greek + English receipts)
    private val totalPatterns = listOf(
        // Look for our normalized keys (High confidence)
        Pattern.compile(
            """(?:TOTAL_KEY|AMOUNT_KEY|PAYMENT_KEY|CASH_KEY|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΣΥΝΟΛΟ|ΣYNONO|TOTAL|AMOUNT|CASH|METPHTA|ΜΕΤΡΗΤΑ)\s*[:\s]*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Fallback for raw totals or noise in between
        Pattern.compile(
            """(?:TOTAL_KEY|TOTAL|ΣΥΝΟΛΟ|AMOUNT_KEY|ΠΟΣΟ|AMOUNT|ΣYNONO|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|CASH_KEY|CASH|ΜΕΤΡΗΤΑ|METPHTA).*?(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Tax patterns
    private val taxPatterns = listOf(
        Pattern.compile(
            """(?:VAT_KEY|VAT|TAX|Φ\.?Π\.?Α\.?)[^(\d+[.,]\d{2})]*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Date patterns
    private val datePatterns = listOf(
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{4}|\d{2})"""),  // DD/MM/YYYY or DD/MM/YY
        Pattern.compile("""(\d{4})[/\-.](\d{2})[/\-.](\d{2})""")   // YYYY/MM/DD
    )

    // Line item pattern: "description  price" with at least 2 spaces or tab
    private val lineItemPatterns = listOf(
        // "Item description    12.50" (fuzzy spaces in amount)
        Pattern.compile(
            """^(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "Quantity x Description   Sum"
        Pattern.compile(
            """^(\d+)\s*[xX*]\s*(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "Description @ UnitPrice   Sum"
        Pattern.compile(
            """^(.{3,40}?)\s*@\s*(\d+[\s.,]\d{2})\s{2,}(\d+[\s.,]\d{2})\s*$""",
            Pattern.MULTILINE
        ),
        // "Qty x Desc @ UnitPrice   Sum"
        Pattern.compile(
            """^(\d+)\s*[xX*]\s*(.{3,40}?)\s*@\s*(\d+[\s.,]\d{2})\s{2,}(\d+[\s.,]\d{2})\s*$""",
            Pattern.MULTILINE
        )
    )

    // Subtotal patterns (to distinguish from total)
    private val subtotalPatterns = listOf(
        Pattern.compile(
            """(?:SUBTOTAL_KEY|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΚΑΘΑΡΗ\s*ΑΞΙΑ)\s*[:\s]*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Discount patterns
    private val discountPatterns = listOf(
        Pattern.compile(
            """(?:DISCOUNT|ΕΚΠΤΩΣΗ|SAVINGS?|ΜΕΙΟΝ|ΕΚΠΤ)\s*[:\s]*-?\s*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    fun parse(rawText: String): ParsedReceipt {
        // 1. Pre-process text to fix OCR spacing issues and Greek characters
        val cleanedText = normalizeGreekOcr(rawText)
        val lines = cleanedText.lines().filter { it.isNotBlank() }

        // 2. Extract merchant
        val merchant = extractMerchant(lines)

        // 3. Extract date
        val date = extractDate(cleanedText)

        // 4. Extract total
        val total = extractTotal(lines)

        // 5. Extract subtotal (using original text as fallback or new logic if needed)
        val subtotal = extractSubtotal(cleanedText) ?: extractSubtotal(rawText)

        // 6. Extract tax
        val tax = extractTax(cleanedText) ?: extractTax(rawText)

        // 7. Extract line items
        val lineItems = extractLineItems(cleanedText)

        // 8. Cross-validate
        val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }

        // 9. Calculate subtotal
        val finalSubtotal = subtotal
            ?: if (finalTotal != null && tax != null) finalTotal - tax else null

        // 10. Confidence
        val confidence = calculateConfidence(merchant, finalTotal, date, lineItems, tax)

        return ParsedReceipt(
            merchantName = merchant,
            total = finalTotal,
            subtotal = finalSubtotal,
            tax = tax,
            date = date,
            currency = detectCurrency(cleanedText),
            lineItems = lineItems,
            confidence = confidence
        )
    }

    private fun normalizeGreekOcr(text: String): String {
        var normalized = text.uppercase()

        // Fix numbers FIRST - Remove spaces in numbers like "4 5 . 5 0"
        normalized = normalized.replace(Regex("""(?<=\d)\s+(?=[.,\d])"""), "")
        normalized = normalized.replace(Regex("""(?=[.,\d])\s+(?=\d)"""), "")

        // Normalize Greek characters to English counterparts for easier matching
        // Use more robust matching for Greek words without \b if possible

        // Compound keywords - MUST be before single ones
        normalized = normalized.replace(Regex("""ΣΥΝΟΛΙΚΗ\s+ΑΞΙΑ"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""ΚΑΘΑΡΗ\s+ΑΞΙΑ"""), "SUBTOTAL_KEY")
        normalized = normalized.replace(Regex("""ΓΕΝΙΚΟ\s+ΣΥΝΟΛΟ"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""ΜΕΡΙΚΟ\s+ΣΥΝΟΛΟ"""), "SUBTOTAL_KEY")
        normalized = normalized.replace(Regex("""ΤΕΛΙΚΗ\s+ΑΞΙΑ"""), "TOTAL_KEY")

        // Single keywords (Using more flexible boundaries for Greek/Latin mix)
        val boundary = """(?:^|[\s:;.,/-])"""
        val endBoundary = """(?:$|[\s:;.,/-])"""

        // Total keywords - ΣΥΝΟΛΟ and variations
        normalized = normalized.replace(Regex(boundary + "ΣΥΝΟΛΟ" + endBoundary), " TOTAL_KEY ")
        normalized = normalized.replace(Regex(boundary + "ΤΕΛΙΚΟ" + endBoundary), " TOTAL_KEY ")
        normalized = normalized.replace(Regex(boundary + "ΠΛΗΡΩΤΕΟ" + endBoundary), " TOTAL_KEY ")

        // Common OCR errors for ΣΥΝΟΛΟ - Unified boundary check
        val synoloVariations = listOf(
            "[EZI23][YVUI]N[O0I]?[AΛVLN][O0ΩI]?", // Flexible pattern for ΣΥΝΟΛΟ
            "ZYNOAO", "ZYNOAΩ", "2YNONO", "2YNOAO", 
            "EYNOAO", "EYNONO", "SYNOAO", "ZYNOIO"
        )
        for (variant in synoloVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }

        // ΤΕΛΙΚΟ variations
        val telikoVariations = listOf("TEAIKO", "TEΛIKO", "TΕΛΙΚΟ")
        for (variant in telikoVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }

        // ΠΛΗΡΩΤΕΟ variations
        val pliroteoVariations = listOf("NAHPΩTEO", "NAHPQTEO", "ΠΛHPΩTEO")
        for (variant in pliroteoVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }

        // Amount keywords
        normalized = normalized.replace(Regex(boundary + "ΠΟΣΟ" + endBoundary), " AMOUNT_KEY ")
        normalized = normalized.replace(Regex(boundary + "[NΠn][O0][SZsz][O0]" + endBoundary), " AMOUNT_KEY ")

        // Cash keywords
        normalized = normalized.replace(Regex(boundary + "ΜΕΤΡΗΤΑ" + endBoundary), " CASH_KEY ")
        normalized = normalized.replace(Regex(boundary + "METPHTA" + endBoundary), " CASH_KEY ")

        // VAT/Tax keywords - ΦΠΑ and OCR corruptions
        normalized = normalized.replace(Regex(boundary + "Φ\\.?Π\\.?Α\\.?" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "0\\.?n\\.?A\\.?" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "0\\.?Π\\.?Α" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "O\\.?n\\.?A" + endBoundary), " VAT_KEY ")

        // Date keywords
        normalized = normalized.replace(Regex(boundary + "ΗΜΕΡΟΜΗΝΙΑ" + endBoundary), " DATE_KEY ")
        normalized = normalized.replace(Regex(boundary + "HM/NIA" + endBoundary), " DATE_KEY ")
        normalized = normalized.replace(Regex(boundary + "HMEPOMHNIA" + endBoundary), " DATE_KEY ")

        // Value keyword
        normalized = normalized.replace(Regex(boundary + "ΑΞΙΑ" + endBoundary), " VALUE_KEY ")

        // Currency keywords - ΕΥΡΩ and variations
        normalized = normalized.replace(Regex(boundary + "ΕΥΡΩ" + endBoundary), " EUR ")
        normalized = normalized.replace(Regex(boundary + "ΕΥΡΑ" + endBoundary), " EUR ")
        normalized = normalized.replace(Regex(boundary + "[E3]YP[ΩO9]" + endBoundary), " EUR ")

        // Date OCR fixes: 16-D4 -> 16-04
        normalized = normalized.replace(Regex("""(\d{1,2})[-/][DO0](\d{1,2})[-/](\d{2,4})"""), "$1-0$2-$3")
        // Fix double zero if above resulted in 16-004
        normalized = normalized.replace("-00", "-0")

        return normalized
    }

    // --- MERCHANT EXTRACTION ---
    private fun extractMerchant(lines: List<String>): String? {
        // Expanded invalid merchant patterns
        val invalidMerchants = listOf(
            // Keywords that should never be merchants
            "APODEIXI", "AIOAEIEH", "ANOD", "NOMIMH", "ENARXI", "START",
            "EAPA", "ADDRESS", "THA", "TEL", "AFM", "AOM", "A.M.", "ΑΦΜ",
            "EYNONO", "ZYNOAO", "SYNOAO", "TOTAL_KEY", "CASH_KEY", "AMOUNT_KEY",
            // Card processors - CRITICAL
            "CARDLINK", "WORLDLINE", "VISA", "MASTERCARD", "MAESTRO",
            "AMERICAN EXPRESS", "AMEX", "DINERS", "DISCOVER",
            // Banks
            "PIRAEUS", "EUROBANK", "ALPHA BANK", "NBG", "NATIONAL BANK",
            "LYNK", "BANK OF CYPRUS", "HELLENIC BANK", "REVOLUT",
            "VIVA", "SUMUP", "MYPOS", "STRIPE",
            // Transaction types
            "AGORA", "SALE", "PURCHASE", "CONTACTLESS", "TERMINAL",
            "TRANSACTION", "ΠΑΡΑΛΑΒΗ", "ΑΓΟΡΑ",
            // Serial/reference patterns
            "ZEIPA", "SERIAL", "ΑΡΙΘΜΟΣ", "APIOMOE", "APIOMOX",
            // URLs and garbage
            "WWW.", "HTTP", ".GR", ".COM", "HTTPS://",
            // Payment related
            "KAPTA", "KAPTEE", "CARD", "ΚΑΡΤΑ", "METPHTA", "ΜΕΤΡΗΤΑ"
        )

        // Header markers (indicate we're past the merchant name)
        val headerMarkers = listOf(
            "ΑΦΜ", "A.Φ.Μ.", "Α.Φ.Μ", "@.M.", "A.M.", "AΦM",
            "Α.Ο.Υ.", "ΑΟΥ", "A.0.Y.", "Δ.Ο.Υ.", "ΔΟΥ",
            "ΤΗΛ", "THA", "THΛ", "ΤΗΛ:", "THA:",
            "ΟΔΟΣ", "ΣΤΡ.", "STR.", "ADDRESS",
            "Τ.Κ.", "TK", "Τ.Κ", "T.K.",
            "Α.Μ.Μ.", "ΑΜΜ", "ΑΜΜ.",
            "ΗΜΕΡΟΜΗΝΙΑ", "HM/NIA", "DATE_KEY",
            // Card receipt markers
            "ΑΓΟΡΑ", "AGORA", "AGORA-SALE", "SALE", "PURCHASE", 
            "CONTACTLESS", "TERMINAL", "TRANSACTION", "ENTER BONUS",
            // Card reference patterns
            "****", "5356", "MARK:", "UID:", "AUTH:"
        )

        // Find markers and extract merchant above them
        for ((index, line) in lines.withIndex()) {
            if (index > 10) break

            for (marker in headerMarkers) {
                if (line.contains(marker, ignoreCase = true)) {
                    // Scan upwards for valid merchant
                    for (j in index - 1 downTo 0) {
                        val candidate = lines[j]
                        if (isValidMerchantLine(candidate, invalidMerchants)) {
                            val cleaned = cleanMerchantName(candidate)
                            // Additional check: don't return card processor names
                            if (!isCardProcessor(cleaned)) {
                                return cleaned
                            }
                        }
                    }
                }
            }
        }

        // Fallback
        for (line in lines.take(5)) {
            if (isValidMerchantLine(line, invalidMerchants)) {
                val cleaned = cleanMerchantName(line)
                if (!isCardProcessor(cleaned)) {
                    return cleaned
                }
            }
        }

        return null
    }

    private fun isCardProcessor(name: String): Boolean {
        val processors = listOf(
            "CARDLINK", "WORLDLINE", "VIVA", "PIRAEUS", "EUROBANK", "ALPHA BANK",
            "LYNK", "BANK OF CYPRUS", "HELLENIC BANK", "NBG", "REVOLUT", "STRIPE",
            "SUMUP", "MYPOS", "CIBC", "TD BANK", "AMEX", "AMERICAN EXPRESS", "DINERS"
        )
        return processors.any { name.contains(it, ignoreCase = true) }
    }

    private fun isValidMerchantLine(line: String, invalidHeaders: List<String>): Boolean {
        if (line.length < 3) return false
        if (line.all { !it.isLetter() }) return false // Must have letters
        if (invalidHeaders.any { line.contains(it) }) return false

        // Skip if line is mostly numbers
        val digitCount = line.count { it.isDigit() }
        if (digitCount > line.length / 2) return false

        // Skip lines that are dates or times
        if (line.matches(Regex(""".*(\\d{2}[/-]\\d{2}[/-]\\d{4}|\\d{2}:\\d{2}:\\d{2}|A\\.?Φ\\.?Μ\\.?).*$"""))) return false

        return true
    }

    private fun cleanMerchantName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Zα-ωΑ-Ω0-9\\s&.-]"), "").trim()
    }

    private fun extractTotal(lines: List<String>): Double? {
        val amountRegex = Regex("""(\d{1,10}(?:[.,\s]\d{3})*[.,]\d{2})(?!\s?%)""")

        // Lines that should be COMPLETELY skipped (receipt numbers, IDs, etc.)
        val nonTotalIndicators = listOf(
            "APIOMOE", "APIOMOX", "ZEIPA", "SERIAL", "AA/Y",
            "AP.r.E.MH", "APIEMOE", "ANEAATH", "APIEMOX",
            "AOM", "AFM", "A.F.M.", "THA", "THA:", "DATE_KEY", "HM/NIA",
            // Card receipt markers
            "5356", "****", "ENTER BONUS", "MARK:", "UID:", "AUTH:",
            // Change/Resta patterns
            "CHANGE_KEY", "ΡΕΣΤΑ", "RESTA", "ΑΛΛΑΓΗ",
            // Time markers (Greek QPA = TIME)
            "QPA:", "OPA:", "ΩΡΑ:"
        )

        // Priority-based extraction: track best candidate
        // Priority: TOTAL_KEY > AMOUNT_KEY > CASH_KEY > standalone amounts
        var bestTotal: Double? = null
        var bestPriority: Int = -1 // 3=TOTAL_KEY, 2=AMOUNT_KEY, 1=CASH_KEY, 0=standalone

        // Strategy 1: Look for TOTAL_KEY (highest priority)
        val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
        if (totalLineIndex != -1) {
            // Check this line and next 3 lines (amount may be split)
            for (offset in 0..3) {
                if (totalLineIndex + offset < lines.size) {
                    val lineToCheck = lines[totalLineIndex + offset]
                    val amount = extractAmountFromLine(lineToCheck, amountRegex)
                    if (amount != null && amount > 0.01) {
                        bestTotal = amount
                        bestPriority = 3
                        break
                    }
                }
            }
        }

        // If we found TOTAL_KEY amount, return it immediately
        if (bestTotal != null && bestPriority == 3) return bestTotal

        // Strategy 2: Look for AMOUNT_KEY (medium priority)
        val amountLineIndex = lines.indexOfLast { it.contains("AMOUNT_KEY") && !it.contains("TOTAL_KEY") }
        if (amountLineIndex != -1) {
            for (offset in 0..2) {
                if (amountLineIndex + offset < lines.size) {
                    val lineToCheck = lines[amountLineIndex + offset]
                    val amount = extractAmountFromLine(lineToCheck, amountRegex)
                    if (amount != null && amount > 0.01 && (bestPriority < 2)) {
                        bestTotal = amount
                        bestPriority = 2
                        break
                    }
                }
            }
        }

        // Strategy 3: Look for CASH_KEY (lower priority - only if nothing better found)
        val cashLineIndex = lines.indexOfLast { it.contains("CASH_KEY") && !it.contains("TOTAL_KEY") && !it.contains("CHANGE_KEY") }
        if (cashLineIndex != -1 && bestPriority < 2) {
            for (offset in 0..2) {
                if (cashLineIndex + offset < lines.size) {
                    val lineToCheck = lines[cashLineIndex + offset]
                    val amount = extractAmountFromLine(lineToCheck, amountRegex)
                    if (amount != null && amount > 0.01 && (bestPriority < 1)) {
                        bestTotal = amount
                        bestPriority = 1
                        break
                    }
                }
            }
        }

        // If we found any keyword-based amount, return it
        if (bestTotal != null && bestPriority > 0) return bestTotal

        // Strategy 3.5: Look for card receipt format "POSO/AMOUNT:" or "€XX,XX" alone
        for (i in lines.indices) {
            val line = lines[i]

            // Card receipt pattern: "POSO/AMOUNT:" or standalone euro amount
            if (line.contains("POSO") || line.matches(Regex("""^€?\s*\d+[.,]\d{2}\s*€?\s*$"""))) {
                val amount = extractAmountFromLine(line, amountRegex)
                if (amount != null && isValidAmount(amount, line) && bestPriority < 2) {
                    bestTotal = amount
                    bestPriority = 2
                }
            }
        }

        if (bestTotal != null) return bestTotal

        // Strategy 4: Fallback - Find largest VALID standalone amount
        var maxAmount = 0.0

        // More flexible regex for fallback that includes currency symbols
        val fallbackRegex = Regex("""€?\s*(\d{1,10}(?:[.,\s]\d{3})*[.,]\d{2})\s*€?""")

        // Time pattern to skip
        val timePattern = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")

        // Change/Resta pattern
        val changePattern = Regex("""(CHANGE|ΡΕΣΤΑ|RESTA|ΑΛΛΑΓΗ)""")

        for (i in lines.indices) {
            val line = lines[i]

            // Skip lines with non-total indicators or tax-only lines
            if (nonTotalIndicators.any { line.contains(it, ignoreCase = true) }) continue
            if (isTaxOnlyLine(line)) continue
            if (timePattern.containsMatchIn(line)) continue
            if (changePattern.containsMatchIn(line)) continue

            // Skip long number lines (barcodes/IDs)
            if (line.replace(Regex("[^0-9]"), "").length > 9) continue

            // Skip VAT percentage lines
            if (line.contains("%") && !line.contains("TOTAL")) continue

            // Skip card reference lines
            if (line.contains("5356") || line.contains("****") || line.contains("ENTER BONUS")) continue

            // Try both the primary regex and fallback regex
            val matches = amountRegex.findAll(line).toList() + fallbackRegex.findAll(line).toList()
            for (match in matches) {
                val rawVal = match.groupValues[1]
                val amount = parseAmount(rawVal)

                if (isValidAmount(amount, line) && amount > maxAmount) {
                    maxAmount = amount
                }
            }
        }

        // Use fallback if we found something and no better option exists
        if (maxAmount > 0.0 && bestPriority < 1) return maxAmount

        // Return best found (might be null)
        return bestTotal ?: if (maxAmount > 0.0) maxAmount else null
    }

    private fun isValidAmount(amount: Double, line: String): Boolean {
        // Reject zero or near-zero
        if (amount < 0.01) return false

        // Reject unreasonably large amounts
        if (amount > 5000.0) return false

        // Reject year-like numbers (allow decimal years only if not whole)
        if (amount >= 2015.0 && amount <= 2035.0 && amount % 1.0 == 0.0) return false

        // NEW: Reject if line looks like a receipt number line
        val receiptNumberPatterns = listOf(
            Regex("""APIOMOE|APIOMOX|ΑΡΙΘΜΟΣ""", RegexOption.IGNORE_CASE),
            Regex("""ZEIPA|ΣΕΙΡΑ"""),
            Regex("""AP\.?r\.?E\.?MH"""),
            Regex("""ΑΠΟΔΕΙΞΗ|ΠΑΡΑΣΤΑΤΙΚΟ""", RegexOption.IGNORE_CASE)
        )
        if (receiptNumberPatterns.any { it.containsMatchIn(line) }) return false

        return true
    }

    private fun parseAmount(rawAmount: String): Double {
        if (rawAmount.isBlank()) return 0.0

        var cleaned = rawAmount

        // NEW: Handle E-prefixed amounts (E0,13 -> try to extract 0.13)
        if (cleaned.startsWith("E") || cleaned.startsWith("e")) {
            val rest = cleaned.substring(1)
            // Use simple check if rest looks like number start
            if (rest.isNotEmpty() && rest[0].isDigit()) {
                 cleaned = rest
            }
        }

        // Remove all spaces
        cleaned = cleaned.replace(" ", "")

        // Find last separator
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val lastSepIndex = kotlin.math.max(lastComma, lastDot)

        return if (lastSepIndex >= 0) {
            val integerPart = cleaned.substring(0, lastSepIndex).replace(".", "").replace(",", "")
            val decimalPart = cleaned.substring(lastSepIndex + 1)
            "$integerPart.$decimalPart".toDoubleOrNull() ?: 0.0
        } else {
            cleaned.toDoubleOrNull() ?: 0.0
        }
    }

    private fun extractAmountFromLine(line: String, regex: Regex): Double? {
        // NEW: First check if line contains percentage - if so, extract differently
        if (line.contains("%")) {
            // Try before % first (for "20,13 24,00%")
            val beforePercent = line.substringBefore("%", "")
            val matchesBefore = regex.findAll(beforePercent).toList()
            if (matchesBefore.isNotEmpty()) {
                // Return the FIRST amount before the percentage sign
                return parseAmount(matchesBefore.first().groupValues[1])
            }

            // Fallback to after % (for "ΦΠΑ 24%: 4,14")
            val afterPercent = line.substringAfter("%", "")
            val matchesAfter = regex.findAll(afterPercent).toList()
            if (matchesAfter.isNotEmpty()) {
                // Return the LAST amount after the percentage sign
                return parseAmount(matchesAfter.last().groupValues[1])
            }
        }

        // NEW: Skip lines that look like time: "14:24" or "QPA: 14.24"
        if (line.matches(Regex(""".*\b\d{1,2}:\d{2}\b.*"""))) {
            // Check if the amount is actually a time
            val timeMatch = Regex("""\b(\d{1,2}):(\d{2})\b""").find(line)
            if (timeMatch != null) {
                val hour = timeMatch.groupValues[1].toIntOrNull()
                val minute = timeMatch.groupValues[2].toIntOrNull()
                if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                    // This is likely a time, skip it
                    return null
                }
            }
        }

        // NEW: Handle E-prefixed numbers (E0,13 -> extract 0,13)
        val cleanedLine = line.replace(Regex("""\bE(\d)"""), "$1")

        val matches = regex.findAll(cleanedLine)
        return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
    }

    private fun isTaxOnlyLine(line: String): Boolean {
        // Lines like "ΦΠΑ 24%: 4,14" or "VAT 20% 1.00"
        val taxKeywords = listOf("ΦΠΑ", "VAT", "TAX", "VAT_KEY")
        val hasTaxKeyword = taxKeywords.any { line.contains(it, ignoreCase = true) }
        return hasTaxKeyword && line.contains("%")
    }

    private fun extractSubtotal(text: String): Double? {
        for (pattern in subtotalPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractTax(text: String): Double? {
        // Multiple tax patterns to handle Greek ΦΠΑ OCR variations
        val taxPatterns = listOf(
            // Normalized VAT_KEY pattern
            Regex("""VAT_KEY\s*[:\s]*(\d+[.,]\d{2})"""),
            // Greek with percentage: "ΦΠΑ 24%: 4,14"
            Regex("""(?:Φ\.?Π\.?Α\.?|VAT|TAX)\s*\d*[.,]?\d*%?\s*:?\s*(\d+[.,]\d{2})"""),
            // OCR corrupted: "0.n.A 24,00%" or "O.n.A"
            Regex("""0\.?n\.?A\.?\s*\d*[.,]?\d*%?\s*(\d+[.,]\d{2})"""),
            Regex("""O\.?n\.?A\s*\d*[.,]?\d*%?\s*(\d+[.,]\d{2})"""),
            Regex("""0\.?Π\.?Α\s*\d*[.,]?\d*%?\s*(\d+[.,]\d{2})"""),
            // Line with tax percentage: "4,14 24%"
            Regex("""(\d+[.,]\d{2})\s*\d{1,3}[.,]?\d{0,2}%""")
        )

        for (pattern in taxPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].replace(",", ".").toDoubleOrNull()
            }
        }
        return null
    }

    // --- DATE EXTRACTION ---
    private fun extractDate(text: String): Long? {
        // Regex handles: dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy
        val datePatterns = listOf(
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(20\d{2})\b"""),
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})\b""")
        )

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y

                // SANITY CHECK: Year must be reasonable (Dynamic range)
                val yearInt = year.toIntOrNull() ?: 0
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                if (yearInt in (currentYear - 10)..(currentYear + 1)) { 
                    try {
                        return sdf.parse("$d/$m/$year")?.time
                    } catch (e: Exception) { }
                }
            }
        }
        return null
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val items = mutableListOf<LineItem>()

        // Skip lines that look like totals/subtotals
        val skipLinePattern = Regex(
            """(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ|CASH|CARD|VISA|MASTER|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΠΛΗΡΩΜΗ|PAYMENT|DISCOUNT|ΕΚΠΤΩΣΗ|AMOUNT|ΠΟΣΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΑΞΙΑ|VALUE)"""
        )

        // Pattern 1: "description   amount"
        val matcher1 = lineItemPatterns[0].matcher(text)
        while (matcher1.find()) {
            val desc = matcher1.group(1)?.trim() ?: continue
            val price = matcher1.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = null,
                    unitPrice = null,
                    totalPrice = price
                )
            )
        }

        // Pattern 2: "qty x description   amount"
        val matcher2 = lineItemPatterns[1].matcher(text)
        while (matcher2.find()) {
            val qty = matcher2.group(1)?.toDoubleOrNull() ?: continue
            val desc = matcher2.group(2)?.trim() ?: continue
            val price = matcher2.group(3)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = qty,
                    unitPrice = if (qty > 0) price / qty else null,
                    totalPrice = price
                )
            )
        }

        return items
    }

    private fun detectCurrency(text: String): String {
        return when {
            text.contains("€") || 
            text.contains("EUR", ignoreCase = true) ||
            text.contains("ΕΥΡΩ", ignoreCase = true) ||
            text.contains("ΕΥΡ", ignoreCase = true) -> "EUR"

            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"

            // Be more strict with GBP to avoid OYP/OYR corruption
            text.contains("£") || 
            (text.contains("GBP", ignoreCase = true) && !text.contains("OYP") && !text.contains("OYR")) -> "GBP"

            else -> "EUR" // Default for Greek receipts
        }
    }

    private fun calculateConfidence(
        merchant: String?,
        total: Double?,
        date: Long?,
        items: List<LineItem>,
        tax: Double?
    ): Float {
        var score = 0f

        // Merchant (15%)
        if (merchant != null && merchant.length >= 3) {
            score += 0.15f
            // Bonus for recognizable business patterns (uppercase names)
            if (merchant.matches(Regex(".*[A-Z]{3,}.*"))) score += 0.05f
        }

        // Total (40%) - Most important
        if (total != null && total > 0) {
            score += 0.40f
            // Bonus if total is reasonable
            if (total in 0.5..2000.0) score += 0.05f
        }

        // Date (15%)
        if (date != null) {
            score += 0.15f
            // Bonus if date is recent
            val daysDiff = (System.currentTimeMillis() - date) / (1000 * 60 * 60 * 24)
            if (daysDiff in 0..365) score += 0.05f
        }

        // Line items (15%)
        if (items.isNotEmpty()) {
            score += 0.10f
            if (items.size >= 2) score += 0.05f
        }

        // Tax (5%)
        if (tax != null && tax > 0) score += 0.05f

        // Cross-validation bonus (10%)
        if (total != null && items.isNotEmpty()) {
            val itemsSum = items.sumOf { it.totalPrice }
            val diff = kotlin.math.abs(total - itemsSum)
            if (diff < total * 0.10) { // Within 10%
                score += 0.10f
            }
        }

        return score.coerceIn(0f, 1f)
    }

    // Utility: serialize line items to JSON
    fun lineItemsToJson(items: List<LineItem>): String {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("description", item.description)
                put("totalPrice", item.totalPrice)
                item.quantity?.let { put("quantity", it) }
                item.unitPrice?.let { put("unitPrice", it) }
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    // Utility: deserialize line items from JSON
    fun lineItemsFromJson(json: String?): List<LineItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                LineItem(
                    description = obj.getString("description"),
                    totalPrice = obj.getDouble("totalPrice"),
                    quantity = if (obj.has("quantity")) obj.getDouble("quantity") else null,
                    unitPrice = if (obj.has("unitPrice")) obj.getDouble("unitPrice") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\AppConstants.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilappconstantskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

object AppConstants {

    // Confidence Thresholds (LOGIC-004 Consolidation)
    object Confidence {
        const val RULE_BASED = 0.95f
        const val ML_PREDICTION = 0.60f
        const val FUZZY_MATCH = 0.80f
        const val MANUAL_OVERRIDE = 1.0f
        const val RECEIPT_FALLBACK = 0.70f
    }

    // Time Windows (In Milliseconds)
    object Windows {
        const val DUPLICATE_DETECTION = 300_000L // 5 minutes (LOGIC-002 expansion)
        const val NOTIFICATION_LRU_MAX_AGE = 30 * 60 * 1000L // 30 minutes
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\BKTree.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilbktreekt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BK-Tree (Burkhard-Keller Tree) for efficient fuzzy string searching.
 * 
 * Allows finding all strings within a certain edit distance in O(log n)
 * instead of O(n) for linear search.
 */
class StringBKTree private constructor(
    private val distanceFunction: (String, String) -> Int
) {
    private data class Node(
        val item: String,
        val children: MutableMap<Int, Node> = mutableMapOf()
    )

    private var root: Node? = null
    private var _size = 0
    private val mutex = Mutex()

    val size: Int get() = _size
    val isEmpty: Boolean get() = root == null

    companion object {
        /**
         * Create a BK-Tree using Levenshtein distance.
         */
        fun create(): StringBKTree {
            return StringBKTree { s1, s2 -> 
                StringDistanceUtils.levenshteinDistance(s1, s2) 
            }
        }
    }

    /**
     * Insert an item into the tree.
     */
    suspend fun insert(item: String) = mutex.withLock {
        val normalized = item.lowercase().trim()

        if (root == null) {
            root = Node(normalized)
            _size = 1
            return@withLock
        }

        var current = root!!
        while (true) {
            val dist = distanceFunction(current.item, normalized)

            if (dist == 0) return@withLock // Duplicate

            val child = current.children[dist]
            if (child == null) {
                current.children[dist] = Node(normalized)
                _size++
                return@withLock
            }
            current = child
        }
    }

    /**
     * Insert multiple items.
     */
    suspend fun insertAll(items: Collection<String>) {
        items.forEach { insert(it) }
    }

    /**
     * Find all items within a maximum distance from the query.
     */
    suspend fun search(query: String, maxDistance: Int): List<Pair<String, Int>> = mutex.withLock {
        val results = mutableListOf<Pair<String, Int>>()
        val normalized = query.lowercase().trim()
        searchRecursive(root, normalized, maxDistance, results)
        results.sortedBy { it.second }
    }

    private fun searchRecursive(
        node: Node?,
        query: String,
        maxDistance: Int,
        results: MutableList<Pair<String, Int>>
    ) {
        if (node == null) return

        val dist = distanceFunction(node.item, query)

        if (dist <= maxDistance) {
            results.add(node.item to dist)
        }

        val minDist = maxOf(0, dist - maxDistance)
        val maxDist = dist + maxDistance

        for ((edgeDist, child) in node.children) {
            if (edgeDist in minDist..maxDist) {
                searchRecursive(child, query, maxDistance, results)
            }
        }
    }

    /**
     * Find the single best match within maxDistance.
     */
    suspend fun findBestMatch(query: String, maxDistance: Int): Pair<String, Int>? {
        return search(query, maxDistance).minByOrNull { it.second }
    }

    /**
     * Clear all items.
     */
    suspend fun clear() = mutex.withLock {
        root = null
        _size = 0
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\CalendarUtils.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilcalendarutilskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

import java.util.Calendar

object CalendarUtils {

    fun getStartOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun getStartOfMonth(timestamp: Long = System.currentTimeMillis()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun getDaysRemainingInMonth(timestamp: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        return daysInMonth - dayOfMonth
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\CommonPatterns.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilcommonpatternskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

import java.util.regex.Pattern

object CommonPatterns {

    // Core amount extraction regex (DUP-005 consolidation)
    // Matches patterns like 10.00, 1.234,56, € 10, 10 EUR, etc.
    val AMOUNT_REGEX: Pattern = Pattern.compile(
        """(?:([€$£]|EUR|USD|GBP)\s*)?([-+]?\s*\d+(?:[.,\s]\d{3})*(?:[.,]\d{2}))(?:\s*([€$£]|EUR|USD|GBP))?""",
        Pattern.CASE_INSENSITIVE
    )

    // Common merchant noise prefixes (DUP-006 consolidation)
    val MERCHANT_PREFIXES = listOf(
        "VRP*", "SQ *", "PAYPAL *", "IZ *", "ZETTLE *", "SUMUP *", 
        "STRIPE *", "AMZN Mktp", "APPLE.COM/BILL"
    )
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\CurrencyNormalizer.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilcurrencynormalizerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * centralized utility for normalizing currency strings.
 * Converts symbols (€, $, £) to ISO 4217 codes (EUR, USD, GBP).
 * Defaults to "EUR" if unknown.
 */
@Singleton
class CurrencyNormalizer @Inject constructor() {

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return "EUR"

        val cleaned = raw.trim().uppercase(Locale.getDefault())

        return when (cleaned) {
            "€", "EUR", "EURO" -> "EUR"
            "$", "USD", "DOLLAR" -> "USD"
            "£", "GBP", "POUND" -> "GBP"
            "CHF", "FRANC" -> "CHF"
            "¥", "JPY", "YEN" -> "JPY"
            else -> {
                // If it looks like a valid 3-letter code, keep it, otherwise default
                if (cleaned.length == 3 && cleaned.all { it.isLetter() }) {
                    cleaned
                } else {
                    "EUR"
                }
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\MerchantCleaner.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilmerchantcleanerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized utility for cleaning and normalizing merchant names.
 * Consolidates logic from various parsers to ensure consistency.
 */
@Singleton
class MerchantCleaner @Inject constructor() {

    private val timeRegex = Regex("""\s\d{1,2}:\d{2}(?::\d{2})?.*$""")
    private val dateRegex = Regex("""\s\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?.*$""")
    private val cardInfoRegex = Regex("""\s*(?:(?:Mastercard|Visa|Amex|card|•|·|-)+\s*)+\*?\.?\d+.*$""", RegexOption.IGNORE_CASE)
    private val stopWords = listOf(
        "confirmed", "successful", "completed", "declined", "pending",
        "ολοκληρώθηκε", "επιτυχής", "with card", "με κάρτα", "στις", "at", "on", "to"
    )

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown"

        var candidate = raw.trim()
            .replace('\u00A0', ' ') // Non-breaking space
            .replace(timeRegex, "")
            .replace(dateRegex, "")
            .replace(cardInfoRegex, "")

        // Remove stop words from the end
        for (stop in stopWords) {
            val idx = candidate.indexOf(" $stop", ignoreCase = true)
            if (idx != -1) candidate = candidate.substring(0, idx)

            // Check if it's the very start (e.g. "at Starbucks")
            if (candidate.startsWith("$stop ", ignoreCase = true)) {
                candidate = candidate.substring(stop.length + 1)
            }
        }

        return candidate
            .replace(Regex("""\s{2,}"""), " ") // Standardize whitespace
            .replace(Regex("""[.!;]$"""), "") // Remove trailing punctuation
            .trim()
            .take(40)
            .let { if (it.isEmpty()) "Unknown" else it }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\StatisticsUtils.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilstatisticsutilskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

import kotlin.math.sqrt

object StatisticsUtils {

    /**
     * Calculates the Sample Standard Deviation from a list of values.
     * Uses Bessel's correction (N-1).
     * Returns 0.0 if there are fewer than 2 values.
     */
    fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val sumSq = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (values.size - 1))
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\domain\util\StringDistanceUtils.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainutilstringdistanceutilskt"></a>
```kotlin
package com.yourname.expensetracker.domain.util

/**
 * Utility functions for calculating string distances and similarities.
 */
object StringDistanceUtils {

    /**
     * Calculate Levenshtein distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    minOf(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }

    /**
     * Calculate Levenshtein similarity (0.0 to 1.0).
     */
    fun levenshteinSimilarity(s1: String, s2: String): Double {
        val dist = levenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - dist.toDouble() / maxLen
    }

    /**
     * Calculate Jaro similarity between two strings.
     */
    fun jaroSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val matchWindow = maxOf(0, maxOf(s1.length, s2.length) / 2 - 1)
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        for (i in s1.indices) {
            val start = maxOf(0, i - matchWindow)
            val end = minOf(i + matchWindow + 1, s2.length)
            for (j in start until end) {
                if (!s2Matches[j] && s1[i] == s2[j]) {
                    s1Matches[i] = true
                    s2Matches[j] = true
                    matches++
                    break
                }
            }
        }

        if (matches == 0) return 0.0

        var transpositions = 0.0
        var k = 0
        for (i in s1.indices) {
            if (s1Matches[i]) {
                while (!s2Matches[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }

        return (matches.toDouble() / s1.length + 
                matches.toDouble() / s2.length + 
                (matches - transpositions / 2.0) / matches) / 3.0
    }

    /**
     * Calculate Jaro-Winkler similarity.
     */
    fun jaroWinklerSimilarity(s1: String, s2: String, prefixWeight: Double = 0.1): Double {
        val jaro = jaroSimilarity(s1, s2)
        if (jaro < 0.7) return jaro

        var prefix = 0
        for (i in 0 until minOf(4, minOf(s1.length, s2.length))) {
            if (s1[i] == s2[i]) prefix++
            else break
        }

        return jaro + prefix * prefixWeight * (1.0 - jaro)
    }

    /**
     * Combined similarity measure.
     */
    fun combinedSimilarity(s1: String, s2: String): Double {
        val jaroWinkler = jaroWinklerSimilarity(s1, s2)
        val levenshtein = levenshteinSimilarity(s1, s2)

        return 0.7 * jaroWinkler + 0.3 * levenshtein
    }
}

```

---

