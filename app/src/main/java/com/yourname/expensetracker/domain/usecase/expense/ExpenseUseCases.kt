package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * HIGH FIX (HIGH-1): Example UseCase following Clean Architecture.
 * 
 * Demonstrates proper Clean Architecture flow:
 * ViewModel → UseCase → Repository → DAO
 * 
 * This replaces direct Repository access from ViewModels.
 * 
 * Benefits:
 * - Testability: Can mock UseCase, not Repository
 * - Reusability: Same business logic across multiple ViewModels
 * - Maintainability: Changes isolated to UseCase layer
 * - Single Responsibility: Each UseCase does one thing
 */
class GetExpensesBetweenDatesUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository
) {
    /**
     * Gets expenses within a date range.
     * 
     * @param startDate Start timestamp (inclusive)
     * @param endDate End timestamp (inclusive)
     * @return Flow of expenses for reactive UI updates
     */
    operator fun invoke(startDate: Long, endDate: Long): Flow<List<Expense>> {
        return expenseRepository.getExpensesBetweenFlow(startDate, endDate)
    }
}

/**
 * UseCase to get expense statistics for a period.
 * Combines multiple repository calls into single business operation.
 */
class GetExpenseStatisticsUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
) {
    data class Statistics(
        val totalSpent: Double,
        val transactionCount: Int,
        val averageTransaction: Double,
        val largestExpense: Expense?
    )
    
    suspend operator fun invoke(startDate: Long, endDate: Long): Statistics {
        val expenses = expenseRepository.getExpensesBetween(startDate, endDate)
        
        if (expenses.isEmpty()) {
            return Statistics(0.0, 0, 0.0, null)
        }
        
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }
        val normalized = runCatching {
            analyticsCurrencyNormalizer.normalizeExpenses(expenses, homeCurrency)
        }.getOrNull()
        val normalizedAmountById = normalized?.includedExpenses?.associateBy { it.id }
            ?: emptyMap()
        
        // SAFE: normalized via AnalyticsCurrencyNormalizer before summing
        val total = expenses.sumOf {
            normalizedAmountById[it.id]?.effectiveAmount ?: it.effectiveAmount
        }
        val count = expenses.size
        val average = total / count
        val largest = expenses.maxByOrNull {
            normalizedAmountById[it.id]?.effectiveAmount ?: it.effectiveAmount
        }
        
        return Statistics(total, count, average, largest)
    }
}

/**
 * UseCase to mark an expense as reviewed/confirmed.
 * Encapsulates the business rule of what "reviewing" means.
 */
class ReviewExpenseUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository
) {
    sealed class Result {
        object Success : Result()
        data class Error(val message: String) : Result()
    }
    
    suspend operator fun invoke(expenseId: Long, categoryId: Long?): Result {
        return try {
            expenseRepository.updateExpenseCategory(expenseId, categoryId)
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }
}
