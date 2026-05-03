package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SourceStats
import kotlinx.coroutines.flow.Flow

/**
 * DAO for source-level statistics (notifications, accept/reject/duplicate counts).
 *
 * ## Future migration
 *
 * These statistics are currently maintained inline by [ReviewQueueRepository] via
 * direct DAO increment/decrement calls.  This approach is fragile because:
 *   - It requires every code path to remember to update stats (missing updates
 *     lead to silent drift).
 *   - It cannot reconstruct historical state (the table only reflects current
 *     cumulative totals).
 *
 * ### Event-derived replacement
 * A future refactoring should derive source statistics exclusively from the
 * [com.yourname.expensetracker.data.database.entity.TransactionEvent] audit log.
 * Every event type (CREATED, CREATE_DUPLICATE_SKIPPED, REJECTED, etc.) already
 * carries the source and a timestamp, making it possible to **replay the event
 * stream** to compute any aggregate at any point in time — without needing to
 * keep inline counters in sync.
 *
 * Until that migration happens, this DAO and its call sites remain the
 * canonical path for source-level stats.
 */
@Dao
interface SourceStatsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(stats: SourceStats)

    @Query("SELECT * FROM source_stats WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): SourceStats?

    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    fun getAllFlow(): Flow<List<SourceStats>>

    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    suspend fun getAll(): List<SourceStats>

    /**
     * Insert all source stats. Uses IGNORE because source stats are derived audit data;
     * duplicates should never overwrite existing aggregated counters.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(stats: List<SourceStats>)

    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1, 
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotal(packageName: String, now: Long)

    @Query("""
        UPDATE source_stats 
        SET acceptedAsExpense = acceptedAsExpense + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAccepted(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET rejectedByUser = rejectedByUser + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementRejected(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET autoRejected = autoRejected + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAutoRejected(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET pendingReview = pendingReview + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementPending(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET duplicates = duplicates + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementDuplicate(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET pendingReview = MAX(0, pendingReview - 1) 
        WHERE packageName = :packageName
    """)
    suspend fun decrementPending(packageName: String)

    @Query("UPDATE source_stats SET pendingReview = 0")
    suspend fun resetAllPendingCounts()

    @Query("DELETE FROM source_stats")
    suspend fun deleteAll()

    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1,
            acceptedAsExpense = acceptedAsExpense + 1,
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotalAndAccepted(packageName: String, now: Long)

    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1,
            duplicates = duplicates + 1,
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotalAndDuplicate(packageName: String, now: Long)

    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1,
            pendingReview = pendingReview + 1,
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotalAndPending(packageName: String, now: Long)

    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1,
            autoRejected = autoRejected + 1,
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotalAndAutoRejected(packageName: String, now: Long)
}
