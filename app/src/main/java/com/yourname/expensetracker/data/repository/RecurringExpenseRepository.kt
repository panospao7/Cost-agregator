package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for recurring expense CRUD operations.
 *
 * All data-access delegation goes through [ManualRecurringExpenseDao].
 *
 * Future higher-level recurring expense workflows (auto-advance, lifecycle
 * management, notification scheduling, etc.) should be coordinated via the
 * [com.yourname.expensetracker.domain.logic.RecurringLifecycleCoordinator]
 * (to be created in a follow-up PR) rather than calling this repository
 * directly.
 */
@Singleton
class RecurringExpenseRepository @Inject constructor(
    private val dao: ManualRecurringExpenseDao
) {
    companion object {
        fun createRecurringExpenseEntity(
            merchant: String,
            amount: Double,
            frequency: RecurrenceFrequency,
            lastDate: Long,
            currency: String = "EUR",
            note: String? = null
        ): ManualRecurringExpense {
            val normalizedLastDate = RecurrenceCalculator.normalizeToDateOnly(lastDate)
            val nextDate = RecurrenceCalculator.calculateNextDate(normalizedLastDate, frequency)

            return ManualRecurringExpense(
                merchant = merchant,
                amount = amount,
                currency = currency,
                frequency = frequency,
                nextDate = nextDate,
                note = note ?: "Created from manual entry"
            )
        }
    }

    /**
     * Observe active recurring expenses only (isActive = 1).
     * B4: contract changed from all-rows to active-only.
     */
    fun getAllFlow(): Flow<List<ManualRecurringExpense>> = dao.getAllActiveFlow()

    /**
     * One-shot read of active recurring expenses only (isActive = 1).
     * B4: contract changed from all-rows to active-only.
     */
    suspend fun getAll(): List<ManualRecurringExpense> = dao.getAllActive()
    
    suspend fun getById(id: Long): ManualRecurringExpense? = dao.getById(id)

    suspend fun getByMerchant(merchant: String): ManualRecurringExpense? = dao.getByMerchant(merchant)

    suspend fun addRecurringExpense(
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency,
        lastDate: Long,
        currency: String = "EUR",
        note: String? = null
    ): Long {
        val expense = createRecurringExpenseEntity(
            merchant = merchant,
            amount = amount,
            frequency = frequency,
            lastDate = lastDate,
            currency = currency,
            note = note
        )
        return dao.insert(expense)
    }

    suspend fun insert(expense: ManualRecurringExpense) = dao.insert(expense)

    suspend fun delete(expense: ManualRecurringExpense) = dao.delete(expense)
    
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun update(expense: ManualRecurringExpense) = dao.update(expense)

}
