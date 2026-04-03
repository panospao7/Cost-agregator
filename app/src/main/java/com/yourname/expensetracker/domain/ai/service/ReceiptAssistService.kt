package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion

interface ReceiptAssistService {
    suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion>
    fun usedImageInput(input: ReceiptAssistInput): Boolean = false
}
