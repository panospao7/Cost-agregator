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
    companion object {
        private const val AUTO_WINDOW_MS = -1L
        private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L
    }

    enum class DuplicateDetectionSource(val defaultWindowMs: Long) {
        NOTIFICATION(defaultWindowMs = FIVE_MINUTES_MS),
        MANUAL_ENTRY(defaultWindowMs = TWENTY_FOUR_HOURS_MS),
        STATEMENT_IMPORT(defaultWindowMs = TWENTY_FOUR_HOURS_MS),
        OCR_IMPORT(defaultWindowMs = TWENTY_FOUR_HOURS_MS),
        UNKNOWN(defaultWindowMs = TWENTY_FOUR_HOURS_MS)
    }

    suspend operator fun invoke(
        amount: Double,
        merchant: String,
        date: Long,
        windowMs: Long = AUTO_WINDOW_MS,
        source: DuplicateDetectionSource = DuplicateDetectionSource.UNKNOWN
    ): DuplicateCheckResult {
        val effectiveWindowMs = if (windowMs == AUTO_WINDOW_MS) {
            source.defaultWindowMs
        } else {
            windowMs.coerceAtLeast(0L)
        }

        val startDate = date - effectiveWindowMs
        val endDateExclusive = date + effectiveWindowMs + 1
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
            timeWindowMs = effectiveWindowMs
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
