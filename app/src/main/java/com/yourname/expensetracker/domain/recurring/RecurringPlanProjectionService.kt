package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates [PlannedExpense] projections from recurring rules via the
 * [RecurringLifecycleCoordinator].
 *
 * This service bridges the recurring lifecycle system and the forecasting /
 * budgeting pipeline by materialising [PlannedExpense] rows from occurrences
 * produced by [RecurringLifecycleCoordinator.generateOccurrences].
 *
 * ## REC-25: `isRecurring` determination
 * A planned expense is considered recurring when its [PlannedExpense.sourceRecurringRuleId]
 * is non-null — i.e. it was generated from a recurring rule. The `isRecurring`
 * boolean flag is set to `true` for all such occurrences. Code that checks
 * whether an expense is recurring should prefer checking `sourceRecurringRuleId != null`
 * over the `isRecurring` flag alone, because the flag may be `false` for
 * occurrence-linked patterns that were created before the flag was set.
 *
 * ## Design rationale
 *
 * The recurring lifecycle coordinator is the single source of truth for
 * recurrence expansion. Previously, [ForecastInputAssembler] assembled
 * recurring patterns and planned expenses independently, creating a
 * double-count risk. This service replaces that ad-hoc expansion with
 * coordinator-driven generation and deduplication via [sourceOccurrenceKey].
 */
@Singleton
class RecurringPlanProjectionService @Inject constructor(
    private val coordinator: RecurringLifecycleCoordinator,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val timeProvider: TimeProvider,
    private val writeBarrier: com.yourname.expensetracker.data.backup.DatabaseWriteBarrier,
    private val occurrenceDao: com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
) {

    /**
     * Generates planned expense projections from a recurring rule.
     *
     * First materialises occurrences via [RecurringLifecycleCoordinator.generateOccurrences]
     * for the range `[now, addMonths(now, monthsAhead))`, then creates a
     * [PlannedExpense] row for every PLANNED occurrence that does not already
     * have a matching planned expense (checked via [sourceOccurrenceKey]).
     *
     * @param ruleId The ID of the [com.yourname.expensetracker.data.database.entity.ManualRecurringExpense] rule.
     * @param monthsAhead How many months ahead to project (default 3).
     * @return The number of newly created planned expenses.
     */
    suspend fun projectFromRule(ruleId: Long, monthsAhead: Int = 3): Int {
        // P4-CURRENT-009: Block projections during restore maintenance
        writeBarrier.checkWritesAllowed("RecurringPlanProjectionService.projectFromRule")
        // TODO: Full atomicity — wrap coordinator + planned inserts in a single transaction
        val now = timeProvider.now()
        val endDate = TimePeriodUtils.addMonths(now, monthsAhead)

        // 1. Expand, resolve, and materialise occurrences for the rule in range
        coordinator.generateOccurrences(
            ruleId = ruleId,
            startDate = now,
            endDate = endDate,
            options = com.yourname.expensetracker.domain.recurring.lifecycle.OccurrenceGenerationOptions(
                createReminderDeliveries = true,
                generationSource = com.yourname.expensetracker.domain.recurring.lifecycle.OccurrenceGenerationSource.REMINDER_PROJECTION
            )
        )

        // 2. Fetch all occurrences for this rule
        // Use start-of-day for `now` so occurrences due today at 00:00 are included
        val start = TimePeriodUtils.getStartOfDay(now)
        val occurrences = coordinator.getOccurrences(ruleId)
            .filter { it.status == "PLANNED" && it.dueDate >= start && it.dueDate < endDate }

        // 3. Create PlannedExpense rows for occurrences that have no match yet
        var created = 0
        for (occ in occurrences) {
            val existing = plannedExpenseDao.getBySourceOccurrenceKey(occ.occurrenceKey)
            if (existing == null) {
                val merchantName = occ.merchant ?: "Recurring Expense"
                val insertedId = plannedExpenseDao.insertPlannedExpense(
                    PlannedExpense(
                        description = merchantName,
                        amount = occ.expectedAmount,
                        currency = occ.expectedCurrency,
                        date = occ.dueDate,
                        categoryId = occ.categoryId,
                        isRecurring = true,
                        priority = PlannedExpensePriority.MUST,
                        createdAt = now,
                        sourceOccurrenceKey = occ.occurrenceKey,
                        openSourceOccurrenceKey = occ.occurrenceKey,
                        sourceRecurringRuleId = ruleId,
                        merchantKey = MerchantKeyGenerator.generate(merchantName),
                        updatedAt = now
                    )
                )
                if (insertedId > 0) created++
            }
        }

        return created
    }

    /**
     * Transaction-safe planned projection. Does NOT start its own transaction.
     * Call this from inside an existing `database.withTransaction` block.
     *
     * Creates planned_expenses rows for PLANNED occurrences that don't yet have one.
     */
    suspend fun projectFromOccurrencesInCurrentTransaction(
        ruleId: Long,
        startDate: Long,
        endDate: Long,
        now: Long
    ): Int {
        val occurrences = occurrenceDao.getByDateRange(startDate, endDate)
            .filter { it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE
                      && it.sourceId == ruleId
                      && it.status == "PLANNED" }

        var created = 0
        for (occ in occurrences) {
            val existing = plannedExpenseDao.getBySourceOccurrenceKey(occ.occurrenceKey)
            if (existing == null) {
                val merchantName = occ.merchant ?: "Recurring Expense"
                val id = plannedExpenseDao.insertPlannedExpense(
                    com.yourname.expensetracker.data.database.entity.PlannedExpense(
                        description = merchantName,
                        amount = occ.expectedAmount,
                        currency = occ.expectedCurrency,
                        date = occ.dueDate,
                        categoryId = occ.categoryId,
                        isRecurring = true,
                        priority = PlannedExpensePriority.MUST,
                        createdAt = now,
                        sourceOccurrenceKey = occ.occurrenceKey,
                        openSourceOccurrenceKey = occ.occurrenceKey,
                        sourceRecurringRuleId = ruleId,
                        merchantKey = MerchantKeyGenerator.generate(merchantName),
                        updatedAt = now
                    )
                )
                if (id > 0) created++
            }
        }
        return created
    }
}
