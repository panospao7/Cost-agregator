package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole

@Entity(
    tableName = "ai_chat_messages",
    foreignKeys = [
        // DB-8: CASCADE on AiChatMessageEntity.sessionId → AiChatSessionEntity(id)
        // Safe: Messages are child records of a chat session; orphaned messages have no meaning.
        ForeignKey(
            entity = AiChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "createdAt"]),
        Index(value = ["createdAt"])
    ]
)
data class AiChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: AssistantMessageRole,
    val kind: AssistantMessageKind,
    val text: String,
    val payloadJson: String? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L
)
