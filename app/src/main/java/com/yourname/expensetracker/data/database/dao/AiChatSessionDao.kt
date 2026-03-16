package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiChatSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: AiChatSessionEntity): Long

    @Query("SELECT * FROM ai_chat_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: Long): AiChatSessionEntity?

    @Query("SELECT * FROM ai_chat_sessions ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<AiChatSessionEntity>>

    @Query("UPDATE ai_chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateLastTouched(sessionId: Long, updatedAt: Long)

    @Query("DELETE FROM ai_chat_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("DELETE FROM ai_chat_sessions")
    suspend fun deleteAll()
}
