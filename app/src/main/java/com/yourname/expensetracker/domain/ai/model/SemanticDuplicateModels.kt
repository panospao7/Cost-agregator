package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.model.DomainTransactionType

/**
 * Input data for semantic duplicate detection.
 * 
 * This represents a transaction candidate to check for duplicates.
 * 
 * @property amount Transaction amount
 * @property currency Currency code (ISO 4217)
 * @property merchant Merchant or counterparty name
 * @property date Transaction timestamp
 * @property notificationText Original notification text (if available)
 * @property transactionType Type of transaction
 */
data class DuplicateCheckCandidate(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val date: Long,
    val notificationText: String?,
    val transactionType: DomainTransactionType
)

/**
 * Result from semantic duplicate detection.
 * 
 * @property isDuplicate Whether the AI considers these transactions to be duplicates
 * @property confidence Confidence score (0.0-1.0) in the duplicate detection
 * @property reasoning Explanation of why they are/aren't duplicates (for debugging)
 * @property merchantSimilarity Score for merchant name similarity (0.0-1.0)
 * @property contextSimilarity Score for overall transaction context similarity (0.0-1.0)
 * @property suggestion Action suggestion for the user
 */
data class SemanticDuplicateResult(
    val isDuplicate: Boolean,
    val confidence: Float,
    val reasoning: String?,
    val merchantSimilarity: Float,
    val contextSimilarity: Float,
    val suggestion: DuplicateSuggestion
)

/**
 * Suggested action for duplicate handling.
 */
enum class DuplicateSuggestion {
    MERGE,      // High confidence duplicate - suggest merging
    REVIEW,     // Medium confidence - user should review manually
    KEEP_BOTH,  // Low confidence - these are different transactions
    UNCERTAIN   // Cannot determine - needs more information
}
