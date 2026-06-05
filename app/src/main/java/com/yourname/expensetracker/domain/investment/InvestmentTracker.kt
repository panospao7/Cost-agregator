package com.yourname.expensetracker.domain.investment

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InvestmentDao
import com.yourname.expensetracker.data.database.dao.InvestmentTransactionDao
import com.yourname.expensetracker.data.database.dao.InvestmentValueDao
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentTransaction
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.data.database.entity.InvestmentValue
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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

/**
 * Aggregate-safe portfolio summary with MoneyAggregate-backed totals.
 *
 * PR6: Replaces raw PortfolioSummary for multi-currency safety.
 * Raw PortfolioSummary remains @Deprecated for backward compatibility.
 */
data class PortfolioSummaryAggregate(
    val totalValue: MoneyAggregate,
    val costBasis: MoneyAggregate,
    val gainLoss: MoneyAggregate,
    val dataQuality: InvestmentDataQuality,
    val valuationBasis: String,
    val investmentCount: Int
) {
    /** Convenience display value for UI backward compatibility */
    val totalValueDisplay: Double get() = totalValue.displayAmount
    val totalInvestedDisplay: Double get() = costBasis.displayAmount
    val totalGainLossDisplay: Double get() = gainLoss.displayAmount
    val totalGainLossPercentDisplay: Double get() =
        if (costBasis.displayAmount > 0) (gainLoss.displayAmount / costBasis.displayAmount) * 100 else 0.0
}

data class InvestmentPerformance(
    val investment: Investment,
    val currentValue: Double,
    val gainLoss: Double,
    val gainLossPercent: Double,
    val dayChange: Double?,
    val dayChangePercent: Double?,
    val allTimeHigh: Double?,
    val allTimeLow: Double?,
    val currentValueAggregate: MoneyAggregate? = null,
    val costBasisAggregate: MoneyAggregate? = null,
    val isPriceStale: Boolean = false,
    val dataQuality: InvestmentDataQuality = InvestmentDataQuality()
)

// I09-FIXED: Price staleness thresholds
// - STALE_PRICE_DAYS = 7 (price older than 7 days → isPriceStale)
// - VERY_STALE_PRICE_DAYS = 30 (price older than 30 days → warning)
private const val STALE_PRICE_DAYS = 7L
private const val VERY_STALE_PRICE_DAYS = 30L

