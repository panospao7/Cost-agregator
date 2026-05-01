package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
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
    private val timeProvider: TimeProvider
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
        val now = timeProvider.now()
        val endDate = TimePeriodUtils.addMonths(now, monthsAhead)

        // 1. Expand, resolve, and materialise occurrences for the rule in range
        coordinator.generateOccurrences(ruleId, now, endDate)

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
                plannedExpenseDao.insertPlannedExpense(
                    PlannedExpense(
                        description = occ.merchant ?: "Recurring Expense",
                        amount = occ.expectedAmount,
                        currency = occ.expectedCurrency,
                        date = occ.dueDate,
                        categoryId = occ.categoryId,
                        isRecurring = true,
                        priority = PlannedExpensePriority.MUST,
                        createdAt = timeProvider.now(),
                        sourceOccurrenceKey = occ.occurrenceKey,
                        sourceRecurringRuleId = ruleId
                    )
                )
                created++
            }
        }

        return created
    }
}
