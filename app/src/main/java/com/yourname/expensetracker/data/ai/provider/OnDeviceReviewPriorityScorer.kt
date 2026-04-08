package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.ReviewPriorityFactors
import com.yourname.expensetracker.domain.ai.model.ReviewPriorityScore
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReviewPriorityScorer
import com.yourname.expensetracker.domain.dto.ReviewPriorityInput
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Hybrid review priority scorer combining deterministic calculations and optional AI enhancement.
 * 
 * This implementation:
 * 1. Uses fast deterministic calculations for base scores (always available)
 * 2. Optionally uses on-device AI for semantic analysis of notification text
 * 3. Blends scores: 70% deterministic, 30% AI (when available)
 * 4. Falls back to deterministic-only when AI is disabled
 * 
 * Deterministic factors:
 * - Confidence level (inverse - low confidence = higher priority)
 * - Duplicate risk (from CrossSourceDeduplication)
 * - Merchant clarity (Unknown = higher priority)
 * - Time sensitivity (older = higher priority)
 * - Category clarity (unknown category = higher priority)
 * - Amount significance (higher amounts = higher priority)
 * 
 * AI factors (optional):
 * - Semantic analysis of notification text
 * - Historical user behavior patterns
 * - Likelihood of being duplicate based on context
 */
