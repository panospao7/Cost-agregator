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
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
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
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.insert")
        val now = timeProvider.now()
        val entity = if (expense.createdAt == 0L) expense.copy(createdAt = now) else expense
        val id = dao.insert(entity)
        writeLifecycleEvent(id, "RULE_CREATED", now, """{"merchant":"${entity.merchant}","amount":${entity.amount},"frequency":"${entity.frequency}"}""")
        return id
    }

    suspend fun setActiveStatus(id: Long, isActive: Boolean) {
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.setActiveStatus")
        val now = timeProvider.now()
        val eventType = if (isActive) "RULE_ACTIVATED" else "RULE_DEACTIVATED"
        val existing = dao.getById(id)
        writeLifecycleEvent(id, eventType, now,
            """{"merchant":"${existing?.merchant.orEmpty()}","isActive":$isActive}""")
        dao.setActiveStatus(id, isActive)
    }

    suspend fun deleteById(id: Long) {
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.deleteById")
        val now = timeProvider.now()
        val existing = dao.getById(id)
        if (existing != null) {
            writeLifecycleEvent(id, "RULE_DELETED", now,
                """{"merchant":"${existing.merchant}","amount":${existing.amount},"frequency":"${existing.frequency}"}""")
        }
        dao.deleteById(id)
    }

    suspend fun updateNextDate(id: Long, nextDate: Long) {
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.updateNextDate")
        val now = timeProvider.now()
        writeLifecycleEvent(id, "NEXT_DATE_ADVANCED", now,
            """{"nextDate":$nextDate}""")
        dao.updateNextDate(id, nextDate)
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