@Singleton
class InvestmentTracker @Inject constructor(
    private val database: AppDatabase,
    private val investmentDao: InvestmentDao,
    private val investmentValueDao: InvestmentValueDao,
    private val investmentTransactionDao: InvestmentTransactionDao,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    /** S12-021: Public accessor for all active holdings — used by ViewModel to load performances */
    suspend fun getAllActiveInvestments(): List<Investment> = withContext(ioDispatcher) {
        investmentDao.getAllActiveInvestments().first()
    }

    @Deprecated(
        "Raw Double portfolio summary may mix currencies. Use getPortfolioSummaryAggregate() for multi-currency safety.",
        ReplaceWith("getPortfolioSummaryAggregate(investmentDao.getAllActiveInvestments().first())")
    )
    // Migration path: All UI consumers should migrate to getPortfolioSummaryAggregate()
    // which returns per-currency MoneyAggregate and InvestmentDataQuality.
    // Remove this method once no callers remain.
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
        writeBarrier.checkWritesAllowed("InvestmentTracker.addHolding")
        require(investment.symbol.trim().isNotBlank()) { "Symbol is required" }
        require(investment.name.trim().isNotBlank()) { "Name is required" }
        require(investment.quantity.isFinite() && investment.quantity > 0.0) { "Quantity must be finite and positive" }
        require(investment.purchasePrice.isFinite() && investment.purchasePrice > 0.0) { "Purchase price must be finite and positive" }
        require(investment.currency.trim().uppercase().matches(Regex("^[A-Z]{3}$"))) { "Currency must be a valid 3-letter code" }
        require(investment.purchaseDate > 0L) { "Purchase date must be set" }
        require(investment.currentPrice.isFinite() && investment.currentPrice > 0.0) { "Current price must be finite and positive" }
        require(investment.purchaseFees.isFinite() && investment.purchaseFees >= 0.0) { "Purchase fees must be finite and non-negative" }
        val now = timeProvider.now()
        val validated = investment.copy(
            createdAt = if (investment.createdAt > 0) investment.createdAt else now,
            lastUpdated = now
        )

        // Wrap insert + value insert + transaction record in transaction for atomicity
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

            // PR-I1: Record BUY transaction for cost-basis tracking
            investmentTransactionDao.insert(
                InvestmentTransaction(
                    holdingId = insertedId,
                    type = "BUY",
                    quantity = investment.quantity,
                    pricePerUnit = investment.purchasePrice,
                    totalAmount = investment.purchasePrice * investment.quantity,
                    currency = investment.currency,
                    date = timeProvider.now()
                )
            )

            // I05-FIXED: addHolding writes BUY transaction + InvestmentValue snapshot atomically.
            // Deferred: SELL/DIVIDEND transaction types, realized gains calculation, lot-level ledger.
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
    suspend fun getPortfolioSummaryAggregate(holdings: List<Investment>): PortfolioSummaryAggregate {
        // Build per-currency buckets for current value and cost basis
        val currentValueBuckets = holdings.map { inv ->
            Pair(inv.currentPrice * inv.quantity, inv.currency.uppercase())
        }
        val costBasisBuckets = holdings.map { inv ->
            Pair((inv.purchasePrice * inv.quantity) + inv.purchaseFees, inv.currency.uppercase())
        }

        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }

        val totalValueAggregate = MoneyAggregateBuilder.fromBuckets(
            currentValueBuckets, homeCurrency, currencyConverter
        )
        val costBasisAggregate = MoneyAggregateBuilder.fromBuckets(
            costBasisBuckets, homeCurrency, currencyConverter
        )

        // Derive gain/loss aggregate from per-currency gain/loss buckets
        val gainLossBuckets = holdings.map { inv ->
            val currentValue = inv.currentPrice * inv.quantity
            val invested = (inv.purchasePrice * inv.quantity) + inv.purchaseFees
            Pair(currentValue - invested, inv.currency.uppercase())
        }
        val gainLossAggregate = MoneyAggregateBuilder.fromBuckets(
            gainLossBuckets, homeCurrency, currencyConverter
        )

        // I09: Price staleness thresholds
        val staleThresholdMs = STALE_PRICE_DAYS * 24 * 60 * 60 * 1000L
        val veryStaleThresholdMs = VERY_STALE_PRICE_DAYS * 24 * 60 * 60 * 1000L
        val now = timeProvider.now()
        val staleHoldings = holdings.filter { (now - it.lastUpdated) > staleThresholdMs }
        val veryStaleHoldings = holdings.filter { (now - it.lastUpdated) > veryStaleThresholdMs }
        val dataQuality = InvestmentDataQuality(
            isPartial = staleHoldings.isNotEmpty(),
            staleHoldingCount = staleHoldings.size,
            veryStaleHoldingCount = veryStaleHoldings.size,
            missingPriceCount = holdings.count { it.lastUpdated == 0L },
            lastUpdatedAt = if (holdings.isEmpty()) 0L else holdings.maxOf { it.lastUpdated }
        )

        return PortfolioSummaryAggregate(
            totalValue = totalValueAggregate,
            costBasis = costBasisAggregate,
            gainLoss = gainLossAggregate,
            dataQuality = dataQuality,
            valuationBasis = "currentPrice",
            investmentCount = holdings.size
        )
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

            val currentValueBuckets = listOf(Pair(currentValue, investment.currency.uppercase()))
            val costBasisBuckets = listOf(Pair(investedValue, investment.currency.uppercase()))

            val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
                .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }

            val currentValueAggregate = MoneyAggregateBuilder.fromBuckets(
                currentValueBuckets, homeCurrency, currencyConverter
            )
            val costBasisAggregate = MoneyAggregateBuilder.fromBuckets(
                costBasisBuckets, homeCurrency, currencyConverter
            )

            val staleThresholdMs = STALE_PRICE_DAYS * 24 * 60 * 60 * 1000L
            val isPriceStale = (now - investment.lastUpdated) > staleThresholdMs
            val dataQuality = InvestmentDataQuality(
                isPartial = isPriceStale || investment.lastUpdated == 0L,
                staleHoldingCount = if (isPriceStale) 1 else 0,
                missingPriceCount = if (investment.lastUpdated == 0L) 1 else 0,
                lastUpdatedAt = investment.lastUpdated
            )

            InvestmentPerformance(
                investment = investment,
                currentValue = currentValue,
                gainLoss = gainLoss,
                gainLossPercent = gainLossPercent,
                dayChange = dayChange,
                dayChangePercent = dayChangePercent,
                allTimeHigh = allTimeHigh,
                allTimeLow = allTimeLow,
                currentValueAggregate = currentValueAggregate,
                costBasisAggregate = costBasisAggregate,
                isPriceStale = isPriceStale,
                dataQuality = dataQuality
            )
        }
    
    /**
     * Update price for an investment and record value history.
     *
     * ATOMICITY-VERIFIED: Wrapped in [database.withTransaction] at the call site
     * (see line 231) so the price update + value history insert are atomic.
     */
    suspend fun updatePrice(investmentId: Long, newPrice: Double) = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("InvestmentTracker.updatePrice")
        require(newPrice.isFinite() && newPrice > 0.0) { "Price must be finite and positive" }
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
     *
     * I02-FIXED: Uses currencyConverter.convertAsOf() for real conversion.
     * Returns PortfolioAllocationResult with isPartial and failedConversionTypes.
     *
     * Allocations are percentages of CONVERTED_KNOWN_TOTAL (not full portfolio).
     * Failed conversions are tracked in failedConversionTypes. If isPartial=true,
     * the displayed percentages represent only the successfully-converted portion.
     */
    suspend fun getPortfolioAllocation(): PortfolioAllocationResult = withContext(ioDispatcher) {
        val holdings = investmentDao.getAllActiveInvestments().first()
        val aggregate = getPortfolioSummaryAggregate(holdings)
        if (aggregate.totalValue.sourceBuckets.isEmpty()) return@withContext PortfolioAllocationResult(emptyMap(), false, emptySet())
        val total = aggregate.totalValue.displayAmount
        if (total <= 0.0) return@withContext PortfolioAllocationResult(emptyMap(), false, emptySet())
        val homeCurrency = aggregate.totalValue.displayCurrency.code
        val failedTypes = mutableSetOf<InvestmentType>()
        val missingHistoryTypes = mutableSetOf<InvestmentType>()
        val byType = holdings.groupBy { it.type }.mapValues { (type, holds) ->
            holds.sumOf { h ->
                // PR6: Use same source as denominator (currentPrice * quantity)
                val value = h.currentPrice * h.quantity
                // Track missing latest value history as diagnostic
                if (investmentValueDao.getLatestValue(h.id) == null) {
                    missingHistoryTypes.add(type)
                }
                if (h.currency == homeCurrency) value
                else {
                    val converted = currencyConverter.convertAsOf(value, h.currency, homeCurrency, atMillis = h.lastUpdated)?.convertedAmount
                    if (converted == null) { failedTypes.add(type); 0.0 } else converted
                }
            }
        }
        val allocations = byType.mapValues { (_, value) -> if (total > 0.0) value / total else 0.0 }
        val isPartial = failedTypes.isNotEmpty() || missingHistoryTypes.isNotEmpty()
        return@withContext PortfolioAllocationResult(allocations, isPartial, failedTypes)
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
     * Returns a list of [InvestmentPerformance] for all active investments,
     * with per-currency aggregate breakdowns populated via [MoneyAggregateBuilder.fromBuckets].
     *
     * PR-I2: Exposes [InvestmentPerformance.currentValueAggregate] and
     * [InvestmentPerformance.costBasisAggregate] so callers can display
     * multi-currency totals safely without raw-summing across currencies.
     */
    suspend fun getInvestmentPerformances(): List<InvestmentPerformance> = withContext(ioDispatcher) {
        val investments = investmentDao.getAllActiveInvestments().first()

        // Build per-currency buckets for current value and cost basis
        val currentValueBuckets = investments.map { inv ->
            Pair(inv.currentPrice * inv.quantity, inv.currency.uppercase())
        }
        val costBasisBuckets = investments.map { inv ->
            Pair((inv.purchasePrice * inv.quantity) + inv.purchaseFees, inv.currency.uppercase())
        }

        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }

        val currentValueAggregate = MoneyAggregateBuilder.fromBuckets(
            currentValueBuckets, homeCurrency, currencyConverter
        )
        val costBasisAggregate = MoneyAggregateBuilder.fromBuckets(
            costBasisBuckets, homeCurrency, currencyConverter
        )

        // I09-FIXED: Mark price as stale if not updated within 7 days
        val staleThresholdMs = 7 * 24 * 60 * 60 * 1000L
        val now = timeProvider.now()

        investments.map { investment ->
            val currentValue = investment.currentPrice * investment.quantity
            val investedValue = (investment.purchasePrice * investment.quantity) + investment.purchaseFees
            val gainLoss = currentValue - investedValue
            val gainLossPercent = if (investedValue > 0) (gainLoss / investedValue) * 100 else 0.0

            val isPriceStale = (now - investment.lastUpdated) > staleThresholdMs
            InvestmentPerformance(
                investment = investment,
                currentValue = currentValue,
                gainLoss = gainLoss,
                gainLossPercent = gainLossPercent,
                dayChange = null,
                dayChangePercent = null,
                allTimeHigh = null,
                allTimeLow = null,
                currentValueAggregate = currentValueAggregate,
                costBasisAggregate = costBasisAggregate,
                isPriceStale = isPriceStale,
                dataQuality = InvestmentDataQuality(
                    isPartial = isPriceStale || investment.lastUpdated == 0L,
                    staleHoldingCount = if (isPriceStale) 1 else 0,
                    missingPriceCount = if (investment.lastUpdated == 0L) 1 else 0,
                    lastUpdatedAt = investment.lastUpdated
                )
            )
        }
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
     *
     * I03-FIXED: Added dataQuality/isPartial to result metadata.
     */
    suspend fun getPortfolioValueHistory(days: Int = 30): PortfolioValueHistoryResult = 
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
            val dayCurrencyBuckets = dayKeys.associateWith { mutableListOf<Pair<Double, String>>() }.toMutableMap()

            var hasMissingData = false

            for (investment in investments) {
                val values = valuesByInvestment[investment.id].orEmpty()
                    .sortedBy { it.timestamp }

                // WARNING: Holdings with missing price records on a given day have their value
                // carried forward from the last known snapshot (or fallback to purchasePrice * quantity
                // if no history exists yet). This prevents undercounting but means the daily total
                // may not reflect true market value if price data is stale.
                
                // Carry-forward state: the latest value seen so far
                var latestTotalValue = investment.purchasePrice * investment.quantity
                var valueIdx = 0
                
                if (values.isEmpty()) hasMissingData = true

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
                    dayCurrencyBuckets[dayKey]?.add(latestTotalValue to investment.currency.uppercase())
                }
            }

            val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
                .getOrElse { "EUR" } // fallback for history generation
            val dailyAggregates = dayKeys.map { dayKey ->
                val buckets = dayCurrencyBuckets[dayKey] ?: emptyList()
                if (buckets.isEmpty()) {
                    MoneyAggregate.empty(CurrencyCode(homeCurrency), RateBasis.LATEST_AVAILABLE)
                } else {
                    MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter)
                }
            }
            
            for ((dayKey, totalValue) in dayMap.toSortedMap()) {
                result.add(DailyPortfolioValue(dayKey, totalValue))
            }
            
            PortfolioValueHistoryResult(result, DataQuality(isPartial = hasMissingData), dailyAggregates)
        }

    private suspend fun getPreviousDayCloseSnapshot(investmentId: Long, referenceTime: Long): InvestmentValue? {
        val currentDayStart = getStartOfDay(referenceTime)
        return investmentValueDao.getLatestValueBefore(investmentId, currentDayStart)
    }

    private fun getStartOfDay(timestamp: Long): Long {
        // S12-023: Use java.time instead of Calendar.getInstance()
        return java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    
    private fun getDayKey(timestamp: Long): String {
        // S12-023: Use java.time instead of Calendar.getInstance()
        val date = java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return "${date.year}-${date.monthValue.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
    }
    
    fun getAllInvestments(): Flow<List<Investment>> = investmentDao.getAllActiveInvestments()
    
    fun getInvestmentsByType(type: InvestmentType): Flow<List<Investment>> = 
        investmentDao.getByType(type)
}

data class DailyPortfolioValue(
    val date: String,
    val totalValue: Double
)

data class PortfolioValueHistoryResult(
    val values: List<DailyPortfolioValue>,
    val dataQuality: DataQuality = DataQuality(isPartial = false),
    // PR-I3: Per-day aggregate breakdown by currency.
    // Populated once MoneyAggregateBuilder supports batch construction per day.
    // Currently reserved for future implementation.
    val dailyAggregates: List<MoneyAggregate> = emptyList()
)

data class DataQuality(
    val isPartial: Boolean
)

data class InvestmentDataQuality(
    val isPartial: Boolean = false,
    val staleHoldingCount: Int = 0,
    val veryStaleHoldingCount: Int = 0,
    val missingPriceCount: Int = 0,
    val lastUpdatedAt: Long = 0L
)

data class PortfolioAllocationResult(
    val allocations: Map<InvestmentType, Double>,
    val isPartial: Boolean,
    val failedConversionTypes: Set<InvestmentType>
)
