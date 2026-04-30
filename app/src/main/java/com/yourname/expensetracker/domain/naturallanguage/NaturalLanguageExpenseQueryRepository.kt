package com.yourname.expensetracker.domain.naturallanguage

data class NaturalLanguageExpense(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val currency: String,
    val merchant: String,
    val date: Long,
    val categoryId: Long?
)

interface NaturalLanguageExpenseQueryRepository {
    suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense>
}
