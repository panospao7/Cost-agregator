package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import javax.inject.Inject

class DetectDuplicateExpenseUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val userCorrectionRepository: UserCorrectionRepository,
    private val crossSourceDeduplication: CrossSourceDeduplication
) {
    companion object {
        private const val AUTO_WINDOW_MS = -1L
    }

    enum class DuplicateDetectionSource(val defaultWindowMs: Long) {
        NOTIFICATION(defaultWindowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS),
        MANUAL_ENTRY(defaultWindowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS),
        STATEMENT_IMPORT(defaultWindowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS),
        OCR_IMPORT(defaultWindowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS),
        UNKNOWN(defaultWindowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS)
    }

    /**
     * Check for duplicate expenses.
     *
     * @param amount          transaction amount
     * @param merchant        merchant display name
     * @param date            event timestamp (epoch ms)
     * @param currency        ISO-4217 currency code; **must be provided explicitly** for blocking
     *                        duplicate checks — callers that genuinely do not know the currency
     *                        should pass [DuplicateDetectionPolicy.DEFAULT_CURRENCY] rather than
     *                        null so that the candidate query is always currency-aware.
     * @param transactionType transaction type for type-compatible filtering
     * @param windowMs        override window (-1 = use source default)
     * @param source          ingestion source (determines default window)
     */
    suspend operator fun invoke(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String,
        transactionType: TransactionType = TransactionType.UNKNOWN,
        windowMs: Long = AUTO_WINDOW_MS,
        source: DuplicateDetectionSource = DuplicateDetectionSource.UNKNOWN
    ): DuplicateCheckResult {
        val effectiveWindowMs = if (windowMs == AUTO_WINDOW_MS) {
            source.defaultWindowMs
        } else {
            windowMs.coerceAtLeast(0L)
        }

        val nearbyExpenses = expenseRepository.getDuplicateCandidatesInWindow(
            amount = amount,
            date = date,
            currency = currency,
            transactionType = transactionType,
            windowMs = effectiveWindowMs
        )

        val duplicate = crossSourceDeduplication.findExpenseDuplicate(
            amount = amount,
            merchant = merchant,
            date = date,
            expenses = nearbyExpenses,
            currency = currency,
            transactionType = transactionType,
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
