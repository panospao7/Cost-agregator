package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.DuplicateCheckCandidate
import com.yourname.expensetracker.domain.ai.model.SemanticDuplicateResult
import com.yourname.expensetracker.domain.ai.service.SemanticDuplicateDetector
import javax.inject.Inject

/**
 * Use case for detecting semantic duplicates using AI.
 * 
 * This use case wraps the SemanticDuplicateDetector and provides
 * a clean API for the CrossSourceDeduplication engine.
 * 
 * Usage:
 * - Called when deterministic duplicate detection is inconclusive
 * - Returns semantic analysis with confidence scores
 * - Helps identify duplicates across different languages/formats
 */
class DetectSemanticDuplicateUseCase @Inject constructor(
    private val detector: SemanticDuplicateDetector
) {
    /**
     * Execute semantic duplicate detection.
     * 
     * @param transaction1 First transaction candidate
     * @param transaction2 Second transaction candidate
     * @return Semantic duplicate result with confidence
     */
    suspend fun execute(
        transaction1: DuplicateCheckCandidate,
        transaction2: DuplicateCheckCandidate
    ): SemanticDuplicateResult {
        // First, quick deterministic check
        if (detector.isObviouslyDifferent(transaction1, transaction2)) {
            return SemanticDuplicateResult(
                isDuplicate = false,
                confidence = 0.0f,
                reasoning = "Deterministic check: amounts or time window too different",
                merchantSimilarity = 0.0f,
                contextSimilarity = 0.0f,
                suggestion = com.yourname.expensetracker.domain.ai.model.DuplicateSuggestion.KEEP_BOTH
            )
        }
        
        // Use AI for semantic analysis
        return detector.calculateSimilarity(transaction1, transaction2)
    }
    
    /**
     * Quick check without AI (deterministic only).
     * 
     * Use this when AI is unavailable or for pre-filtering.
     * 
     * @param transaction1 First transaction
     * @param transaction2 Second transaction
     * @return True if they might be duplicates (needs further checking)
     */
    fun quickCheck(
        transaction1: DuplicateCheckCandidate,
        transaction2: DuplicateCheckCandidate
    ): Boolean {
        return !detector.isObviouslyDifferent(transaction1, transaction2)
    }
}
