package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid Expense Classifier for CATEGORIZATION.
 * Strategy priority: Rule-based -> History (TBD) -> ML prediction.
 */
@Singleton
class HybridExpenseClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryRepository: CategoryRepository,
    private val nbClassifier: ExpenseCategoryClassifier
) {
    companion object {
        private const val TAG = "HybridClassifier"
        val RULE_CONFIDENCE = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RULE_BASED
        val ML_THRESHOLD = com.yourname.expensetracker.domain.util.AppConstants.Confidence.ML_PREDICTION
        
        private val CATEGORY_KEYWORDS: Map<String, String> = mapOf(
            "mcdonalds" to "Food", "starbucks" to "Food", "pizza" to "Food",
            "restaurant" to "Food", "cafe" to "Food", "coffee" to "Food",
            "supermarket" to "Groceries", "lidl" to "Groceries", "sklavenitis" to "Groceries",
            "βασιλόπουλος" to "Groceries", "σκλαβενίτης" to "Groceries", "μασούτης" to "Groceries",
            "γαλαξίας" to "Groceries", "κρητικός" to "Groceries", "φούρνος" to "Groceries",
            "uber" to "Transport", "taxi" to "Transport", "bolt" to "Transport",
            "fuel" to "Transport", "gas" to "Transport", "shell" to "Transport", "bp" to "Transport",
            "amazon" to "Shopping", "netflix" to "Entertainment", "spotify" to "Entertainment"
        )
    }

    private val featureExtractor = FeatureExtractor()
    private var categories: List<Category> = emptyList()
    private var categoryMap: Map<String, Category> = emptyMap()

    suspend fun initialize() {
        categories = categoryRepository.getAll()
        categoryMap = categories.associateBy { it.name.lowercase() }
    }

    suspend fun classify(
        merchantName: String,
        amount: Double,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ClassificationResult = withContext(Dispatchers.Default) {
        
        if (categories.isEmpty()) initialize()
        
        val features = featureExtractor.extractFromNotification(
            title = notificationTitle,
            text = notificationText,
            packageName = packageName,
            amount = amount,
            merchant = merchantName
        )

        // 1. Rules
        val ruleResult = classifyWithRules(features)
        if (ruleResult != null && ruleResult.confidence >= RULE_CONFIDENCE) {
            return@withContext ruleResult
        }

        // 2. ML Prediction
        if (nbClassifier.isReady()) {
            val mlResults = nbClassifier.classify(features)
            if (mlResults.isNotEmpty()) {
                val best = mlResults.first()
                if (best.score >= ML_THRESHOLD) {
                    val category = categories.find { it.id == best.categoryId }
                    return@withContext ClassificationResult(
                        categoryId = best.categoryId,
                        categoryName = category?.name ?: "Unknown",
                        confidence = best.score,
                        alternatives = mlResults.take(3).map { res ->
                            res.copy(categoryName = categories.find { it.id == res.categoryId }?.name ?: "Unknown")
                        },
                        matchType = MatchType.ML_PREDICTION
                    )
                }
            }
        }

        // 3. Fallback (Improved for BUG-012)
        val defaultCategory = categories.find { it.name.equals("Uncategorized", ignoreCase = true) }
            ?: categories.find { it.name.contains("Other", ignoreCase = true) }
            ?: categories.firstOrNull()

        ClassificationResult(
            categoryId = defaultCategory?.id ?: -1,
            categoryName = defaultCategory?.name ?: "Uncategorized",
            confidence = 0.0f,
            matchType = MatchType.FALLBACK
        )
    }

    private fun classifyWithRules(features: ExpenseFeatures): ClassificationResult? {
        val tokens = features.merchantTokens.map { it.lowercase() }
        for (token in tokens) {
            val catName = CATEGORY_KEYWORDS[token]
            if (catName != null) {
                val category = categoryMap[catName.lowercase()]
                if (category != null) {
                    return ClassificationResult(
                        categoryId = category.id,
                        categoryName = category.name,
                        confidence = 0.98f,
                        matchType = MatchType.RULE_MATCH
                    )
                }
            }
        }
        return null
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
            merchant = merchantName
        )
        nbClassifier.train(features, correctCategoryId)
    }
}
