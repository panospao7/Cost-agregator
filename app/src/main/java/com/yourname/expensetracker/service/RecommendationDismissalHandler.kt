package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.util.CancellationSafe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationDismissalHandler @Inject constructor(
    private val repository: RecommendationRepository,
    private val stateManager: RecommendationStateManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun dismiss(recommendation: DashboardFollowThroughRecommendation) {
        withContext(ioDispatcher) {
            try {
                repository.dismiss(recommendation.id)
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.e(e, "Failed to persist dismissal for recommendation: ${recommendation.id}")
                return@withContext
            }

            try {
                stateManager.removeFromState(recommendation.id)
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.e(e, "Failed to remove recommendation from state after dismissal: ${recommendation.id}")
                refreshCurrentUserIfNeeded(recommendation.userId)
            }
        }
    }

    suspend fun dismissAndRefresh(userId: String) {
        withContext(ioDispatcher) {
            try {
                stateManager.refreshForUser(userId, forceRefresh = true)
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.e(e, "Failed to refresh recommendations for user: $userId")
            }
        }
    }

    private fun refreshCurrentUserIfNeeded(userId: String) {
        val currentUserId = try {
            stateManager.getCurrentUserId()
        } catch (e: Exception) {
            Timber.e(e, "Failed to read current recommendation user before refresh: $userId")
            return
        }

        if (currentUserId != userId) return

        try {
            stateManager.refreshForUser(userId, forceRefresh = true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh recommendations after dismissal state mismatch for user: $userId")
        }
    }
}
