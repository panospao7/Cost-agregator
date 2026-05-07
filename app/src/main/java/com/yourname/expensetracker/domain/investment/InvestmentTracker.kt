package com.yourname.expensetracker.domain.investment

import com.yourname.expensetracker.data.database.dao.InvestmentDao
import com.yourname.expensetracker.data.database.dao.InvestmentValueDao
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.data.database.entity.InvestmentValue
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
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
        
        // Group by currency for MoneyAggregate
        val byCurrency = holdings.groupBy { it.currency.uppercase() }
            .mapValues { (_, list) -> list.sumOf { (it.currentPrice) * (it.quantity) } }
        
        val sourceBuckets = byCurrency.map { (ccy, value) ->
            MoneyBucket(CurrencyCode(ccy), value, 0)
        }

        if (sourceBuckets.size == 1) {
            return Pair(summary, MoneyAggregate.singleCurrency(sourceBuckets[0].amount, sourceBuckets[0].currency, holdings.size))
        }

        val homeCurrencyCode = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val homeCurrency = CurrencyCode(homeCurrencyCode)

        val pairs = sourceBuckets.map { bucket ->
            Pair(bucket.amount, bucket.currency.code)
        }
        val conversionResult = currencyConverter.convertMultiple(pairs, homeCurrencyCode)

        val failures = conversionResult.failedConversions.map { failed ->
            ConversionFailure(
                originalAmount = MoneyAmount(failed.originalAmount, CurrencyCode(failed.originalCurrency)),
                targetCurrency = homeCurrency,
                reason = FailureReason.MISSING_RATE
            )
        }

        val aggregate = if (failures.isEmpty()) {
            MoneyAggregate.singleCurrency(
                amount = conversionResult.total,
                currency = homeCurrency,
                transactionCount = holdings.size
            )
        } else {
            MoneyAggregate.partial(
                displayAmount = conversionResult.total,
                displayCurrency = homeCurrency,
                sourceBuckets = sourceBuckets,
                failures = failures
            )
        }
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
     */
    suspend fun updatePrice(investmentId: Long, newPrice: Double) = withContext(ioDispatcher) {
        val investment = investmentDao.getById(investmentId) ?: return@withContext
        val timestamp = timeProvider.now()
        
        val previousDayClose = getPreviousDayCloseSnapshot(investmentId, timestamp)
        val dayChange = previousDayClose?.let { newPrice - it.price }
        val dayChangePercent = previousDayClose?.let {
            if (it.price > 0.0) ((newPrice - it.price) / it.price) * 100 else 0.0
        }
        
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

        // TODO (I02): Wrap updatePrice + insert in database.withTransaction for atomicity.
        // Requires injecting AppDatabase.
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
     */
    suspend fun getPortfolioValueHistory(days: Int = 30): List<DailyPortfolioValue> = 
        withContext(ioDispatcher) {
            val endDate = timeProvider.now()
            val startDate = endDate - (days * 24 * 60 * 60 * 1000L)
            
            val investments = investmentDao.getAllInvestments()
            val result = mutableListOf<DailyPortfolioValue>()
            
            // Group by day
            val dayMap = mutableMapOf<String, Double>()

            val investmentIds = investments.map { it.id }
            val valuesByInvestment = if (investmentIds.isEmpty()) {
                emptyMap<Long, List<InvestmentValue>>()
            } else {
                investmentValueDao.getPortfolioHistoryBatch(investmentIds, startDate, endDate)
                    .groupBy { it.investmentId }
            }

            for (investment in investments) {
                val values = valuesByInvestment[investment.id].orEmpty()
                val latestValueByDay = mutableMapOf<String, InvestmentValue>()

                for (value in values) {
                    val dayKey = getDayKey(value.timestamp)
                    val existingValue = latestValueByDay[dayKey]
                    if (existingValue == null || value.timestamp >= existingValue.timestamp) {
                        latestValueByDay[dayKey] = value
                    }
                }

                for ((dayKey, latestValue) in latestValueByDay) {
                    val current = dayMap[dayKey] ?: 0.0
                    dayMap[dayKey] = current + latestValue.totalValue
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
