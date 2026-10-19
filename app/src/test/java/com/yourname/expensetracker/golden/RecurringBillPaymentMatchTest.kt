package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden Scenario Test 3: Recurring Bill Payment Match
 *
 * Verifies that when an expense matching a recurring bill is created:
 * 1. The planned expense is fulfilled (with linked-expense provenance)
 * 2. Open reminders are suppressed
 *
 * The atomic occurrence claim (PLANNED -> PAID, no double-link) is pinned by
 * ConcurrentOccurrenceClaimTest.
 */
class RecurringBillPaymentMatchTest : GoldenTestBase() {

    // Occurrence-claim atomicity (PLANNED -> PAID once, second claim rejected) is covered by
    // ConcurrentOccurrenceClaimTest; this class keeps the downstream fulfil/suppress effects.

    @Test
    fun `fulfillByOccurrenceKey fulfils exactly the one open planned expense with provenance`() = runTest {
        val ruleId = seedRule()
        val occKey = "bill-match-key-1"
        seedPlannedExpenseForOccurrence(ruleId, occKey)

        // When: Fulfill planned expense by occurrence key.
        val fulfilled = database.plannedExpenseDao().fulfillByOccurrenceKey(occKey, 999L, fixedNow)

        // Then: the UPDATE matches sourceOccurrenceKey AND status='PLANNED'; exactly one such row is seeded.
        assertEquals(1, fulfilled)
        val planned = database.plannedExpenseDao().getBySourceOccurrenceKey(occKey)
        assertEquals("FULFILLED", planned?.status)
        assertEquals(999L, planned?.linkedActualExpenseId)
        assertNull(planned?.openSourceOccurrenceKey)
        assertEquals(fixedNow, planned?.updatedAt)

        // And: a second fulfil is a no-op because the row is no longer PLANNED.
        val again = database.plannedExpenseDao().fulfillByOccurrenceKey(occKey, 1000L, fixedNow)
        assertEquals(0, again)
        assertEquals(999L, database.plannedExpenseDao().getBySourceOccurrenceKey(occKey)?.linkedActualExpenseId)
    }

    @Test
    fun `reminders suppressed after occurrence claim`() = runTest {
        val ruleId = seedRule()
        val occId = seedPlannedOccurrence(ruleId)
        seedReminder(occId)

        // Verify reminder exists
        val before = database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occId, "DUE_DAY")
        assertNotNull(before)
        assertEquals("SCHEDULED", before!!.status)

        // When: Suppress reminders for this occurrence
        val suppressed = database.recurringReminderDeliveryDao().suppressByOccurrenceId(occId, fixedNow)

        // Then: exactly the one seeded SCHEDULED reminder is cancelled with bounded reason + timestamp
        assertEquals(1, suppressed)
        val after = database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occId, "DUE_DAY")
        assertEquals("CANCELLED", after!!.status)
        assertEquals("occurrence_paid", after.failureReason)
        assertEquals(fixedNow, after.updatedAt)
    }

    // ── Helpers ──

    private suspend fun seedRule(): Long {
        return database.manualRecurringExpenseDao().insert(ManualRecurringExpense(
            merchant = "Netflix",
            amount = 15.99,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow + 86400000L * 30,
            isActive = true,
            createdAt = fixedNow
        ))
    }

    private suspend fun seedPlannedOccurrence(ruleId: Long): Long {
        return database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE",
            sourceId = ruleId,
            occurrenceKey = "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30}|MONTHLY",
            dueDate = fixedNow + 86400000L * 30,
            expectedAmount = 15.99,
            expectedCurrency = "EUR",
            status = "PLANNED",
            createdAt = fixedNow,
            frequency = "MONTHLY",
            merchant = "Netflix"
        ))
    }

    private suspend fun seedPlannedExpenseForOccurrence(ruleId: Long, occKey: String) {
        database.plannedExpenseDao().insertPlannedExpense(PlannedExpense(
            description = "Netflix subscription",
            amount = 15.99,
            currency = "EUR",
            date = fixedNow + 86400000L * 30,
            sourceRecurringRuleId = ruleId,
            sourceOccurrenceKey = occKey,
            openSourceOccurrenceKey = occKey,
            status = "PLANNED",
            createdAt = fixedNow,
            updatedAt = fixedNow
        ))
    }

    private suspend fun seedReminder(occId: Long) {
        database.recurringReminderDeliveryDao().insert(RecurringReminderDelivery(
            occurrenceId = occId,
            reminderWindow = "DUE_DAY",
            scheduledAt = fixedNow + 86400000L * 29,
            status = "SCHEDULED"
        ))
    }
}
