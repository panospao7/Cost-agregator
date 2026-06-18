package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult

/**
 * Service interface for AI-powered receipt item categorization.
 */
interface ReceiptItemCategorizationService {
    /**
     * Categorizes individual items on a receipt.
     * 
     * @param input The receipt data and user's categories
     * @return Categorization result with suggestions, or null if service unavailable
     */
    suspend fun categorizeItems(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult?
}
