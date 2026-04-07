package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import com.yourname.expensetracker.domain.dto.CategoryRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceReceiptItemCategorizationService @Inject constructor() : ReceiptItemCategorizationService {
    
    // Simple keyword-based categorization for on-device fallback
    private val categoryKeywords = mapOf(
        "Food" to listOf("apple", "banana", "bread", "milk", "cheese", "meat", "vegetable", "fruit", "groceries", "food"),
        "Household" to listOf("detergent", "soap", "toilet", "paper", "cleaning", "household"),
        "Shopping" to listOf("shirt", "shoes", "clothes", "shopping", "store", "nike", "adidas", "apparel"),
        "Electronics" to listOf("phone", "laptop", "computer", "electronic", "cable", "charger"),
        "Transport" to listOf("fuel", "gas", "parking", "transport", "bus", "train", "metro"),
        "Entertainment" to listOf("movie", "cinema", "game", "ticket", "entertainment"),
        "Health" to listOf("pharmacy", "medicine", "doctor", "health", "medical", "vitamin"),
        "Dining" to listOf("restaurant", "cafe", "coffee", "lunch", "dinner", "breakfast")
    )
    
    override suspend fun categorizeItems(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? {
        return withContext(Dispatchers.Default) {
            try {
                val categorizedItems = input.lineItems.map { item ->
                    categorizeSingleItem(item.description, item.totalPrice, input.userCategories)
                }
                
                // Calculate tax distribution proportionally
                val taxDistribution = if (input.totalTax != null && input.totalTax > 0) {
                    calculateTaxDistribution(input.totalTax, categorizedItems)
                } else emptyMap()
                
                val avgConfidence = categorizedItems.map { it.confidence }.average().toFloat()
                
                ReceiptItemCategorizationResult(
                    items = categorizedItems,
                    totalConfidence = avgConfidence,
                    needsReview = categorizedItems.any { it.needsReview },
                    suggestedNewCategories = emptyList(),
                    taxDistribution = taxDistribution
                )
            } catch (e: Exception) {
                Timber.e(e, "Error in on-device item categorization")
                null
            }
        }
    }
    
    private fun categorizeSingleItem(
        description: String,
        amount: Double,
        userCategories: List<CategoryRef>
    ): CategorizedReceiptItem {
        val normalizedDesc = description.lowercase()
        
        // Find best matching category
        var bestMatch: Pair<CategoryRef, Float>? = null
        var bestScore = 0f
        
        for (category in userCategories) {
            val score = calculateMatchScore(normalizedDesc, category.name)
            if (score > bestScore) {
                bestScore = score
                bestMatch = category to score
            }
        }
        
        // If no good match found, try keyword matching
        if (bestScore < 0.3f) {
            for ((categoryName, keywords) in categoryKeywords) {
                val matchingCategory = userCategories.find { 
                    it.name.equals(categoryName, ignoreCase = true) 
                }
                
                if (matchingCategory != null) {
                    val keywordScore = calculateKeywordScore(normalizedDesc, keywords)
                    if (keywordScore > bestScore) {
                        bestScore = keywordScore
                        bestMatch = matchingCategory to keywordScore
                    }
                }
            }
        }
        
        // Create suggestions
        val alternatives = if (bestScore < 0.7f) {
            userCategories
                .filter { it.id != bestMatch?.first?.id }
                .take(2)
                .map { ref ->
                    CategorySuggestion(
                        categoryId = ref.id,
                        categoryName = ref.name,
                        confidence = 0.4f
                    )
                }
        } else emptyList()
        
        val confidence = bestScore.coerceIn(0.4f, 0.85f) // Cap at 0.85 for on-device
        
        return CategorizedReceiptItem(
            itemDescription = description,
            amount = amount,
            suggestedCategory = bestMatch?.let { (ref, _) ->
                CategorySuggestion(
                    categoryId = ref.id,
                    categoryName = ref.name,
                    confidence = confidence,
                    isNewCategorySuggestion = false
                )
            } ?: CategorySuggestion(
                categoryId = userCategories.firstOrNull()?.id,
                categoryName = userCategories.firstOrNull()?.name ?: "Uncategorized",
                confidence = 0.4f
            ),
            confidence = confidence,
            rationale = generateRationale(description, bestMatch?.first?.name),
            alternatives = alternatives,
            needsReview = confidence < 0.7f
        )
    }
    
    private fun calculateMatchScore(description: String, categoryName: String): Float {
        val normalizedCat = categoryName.lowercase()
        
        // Direct substring match
        if (description.contains(normalizedCat)) {
            return 0.8f
        }
        
        // Word overlap
        val descWords = description.split(" ", ",", "-", ".")
        val catWords = normalizedCat.split(" ")
        
        val overlap = descWords.intersect(catWords.toSet()).size
        val score = overlap.toFloat() / catWords.size.coerceAtLeast(1)
        
        return score.coerceIn(0.3f, 0.7f)
    }
    
    private fun calculateKeywordScore(description: String, keywords: List<String>): Float {
        var matches = 0
        for (keyword in keywords) {
            if (description.contains(keyword)) {
                matches++
            }
        }
        
        return if (matches > 0) {
            (0.5f + (matches * 0.1f)).coerceAtMost(0.8f)
        } else 0f
    }
    
    private fun generateRationale(description: String, categoryName: String?): String {
        return when {
            categoryName != null -> "Matched to '$categoryName' based on keywords in '$description'"
            else -> "Uncertain match - please review"
        }
    }
    
    private fun calculateTaxDistribution(
        totalTax: Double,
        items: List<CategorizedReceiptItem>
    ): Map<Long, Double> {
        val totalAmount = items.sumOf { it.amount }
        if (totalAmount <= 0) return emptyMap()
        
        return items.groupBy { it.suggestedCategory?.categoryId ?: -1 }
            .mapValues { (_, itemsInCategory) ->
                val categoryTotal = itemsInCategory.sumOf { it.amount }
                val proportion = categoryTotal / totalAmount
                totalTax * proportion
            }
            .filter { it.key > 0 }
    }
}
