package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.util.TimeProvider
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
 *
 * Thread-safety: ALL mutations to [currentUserId], [_recommendations], and
 * [stateGeneration] are serialized through the single [stateLock] owner.
 * This includes both suspend (coroutine) callers and non-suspend callers such
 * as [removeFromState].
 *
 * Design rationale — one owner for all shared state:
 *   The previous design split ownership between a coroutine [Mutex] (for suspend
 *   paths) and a separate JVM monitor (for [removeFromState]). Because the two
 *   locks did not exclude each other, a synchronous remove could race a
 *   coroutine-path mutation and silently re-introduce a removed item.
 *
 *   The unified design replaces both with a single JVM monitor ([stateLock]).
 *   Coroutine paths perform all repository I/O **outside** the lock (no suspend
 *   inside synchronized), then acquire [stateLock] only for the brief state
 *   write. [removeFromState] acquires the same [stateLock] inline and completes
 *   the mutation before the method returns, preserving the behaviorally-synchronous
 *   public contract without any coroutine dispatch.
 *
 * A monotonic [stateGeneration] counter (guarded by [stateLock]) prevents stale
 * background refreshes from overwriting newer user state.
 */
@Singleton
class RecommendationStateManager @Inject constructor(
    private val repository: RecommendationRepository,
    private val timeProvider: TimeProvider,
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

    /**
     * Single synchronization owner for all shared state mutations.
     *
     * Used by both coroutine paths (via [synchronized]) and the synchronous
     * [removeFromState] path. Repository I/O is always performed **outside** this
     * lock to avoid holding a monitor across suspension points; the lock is acquired
     * only for the brief in-memory read-check-write step.
     *
     * Lock-ordering rule: [stateLock] is never nested — no code acquires [stateLock]
     * while already holding it.
     */
    private val stateLock = Any()

    /**
     * Monotonically increasing generation counter. Guarded by [stateLock].
     * Every state-mutating operation increments this value. A background refresh
     * only publishes its result when the generation captured at launch time still
     * matches the current generation, preventing stale data from overwriting newer
     * user state.
     */
    private var stateGeneration: Long = 0L

    private var currentUserId: String? = null

    /**
     * Refresh recommendations for a specific user.
     * Expires old recommendations and loads active ones.
     *
     * Same-user callers must still re-query storage. Invalidation/reload paths call
     * this method for the already-active user after upstream data changes, so the
     * current user match cannot be used as a skip gate.
     *
     * Repository I/O is performed outside [stateLock]. The lock is acquired only
     * for the brief generation-capture step (before I/O) and the publish step
     * (after I/O), ensuring no monitor is held across a suspension point.
     *
     * A generation guard ensures that if a newer mutation (user switch, dismiss,
     * clear, etc.) occurs while this refresh is in flight, its result is discarded.
     */
    fun refreshForUser(userId: String, forceRefresh: Boolean = false) {
        scope.launch {
            // --- lock section 1: validate and capture generation ---
            val capturedGeneration: Long = synchronized(stateLock) {
                // If this is a force-refresh but the current user context has changed
                // (clear set it to null, or another user was switched in), bail out.
                if (forceRefresh && currentUserId != userId) return@launch
                currentUserId = userId
                ++stateGeneration
            }
            // --- end lock section 1 ---

            try {
                // Repository I/O — outside the lock, safe to suspend here
                repository.expireOld(userId)
                val active = repository.getActiveForUser(userId)

                val nowMillis = timeProvider.now()
                val validRecommendations = active
                    .filter { it.isActive(nowMillis) }
                    .sortedWith(
                        compareByDescending<DashboardFollowThroughRecommendation> { it.priority.rank }
                            .thenByDescending { it.createdAt }
                    )
                    .take(5)

                // --- lock section 2: generation-guarded publish ---
                synchronized(stateLock) {
                    if (stateGeneration == capturedGeneration) {
                        _recommendations.value = validRecommendations
                    }
                }
                // --- end lock section 2 ---
            } catch (e: Exception) {
                // Log error but don't crash — emit empty list only if still current
                synchronized(stateLock) {
                    if (stateGeneration == capturedGeneration) {
                        _recommendations.value = emptyList()
                    }
                }
            }
        }
    }

    /**
     * Dismiss a recommendation by ID.
     *
     * Repository I/O is performed outside [stateLock]. The lock is acquired only
     * for the brief in-memory filter and generation bump.
     */
    fun dismiss(recommendationId: String) {
        scope.launch {
            try {
                // Repository I/O — outside the lock
                repository.dismiss(recommendationId)

                // --- lock section: remove from state and bump generation ---
                val userToRefresh: String? = synchronized(stateLock) {
                    _recommendations.value = _recommendations.value.filter { it.id != recommendationId }
                    ++stateGeneration
                    currentUserId
                }
                // --- end lock section ---

                // Refresh to load any additional recommendations
                userToRefresh?.let { refreshForUser(it, forceRefresh = true) }
            } catch (e: Exception) {
                // Log error
            }
        }
    }

    /**
     * Remove an item from in-memory UI state only.
     *
     * Uses [stateLock] — the single shared-state owner — so the mutation
     * completes inline, before the method returns, without dispatching to [scope].
     * This preserves the behaviorally-synchronous public contract:
     * [recommendations].value reflects the removal as soon as this call returns.
     *
     * Bumping [stateGeneration] ensures that any in-flight refresh whose captured
     * generation is now stale will not silently re-add the removed item.
     *
     * Synchronous public API: callers do not need to be in a coroutine context.
     */
    fun removeFromState(recommendationId: String) {
        synchronized(stateLock) {
            ++stateGeneration
            _recommendations.value = _recommendations.value.filter { it.id != recommendationId }
        }
    }

    /**
     * Return the currently active user ID for recommendation state.
     *
     * Reads [currentUserId] under [stateLock] to guarantee a consistent view
     * after any prior write completes.
     *
     * Synchronous public API: callers do not need to be in a coroutine context.
     */
    fun getCurrentUserId(): String? = synchronized(stateLock) { currentUserId }

    /**
     * Clear all recommendations (e.g., user logout or account switch).
     *
     * Bumps [stateGeneration] to invalidate any in-flight background refresh.
     * The mutation is performed **inline** under [stateLock] — no coroutine
     * dispatch — so callers observe the cleared state as soon as this method
     * returns. This preserves the behaviorally-synchronous public contract
     * (same pattern as [removeFromState]).
     *
     * Synchronous public API: callers do not need to be in a coroutine context.
     */
    fun clear() {
        synchronized(stateLock) {
            ++stateGeneration
            _recommendations.value = emptyList()
            currentUserId = null
        }
    }

    /**
     * Clear all recommendations for a specific user and refresh.
     *
     * Repository I/O is performed outside [stateLock].
     */
    fun clearForUser(userId: String) {
        scope.launch {
            try {
                // Repository I/O — outside the lock
                repository.clearForUser(userId)

                // --- lock section: conditional clear ---
                synchronized(stateLock) {
                    // Only clear if this user is still the current user
                    if (currentUserId == userId) {
                        _recommendations.value = emptyList()
                        ++stateGeneration
                    }
                }
                // --- end lock section ---
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}
