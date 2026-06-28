package com.yourname.expensetracker.domain.privacy

/**
 * PR9: Result of a single retention purge operation.
 */
data class RetentionPurgeResult(
    val targetName: String,
    val rowsPurged: Int,
    val success: Boolean,
    val errorMessage: String? = null,
    val isTransient: Boolean = false
)

/**
 * PR9: Contract for a retention purge target.
 *
 * Implementations are registered in [DataRetentionWorker] and executed
 * sequentially. Each target reports how many rows it purged.
 *
 * Invariants:
 * - [purge] must be idempotent — rows already purged must not be re-purged.
 * - [purge] must be safe to call even if the target table is empty.
 * - Errors must be caught internally and reported in [RetentionPurgeResult.errorMessage].
 */
interface RetentionTarget {
    val name: String
    suspend fun purge(cutoffMs: Long): RetentionPurgeResult
}
