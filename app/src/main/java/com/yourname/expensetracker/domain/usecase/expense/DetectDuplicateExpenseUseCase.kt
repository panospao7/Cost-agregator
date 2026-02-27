package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
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
        val expenses = expenseRepository.getAllExpenses().first()
        
        val nearbyExpenses = expenses.filter { expense ->
            kotlin.math.abs(expense.date - date) < windowMs
        }
        
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
