package com.yourname.expensetracker.domain.investment

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InvestmentDao
import com.yourname.expensetracker.data.database.dao.InvestmentValueDao
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.data.database.entity.InvestmentValue
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PortfolioSummary(
    val totalValue: Double,
    val totalInvested: Double,
    val totalGainLoss: Double,
    val totalGainLossPercent: Double,
    val investmentCount: Int,
    val byType: Map<InvestmentType, Double>
)

data class InvestmentPerformance(
    val investment: Investment,
    val currentValue: Double,
    val gainLoss: Double,
    val gainLossPercent: Double,
    val dayChange: Double?,
    val dayChangePercent: Double?,
    val allTimeHigh: Double?,
    val allTimeLow: Double?
)

@Singleton
class InvestmentTracker @Inject constructor(
    private val database: AppDatabase,
    private val investmentDao: InvestmentDao,
    private val investmentValueDao: InvestmentValueDao,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    @Deprecated(
        "Raw Double portfolio summary may mix currencies. Use getPortfolioSummaryAggregate() for multi-currency safety.",
        ReplaceWith("getPortfolioSummaryAggregate(investmentDao.getAllActiveInvestments().first())")
    )
    /**
     * Get complete portfolio summary.
     */
    suspend fun getPortfolioSummary(): PortfolioSummary = withContext(ioDispatcher) {
        val investments = investmentDao.getAllActiveInvestments().first()
        
        var totalValue = 0.0
        var totalInvested = 0.0
        
        for (investment in investments) {
            totalValue += investment.currentPrice * investment.quantity
            totalInvested += (investment.purchasePrice * investment.quantity) + investment.purchaseFees
        }
        
        val gainLoss = totalValue - totalInvested
        val gainLossPercent = if (totalInvested > 0) (gainLoss / totalInvested) * 100 else 0.0
        
        // Group by type
        val byType = mutableMapOf<InvestmentType, Double>()
        for (investment in investments) {
            val currentValue = investment.currentPrice * investment.quantity
            val current = byType[investment.type] ?: 0.0
            byType[investment.type] = current + currentValue
        }
        
        PortfolioSummary(
            totalValue = totalValue,
            totalInvested = totalInvested,
            totalGainLoss = gainLoss,
            totalGainLossPercent = gainLossPercent,
            investmentCount = investments.size,
            byType = byType
        )
    }
    
    /**
     * Add a new investment holding with validation.
     *
     * Validates that quantity and purchase price are positive, currency is provided,
     * then persists the investment and records its initial value history snapshot.
     *
     * @param investment The investment to add (must have quantity > 0, purchasePrice > 0,
     *                   and non-blank currency).
     * @return [Result.success] with the generated ID, or [Result.failure] if validation fails.
     * @throws IllegalArgumentException if any validation constraint is violated.
     */
    suspend fun addHolding(investment: Investment): Result<Long> {
        require(investment.quantity > 0) { "Quantity must be positive" }
        require(investment.purchasePrice > 0) { "Purchase price must be positive" }
        require(investment.currency.isNotBlank()) { "Currency is required" }
        val now = timeProvider.now()
        val validated = investment.copy(
            createdAt = if (investment.createdAt > 0) investment.createdAt else now,
            lastUpdated = now
        )

        // Wrap insert + value insert in transaction for atomicity
        val id = database.withTransaction {
            val insertedId = investmentDao.insert(validated)

            // Record initial value history snapshot
            investmentValueDao.insert(
                InvestmentValue(
                    investmentId = insertedId,
                    price = investment.purchasePrice,
                    totalValue = investment.purchasePrice * investment.quantity,
                    timestamp = now
                )
            )
            insertedId
        }
        return Result.success(id)
    }

    /**
     * Get portfolio summary together with a MoneyAggregate grouped by currency.
     *
     * Computes the same [PortfolioSummary] as [getPortfolioSummary] but also
     * returns a [MoneyAggregate] with per-currency buckets, so callers can
     * display multi-currency totals safely without raw-summing across currencies.
     *
     * I01: Now converts all currencies to home currency using CurrencyConverter.
     */
    suspend fun getPortfolioSummaryAggregate(holdings: List<Investment>): Pair<PortfolioSummary, MoneyAggregate> {
        var totalValue = 0.0
        var totalInvested = 0.0
        
        for (investment in holdings) {
            totalValue += investment.currentPrice * investment.quantity
            totalInvested += (investment.purchasePrice * investment.quantity) + investment.purchaseFees
        }
        
        val gainLoss = totalValue - totalInvested
        val gainLossPercent = if (totalInvested > 0) (gainLoss / totalInvested) * 100 else 0.0
        
        // Group by type
        val byType = mutableMapOf<InvestmentType, Double>()
        for (investment in holdings) {
            val currentValue = investment.currentPrice * investment.quantity
            val current = byType[investment.type] ?: 0.0
            byType[investment.type] = current + currentValue
        }
        
        val summary = PortfolioSummary(
            totalValue = totalValue,
            totalInvested = totalInvested,
            totalGainLoss = gainLoss,
            totalGainLossPercent = gainLossPercent,
            investmentCount = holdings.size,
            byType = byType
        )
        
        // Group by currency for MoneyAggregate using the builder
        val byCurrency = holdings.groupBy { it.currency.uppercase() }
            .mapValues { (_, list) -> list.sumOf { it.currentPrice * it.quantity } }

        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val buckets = byCurrency.map { Pair(it.value, it.key) }
        val aggregate = MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter)
        return Pair(summary, aggregate)
    }
    
    /**
     * Get detailed performance for a single investment.
     */
    suspend fun getInvestmentPerformance(investmentId: Long): InvestmentPerformance? = 
        withContext(ioDispatcher) {
            val investment = investmentDao.getById(investmentId) ?: return@withContext null
            
            val currentValue = investment.currentPrice * investment.quantity
            val investedValue = (investment.purchasePrice * investment.quantity) + investment.purchaseFees
            val gainLoss = currentValue - investedValue
            val gainLossPercent = if (investedValue > 0) (gainLoss / investedValue) * 100 else 0.0
            
            val now = timeProvider.now()
            val previousDayClose = getPreviousDayCloseSnapshot(investmentId, now)
            val dayChange = previousDayClose?.let { investment.currentPrice - it.price }
            val dayChangePercent = previousDayClose?.let {
                if (it.price > 0.0) ((investment.currentPrice - it.price) / it.price) * 100 else 0.0
            }
            
            // True all-time high/low: query from epoch 0 (all recorded history)
            val allTimeHigh = investmentValueDao.getMaxPrice(investmentId, 0L)
            val allTimeLow = investmentValueDao.getMinPrice(investmentId, 0L)
            
            InvestmentPerformance(
                investment = investment,
                currentValue = currentValue,
                gainLoss = gainLoss,
                gainLossPercent = gainLossPercent,
                dayChange = dayChange,
                dayChangePercent = dayChangePercent,
                allTimeHigh = allTimeHigh,
                allTimeLow = allTimeLow
            )
        }
    
    /**
     * Update price for an investment and record value history.
     *
     * ATOMICITY-VERIFIED: Wrapped in [database.withTransaction] at the call site
     * (see line 231) so the price update + value history insert are atomic.
     */
    suspend fun updatePrice(investmentId: Long, newPrice: Double) = withContext(ioDispatcher) {
        val investment = investmentDao.getById(investmentId) ?: return@withContext
        val timestamp = timeProvider.now()
        
        val previousDayClose = getPreviousDayCloseSnapshot(investmentId, timestamp)
        val dayChange = previousDayClose?.let { newPrice - it.price }
        val dayChangePercent = previousDayClose?.let {
            if (it.price > 0.0) ((newPrice - it.price) / it.price) * 100 else 0.0
        }
        
        // Wrap update + insert in transaction for atomicity
        database.withTransaction {
            // Update investment
            investmentDao.updatePrice(investmentId, newPrice, timestamp)
            
            // Record value history
            val value = InvestmentValue(
                investmentId = investmentId,
                price = newPrice,
                totalValue = newPrice * investment.quantity,
                timestamp = timestamp,
                dayChange = dayChange,
                dayChangePercent = dayChangePercent
            )
            investmentValueDao.insert(value)
        }
    }
    
    /**
     * Get investments that have reached target price (for alerts).
     */
    suspend fun getTargetPriceHits(): List<Investment> = withContext(ioDispatcher) {
        val investments = investmentDao.getAllInvestments()
        investments.filter { investment ->
            investment.targetPrice != null && 
            investment.currentPrice >= investment.targetPrice
        }
    }
    
    /**
     * Get investments that hit stop loss.
     */
    suspend fun getStopLossHits(): List<Investment> = withContext(ioDispatcher) {
        val investments = investmentDao.getAllInvestments()
        investments.filter { investment ->
            investment.stopLossPrice != null && 
            investment.currentPrice <= investment.stopLossPrice
        }
    }
    
    /**
     * Calculate portfolio allocation percentages.
     */
    suspend fun getPortfolioAllocation(): Map<InvestmentType, Double> = withContext(ioDispatcher) {
        val summary = getPortfolioSummary()
        val totalValue = summary.totalValue
        
        if (totalValue == 0.0) return@withContext emptyMap()
        
        summary.byType.mapValues { (_, value) -> (value / totalValue) * 100 }
    }
    
    /**
     * Get best and worst performing investments.
     */
    suspend fun getTopPerformers(count: Int = 5): Pair<List<InvestmentPerformance>, List<InvestmentPerformance>> = 
        withContext(ioDispatcher) {
            val investments = investmentDao.getAllActiveInvestments().first()
            val performances = investments.mapNotNull { getInvestmentPerformance(it.id) }
            
            val sortedByPerformance = performances.sortedByDescending { it.gainLossPercent }
            
            val topPerformers = sortedByPerformance.take(count)
            val worstPerformers = sortedByPerformance.takeLast(count).reversed()
            
            Pair(topPerformers, worstPerformers)
        }
    
    /**
     * Get portfolio value history over time.
     *
     * PR-E20: Carry-forward algorithm — for each holding and each day in the
     * range, the latest known totalValue on or before that day is used.
     * This ensures holdings with no price update on a given day still contribute
     * to the daily portfolio total, preventing undercounting.
     *
     * Algorithm:
     * 1. Collect ALL InvestmentValue records per holding in [startDate, endDate).
     * 2. Sort records by timestamp ascending.
     * 3. For each day D in the range:
     *    a. Find the most recent InvestmentValue with timestamp <= end of day D.
     *       This is the "carry-forward" value for that day.
     *    b. If no record exists, use the holding's purchasePrice * quantity as
     *       the initial value (fallback).
     *    c. Sum all holdings' carry-forward totalValue for the day.
     * 4. Return sorted daily portfolio values.
     */
    suspend fun getPortfolioValueHistory(days: Int = 30): List<DailyPortfolioValue> = 
        withContext(ioDispatcher) {
            val endDate = timeProvider.now()
            val startDate = endDate - (days * 24 * 60 * 60 * 1000L)
            
            val investments = investmentDao.getAllInvestments()
            val result = mutableListOf<DailyPortfolioValue>()

            val investmentIds = investments.map { it.id }
            val valuesByInvestment = if (investmentIds.isEmpty()) {
                emptyMap<Long, List<InvestmentValue>>()
            } else {
                investmentValueDao.getPortfolioHistoryBatch(investmentIds, startDate, endDate)
                    .groupBy { it.investmentId }
            }

            // Build a sorted list of day keys covering the full range
            val dayKeys = mutableListOf<String>()
            var cursor = getStartOfDay(startDate)
            while (cursor < endDate) {
                dayKeys.add(getDayKey(cursor))
                cursor += 24 * 60 * 60 * 1000L
            }
            val dayMap = dayKeys.associateWith { 0.0 }.toMutableMap()

            for (investment in investments) {
                val values = valuesByInvestment[investment.id].orEmpty()
                    .sortedBy { it.timestamp }

                // Carry-forward state: the latest value seen so far
                var latestTotalValue = investment.purchasePrice * investment.quantity
                var valueIdx = 0

                for (dayKey in dayKeys) {
                    // Advance to values on or before this day
                    while (valueIdx < values.size) {
                        val v = values[valueIdx]
                        val valueDayKey = getDayKey(v.timestamp)
                        // If value's day <= current day, update latest
                        if (valueDayKey <= dayKey) {
                            latestTotalValue = v.totalValue
                            valueIdx++
                        } else {
                            break
                        }
                    }
                    dayMap[dayKey] = (dayMap[dayKey] ?: 0.0) + latestTotalValue
                }
            }
            
            for ((dayKey, totalValue) in dayMap.toSortedMap()) {
                result.add(DailyPortfolioValue(dayKey, totalValue))
            }
            
            result
        }

    private suspend fun getPreviousDayCloseSnapshot(investmentId: Long, referenceTime: Long): InvestmentValue? {
        val currentDayStart = getStartOfDay(referenceTime)
        return investmentValueDao.getLatestValueBefore(investmentId, currentDayStart)
    }

    private fun getStartOfDay(timestamp: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    private fun getDayKey(timestamp: Long): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }
    
    fun getAllInvestments(): Flow<List<Investment>> = investmentDao.getAllActiveInvestments()
    
    fun getInvestmentsByType(type: InvestmentType): Flow<List<Investment>> = 
        investmentDao.getByType(type)
}

data class DailyPortfolioValue(
    val date: String,
    val totalValue: Double
)
