package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Calculates adaptive spending thresholds based on user's transaction history.
 * 
 * Uses percentile-based analysis to determine what constitutes a "high amount"
 * for each user, replacing hardcoded thresholds with personalized calculations.
 * 
 * Algorithm:
 * - Analyzes last 90 days of PURCHASE transactions
 * - Calculates P50, P75, P90, P95, P99 percentiles
 * - Uses P90 as "high amount" threshold (top 10% of spending)
 * - Minimum threshold: €50 (even if P90 < 50)
 * - Caches result, recalculates daily or after significant transaction activity
 */
@Singleton
class SpendingThresholdCalculator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    companion object {
        private const val DEFAULT_USER_ID = "default"
        private const val ANALYSIS_WINDOW_DAYS = 90
        private const val MIN_THRESHOLD = 50.0
        private const val MIN_SAMPLE_SIZE = 10
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
    
    // In-memory cache per user
    private val cache = mutableMapOf<String, CachedPercentiles>()
    
    /**
     * Calculate the "high amount" threshold for a user.
     * 
     * Returns P90 (90th percentile) of user's last 90 days of spending,
     * with a minimum of €50.
     * 
     * @param userId User identifier
     * @return Threshold amount (minimum €50)
     */
    suspend fun calculateHighAmountThreshold(userId: String): Double {
        return withContext(ioDispatcher) {
            val percentiles = calculatePercentiles(userId)
            
            if (percentiles.sampleSize < MIN_SAMPLE_SIZE) {
                Timber.d("SpendingThresholdCalculator: Insufficient data for $userId (${percentiles.sampleSize} transactions), using minimum threshold")
                return@withContext MIN_THRESHOLD
            }
            
            // Use P90, but enforce minimum
            val threshold = maxOf(percentiles.p90, MIN_THRESHOLD)
            
            Timber.d("SpendingThresholdCalculator: Threshold for $userId = €$threshold (P90=${percentiles.p90}, sample=${percentiles.sampleSize})")
            threshold
        }
    }
    
    /**
     * Calculate spending percentiles for a user.
     * 
     * Analyzes last 90 days of PURCHASE transactions and returns
     * P50, P75, P90, P95, P99 percentiles.
     * 
     * @param userId User identifier
     * @return SpendingPercentiles with calculated values
     */
    suspend fun calculatePercentiles(userId: String): SpendingPercentiles {
        return withContext(ioDispatcher) {
            // Check cache first
            val cached = cache[userId]
            val now = timeProvider.now()
            
            if (cached != null && (now - cached.percentiles.calculatedAt) < CACHE_TTL_MS) {
                Timber.d("SpendingThresholdCalculator: Using cached percentiles for $userId")
                return@withContext cached.percentiles
            }
            
            // Calculate from scratch
            val startDate = now - (ANALYSIS_WINDOW_DAYS * 24 * 60 * 60 * 1000L)
            val amounts = expenseDao.getAmountsForPercentileCalc(startDate, now)
            
            if (amounts.isEmpty()) {
                Timber.d("SpendingThresholdCalculator: No transactions for $userId in last $ANALYSIS_WINDOW_DAYS days")
                return@withContext SpendingPercentiles.empty(now)
            }
            
            val sorted = amounts.sorted()
            val size = sorted.size
            
            val percentiles = SpendingPercentiles(
                p50 = percentile(sorted, 0.50),
                p75 = percentile(sorted, 0.75),
                p90 = percentile(sorted, 0.90),
                p95 = percentile(sorted, 0.95),
                p99 = percentile(sorted, 0.99),
                sampleSize = size,
                calculatedAt = now
            )
            
            // Cache result
            cache[userId] = CachedPercentiles(percentiles, now)
            
            Timber.d("SpendingThresholdCalculator: Calculated percentiles for $userId: P50=${percentiles.p50}, P90=${percentiles.p90}, sample=$size")
            percentiles
        }
    }
    
    /**
     * Force refresh of thresholds for a user.
     * 
     * Clears cache and recalculates on next request.
     * Call this after significant transaction activity (e.g., 20+ new transactions).
     * 
     * @param userId User identifier
     */
    suspend fun refreshThresholds(userId: String) {
        withContext(ioDispatcher) {
            cache.remove(userId)
            Timber.d("SpendingThresholdCalculator: Cache cleared for $userId")
        }
    }
    
    /**
     * Convenience method: Get threshold for default user (single-user app).
     * 
     * @return Threshold amount (minimum €50)
     */
    suspend fun getThreshold(): Double {
        return calculateHighAmountThreshold(DEFAULT_USER_ID)
    }
    
    /**
     * Convenience method: Refresh threshold for default user (single-user app).
     */
    suspend fun refreshThreshold() {
        refreshThresholds(DEFAULT_USER_ID)
    }
    
    /**
     * Calculate a specific percentile from sorted data.
     * 
     * Uses linear interpolation for values between data points.
     * 
     * @param sortedData Sorted list of values
     * @param percentile Percentile to calculate (0.0 - 1.0)
     * @return Calculated percentile value
     */
    private fun percentile(sortedData: List<Double>, percentile: Double): Double {
        if (sortedData.isEmpty()) return 0.0
        if (sortedData.size == 1) return sortedData[0]
        
        val index = percentile * (sortedData.size - 1)
        val lower = index.toInt()
        val upper = min(lower + 1, sortedData.size - 1)
        val fraction = index - lower
        
        return sortedData[lower] + fraction * (sortedData[upper] - sortedData[lower])
    }
    
    private data class CachedPercentiles(
        val percentiles: SpendingPercentiles,
        val cachedAt: Long
    )
}

/**
 * Spending percentiles for a user.
 * 
 * Contains P50 (median), P75, P90, P95, P99 percentiles
 * calculated from recent transaction history.
 */
data class SpendingPercentiles(
    val p50: Double,  // Median
    val p75: Double,  // 75th percentile
    val p90: Double,  // 90th percentile (used as "high amount" threshold)
    val p95: Double,  // 95th percentile
    val p99: Double,  // 99th percentile
    val sampleSize: Int,
    val calculatedAt: Long
) {
    companion object {
        fun empty(timestamp: Long) = SpendingPercentiles(
            p50 = 0.0,
            p75 = 0.0,
            p90 = 0.0,
            p95 = 0.0,
            p99 = 0.0,
            sampleSize = 0,
            calculatedAt = timestamp
        )
    }
}
