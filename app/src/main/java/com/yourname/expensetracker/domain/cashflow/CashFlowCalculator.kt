package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.entity.toRecurringPattern
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class DailyCashFlow(
    val date: Date,
    val startingBalance: Double,
    val income: List<Expense>,
    val expenses: List<Expense>,
    val predictedRecurring: List<RecurringPattern>,
    val endingBalance: Double,
    val riskLevel: CashFlowRiskLevel,
    /** @suppress Currency code this cash flow is denominated in (e.g. "EUR", "USD"). */
    val currency: String = ""
)

enum class CashFlowRiskLevel {
    NONE,      // Healthy surplus
    LOW,       // Slight surplus
    MEDIUM,    // Near break-even
    HIGH       // Risk of going negative
}

@Singleton
class CashFlowCalculator @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringPatternsProvider: MergedRecurringPatternsProvider,
    private val timeProvider: TimeProvider,
    private val recurringLifecycleCoordinator: RecurringLifecycleCoordinator,
    private val recurringOccurrenceDao: RecurringOccurrenceDao,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val currencyConverter: CurrencyConverter
) {
    companion object {
        private const val TAG = "CashFlowCalculator"
    }

    /**
     * Calculates daily cash flow for a given date range.
     *
     * ## FCST-3: Occurrence-driven prediction
     * This method uses [RecurringLifecycleCoordinator.generateOccurrences] (called inside
     * [getUpcomingBills]) to materialise PLANNED occurrences from manual recurring rules,
     * then queries [RecurringOccurrenceDao] for the canonical list of upcoming obligations.
     * Detected-only patterns (without a manual rule) are handled via ad-hoc date matching
     * on [RecurringPattern.nextExpectedDate]. This two-path approach ensures that all
     * recurring obligations are captured without double-counting.
     */
    suspend fun calculateDailyCashFlow(
        startDate: Date,
        endDate: Date,
        startingBalance: Double = 0.0
    ): List<DailyCashFlow> {
        val homeCurrency = try {
            currencySettingsRepository.homeCurrency().first()
        } catch (e: Exception) {
            "EUR"
        }
        val calendar = Calendar.getInstance()
        val results = mutableListOf<DailyCashFlow>()
        var runningBalance = startingBalance

        val startTime = startDate.time
        val endTime = endDate.time

        // ── Pre-compute occurrence-driven predictions (FCST-3) ──────────────
        // Part 1: Manual rules — materialise occurrences via the lifecycle
        // coordinator, then query PLANNED occurrences for the full range.
        val recurringPatterns = recurringPatternsProvider.getConfirmedPatterns()
        val ruleIds = recurringPatterns
            .filter { it.id != null }
            .mapNotNull { it.id }
            .distinct()

        if (ruleIds.isNotEmpty()) {
            for (ruleId in ruleIds) {
                try {
                    recurringLifecycleCoordinator.generateOccurrences(ruleId, startTime, endTime)
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: generateOccurrences failed for ruleId=%d", ruleId)
                }
            }
        }

        val plannedOccurrences = if (ruleIds.isNotEmpty()) {
            recurringOccurrenceDao.getByDateRange(startTime, endTime)
                .filter {
                    it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE &&
                        it.sourceId in ruleIds &&
                        it.status == "PLANNED"
                }
        } else {
            emptyList()
        }

        // Build a day-indexed map from occurrences (yyyy-MM-dd → patterns)
        val occurrencePatternsByDay = mutableMapOf<String, MutableList<RecurringPattern>>()
        for (occ in plannedOccurrences) {
            val occCal = Calendar.getInstance().apply { timeInMillis = occ.dueDate }
            val dayKey = String.format(
                Locale.US, "%04d-%02d-%02d",
                occCal.get(Calendar.YEAR),
                occCal.get(Calendar.MONTH) + 1,
                occCal.get(Calendar.DAY_OF_MONTH)
            )
            occurrencePatternsByDay.getOrPut(dayKey) { mutableListOf() }
                .add(occ.toRecurringPattern())
        }

        // Part 2: Detected-only patterns (no manual rule) —
        // ad-hoc date matching on nextExpectedDate.
        val detectedPatterns = recurringPatterns.filter { it.id == null }

        // Get historical data for the period
        val historicalExpenses = expenseRepository.getExpensesBetween(startTime, endTime)

        // Group historical expenses by day key (yyyy-MM-dd) to avoid cross-year collisions
        val expensesByDay = mutableMapOf<String, MutableList<Expense>>()
        for (expense in historicalExpenses) {
            calendar.timeInMillis = expense.date
            val dayKey = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            val list = expensesByDay.getOrPut(dayKey) { mutableListOf() }
            list.add(expense)
        }

        // Process each day
        calendar.time = startDate
        while (calendar.time.before(endDate)) {
            val currentDay = calendar.time
            val dayKey = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )

            // Get day's expenses
            val dayExpenses = expensesByDay[dayKey] ?: mutableListOf()

            // Split into inflow and outflow using explicit transaction-type classification.
            // Inflow  = DEPOSIT, or TRANSFER with transferDirection == INCOMING.
            // Outflow = PURCHASE, WITHDRAWAL, or TRANSFER with transferDirection == OUTGOING.
            // TRANSFER rows without a transferDirection and UNKNOWN are excluded
            // from both sides so they don't distort the cash-flow balance.
            val incomeList = mutableListOf<Expense>()
            val expenseList = mutableListOf<Expense>()
            for (expense in dayExpenses) {
                when (expense.transactionType.toDomain()) {
                    DomainTransactionType.DEPOSIT -> incomeList.add(expense)
                    DomainTransactionType.PURCHASE,
                    DomainTransactionType.WITHDRAWAL -> expenseList.add(expense)
                    DomainTransactionType.TRANSFER -> {
                        when (expense.transferDirection) {
                            TransferDirection.INCOMING -> incomeList.add(expense)
                            TransferDirection.OUTGOING -> expenseList.add(expense)
                            null -> { /* unclassified transfer – no cash-flow impact */ }
                        }
                    }
                    else -> { /* UNKNOWN – no cash-flow impact */ }
                }
            }

            // Calculate predicted recurring for this day — two-path approach (FCST-3)
            val predictedRecurringList = mutableListOf<RecurringPattern>()

            // Path 1: Occurrence-driven predictions from manual rules
            occurrencePatternsByDay[dayKey]?.let { predictedRecurringList.addAll(it) }

            // Path 2: Detected-only patterns — ad-hoc date matching on nextExpectedDate
            val currentDayStart = TimePeriodUtils.getStartOfDay(currentDay.time)
            val currentDayEnd = TimePeriodUtils.getEndOfDay(currentDay.time)
            for (pattern in detectedPatterns) {
                val expectedDayStart = TimePeriodUtils.getStartOfDay(pattern.nextExpectedDate)
                if (expectedDayStart >= currentDayStart && expectedDayStart < currentDayEnd) {
                    predictedRecurringList.add(pattern)
                }
            }
            
            // FCST-12: Deduplicate by merchant/date when both actual and predicted
            // expenses exist on the same day. If an actual expense has the same merchant
            // as a predicted recurring pattern, the predicted amount is omitted to
            // prevent double-counting.
            val actualMerchants = (incomeList + expenseList).mapTo(mutableSetOf()) {
                it.merchant.lowercase().trim()
            }
            val deduplicatedPredicted = predictedRecurringList.filterNot { pattern ->
                pattern.merchantName.lowercase().trim() in actualMerchants
            }

            // Calculate ending balance — normalize to home currency
            var dayIncome = 0.0
            for (inc in incomeList) {
                if (inc.currency.equals(homeCurrency, ignoreCase = true)) {
                    dayIncome += inc.effectiveAmount
                } else {
                    val converted = currencyConverter.convert(inc.effectiveAmount, inc.currency, homeCurrency)
                    if (converted != null) {
                        dayIncome += converted.convertedAmount
                    }
                }
            }
            
            var dayExpensesTotal = 0.0
            for (exp in expenseList) {
                if (exp.currency.equals(homeCurrency, ignoreCase = true)) {
                    dayExpensesTotal += exp.effectiveAmount
                } else {
                    val converted = currencyConverter.convert(exp.effectiveAmount, exp.currency, homeCurrency)
                    if (converted != null) {
                        dayExpensesTotal += converted.convertedAmount
                    }
                }
            }
            for (recurring in deduplicatedPredicted) {
                if (recurring.currency.equals(homeCurrency, ignoreCase = true)) {
                    dayExpensesTotal += recurring.averageAmount
                } else {
                    val converted = currencyConverter.convert(recurring.averageAmount, recurring.currency, homeCurrency)
                    if (converted != null) {
                        dayExpensesTotal += converted.convertedAmount
                    }
                }
            }
            
            runningBalance = runningBalance + dayIncome - dayExpensesTotal
            
            // Determine risk level
            val riskLevel = when {
                runningBalance > 500 -> CashFlowRiskLevel.NONE
                runningBalance > 100 -> CashFlowRiskLevel.LOW
                runningBalance > 0 -> CashFlowRiskLevel.MEDIUM
                else -> CashFlowRiskLevel.HIGH
            }
            
            results.add(
                DailyCashFlow(
                    date = currentDay,
                    startingBalance = runningBalance - dayIncome + dayExpensesTotal,
                    income = incomeList,
                    expenses = expenseList,
                    predictedRecurring = deduplicatedPredicted,
                    endingBalance = runningBalance,
                    riskLevel = riskLevel,
                    currency = homeCurrency
                )
            )
            
            // Move to next day
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        return results
    }
    
    suspend fun getUpcomingBills(daysAhead: Int): List<RecurringPattern> {
        val now = timeProvider.now()
        val startOfToday = TimePeriodUtils.getStartOfDay(now)
        // Exclusive end — covers all days up to and including `daysAhead` from today
        val endDate = TimePeriodUtils.addDays(startOfToday, daysAhead + 1)

        val patterns = recurringPatternsProvider.getConfirmedPatterns()
        val ruleIds = patterns
            .filter { it.id != null }
            .mapNotNull { it.id }
            .distinct()

        // ── Part 1: Manual rules — canonical occurrence path ────────────────
        val manualUpcoming = if (ruleIds.isNotEmpty()) {
            // Generate (materialise) occurrences for each rule
            for (ruleId in ruleIds) {
                try {
                    recurringLifecycleCoordinator.generateOccurrences(ruleId, startOfToday, endDate)
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: generateOccurrences failed for ruleId=%d, skipping rule", ruleId)
                }
            }
            // Query PLANNED occurrences = upcoming obligations
            recurringOccurrenceDao.getByDateRange(startOfToday, endDate)
                .filter {
                    it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE &&
                        it.sourceId in ruleIds &&
                        it.status == "PLANNED"
                }
                .map { it.toRecurringPattern() }
        } else {
            emptyList()
        }

        // ── Part 2: Detected-only patterns — simplified ad-hoc fallback ──────
        val detectedUpcoming = patterns
            .filter { it.id == null }
            .filter { it.nextExpectedDate >= startOfToday && it.nextExpectedDate < endDate }

        return manualUpcoming + detectedUpcoming
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}
