package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Projects [PlannedExpense] rows from already-materialized recurring
 * occurrences.
 *
 * This service bridges the recurring lifecycle system and the forecasting /
 * budgeting pipeline by materialising [PlannedExpense] rows from PLANNED
 * occurrences produced by the recurring lifecycle pipeline. It performs NO
 * occurrence generation itself and runs strictly inside a caller-owned
 * transaction via [projectFromOccurrencesInCurrentTransaction] — the only
 * entry point, used by RecurringRuleLifecycleCoordinator inside its
 * rule-mutation transaction.
 *
 * RP-04 P4-006 dead-code disposition (2026-09-19): the non-atomic
 * `projectFromRule()` entry point (separate coordinator-plus-insert path) was
 * deleted after a worktree-wide caller audit found zero production or test
 * callers; the now-unused RecurringLifecycleCoordinator and TimeProvider
 * constructor dependencies were removed with it. DatabaseWriteBarrier remains
 * at this DAO write boundary even though the caller owns the transaction.
 *
 * ## REC-25: `isRecurring` determination
 * A planned expense is considered recurring when its [PlannedExpense.sourceRecurringRuleId]
 * is non-null — i.e. it was generated from a recurring rule. The `isRecurring`
 * boolean flag is set to `true` for all such occurrences. Code that checks
 * whether an expense is recurring should prefer checking `sourceRecurringRuleId != null`
 * over the `isRecurring` flag alone, because the flag may be `false` for
 * occurrence-linked patterns that were created before the flag was set.
 */
@Singleton
class RecurringPlanProjectionService @Inject constructor(
    private val plannedExpenseDao: PlannedExpenseDao,
    private val occurrenceDao: com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao,
    private val writeBarrier: DatabaseWriteBarrier
) {

    // RP-04 P4-006 dead-code disposition: `projectFromRule()` was deleted here.
    // Caller audit (worktree-wide grep, 2026-09-19) found zero production or
    // test callers; it used a separate non-atomic coordinator-plus-insert path.
    // The only remaining projection primitive is
    // [projectFromOccurrencesInCurrentTransaction], used by
    // RecurringRuleLifecycleCoordinator inside its rule-mutation transaction.

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
        writeBarrier.checkWritesAllowed(
            "RecurringPlanProjectionService.projectFromOccurrencesInCurrentTransaction"
        )
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
