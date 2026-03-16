package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion

interface CategorizationAssistService {
    suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion?
}
