package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class ManualRecurringExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: ManualRecurringExpenseDao,
    private val lifecycleEventDao: com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
    private val ruleLifecycleCoordinator: dagger.Lazy<com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator>
) {
    /**
     * Returns only active recurring expenses.
     * B4: contract changed from all-rows to active-only for consistency
     * with [RecurringExpenseRepository].
     */
    suspend fun getAll(): List<ManualRecurringExpense> = dao.getAllActive()

    /** Returns all rows including inactive — use only when explicitly needed. */
    suspend fun getAllIncludingInactive(): List<ManualRecurringExpense> = dao.getAll()

    suspend fun insert(expense: ManualRecurringExpense): Long {
        return ruleLifecycleCoordinator.get().createRule(expense)
    }

    suspend fun setActiveStatus(id: Long, isActive: Boolean) {
        if (!isActive) {
            ruleLifecycleCoordinator.get().deactivateRule(id)
            return
        }
        ruleLifecycleCoordinator.get().activateRule(id)
    }

    suspend fun updateNextDate(id: Long, nextDate: Long) {
        ruleLifecycleCoordinator.get().advanceNextDate(id, nextDate)
    }

    suspend fun deleteById(id: Long) {
        ruleLifecycleCoordinator.get().deleteRule(id)
    }

    private suspend fun writeLifecycleEvent(
        ruleId: Long,
        eventType: String,
        occurredAt: Long,
        metadata: String?
    ) {
        if (ruleId <= 0) return
        try {
            lifecycleEventDao.insert(
                com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = eventType,
                    occurredAt = occurredAt,
                    oldStatus = null,
                    newStatus = null,
                    metadata = metadata
                )
            )
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Non-critical: failed to write lifecycle event %s for rule %d", eventType, ruleId)
        }
    }
}
