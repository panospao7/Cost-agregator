package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import javax.inject.Inject

class NoOpReceiptAssistService @Inject constructor() : ReceiptAssistService {
    override suspend fun suggest(input: ReceiptAssistInput): ReceiptAssistSuggestion? = null
}
