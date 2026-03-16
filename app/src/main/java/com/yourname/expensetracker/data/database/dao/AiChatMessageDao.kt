package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.AiChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AiChatMessageEntity): Long

    @Query("SELECT * FROM ai_chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySession(sessionId: Long): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: Long): List<AiChatMessageEntity>

    @Query("DELETE FROM ai_chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("DELETE FROM ai_chat_messages")
    suspend fun deleteAll()
}
