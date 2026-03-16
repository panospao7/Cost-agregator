package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import javax.inject.Inject

class NoOpCategorizationAssistService @Inject constructor() : CategorizationAssistService {
    override suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion? = null
}
