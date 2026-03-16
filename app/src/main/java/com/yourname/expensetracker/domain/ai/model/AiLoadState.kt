package com.yourname.expensetracker.domain.ai.model

/**
 * Generic AI surface state used by all ViewModels that surface AI content.
 *
 * - [Disabled]  AI is turned off in settings; show deterministic fallback.
 * - [Idle]      AI is enabled but no generation has been requested yet.
 * - [Loading]   A generation or cache-fetch is in progress.
 * - [Ready]     A result is available. [stale] is true when the cached value
 *               is beyond its TTL but a fresh result is not yet available.
 * - [Error]     Generation failed; show deterministic fallback and optionally
 *               surface [message] for a retry affordance.
 */
sealed interface AiLoadState<out T> {
    data object Disabled : AiLoadState<Nothing>
    data object Idle     : AiLoadState<Nothing>
    data object Loading  : AiLoadState<Nothing>
    data class  Ready<T>(val value: T, val stale: Boolean = false) : AiLoadState<T>
    data class  Error(val message: String) : AiLoadState<Nothing>
}
