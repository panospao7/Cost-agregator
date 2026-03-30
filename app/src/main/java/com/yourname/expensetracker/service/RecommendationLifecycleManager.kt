package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages lifecycle of dashboard recommendations: expiration, cleanup, and threshold refresh.
 * 
 * **Phase 3A**: Added threshold refresh to keep adaptive thresholds up-to-date.
 */
@Singleton
class RecommendationLifecycleManager @Inject constructor(
    private val repository: RecommendationRepository,
    private val stateManager: RecommendationStateManager,
    private val cacheService: RecommendationCacheService,
    private val thresholdCalculator: SpendingThresholdCalculator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val periodicStarted = AtomicBoolean(false)

    suspend fun checkAndExpire(userId: String) {
        withContext(ioDispatcher) {
            try {
                repository.expireOld(userId)
                cacheService.evictExpired()
                stateManager.refreshForUser(userId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to check and expire recommendations for user: $userId")
            }
        }
    }

    suspend fun cleanupExpired() {
        withContext(ioDispatcher) {
            try {
                repository.cleanupExpired()
                cacheService.evictExpired()
                stateManager.getCurrentUserId()?.let { stateManager.refreshForUser(it) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup expired recommendations")
            }
        }
    }

    /**
     * Refresh the adaptive spending threshold.
     * Call this when significant data changes occur (e.g., new transaction imported).
     * 
     * **Phase 3A**: Ensures high-amount detection uses fresh P90 percentile.
     */
    suspend fun refreshThreshold() {
        withContext(ioDispatcher) {
            try {
                thresholdCalculator.refreshThreshold()
                Timber.d("Refreshed adaptive spending threshold")
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh spending threshold")
            }
        }
    }

    fun startPeriodicExpirationCheck() {
        if (periodicStarted.getAndSet(true)) return

        applicationScope.launch {
            while (isActive) {
                cleanupExpired()
                delay(PERIODIC_CHECK_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val PERIODIC_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6h
    }
}
