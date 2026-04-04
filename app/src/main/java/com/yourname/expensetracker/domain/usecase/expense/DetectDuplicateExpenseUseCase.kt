package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import javax.inject.Inject

class DetectDuplicateExpenseUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val userCorrectionRepository: UserCorrectionRepository,
    private val crossSourceDeduplication: CrossSourceDeduplication
) {
    suspend operator fun invoke(
        amount: Double,
        merchant: String,
        date: Long,
        windowMs: Long = 300_000
    ): DuplicateCheckResult {
        val startDate = date - windowMs
        val endDateExclusive = date + windowMs + 1
        val targetMerchantKey = MerchantKeyGenerator.generate(merchant)

        val nearbyExpenses = expenseRepository.getExpensesBetween(startDate, endDateExclusive)
            .asSequence()
            // Optional pre-filter: amount band for faster candidate pruning
            .filter { expense -> kotlin.math.abs(expense.amount - amount) <= 0.01 }
            // Optional pre-filter: canonical merchant key if available on both rows
            .filter { expense ->
                val existingKey = expense.merchantKey
                existingKey == null || existingKey == targetMerchantKey
            }
            .toList()
        
        val duplicate = crossSourceDeduplication.findExpenseDuplicate(
            amount = amount,
            merchant = merchant,
            date = date,
            expenses = nearbyExpenses,
            timeWindowMs = windowMs
        )
        
        return if (duplicate != null) {
            DuplicateCheckResult.Duplicate(duplicate)
        } else {
            DuplicateCheckResult.None
        }
    }
}

sealed class DuplicateCheckResult {
    data class Duplicate(val existingExpense: Expense) : DuplicateCheckResult()
    object None : DuplicateCheckResult()
}
