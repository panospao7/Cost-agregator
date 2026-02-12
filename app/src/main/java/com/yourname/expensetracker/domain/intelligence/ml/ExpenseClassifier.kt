package com.yourname.expensetracker.domain.intelligence.ml

/**
 * Match type for categorization results.
 */
enum class MatchType {
    RULE_MATCH,        // Keyword based
    HISTORY_MATCH,     // Previous user choice
    ML_PREDICTION,     // Naive Bayes prediction
    FALLBACK,          // Default category
    EXACT_MATCH,       // Exact string match (for normalization)
    ALIAS_MATCH,       // Known alias (for normalization)
    FUZZY_MATCH,       // Fuzzy/string similarity (for normalization)
    PARTIAL_MATCH,     // Substring match
    USER_DEFINED,      // User explicitly linked
    NEW_MERCHANT       // No match found
}

/**
 * Score for a specific category prediction.
 */
data class CategoryScore(
    val categoryId: Long,
    val categoryName: String,
    val score: Float
)

/**
 * Result of the classification process.
 */
data class ClassificationResult(
    val categoryId: Long,
    val categoryName: String,
    val confidence: Float,
    val alternatives: List<CategoryScore> = emptyList(),
    val matchType: MatchType
)

/**
 * Features extracted for classification.
 */
data class ExpenseFeatures(
    val merchantName: String,
    val merchantTokens: List<String>,
    val notificationTitle: String?,
    val notificationText: String?,
    val allText: String,
    val amount: Double,
    val amountBucket: AmountBucket,
    val dayOfWeek: Int, // 0 = Monday, 6 = Sunday
    val hourOfDay: Int,
    val isWeekend: Boolean,
    val sourcePackage: String
)

/**
 * Amount buckets for qualitative amount features.
 */
enum class AmountBucket {
    TINY,   // < 5
    SMALL,  // 5 - 20
    MEDIUM, // 20 - 50
    LARGE,  // 50 - 200
    HUGE;   // > 200

    companion object {
        fun fromAmount(amount: Double): AmountBucket {
            return when {
                amount < 5.0 -> TINY
                amount < 20.0 -> SMALL
                amount < 50.0 -> MEDIUM
                amount < 200.0 -> LARGE
                else -> HUGE
            }
        }
    }
}
