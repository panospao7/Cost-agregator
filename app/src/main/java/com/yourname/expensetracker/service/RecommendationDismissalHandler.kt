package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
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
            // Update UI state immediately.
            try {
                stateManager.removeFromState(recommendation.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove recommendation from state: ${recommendation.id}")
            }

            // Then archive in storage.
            try {
                repository.dismiss(recommendation.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist dismissal for recommendation: ${recommendation.id}")
            }
        }
    }

    suspend fun dismissAndRefresh(userId: String) {
        withContext(ioDispatcher) {
            try {
                stateManager.refreshForUser(userId, forceRefresh = true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh recommendations for user: $userId")
            }
        }
    }
}
