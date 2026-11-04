package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceStatus
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * The single, pure choice of a recurring rule's next `nextDate` after the occurrence due on the
 * rule's current `nextDate` day has been handled (paid explicitly, skipped, or auto-linked).
 *
 * Shared by the mark-paid use case and the auto-link path of the occurrence coordinator so the
 * choice exists once. It reads nothing and writes nothing; the write goes through
 * `RecurringRuleLifecycleCoordinator.advanceNextDate`.
 *
 * Rule: the earliest OPEN (PLANNED, unlinked) occurrence due at or after the end of the handled
 * day; when there is none, [RecurrenceCalculator] is used only if it yields a date at or after that
 * boundary. A candidate is never at or before the current `nextDate`.
 */
object RecurringNextDatePolicy {

    private val occurrenceOrder = compareBy<RecurringOccurrence>({ it.dueDate }, { it.id })

    fun isOpen(occurrence: RecurringOccurrence): Boolean =
        occurrence.status == RecurringOccurrenceStatus.PLANNED.dbValue && occurrence.linkedExpenseId == null

    /** Exclusive end of the handled day: candidates must be due at or after this instant. */
    fun threshold(currentNextDate: Long): Long = TimePeriodUtils.getEndOfDay(currentNextDate)

    /** Earliest open occurrence due at or after [threshold], or null. */
    fun earliestOpenFrom(occurrences: List<RecurringOccurrence>, threshold: Long): RecurringOccurrence? =
        occurrences
            .filter { isOpen(it) && it.dueDate >= threshold }
            .minWithOrNull(occurrenceOrder)

    /**
     * The next date, or null when nothing strictly later than [currentNextDate] exists.
     * [earliestOpen] is the result of [earliestOpenFrom] (after any caller-side top-up).
     */
    fun candidate(
        earliestOpen: RecurringOccurrence?,
        currentNextDate: Long,
        frequency: RecurrenceFrequency
    ): Long? {
        val boundary = threshold(currentNextDate)
        val raw = earliestOpen?.dueDate
            ?: RecurrenceCalculator.calculateNextDate(currentNextDate, frequency).takeIf { it >= boundary }
        return raw?.takeIf { it > currentNextDate }
    }
}
