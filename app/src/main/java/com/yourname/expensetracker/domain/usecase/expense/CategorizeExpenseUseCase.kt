package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import javax.inject.Inject

class CategorizeExpenseUseCase @Inject constructor(
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: MerchantNormalizer
) {
    suspend operator fun invoke(merchantName: String): CategoryResult {
        val normalized = merchantNormalizer.normalize(merchantName).canonical.normalizedName
        
        val categoryId = categorizationEngine.categorize(normalized)
        
        return CategoryResult(
            merchantName = normalized,
            categoryId = categoryId,
            confidence = if (categoryId != null) 0.8f else 0.0f
        )
    }
    
    suspend fun learnCategory(merchantName: String, categoryId: Long) {
        categorizationEngine.learnMerchantCategory(merchantName, categoryId)
    }
}

data class CategoryResult(
    val merchantName: String,
    val categoryId: Long?,
    val confidence: Float
)
