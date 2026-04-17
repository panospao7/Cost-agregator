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
     * Compute a unique signature for a recommendation.
     *
     * **Signature Components**:
     * - Navigation target (e.g., "TRANSACTION_LIST", "CATEGORY_DETAIL")
      * - Filter criteria hash (categoryId, merchantName, dateRange from JSON)
     *
     * **Examples**:
      * - High-Amount: `TRANSACTION_LIST:cat=5,minAmount=100.0`
      * - Category: `CATEGORY_DETAIL:cat=5,dateRange=1234567890-1234567899`
      * - Merchant: `TRANSACTION_LIST:merchant=Amazon`
      * - Recent: `TRANSACTION_LIST:dateRange=1234567890-1234567899`
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
            filter.minAmount?.let { filterParts.add("minAmount=$it") }
            filter.maxAmount?.let { filterParts.add("maxAmount=$it") }
            filter.dateRange?.let { (start, end) -> 
                filterParts.add("dateRange=$start-$end") 
            }
            filter.transactionType?.let { filterParts.add("type=${it.name}") }
            
            if (filterParts.isNotEmpty()) {
                parts.add(filterParts.joinToString(","))
            }
        }

        return parts.joinToString(":")
    }
}
