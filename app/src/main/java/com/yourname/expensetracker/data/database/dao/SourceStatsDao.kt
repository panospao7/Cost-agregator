package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SourceStats
import kotlinx.coroutines.flow.Flow

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
