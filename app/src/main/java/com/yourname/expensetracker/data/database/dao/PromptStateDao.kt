package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PromptState
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptStateDao {
    
    @Query("""
        SELECT * FROM prompt_states 
        WHERE promptType = :promptType 
        ORDER BY createdAt DESC 
        LIMIT 1
    """)
    suspend fun getLastPrompt(promptType: String): PromptState?
    
    @Query("""
        SELECT * FROM prompt_states 
        WHERE promptType = :promptType 
        AND createdAt > :sinceTimestamp
        ORDER BY createdAt DESC
    """)
    suspend fun getPromptsSince(promptType: String, sinceTimestamp: Long): List<PromptState>
    
    @Query("""
        SELECT COUNT(*) FROM prompt_states 
        WHERE promptType = :promptType 
        AND createdAt > :sinceTimestamp
    """)
    suspend fun countPromptsSince(promptType: String, sinceTimestamp: Long): Int
    
    @Query("""
        SELECT * FROM prompt_states 
        WHERE promptType = :promptType 
        AND userAction = :action
        ORDER BY createdAt DESC 
        LIMIT 1
    """)
    suspend fun getLastPromptWithAction(promptType: String, action: String): PromptState?
    
    @Insert
    suspend fun insertPromptState(promptState: PromptState): Long
    
    @Update
    suspend fun updatePromptState(promptState: PromptState)
    
    @Query("""
        UPDATE prompt_states 
        SET userAction = :action, acknowledgedAt = :timestamp, actionDetails = :details
        WHERE id = :id
    """)
    suspend fun recordAcknowledgment(id: Long, action: String, timestamp: Long, details: String? = null)
    
    @Query("""
        SELECT * FROM prompt_states 
        WHERE promptType = :promptType 
        ORDER BY createdAt DESC 
        LIMIT :limit
    """)
    fun getRecentPrompts(promptType: String, limit: Int): Flow<List<PromptState>>
    
    @Query("DELETE FROM prompt_states WHERE createdAt < :olderThanTimestamp")
    suspend fun deleteOldPrompts(olderThanTimestamp: Long)
}
