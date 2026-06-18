package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MergedRecurringPatternsProvider @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val forecastInputAssembler: ForecastInputAssembler,
    private val timeProvider: TimeProvider
) {

    suspend fun getConfirmedPatterns(): List<RecurringPattern> {
        return getConfirmedPatterns(recurringExpenseRepository.getAll())
    }

    suspend fun getPatterns(): List<RecurringPattern> {
        val now = timeProvider.now()
        val twelveMonthsAgo = TimePeriodUtils.addMonths(now, -12)
        val expenseSnapshots = expenseRepository.getExpenseSnapshotsSince(twelveMonthsAgo)
        val manualRecurring = recurringExpenseRepository.getAll()
        return getPatternsFromSnapshots(expenseSnapshots, manualRecurring)
    }

    suspend fun getPatternsFromSnapshots(
        expenseSnapshots: List<ExpenseSnapshot>,
        manualRecurring: List<ManualRecurringExpense>
    ): List<RecurringPattern> {
        val detectedPatterns = recurringExpenseEngine.detectPatternsFromSnapshots(
            allExpenses = expenseSnapshots,
            excludedMerchantKeys = emptySet()
        )
        return forecastInputAssembler.mergeRecurringPatterns(
            manualEntities = manualRecurring,
            detectedPatterns = detectedPatterns
        )
    }

    fun getConfirmedPatterns(
        manualRecurring: List<ManualRecurringExpense>
    ): List<RecurringPattern> {
        return forecastInputAssembler
            .mapConfirmedRecurringPatterns(manualRecurring)
            .let(forecastInputAssembler::dedupeConfirmedRecurringPatterns)
            .sortedByDescending { it.confidence }
    }
}
