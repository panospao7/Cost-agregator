package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiChatSession
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import kotlinx.coroutines.flow.Flow

interface AiChatRepository {
    fun observeSessions(): Flow<List<AiChatSession>>

    fun observeMessages(sessionId: Long): Flow<List<AiChatMessage>>

    suspend fun getSession(sessionId: Long): AiChatSession?

    suspend fun createSession(title: String? = null): Long?

    suspend fun appendMessage(
        sessionId: Long,
        role: AssistantMessageRole,
        kind: AssistantMessageKind,
        text: String,
        payloadJson: String? = null
    ): Long?

    suspend fun clearSession(sessionId: Long)

    suspend fun clearAllHistory()
}
