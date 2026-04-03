package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the current state of active dashboard recommendations.
 * 
 * Provides a reactive StateFlow for UI observation and handles:
 * - Max 5 recommendation limit
 * - Expiration checking
 * - User-specific recommendations
 */
@Singleton
class RecommendationStateManager @Inject constructor(
    private val repository: RecommendationRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    /**
     * App-owned scope injected via DI.
     *
     * This manager is a singleton and its refresh/dismiss operations are intentionally
     * detached from UI lifecycles, but still structured under a single application scope.
     */
    private val scope: CoroutineScope = applicationScope
    
    private val _recommendations = MutableStateFlow<List<DashboardFollowThroughRecommendation>>(emptyList())
    
    /**
     * Current active recommendations for the user.
     * Emits up to 5 recommendations, sorted by priority.
     */
    val recommendations: StateFlow<List<DashboardFollowThroughRecommendation>> = _recommendations.asStateFlow()
    
    private var currentUserId: String? = null
    
    /**
     * Refresh recommendations for a specific user.
     * Expires old recommendations and loads active ones.
     */
    fun refreshForUser(userId: String, forceRefresh: Boolean = false) {
        if (currentUserId != userId || forceRefresh) {
            currentUserId = userId

            scope.launch {
                try {
                    // First, expire old recommendations
                    repository.expireOld(userId)

                    // Load active recommendations
                    val active = repository.getActiveForUser(userId)

                    // Filter out any that are expired (double-check)
                    val nowMillis = System.currentTimeMillis()
                    val validRecommendations = active
                        .filter { it.isActive(nowMillis) }
                        .sortedWith(
                            compareByDescending<DashboardFollowThroughRecommendation> { it.priority }
                                .thenByDescending { it.createdAt }
                        )
                        .take(5)

                    _recommendations.value = validRecommendations
                } catch (e: Exception) {
                    // Log error but don't crash - emit empty list
                    _recommendations.value = emptyList()
                }
            }
        }
    }
    
    /**
     * Dismiss a recommendation by ID.
     */
    fun dismiss(recommendationId: String) {
        scope.launch {
            try {
                repository.dismiss(recommendationId)

                // Update local state
                _recommendations.value = _recommendations.value.filter { it.id != recommendationId }

                // Refresh to load any additional recommendations
                currentUserId?.let { refreshForUser(it, forceRefresh = true) }
            } catch (e: Exception) {
                // Log error
            }
        }
    }

    /**
     * Remove an item from in-memory UI state only.
     */
    fun removeFromState(recommendationId: String) {
        _recommendations.value = _recommendations.value.filter { it.id != recommendationId }
    }

    /**
     * Return the currently active user ID for recommendation state.
     */
    fun getCurrentUserId(): String? = currentUserId
    
    /**
     * Clear all recommendations (e.g., user logout or account switch).
     */
    fun clear() {
        _recommendations.value = emptyList()
        currentUserId = null
    }
    
    /**
     * Clear all recommendations for a specific user and refresh.
     */
    fun clearForUser(userId: String) {
        scope.launch {
            try {
                repository.clearForUser(userId)
                _recommendations.value = emptyList()
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}
