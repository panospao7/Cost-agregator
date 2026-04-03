package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.PromptStateDao
import com.yourname.expensetracker.data.database.entity.PromptState
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptStateRepository @Inject constructor(
    private val promptStateDao: PromptStateDao,
    private val timeProvider: TimeProvider
) {
    /**
     * Check if a prompt of this type has been shown within the last N days.
     * Used for anti-nag logic.
     */
    suspend fun hasPromptedRecently(promptType: String, daysAgo: Int = 30): Boolean {
        val cutoffTime = timeProvider.now() - (daysAgo * 24 * 60 * 60 * 1000L)
        val count = promptStateDao.countPromptsSince(promptType, cutoffTime)
        return count > 0
    }
    
    /**
     * Record that a prompt was shown to the user.
     */
    suspend fun recordPrompt(promptType: String): Long {
        val promptState = PromptState(
            promptType = promptType,
            createdAt = timeProvider.now()
        )
        return promptStateDao.insertPromptState(promptState)
    }
    
    /**
     * Record how the user responded to a prompt.
     */
    suspend fun recordAcknowledgment(promptType: String, action: String, details: String? = null) {
        val promptState = PromptState(
            promptType = promptType,
            userAction = action,
            acknowledgedAt = timeProvider.now(),
            actionDetails = details
        )
        promptStateDao.insertPromptState(promptState)
    }
    
    /**
     * Get the most recent prompt of a specific type.
     */
    suspend fun getLastPrompt(promptType: String): PromptState? {
        return promptStateDao.getLastPrompt(promptType)
    }
    
    /**
     * Check if user already took a specific action for this prompt type.
     */
    suspend fun hasUserTakenAction(promptType: String, action: String, daysAgo: Int = 90): Boolean {
        val cutoffTime = timeProvider.now() - (daysAgo * 24 * 60 * 60 * 1000L)
        val prompts = promptStateDao.getPromptsSince(promptType, cutoffTime)
        return prompts.any { it.userAction == action }
    }
    
    /**
     * Get recent prompts as a flow for reactive UI updates.
     */
    fun getRecentPromptsFlow(promptType: String, limit: Int = 10): Flow<List<PromptState>> {
        return promptStateDao.getRecentPrompts(promptType, limit)
    }
    
    /**
     * Clean up old prompt records (older than 1 year).
     */
    suspend fun cleanupOldRecords() {
        val cutoffTime = timeProvider.now() - (365 * 24 * 60 * 60 * 1000L)
        promptStateDao.deleteOldPrompts(cutoffTime)
    }
}
