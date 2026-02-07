package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface RawNotificationDao {
    
    @Insert
    suspend fun insert(notification: RawNotification): Long
    
    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun getById(id: Long): RawNotification?
    
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC")
    fun getAllFlow(): Flow<List<RawNotification>>
    
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<RawNotification>>
    
    @Query("SELECT * FROM raw_notifications WHERE packageName = :packageName ORDER BY capturedAt DESC")
    fun getByPackageFlow(packageName: String): Flow<List<RawNotification>>
    
    @Query("SELECT DISTINCT packageName FROM raw_notifications ORDER BY packageName")
    fun getAllPackagesFlow(): Flow<List<String>>
    
    @Query("SELECT COUNT(*) FROM raw_notifications")
    fun getCountFlow(): Flow<Int>
    
    @Query("DELETE FROM raw_notifications")
    suspend fun deleteAll()
    
    @Delete
    suspend fun delete(notification: RawNotification)
    
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM raw_notifications 
            WHERE packageName = :packageName 
            AND timestamp = :timestamp 
            AND (title = :title OR (:title IS NULL AND title IS NULL))
            AND (text = :text OR (:text IS NULL AND text IS NULL))
        )
    """)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean

    @Query("UPDATE raw_notifications SET isRelevant = :isRelevant WHERE id = :id")
    suspend fun markRelevance(id: Long, isRelevant: Boolean)
}
