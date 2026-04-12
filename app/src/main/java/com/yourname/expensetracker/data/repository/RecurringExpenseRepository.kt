package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecurringExpenseRepository @Inject constructor(
    private val dao: RecurringExpenseDao
) {
    /**
     * Observe active recurring expenses only (isActive = 1).
     * B4: contract changed from all-rows to active-only.
     */
    @Suppress("DEPRECATION")
    fun getAllFlow(): Flow<List<ManualRecurringExpense>> = dao.getAllActiveFlow()

    /**
     * One-shot read of active recurring expenses only (isActive = 1).
     * B4: contract changed from all-rows to active-only.
     */
    @Suppress("DEPRECATION")
    suspend fun getAll(): List<ManualRecurringExpense> = dao.getAllActive()
    
    @Suppress("DEPRECATION")
    suspend fun getById(id: Long): ManualRecurringExpense? = dao.getById(id)

    @Suppress("DEPRECATION")
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense? = dao.getByMerchant(merchant)

    @Suppress("DEPRECATION")
    suspend fun addRecurringExpense(
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency,
        lastDate: Long,
        currency: String = "EUR",
        note: String? = null
    ): Long {
        val nextDate = calculateNextDate(lastDate, frequency)
        
        val expense = ManualRecurringExpense(
            merchant = merchant,
            amount = amount,
            currency = currency,
            frequency = frequency,
            nextDate = nextDate,
            note = note ?: "Created from manual entry"
        )
        return dao.insert(expense)
    }

    @Suppress("DEPRECATION")
    suspend fun insert(expense: ManualRecurringExpense) = dao.insert(expense)

    @Suppress("DEPRECATION")
    suspend fun delete(expense: ManualRecurringExpense) = dao.delete(expense)
    
    @Suppress("DEPRECATION")
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    @Suppress("DEPRECATION")
    suspend fun update(expense: ManualRecurringExpense) = dao.update(expense)

    private fun calculateNextDate(lastDate: Long, frequency: RecurrenceFrequency): Long {
        val lastLocalDate = Instant.ofEpochMilli(lastDate)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

        val nextLocalDate = when (frequency) {
            RecurrenceFrequency.WEEKLY -> lastLocalDate.plusWeeks(1)
            RecurrenceFrequency.BIWEEKLY -> lastLocalDate.plusWeeks(2)
            RecurrenceFrequency.MONTHLY -> lastLocalDate.plusMonths(1)
            RecurrenceFrequency.QUARTERLY -> lastLocalDate.plusMonths(3)
            RecurrenceFrequency.SEMI_ANNUALLY -> lastLocalDate.plusMonths(6)
            RecurrenceFrequency.ANNUALLY -> lastLocalDate.plusYears(1)
            RecurrenceFrequency.IRREGULAR -> lastLocalDate 
            else -> lastLocalDate.plusDays(frequency.days.toLong())
        }

        return nextLocalDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
