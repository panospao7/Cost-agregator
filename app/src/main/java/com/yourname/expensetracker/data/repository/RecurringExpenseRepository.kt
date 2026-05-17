package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: ManualRecurringExpenseDao,
    private val lifecycleEventDao: com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
) {
    companion object {
        fun createRecurringExpenseEntity(
            merchant: String,
            amount: Double,
            frequency: RecurrenceFrequency,
            lastDate: Long,
            currency: String = "EUR",
            note: String? = null,
            now: Long = 0L
        ): ManualRecurringExpense {
            val normalizedLastDate = RecurrenceCalculator.normalizeToDateOnly(lastDate)
            val nextDate = RecurrenceCalculator.calculateNextDate(normalizedLastDate, frequency)

            return ManualRecurringExpense(
                merchant = merchant,
                amount = amount,
                currency = currency,
                frequency = frequency,
                nextDate = nextDate,
                note = note ?: "Created from manual entry",
                createdAt = now
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

    /**
     * REC-15: Lookup by exact merchant name (legacy behavior).
     *
     * This performs a case-sensitive exact match on the raw merchant display name.
     * Prefer [getByMerchantFuzzy] which normalizes both the query and stored names
     * via [MerchantKeyGenerator] to handle minor variations (e.g., "McDonald's"
     * vs "Mc Donalds").
     */
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense? = dao.getByMerchant(merchant)

    /**
     * REC-15: Fuzzy merchant lookup using [MerchantKeyGenerator] normalization.
     *
     * Generates a canonical key from [merchantName], fetches all active recurring
     * expenses, and returns the first whose generated key matches. This handles
     * minor spelling variations, Greek/Latin transliteration differences, and
     * punctuation/whitespace inconsistencies without requiring a schema migration.
     *
     * Returns `null` when:
     * - [merchantName] is blank (no key can be generated)
     * - No active recurring expense has a matching normalized key
     */
    suspend fun getByMerchantFuzzy(merchantName: String): ManualRecurringExpense? {
        val key = MerchantKeyGenerator.generate(merchantName)
        if (key.isBlank()) return null
        val all = dao.getAllActive()
        return all.firstOrNull { MerchantKeyGenerator.generate(it.merchant) == key }
    }

    suspend fun addRecurringExpense(
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency,
        lastDate: Long,
        currency: String = "EUR",
        note: String? = null
    ): Long {
        writeBarrier.checkWritesAllowed("RecurringExpenseRepository.addRecurringExpense")
        val now = timeProvider.now()
        val expense = createRecurringExpenseEntity(
            merchant = merchant,
            amount = amount,
            frequency = frequency,
            lastDate = lastDate,
            currency = currency,
            note = note,
            now = now
        )
        val id = dao.insert(expense)
        writeLifecycleEvent(expense.id.let { if (id > 0) id else 0L }, "RULE_CREATED", now, null, null,
            """{"merchant":"${expense.merchant}","amount":${expense.amount},"frequency":"${expense.frequency}"}""")
        return id
    }

    suspend fun insert(expense: ManualRecurringExpense): Long {
        writeBarrier.checkWritesAllowed("RecurringExpenseRepository.insert")
        val now = timeProvider.now()
        val entity = if (expense.createdAt == 0L) expense.copy(createdAt = now) else expense
        val id = dao.insert(entity)
        writeLifecycleEvent(id, "RULE_CREATED", now, null, null,
            """{"merchant":"${entity.merchant}","amount":${entity.amount},"frequency":"${entity.frequency}"}""")
        return id
    }

    suspend fun delete(expense: ManualRecurringExpense) {
        writeBarrier.checkWritesAllowed("RecurringExpenseRepository.delete")
        val now = timeProvider.now()
        writeLifecycleEvent(expense.id, "RULE_DELETED", now, null, null,
            """{"merchant":"${expense.merchant}","amount":${expense.amount},"frequency":"${expense.frequency}"}""")
        dao.delete(expense)
    }
    
    suspend fun deleteById(id: Long) {
        writeBarrier.checkWritesAllowed("RecurringExpenseRepository.deleteById")
        val now = timeProvider.now()
        val existing = dao.getById(id)
        if (existing != null) {
            writeLifecycleEvent(id, "RULE_DELETED", now, null, null,
                """{"merchant":"${existing.merchant}","amount":${existing.amount},"frequency":"${existing.frequency}"}""")
        }
        dao.deleteById(id)
    }

    suspend fun update(expense: ManualRecurringExpense) {
        writeBarrier.checkWritesAllowed("RecurringExpenseRepository.update")
        val now = timeProvider.now()
        writeLifecycleEvent(expense.id, "RULE_UPDATED", now, null, null,
            """{"merchant":"${expense.merchant}","amount":${expense.amount},"frequency":"${expense.frequency}"}""")
        dao.update(expense.copy(createdAt = if (expense.createdAt == 0L) now else expense.createdAt))
    }

    private suspend fun writeLifecycleEvent(
        ruleId: Long,
        eventType: String,
        occurredAt: Long,
        oldStatus: String?,
        newStatus: String?,
        metadata: String?
    ) {
        if (ruleId <= 0) return
        writeBarrier.checkWritesAllowed("RecurringExpenseRepository.writeLifecycleEvent")
        try {
            lifecycleEventDao.insert(
                com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent(
                    occurrenceId = null,
                    eventType = eventType,
                    occurredAt = occurredAt,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Non-critical: failed to write lifecycle event %s for rule %d", eventType, ruleId)
        }
    }

}
