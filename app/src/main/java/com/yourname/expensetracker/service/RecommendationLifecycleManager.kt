package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.di.IoDispatcher
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

@Singleton
class RecommendationLifecycleManager @Inject constructor(
    private val repository: RecommendationRepository,
    private val stateManager: RecommendationStateManager,
    private val cacheService: RecommendationCacheService,
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
