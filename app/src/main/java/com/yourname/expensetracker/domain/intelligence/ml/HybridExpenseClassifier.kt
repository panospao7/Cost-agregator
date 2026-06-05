package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid Expense Classifier for CATEGORIZATION.
 * Strategy priority: Merchant Dictionary -> ML prediction -> Fallback.
 * Uses CategorizationEngine as single source of truth for merchant->category mapping.
 *
 * ## AIML-18: Full feature set for category classifier (planned)
 * The current feature vector is limited to merchant tokens extracted from the
 * notification title/text. The following additional features are planned to
 * improve classification accuracy:
 * - **Amount ranges**: Bucket the transaction amount into tiers (e.g. <5, 5-20,
 *   20-50, 50-200, 200+) — different categories have distinct spend profiles.
 * - **Day-of-week**: Many expenses are category-specific on weekends vs. weekdays
 *   (e.g. dining/entertainment on weekends, office supplies on weekdays).
 * - **Time-of-day**: Morning vs. afternoon vs. evening patterns (e.g. coffee in
 *   the morning, dinner in the evening).
 * - **Seasonal patterns**: Certain categories spike at predictable times (holiday
 *   shopping in December, travel in summer, tax payments in April).
 * - **Merchant-key prefix matching**: Leverage the existing [merchantKey] field
 *   as a bag-of-words feature alongside raw tokens.
 *
 * The [FeatureExtractor] class should be extended to include these dimensions
 * without increasing the model file size significantly. The NB classifier's
 * `expense_category_model.json` schema would gain new feature namespaced keys
 * (e.g. `amount_range:50_200`, `dow:6`) that do not conflict with merchant tokens.
 */
