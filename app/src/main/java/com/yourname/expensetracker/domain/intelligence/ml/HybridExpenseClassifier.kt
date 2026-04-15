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
    private var categories: List<Category> = emptyList()
    private var categoryMap: Map<String, Category> = emptyMap()

    suspend fun initialize() {
        initMutex.withLock {
            if (!initialized) {
                categories = categoryRepository.getAll()
                categoryMap = categories.associateBy { it.name.lowercase() }
                initialized = true
            }
        }
    }

    suspend fun classify(
        merchantName: String,
        amount: Double,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ClassificationResult = withContext(Dispatchers.Default) {
        
        if (!initialized) initialize()

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
            eventTimeMillis = timeProvider.now()
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
        try {
            val mlResults = nbClassifier.classify(features)
            if (mlResults.isNotEmpty()) {
                val best = mlResults.first()
                // Use > for strict boundary; >= ensures exactly-at-threshold is accepted
                if (best.score >= ML_THRESHOLD) {
                    val category = categories.find { it.id == best.categoryId }
                    return@withContext ClassificationResult(
                        categoryId = best.categoryId,
                        categoryName = category?.name ?: "Unknown",
                        confidence = best.score.coerceIn(0.0f, 1.0f),
                        alternatives = mlResults.take(3).map { res ->
                            res.copy(
                                categoryName = categories.find { it.id == res.categoryId }?.name ?: "Unknown",
                                score = res.score.coerceIn(0.0f, 1.0f)
                            )
                        },
                        matchType = MatchType.ML_PREDICTION
                    )
                }
            }
        } catch (e: Exception) {
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
        
        if (result.categoryId != null) {
            val category = categories.find { it.id == result.categoryId }
            if (category != null) {
                return ClassificationResult(
                    categoryId = category.id,
                    categoryName = category.name,
                    confidence = result.confidence.toFloat().coerceIn(0.0f, 1.0f),
                    matchType = MatchType.RULE_MATCH
                )
            }
        }
        return null
    }

    private fun fallbackResult(): ClassificationResult {
        val defaultCategory = categories.find { it.name.equals("Uncategorized", ignoreCase = true) }
            ?: categories.find { it.name.contains("Other", ignoreCase = true) }
            ?: categories.firstOrNull()
        return ClassificationResult(
            categoryId = defaultCategory?.id ?: -1,
            categoryName = defaultCategory?.name ?: "Uncategorized",
            confidence = 0.0f,
            matchType = MatchType.FALLBACK
        )
    }
    
    // Keep for backward compatibility during migration
    @Suppress("UnusedPrivateMember")
    private fun classifyWithRules(features: ExpenseFeatures): ClassificationResult? {
        return null // No longer used - replaced by classifyWithMerchantDictionary
    }

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
        
        // Also learn in CategorizationEngine for future dictionary lookups
        categorizationEngine.learnMerchantCategory(merchantName, correctCategoryId)
    }
}
