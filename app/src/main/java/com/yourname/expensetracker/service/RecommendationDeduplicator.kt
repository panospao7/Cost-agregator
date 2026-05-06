package com.yourname.expensetracker.service

import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deduplicates recommendation cards to prevent showing multiple cards for
 * the same merchant, category, or analysis target.
 *
 * **Strategy**: Signature-based deduplication using navigation target + filter criteria hash.
 *
 * **Used by**: [com.yourname.expensetracker.data.repository.RecommendationRepository.saveAll]
 * before inserting recommendations into the database.
 *
 * **Phase 3A**: Adaptive Improvements - Recommendation Deduplication
 */
@Singleton
class RecommendationDeduplicator @Inject constructor(
    private val filterSerializer: TransactionFilterSerializer
) {

    /**
     * Remove duplicate recommendations from the list.
     *
     * **Algorithm**:
     * 1. Compute signature for each recommendation (navTarget + filterCriteria)
     * 2. Keep first occurrence of each signature (preserves priority order)
     * 3. Log deduplications for observability
     *
     * @param recommendations List of recommendations to deduplicate
     * @return Deduplicated list with only unique signatures
     */
    fun deduplicate(recommendations: List<DashboardFollowThroughRecommendation>): List<DashboardFollowThroughRecommendation> {
        if (recommendations.isEmpty()) return recommendations

        val seenSignatures = mutableSetOf<String>()
        val deduplicated = mutableListOf<DashboardFollowThroughRecommendation>()
        var duplicateCount = 0

        for (rec in recommendations) {
            val signature = computeSignature(rec)
            if (signature !in seenSignatures) {
                seenSignatures.add(signature)
                deduplicated.add(rec)
            } else {
                duplicateCount++
                Timber.d("Deduplication: Skipped duplicate recommendation [navTarget=${rec.navigationTarget}, signature=$signature]")
            }
        }

        if (duplicateCount > 0) {
            Timber.i("Deduplication: Removed $duplicateCount duplicate(s), kept ${deduplicated.size} unique recommendation(s)")
        }

        return deduplicated
    }

    /**
     * Compute a unique semantic signature for a recommendation.
     *
     * ## AIML-21: Recommendation dedupe includes raw timestamps
     * Raw timestamps in the dateRange cause the same logical recommendation
     * (e.g. "review this week's spending") to have different signatures when
     * generated at different times. We now map raw timestamps to semantic labels:
     *   - 0-1 day span  → "today"
     *   - 2-7 day span  → "this_week"
     *   - 8-31 day span → "this_month"
     *   - 32-93 day span → "this_quarter"
     *   - otherwise     → "custom"
     *
     * **Signature Components**:
     * - Navigation target (e.g., "TRANSACTION_LIST", "CATEGORY_DETAIL")
     * - Filter criteria hash (categoryId, merchantName, semantic dateRange)
     *
     * @param rec Recommendation to compute signature for
     * @return String signature uniquely identifying the recommendation target
     */
    fun computeSignature(rec: DashboardFollowThroughRecommendation): String {
        val parts = mutableListOf<String>()

        // 1. Navigation target (primary key)
        parts.add(rec.navigationTarget)

        // 2. Filter criteria signature (extract key fields from JSON)
        val filter = filterSerializer.deserialize(rec.filterCriteria)
        if (filter != null) {
            val filterParts = mutableListOf<String>()
            
            filter.categoryId?.let { filterParts.add("cat=$it") }
            filter.merchantName?.let { filterParts.add("merchant=$it") }
            filter.ownership?.let { filterParts.add("ownership=${it.name}") }
            filter.minAmount?.let { filterParts.add("minAmount=$it") }
            filter.maxAmount?.let { filterParts.add("maxAmount=$it") }
            // AIML-21: Use semantic date range labels instead of raw timestamps
            filter.dateRange?.let { (start, end) ->
                val spanDays = ((end - start) / 86_400_000L).coerceAtLeast(0)
                val label = when {
                    spanDays <= 1 -> "today"
                    spanDays <= 7 -> "this_week"
                    spanDays <= 31 -> "this_month"
                    spanDays <= 93 -> "this_quarter"
                    else -> "custom"
                }
                filterParts.add("dateRange=$label")
            }
            filter.transactionType?.let { filterParts.add("type=${it.name}") }
            
            if (filterParts.isNotEmpty()) {
                parts.add(filterParts.joinToString(","))
            }
        }

        return parts.joinToString(":")
    }
}