@Singleton
class HybridExpenseClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryRepository: CategoryRepository,
    private val categorizationEngine: CategorizationEngine,
    private val nbClassifier: ExpenseCategoryClassifier,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "HybridClassifier"
        val RULE_CONFIDENCE = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RULE_BASED
        val ML_THRESHOLD = com.yourname.expensetracker.domain.util.AppConstants.Confidence.ML_PREDICTION
    }

    private val featureExtractor = FeatureExtractor()
    private val initMutex = Mutex()
    private var initialized = false
    @Volatile
    private var categorySnapshot: List<Category> = emptyList()

    suspend fun initialize() {
        initMutex.withLock {
            if (!initialized) {
                refreshCategorySnapshot()
                initialized = true
            }
        }
    }

    suspend fun invalidateCategorySnapshot() {
        initMutex.withLock {
            categorySnapshot = categoryRepository.getAll()
            initialized = true
        }
    }

    /**
     * @param eventTimeMillis Explicit event timestamp in millis since epoch.
     *   AIML-19: Must be provided from the notification/transaction timestamp,
     *   NOT from [timeProvider.now()]. Use 0L to signal "unknown" (in which
     *   case the current time is used as fallback).
     */
    suspend fun classify(
        merchantName: String,
        amount: Double,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = "",
        eventTimeMillis: Long = 0L
    ): ClassificationResult = withContext(Dispatchers.Default) {
        
        if (!initialized) initialize()
        val categories = currentCategories()

        val merchantNormalized = merchantName.trim()
        if (merchantNormalized.isBlank() &&
            notificationTitle.isNullOrBlank() &&
            notificationText.isNullOrBlank()
        ) {
            return@withContext fallbackResult()
        }
        
        val features = featureExtractor.extractFromNotification(
            title = notificationTitle,
            text = notificationText,
            packageName = packageName,
            amount = amount,
            merchant = merchantNormalized,
            eventTimeMillis = if (eventTimeMillis > 0L) eventTimeMillis else timeProvider.now()
        )

        // 1. Merchant Dictionary (single source of truth)
        val dictionaryResult = classifyWithMerchantDictionary(merchantNormalized)
        if (dictionaryResult != null) {
            return@withContext dictionaryResult
        }

        // 2. ML Prediction — the classifier itself decides whether it has
        //    enough data (including persisted on-disk state loaded at cold start).
        //    No external isReady() gate: classify() returns an empty list when
        //    the model is not usable, preserving fallback semantics.
        //
        //    AIML-17: Validate returned categoryId against active categories before
        //    using it. If the ML model returns a stale/deleted category ID, skip it
        //    and fall through to the next classification strategy.
        try {
            val mlResults = nbClassifier.classify(features)
            if (mlResults.isNotEmpty()) {
                val best = mlResults.first()
                // Use > for strict boundary; >= ensures exactly-at-threshold is accepted
                if (best.score >= ML_THRESHOLD) {
                    // AIML-17: Validate against active categories — skip if stale/deleted
                    val category = categories.find { it.id == best.categoryId }
                    if (category != null) {
                        return@withContext ClassificationResult(
                            categoryId = best.categoryId,
                            categoryName = category.name,
                            confidence = best.score.coerceIn(0.0f, 1.0f),
                            alternatives = mlResults.take(3).mapNotNull { res ->
                                val altCat = categories.find { it.id == res.categoryId } ?: return@mapNotNull null
                                res.copy(
                                    categoryName = altCat.name,
                                    score = res.score.coerceIn(0.0f, 1.0f)
                                )
                            },
                            matchType = MatchType.ML_PREDICTION
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // E3-NOW-002: Coroutine cancellation must propagate, never swallow
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.tag(TAG).w(e, "ML classifier failed, using fallback")
        }

        // 3. Fallback (dictionary miss, ML unavailable, or model not yet trained)
        fallbackResult()
    }

    /**
     * Uses CategorizationEngine (merchant dictionary) for categorization.
     * This is the single source of truth for merchant->category mapping.
     */
    private suspend fun classifyWithMerchantDictionary(merchantName: String): ClassificationResult? {
        val result = categorizationEngine.categorize(merchantName)
        val categories = currentCategories()
        
        if (result.categoryId != null) {
            val category = categories.find { it.id == result.categoryId }
            if (category != null) {
                return ClassificationResult(
                    categoryId = category.id,
                    categoryName = category.name,
                    confidence = result.confidence.toFloat().coerceIn(0.0f, 1.0f),
                    matchType = MatchType.RULE_MATCH,
                    isAmbiguous = result.isAmbiguous,
                    requiresReview = result.requiresReview,
                    classificationReason = result.explanation
                )
            }
        }
        return null
    }

    private fun fallbackResult(): ClassificationResult {
        val categories = categorySnapshot
        val defaultCategory = categories.find { it.name.equals("Uncategorized", ignoreCase = true) }
            ?: categories.find { it.name.contains("Other", ignoreCase = true) }
            ?: categories.firstOrNull()
        return ClassificationResult(
            categoryId = defaultCategory?.id ?: -1,
            categoryName = defaultCategory?.name ?: "Uncategorized",
            confidence = 0.0f,
            matchType = MatchType.FALLBACK,
            isAmbiguous = false,
            requiresReview = false,
            classificationReason = "No match found"
        )
    }
    
    // Keep for backward compatibility during migration
    @Suppress("UnusedPrivateMember")
    private fun classifyWithRules(features: ExpenseFeatures): ClassificationResult? {
        return null // No longer used - replaced by classifyWithMerchantDictionary
    }

    /**
     * Learn from a user correction.
     *
     * ## AIML-20: Single correction triggers global category learning
     * To prevent a single low-confidence correction from permanently altering the
     * global merchant->category mapping, this method only calls
     * [categorizationEngine.learnMerchantCategory] when there is sufficient evidence:
     * - The merchant already has an existing mapping AND the correction agrees with it
     *   (reinforcement), OR
     * - The merchant has NO existing mapping (first-time learning).
     *
     * If the merchant already has a different high-confidence mapping, the single
     * correction is NOT promoted to the global dictionary. The NB classifier still
     * learns from it for local re-classification.
     */
    suspend fun learnFromCorrection(
        merchantName: String,
        correctCategoryId: Long,
        amount: Double = 0.0,
        packageName: String = ""
    ) {
        val features = featureExtractor.extractFromNotification(
            title = null,
            text = null,
            packageName = packageName,
            amount = amount,
            merchant = merchantName,
            eventTimeMillis = timeProvider.now()
        )
        nbClassifier.train(features, correctCategoryId)
        
        // AIML-20: Confidence-based learning gate — only promote to global dictionary
        // if the merchant has no existing mapping, or the correction agrees with it.
        val existingResult = categorizationEngine.categorize(merchantName)
        val shouldLearnGlobally = when {
            existingResult.categoryId == null -> true       // No existing mapping
            existingResult.categoryId == correctCategoryId -> true  // Reinforcement
            existingResult.matchType == com.yourname.expensetracker.domain.categorization.MatchType.UNKNOWN -> true  // First-time learning only
            else -> false
        }
        // TODO (C10): Require at least 2 confirming corrections to override a disagreeing weak mapping.
        
        if (shouldLearnGlobally) {
            categorizationEngine.learnMerchantCategory(merchantName, correctCategoryId)
        }
        invalidateCategorySnapshot()
    }

    private suspend fun refreshCategorySnapshot() {
        categorySnapshot = categoryRepository.getAll()
    }

    private suspend fun currentCategories(): List<Category> {
        val latest = categoryRepository.getAll()
        categorySnapshot = latest
        return latest
    }
}
