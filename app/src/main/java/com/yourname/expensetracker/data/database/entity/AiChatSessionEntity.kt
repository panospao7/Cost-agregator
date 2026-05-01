package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_chat_sessions",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["createdAt"])
    ]
)
data class AiChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val updatedAt: Long = 0L
)
