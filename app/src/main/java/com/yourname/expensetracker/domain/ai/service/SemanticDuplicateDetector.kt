package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.DuplicateCheckCandidate
import com.yourname.expensetracker.domain.ai.model.SemanticDuplicateResult

/**
 * Interface for AI-powered semantic duplicate detection.
 * 
 * This service detects duplicates even when transaction descriptions differ
 * in language, spelling, or format. It uses semantic understanding to compare
 * merchant names, amounts, dates, and transaction contexts.
 * 
 * Examples of semantic duplicates:
 * - "ΣΚΛΑΒΕΝΙΤΗΣ" and "Sklavenitis Market"
 * - "Revolut transfer to John" and "Sent €50 to John via Revolut"
 * - "Coffee Island" and "COFFEE ISLAND ATHENS"
 * 
 * The AI compares:
 * - Merchant names (including Greeklish variations)
 * - Transaction amounts and dates
 * - Context from notification text
 * - Transaction types
 * 
 * This is invoked within CrossSourceDeduplication when the deterministic
 * similarity check is inconclusive (0.4 < similarity < 0.9).
 */
interface SemanticDuplicateDetector {
    /**
     * Calculate semantic similarity between two transactions.
     * 
     * This method should:
     * 1. Check if amounts match (basic requirement)
     * 2. Calculate merchant name similarity using semantic understanding
     * 3. Compare transaction contexts
     * 4. Return confidence and reasoning
     * 
     * @param transaction1 First transaction to compare
     * @param transaction2 Second transaction to compare
     * @return Semantic duplicate result with confidence and reasoning
     */
    suspend fun calculateSimilarity(
        transaction1: DuplicateCheckCandidate,
        transaction2: DuplicateCheckCandidate
    ): SemanticDuplicateResult
    
    /**
     * Quick deterministic check without AI.
     * 
     * Use this for fast pre-filtering before calling AI.
     * 
     * @param transaction1 First transaction
     * @param transaction2 Second transaction
     * @return True if they are obviously not duplicates (different amounts)
     */
    fun isObviouslyDifferent(
        transaction1: DuplicateCheckCandidate,
        transaction2: DuplicateCheckCandidate
    ): Boolean
}
