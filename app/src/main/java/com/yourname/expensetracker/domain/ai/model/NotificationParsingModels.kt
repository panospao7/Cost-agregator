package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection

/**
 * Input data for AI notification parsing.
 * 
 * @property fullText Combined notification text for AI processing
 * @property packageName Source app package name (for context)
 * @property detectedLanguage Optional detected language code (e.g., "el" for Greek)
 */
data class NotificationParseInput(
    val fullText: String,
    val packageName: String,
    val detectedLanguage: String? = null
)

/**
 * Result from AI notification parsing.
 * 
 * This is the structured output from the AI model, which will be converted
 * to a ParsedTransaction for integration with the existing pipeline.
 * 
 * @property amount Transaction amount (always positive)
 * @property currency ISO 4217 currency code (e.g., "EUR", "USD")
 * @property merchant Merchant or counterparty name (may be empty if unclear)
 * @property transactionType Type of transaction
 * @property direction Transfer direction (for TRANSFER/DEPOSIT types)
 * @property confidence AI confidence in this parsing (0.0-1.0)
 * @property reasoning Optional explanation of AI reasoning (for debugging)
 */
data class NotificationParseResult(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val transactionType: ParsedTransactionType,
    val direction: ParsedTransferDirection?,
    val confidence: Float,
    val reasoning: String?
) {
    init {
        require(amount.isFinite() && amount > 0.0) {
            "NotificationParseResult.amount must be finite and > 0"
        }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "NotificationParseResult.confidence must be finite and within [0, 1]"
        }
    }
}
