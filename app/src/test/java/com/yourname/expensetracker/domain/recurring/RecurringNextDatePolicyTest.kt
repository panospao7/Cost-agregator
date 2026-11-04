package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * DR-03: pure choice of a rule's next `nextDate` after the occurrence due on the current
 * `nextDate` day was handled. Expected values are written as literal calendar dates.
 */
class RecurringNextDatePolicyTest {

    private fun dayOf(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun localDateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun occurrence(
        id: Long,
        due: Long,
        status: String = "PLANNED",
        linkedExpenseId: Long? = null
    ) = RecurringOccurrence(
        id = id,
        sourceType = "RECURRING_RULE",
        sourceId = 1L,
        occurrenceKey = "RECURRING_RULE|1|$due|MONTHLY|$id",
        dueDate = due,
        status = status,
        linkedExpenseId = linkedExpenseId,
        expectedAmount = 10.0,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = "Netflix"
    )

    // ── isOpen ──────────────────────────────────────────────────────────────

    @Test
    fun isOpenIsTrueOnlyForPlannedUnlinkedOccurrences() {
        val due = dayOf(2026, 11, 5)
        assertEquals(true, RecurringNextDatePolicy.isOpen(occurrence(1, due)))
        assertEquals(false, RecurringNextDatePolicy.isOpen(occurrence(2, due, status = "PAID")))
        assertEquals(false, RecurringNextDatePolicy.isOpen(occurrence(3, due, status = "SKIPPED")))
        assertEquals(false, RecurringNextDatePolicy.isOpen(occurrence(4, due, linkedExpenseId = 9L)))
    }

    // ── threshold ───────────────────────────────────────────────────────────

    @Test
    fun thresholdIsTheStartOfTheFollowingLocalDayRegardlessOfTimeOfDay() {
        val expected = dayOf(2026, 11, 6)
        assertEquals(expected, RecurringNextDatePolicy.threshold(dayOf(2026, 11, 5)))
        assertEquals(expected, RecurringNextDatePolicy.threshold(dayOf(2026, 11, 5) + 15 * 3_600_000L))
    }

    // ── earliestOpenFrom ────────────────────────────────────────────────────

    @Test
    fun earliestOpenFromPicksTheEarliestOpenOccurrenceAtOrAfterTheThreshold() {
        val threshold = RecurringNextDatePolicy.threshold(dayOf(2026, 11, 5))
        val pick = RecurringNextDatePolicy.earliestOpenFrom(
            listOf(
                occurrence(1, dayOf(2027, 1, 5)),
                occurrence(2, dayOf(2026, 12, 5)),
                occurrence(3, dayOf(2027, 2, 5))
            ),
            threshold
        )
        assertEquals(2L, pick!!.id)
    }

    @Test
    fun earliestOpenFromTreatsTheThresholdAsInclusiveAndTheHandledDayAsExcluded() {
        val threshold = RecurringNextDatePolicy.threshold(dayOf(2026, 11, 5))
        val handledDay = occurrence(1, dayOf(2026, 11, 5))
        val onThreshold = occurrence(2, threshold)

        assertEquals(2L, RecurringNextDatePolicy.earliestOpenFrom(listOf(handledDay, onThreshold), threshold)!!.id)
        assertNull(RecurringNextDatePolicy.earliestOpenFrom(listOf(handledDay), threshold))
        assertNull(RecurringNextDatePolicy.earliestOpenFrom(listOf(occurrence(3, threshold - 1)), threshold))
    }

    @Test
    fun earliestOpenFromIgnoresPaidSkippedAndLinkedOccurrences() {
        val threshold = RecurringNextDatePolicy.threshold(dayOf(2026, 11, 5))
        val pick = RecurringNextDatePolicy.earliestOpenFrom(
            listOf(
                occurrence(1, dayOf(2026, 11, 20), status = "PAID"),
                occurrence(2, dayOf(2026, 11, 25), status = "SKIPPED"),
                occurrence(3, dayOf(2026, 11, 28), linkedExpenseId = 5L),
                occurrence(4, dayOf(2026, 12, 5))
            ),
            threshold
        )
        assertEquals(4L, pick!!.id)
        assertNull(RecurringNextDatePolicy.earliestOpenFrom(emptyList(), threshold))
    }

    @Test
    fun earliestOpenFromBreaksTiesOnTheSameDueDateByLowestId() {
        val threshold = RecurringNextDatePolicy.threshold(dayOf(2026, 11, 5))
        val due = dayOf(2026, 12, 5)
        val pick = RecurringNextDatePolicy.earliestOpenFrom(
            listOf(occurrence(9, due), occurrence(4, due), occurrence(7, due)),
            threshold
        )
        assertEquals(4L, pick!!.id)
    }

    // ── candidate ───────────────────────────────────────────────────────────

    @Test
    fun candidateUsesTheEarliestOpenOccurrenceDueDateWhenPresent() {
        val current = dayOf(2026, 11, 5)
        val open = occurrence(1, dayOf(2026, 12, 20))
        // The open occurrence wins over the calculator (Dec 5) even though it is later.
        assertEquals(
            dayOf(2026, 12, 20),
            RecurringNextDatePolicy.candidate(open, current, RecurrenceFrequency.MONTHLY)
        )
    }

    @Test
    fun candidateFallsBackToTheRecurrenceCalculatorWhenNoOpenOccurrenceExists() {
        val current = dayOf(2026, 11, 5)
        val monthly = RecurringNextDatePolicy.candidate(null, current, RecurrenceFrequency.MONTHLY)
        val weekly = RecurringNextDatePolicy.candidate(null, current, RecurrenceFrequency.WEEKLY)

        assertEquals(LocalDate.of(2026, 12, 5), localDateOf(monthly!!))
        assertEquals(LocalDate.of(2026, 11, 12), localDateOf(weekly!!))
    }

    @Test
    fun candidateFallbackKeepsTheMonthEndClamp() {
        val monthly = RecurringNextDatePolicy.candidate(
            null,
            dayOf(2027, 1, 31),
            RecurrenceFrequency.MONTHLY
        )
        assertEquals(LocalDate.of(2027, 2, 28), localDateOf(monthly!!))
    }

    @Test
    fun candidateIsNullWhenTheOpenOccurrenceIsNotStrictlyAfterTheCurrentNextDate() {
        val current = dayOf(2026, 11, 5)
        assertNull(
            RecurringNextDatePolicy.candidate(occurrence(1, current), current, RecurrenceFrequency.MONTHLY)
        )
        assertNull(
            RecurringNextDatePolicy.candidate(
                occurrence(2, current - 24 * 3_600_000L),
                current,
                RecurrenceFrequency.MONTHLY
            )
        )
    }
}
