package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

class FinancialQueryInterpretationInputBuilder @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider
) {

    suspend fun build(
        rawQuery: String,
        conversationHistory: List<AiChatMessage> = emptyList()
    ): FinancialQueryInterpretationInput {
        val categories = categoryRepository.getAll()
        val merchants = expenseRepository.getRecentMerchantNames()

        return FinancialQueryInterpretationInput(
            rawQuery = rawQuery
                .trim()
                .take(AppConfig.Ai.MAX_QUERY_INPUT_CHARS),
            currentTimeMs = timeProvider.now(),
            categoryNames = categories.map { it.name }.sorted(),
            merchantNames = merchants.distinct().take(100),
            conversationHistory = conversationHistory
                .takeLast(AppConfig.Ai.MAX_QUERY_HISTORY_TURNS_FOR_MODEL)
        )
    }
}