@Singleton
class OnDeviceReviewPriorityScorer @Inject constructor(
    private val router: AiCapabilityRouter,
    private val settingsRepository: AiSettingsRepository,
    private val deduplication: CrossSourceDeduplication,
    private val reviewQueueRepository: ReviewQueueRepository,
    private val timeProvider: TimeProvider
) : ReviewPriorityScorer {

    companion object {
        private const val DETERMINISTIC_WEIGHT = 0.7f
        private const val AI_WEIGHT = 0.3f
        private const val HIGH_PRIORITY_THRESHOLD = 0.7f
        private const val MEDIUM_PRIORITY_THRESHOLD = 0.4f
    }

    override suspend fun scoreReviews(reviews: List<PendingReview>): List<ReviewPriorityScore> {
        if (reviews.isEmpty()) return emptyList()
        
        // Map PendingReview entities → ReviewPriorityInput domain DTOs at the boundary
        val inputs = reviews.map { it.toReviewPriorityInput() }

        // Check AI availability
        val settings = settingsRepository.settings().first()
        val decision = router.decide(AiCapability.REVIEW_PRIORITIZATION, settings)
        val useAi = decision.route == AiRoute.ON_DEVICE
        
        return inputs.mapIndexed { index, input ->
            val baseFactors = ReviewPriorityFactors.fromReview(input, timeProvider.now())
            val duplicateRisk = calculateDuplicateRisk(input, inputs)
            val baseScore = calculateScoreFromFactors(baseFactors.copy(duplicateRisk = duplicateRisk))
            
            val (finalScore, factors, reasoning) = if (useAi) {
                // Blend deterministic with AI
                val aiScore = calculateAiScore(reviews[index], baseFactors)
                val blendedScore = (baseScore * DETERMINISTIC_WEIGHT + aiScore * AI_WEIGHT)
                    .coerceIn(0f, 1f)
                Triple(blendedScore, baseFactors, generateReasoning(blendedScore, input, baseFactors))
            } else {
                // Deterministic only
                val factorsWithDupes = baseFactors.copy(duplicateRisk = duplicateRisk)
                Triple(baseScore, factorsWithDupes, generateReasoning(baseScore, input, factorsWithDupes))
            }
            
            ReviewPriorityScore(
                reviewId = input.reviewId,
                priorityScore = finalScore,
                urgencyReason = reasoning,
                estimatedApprovalTime = estimateApprovalTime(finalScore),
                factors = factors
            )
        }.also {
            if (useAi) {
                Timber.d("OnDeviceReviewPriorityScorer: Scored ${reviews.size} reviews with AI enhancement")
            } else {
                Timber.d("OnDeviceReviewPriorityScorer: Scored ${reviews.size} reviews with deterministic only")
            }
        }
    }

    override suspend fun scoreSingle(review: PendingReview): ReviewPriorityScore {
        return scoreReviews(listOf(review)).firstOrNull()
            ?: ReviewPriorityScore(
                reviewId = review.id,
                priorityScore = calculateBaseScore(review),
                urgencyReason = null,
                estimatedApprovalTime = null,
                factors = ReviewPriorityFactors.fromReview(review.toReviewPriorityInput(), timeProvider.now())
            )
    }

    override fun calculateBaseScore(review: PendingReview): Float {
        val factors = ReviewPriorityFactors.fromReview(review.toReviewPriorityInput(), timeProvider.now())
        return calculateScoreFromFactors(factors)
    }
    
    private fun calculateScoreFromFactors(factors: ReviewPriorityFactors): Float {
        // Weighted combination of factors
        // Higher values generally indicate more need for attention
        val weights = mapOf(
            "confidence" to 0.25f,      // Low confidence = higher priority
            "duplicate" to 0.20f,       // Likely duplicate = needs verification
            "merchant" to 0.15f,        // Unknown merchant = needs input
            "time" to 0.15f,            // Old = higher priority
            "category" to 0.10f,        // Unknown category = needs categorization
            "amount" to 0.10f,          // Large amount = higher priority
            "historical" to 0.05f       // User patterns
        )
        
        return (
            factors.confidenceLevel * weights["confidence"]!! +
            factors.duplicateRisk * weights["duplicate"]!! +
            (1f - factors.merchantClarity) * weights["merchant"]!! +
            factors.timeSensitivity * weights["time"]!! +
            (1f - factors.categoryClarity) * weights["category"]!! +
            factors.amountSignificance * weights["amount"]!! +
            (1f - factors.historicalPattern) * weights["historical"]!!
        ).coerceIn(0f, 1f)
    }
    
    private suspend fun calculateDuplicateRisk(input: ReviewPriorityInput, allInputs: List<ReviewPriorityInput>): Float {
        // Check if there are similar transactions nearby using the already-mapped domain DTOs
        val existingReviews = reviewQueueRepository.getPendingReviews().first()
        
        // Count potential duplicates (same amount, similar time, same merchant)
        var duplicateCount = 0
        for (existing in existingReviews) {
            if (existing.review.id != input.reviewId &&
                abs(existing.review.suggestedAmount - input.suggestedAmount) < 0.01 &&
                existing.review.suggestedMerchant.equals(input.suggestedMerchant, ignoreCase = true) &&
                abs(existing.review.createdAt - input.createdAt) < 24 * 60 * 60 * 1000 // 24 hours
            ) {
                duplicateCount++
            }
        }
        
        return when {
            duplicateCount >= 2 -> 0.9f
            duplicateCount == 1 -> 0.6f
            else -> 0.2f
        }
    }
    
    private suspend fun calculateAiScore(review: PendingReview, factors: ReviewPriorityFactors): Float {
        // For now, we use a simplified AI calculation without actual ML Kit calls
        // This keeps the implementation fast and deterministic while providing
        // room for future enhancement with true semantic analysis
        
        // In a full implementation, this would:
        // 1. Build a prompt with review details
        // 2. Call ML Kit GenAI
        // 3. Parse the response for priority score
        
        // Simplified semantic analysis:
        val text = review.notificationText ?: ""
        var aiScore = 0.5f
        
        // Check for keywords that indicate high priority
        val highPriorityKeywords = listOf(
            "refund", "return", "dispute", "duplicate", "error", "failed",
            "επιστροφή", "διπλό", "λάθος" // Greek translations
        )
        
        val lowPriorityKeywords = listOf(
            "subscription", "recurring", "automatic", "monthly", "weekly",
            "συνδρομή", "επαναλαμβανόμενη" // Greek translations
        )
        
        highPriorityKeywords.forEach { keyword ->
            if (text.contains(keyword, ignoreCase = true)) {
                aiScore += 0.15f
            }
        }
        
        lowPriorityKeywords.forEach { keyword ->
            if (text.contains(keyword, ignoreCase = true)) {
                aiScore -= 0.1f
            }
        }
        
        return aiScore.coerceIn(0f, 1f)
    }
    
    private fun generateReasoning(score: Float, input: ReviewPriorityInput, factors: ReviewPriorityFactors): String? {
        return when {
            score >= HIGH_PRIORITY_THRESHOLD -> {
                val reasons = mutableListOf<String>()
                if (factors.confidenceLevel > 0.5f) reasons.add("low confidence")
                if (factors.duplicateRisk > 0.5f) reasons.add("possible duplicate")
                if (factors.merchantClarity < 0.5f) reasons.add("unclear merchant")
                if (factors.timeSensitivity > 0.6f) reasons.add("old transaction")
                if (factors.categoryClarity < 0.5f) reasons.add("needs categorization")
                if (factors.amountSignificance > 0.7f) reasons.add("high amount")
                
                if (reasons.isNotEmpty()) {
                    "Priority: ${reasons.joinToString(", ")}"
                } else {
                    "High priority"
                }
            }
            score >= MEDIUM_PRIORITY_THRESHOLD -> {
                when {
                    factors.confidenceLevel > 0.4f -> "Verify details"
                    factors.duplicateRisk > 0.4f -> "Check for duplicate"
                    factors.merchantClarity < 0.6f -> "Confirm merchant"
                    else -> "Medium priority"
                }
            }
            else -> null // Low priority - no badge needed
        }
    }
    
    private fun estimateApprovalTime(score: Float): Int? {
        // Estimate seconds needed to approve
        return when {
            score >= HIGH_PRIORITY_THRESHOLD -> 15 // Needs careful review
            score >= MEDIUM_PRIORITY_THRESHOLD -> 8  // Quick verification
            else -> 5 // Fast approval
        }
    }

    /** Maps the Room entity to a domain DTO at the data/domain boundary. */
    private fun PendingReview.toReviewPriorityInput() = ReviewPriorityInput(
        reviewId = id,
        confidence = confidence,
        suggestedMerchant = suggestedMerchant,
        suggestedCategoryId = suggestedCategoryId,
        suggestedAmount = suggestedAmount,
        createdAt = createdAt
    )
}
