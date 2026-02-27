package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import javax.inject.Inject

class CategorizeExpenseUseCase @Inject constructor(
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: MerchantNormalizer
) {
    suspend operator fun invoke(merchantName: String): CategoryResult {
        val normalized = merchantNormalizer.normalize(merchantName).canonical.normalizedName
        
        val result = categorizationEngine.categorize(normalized)
        
        return CategoryResult(
            merchantName = normalized,
            categoryId = result.categoryId,
            confidence = result.confidence.toFloat(),
            matchType = result.matchType.name,
            explanation = result.explanation
        )
    }
    
    suspend fun learnCategory(merchantName: String, categoryId: Long) {
        categorizationEngine.learnMerchantCategory(merchantName, categoryId)
    }
}

data class CategoryResult(
    val merchantName: String,
    val categoryId: Long?,
    val confidence: Float,
    val matchType: String? = null,
    val explanation: String? = null
)
