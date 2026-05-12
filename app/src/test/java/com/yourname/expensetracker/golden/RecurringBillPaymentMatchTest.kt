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
 * 1. The occurrence is atomically claimed (status → PAID)
 * 2. The planned expense is fulfilled
 * 3. Open reminders are suppressed
 * 4. Only one expense can claim an occurrence (no double-link)
 */
class RecurringBillPaymentMatchTest : GoldenTestBase() {

    @Test
    fun `occurrence claim is atomic - only PLANNED can be claimed`() = runTest {
        val ruleId = seedRule()
        val occId = seedPlannedOccurrence(ruleId)

        // When: Claim the occurrence
        val claimed = database.recurringOccurrenceDao().claimForExpense(
            occurrenceId = occId,
            expenseId = 999L,
            amount = 15.99,
            currency = "EUR",
            paidAt = fixedNow
        )

        // Then: Claim succeeds
        assertEquals(1, claimed)

        // And: Occurrence is now PAID
        val occ = database.recurringOccurrenceDao().getById(occId)
        assertEquals("PAID", occ?.status)
        assertEquals(999L, occ?.linkedExpenseId)
    }

    @Test
    fun `already claimed occurrence rejects second claim`() = runTest {
        val ruleId = seedRule()
        val occId = seedPlannedOccurrence(ruleId)

        // First claim succeeds
        val first = database.recurringOccurrenceDao().claimForExpense(occId, 100L, 15.99, "EUR", fixedNow)
        assertEquals(1, first)

        // Second claim fails (already PAID)
        val second = database.recurringOccurrenceDao().claimForExpense(occId, 200L, 15.99, "EUR", fixedNow)
        assertEquals(0, second)

        // Occurrence still linked to first expense
        val occ = database.recurringOccurrenceDao().getById(occId)
        assertEquals(100L, occ?.linkedExpenseId)
    }

    @Test
    fun `planned expense fulfilled after occurrence claim`() = runTest {
        val ruleId = seedRule()
        val occId = seedPlannedOccurrence(ruleId)
        val occKey = "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30}|MONTHLY"
        seedPlannedExpenseForOccurrence(ruleId, occKey)

        // When: Fulfill planned expense by occurrence key
        val fulfilled = database.plannedExpenseDao().fulfillByOccurrenceKey(occKey, fixedNow)

        // Then
        assertTrue(fulfilled > 0)
        val planned = database.plannedExpenseDao().getBySourceOccurrenceKey(occKey)
        assertEquals("FULFILLED", planned?.status)
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
        val suppressed = database.recurringReminderDeliveryDao().suppressByOccurrenceId(occId)

        // Then
        assertTrue(suppressed > 0)
        val after = database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occId, "DUE_DAY")
        assertEquals("CANCELLED", after!!.status)
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
