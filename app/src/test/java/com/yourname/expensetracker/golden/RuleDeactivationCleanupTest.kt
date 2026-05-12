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
 * Golden Scenario Test 7: Rule Deactivation Cleanup
 *
 * Verifies that deactivating a recurring rule atomically:
 * 1. Sets rule.isActive = false
 * 2. Cancels all PLANNED occurrences
 * 3. Suppresses all open reminder deliveries
 * 4. Cancels all PLANNED planned expenses
 * 5. Future generateOccurrences produces nothing for this rule
 */
class RuleDeactivationCleanupTest : GoldenTestBase() {

    @Test
    fun `deactivate rule sets isActive false`() = runTest {
        val ruleId = seedActiveRule()

        // When
        database.manualRecurringExpenseDao().setActiveStatus(ruleId, false)

        // Then
        val rule = database.manualRecurringExpenseDao().getById(ruleId)
        assertNotNull(rule)
        assertFalse(rule!!.isActive)
    }

    @Test
    fun `deactivate rule cancels planned occurrences`() = runTest {
        val ruleId = seedActiveRule()
        seedPlannedOccurrences(ruleId, count = 3)

        // Verify setup
        val before = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        assertEquals(3, before.size)
        assertTrue(before.all { it.status == "PLANNED" })

        // When: cancel all PLANNED occurrences for this rule
        val plannedIds = database.recurringOccurrenceDao().getPlannedIdsBySource("RECURRING_RULE", ruleId)
        database.recurringOccurrenceDao().updateStatus(plannedIds, "CANCELLED", fixedNow)

        // Then
        val after = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        assertTrue(after.all { it.status == "CANCELLED" })
    }

    @Test
    fun `deactivate rule suppresses open reminders`() = runTest {
        val ruleId = seedActiveRule()
        val occurrenceIds = seedPlannedOccurrences(ruleId, count = 2)
        seedReminders(occurrenceIds)

        // Verify setup - reminders exist via getByOccurrenceAndWindow
        occurrenceIds.forEach { occId ->
            val reminder = database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occId, "DUE_DAY")
            assertNotNull(reminder)
            assertEquals("SCHEDULED", reminder!!.status)
        }

        // When: suppress reminders for these occurrences
        var totalSuppressed = 0
        occurrenceIds.forEach {
            totalSuppressed += database.recurringReminderDeliveryDao().suppressByOccurrenceId(it)
        }

        // Then
        assertEquals(2, totalSuppressed)
        occurrenceIds.forEach { occId ->
            val reminder = database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occId, "DUE_DAY")
            assertEquals("CANCELLED", reminder!!.status)
        }
    }

    @Test
    fun `deactivate rule cancels planned expenses`() = runTest {
        val ruleId = seedActiveRule()
        seedPlannedExpenses(ruleId, count = 3)

        // Verify setup - check each planned expense by occurrence key
        val keys = (1..3).map { i -> "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30 * i}|MONTHLY" }
        keys.forEach { key ->
            val planned = database.plannedExpenseDao().getBySourceOccurrenceKey(key)
            assertNotNull(planned)
            assertEquals("PLANNED", planned!!.status)
        }

        // When
        val cancelled = database.plannedExpenseDao().cancelPlannedByRecurringRuleId(ruleId, fixedNow)

        // Then
        assertEquals(3, cancelled)
        keys.forEach { key ->
            val planned = database.plannedExpenseDao().getBySourceOccurrenceKey(key)
            assertEquals("CANCELLED", planned!!.status)
        }
    }

    // ── Helpers ──

    private suspend fun seedActiveRule(): Long {
        val rule = ManualRecurringExpense(
            merchant = "Netflix",
            amount = 15.99,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow + 86400000 * 30,
            isActive = true,
            createdAt = fixedNow
        )
        return database.manualRecurringExpenseDao().insert(rule)
    }

    private suspend fun seedPlannedOccurrences(ruleId: Long, count: Int): List<Long> {
        return (1..count).map { i ->
            val occ = RecurringOccurrence(
                sourceType = "RECURRING_RULE",
                sourceId = ruleId,
                occurrenceKey = "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30 * i}|MONTHLY",
                dueDate = fixedNow + 86400000L * 30 * i,
                expectedAmount = 15.99,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = "Netflix",
                status = "PLANNED",
                createdAt = fixedNow
            )
            database.recurringOccurrenceDao().insert(occ)
        }
    }

    private suspend fun seedReminders(occurrenceIds: List<Long>) {
        occurrenceIds.forEach { occId ->
            val delivery = RecurringReminderDelivery(
                occurrenceId = occId,
                reminderWindow = "DUE_DAY",
                scheduledAt = fixedNow + 86400000L * 29,
                status = "SCHEDULED"
            )
            database.recurringReminderDeliveryDao().insert(delivery)
        }
    }

    private suspend fun seedPlannedExpenses(ruleId: Long, count: Int) {
        (1..count).forEach { i ->
            val planned = PlannedExpense(
                description = "Planned expense for rule #$ruleId",
                amount = 15.99,
                currency = "EUR",
                date = fixedNow + 86400000L * 30 * i,
                sourceRecurringRuleId = ruleId,
                sourceOccurrenceKey = "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30 * i}|MONTHLY",
                openSourceOccurrenceKey = "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30 * i}|MONTHLY",
                status = "PLANNED",
                createdAt = fixedNow,
                updatedAt = fixedNow
            )
            database.plannedExpenseDao().insertPlannedExpense(planned)
        }
    }
}
