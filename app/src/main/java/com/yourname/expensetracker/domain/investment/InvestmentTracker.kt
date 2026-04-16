package com.yourname.expensetracker.domain.investment

import com.yourname.expensetracker.data.database.dao.InvestmentDao
import com.yourname.expensetracker.data.database.dao.InvestmentValueDao
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.data.database.entity.InvestmentValue
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
    private val timeProvider: TimeProvider
) {
    
    /**
     * Get complete portfolio summary.
     */
    suspend fun getPortfolioSummary(): PortfolioSummary = withContext(Dispatchers.IO) {
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
     * Get detailed performance for a single investment.
     */
    suspend fun getInvestmentPerformance(investmentId: Long): InvestmentPerformance? = 
        withContext(Dispatchers.IO) {
            val investment = investmentDao.getById(investmentId) ?: return@withContext null
            
            val currentValue = investment.currentPrice * investment.quantity
            val investedValue = (investment.purchasePrice * investment.quantity) + investment.purchaseFees
            val gainLoss = currentValue - investedValue
            val gainLossPercent = if (investedValue > 0) (gainLoss / investedValue) * 100 else 0.0
            
            // Get historical values for day change
            val now = timeProvider.now()
            val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000)
            val recentValues = investmentValueDao.getValuesBetween(
                investmentId, 
                thirtyDaysAgo, 
                now
            )
            
            // ASC-ordered list: last element is the most-recent sample in the window.
            val latestRecentValue = recentValues.lastOrNull()
            val dayChange = latestRecentValue?.dayChange
            val dayChangePercent = latestRecentValue?.dayChangePercent
            
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
    suspend fun updatePrice(investmentId: Long, newPrice: Double) = withContext(Dispatchers.IO) {
        val investment = investmentDao.getById(investmentId) ?: return@withContext
        val timestamp = timeProvider.now()
        
        // Calculate day change
        val latestValue = investmentValueDao.getLatestValue(investmentId)
        val dayChange = latestValue?.let { newPrice - it.price }
        val dayChangePercent = latestValue?.let { 
            if (it.price > 0) ((newPrice - it.price) / it.price) * 100 else 0.0 
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
    }
    
    /**
     * Get investments that have reached target price (for alerts).
     */
    suspend fun getTargetPriceHits(): List<Investment> = withContext(Dispatchers.IO) {
        val investments = investmentDao.getAllInvestments()
        investments.filter { investment ->
            investment.targetPrice != null && 
            investment.currentPrice >= investment.targetPrice
        }
    }
    
    /**
     * Get investments that hit stop loss.
     */
    suspend fun getStopLossHits(): List<Investment> = withContext(Dispatchers.IO) {
        val investments = investmentDao.getAllInvestments()
        investments.filter { investment ->
            investment.stopLossPrice != null && 
            investment.currentPrice <= investment.stopLossPrice
        }
    }
    
    /**
     * Calculate portfolio allocation percentages.
     */
    suspend fun getPortfolioAllocation(): Map<InvestmentType, Double> = withContext(Dispatchers.IO) {
        val summary = getPortfolioSummary()
        val totalValue = summary.totalValue
        
        if (totalValue == 0.0) return@withContext emptyMap()
        
        summary.byType.mapValues { (_, value) -> (value / totalValue) * 100 }
    }
    
    /**
     * Get best and worst performing investments.
     */
    suspend fun getTopPerformers(count: Int = 5): Pair<List<InvestmentPerformance>, List<InvestmentPerformance>> = 
        withContext(Dispatchers.IO) {
            val investments = investmentDao.getAllInvestments()
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
        withContext(Dispatchers.IO) {
            val endDate = timeProvider.now()
            val startDate = endDate - (days * 24 * 60 * 60 * 1000L)
            
            val investments = investmentDao.getAllInvestments()
            val result = mutableListOf<DailyPortfolioValue>()
            
            // Group by day
            val dayMap = mutableMapOf<String, Double>()
            
            for (investment in investments) {
                val values = investmentValueDao.getValuesBetween(investment.id, startDate, endDate)
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
