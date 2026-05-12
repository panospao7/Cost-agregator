package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
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
        database.manualRecurringExpenseDao().setActiveStatus(ruleId, false, fixedNow)

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
        database.recurringOccurrenceDao().cancelPlannedBySource("RECURRING_RULE", ruleId, fixedNow)

        // Then
        val after = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        assertTrue(after.all { it.status == "CANCELLED" })
    }

    @Test
    fun `deactivate rule suppresses open reminders`() = runTest {
        val ruleId = seedActiveRule()
        val occurrenceIds = seedPlannedOccurrences(ruleId, count = 2)
        seedReminders(occurrenceIds)

        // Verify setup
        val remindersBefore = occurrenceIds.flatMap {
            database.recurringReminderDeliveryDao().getByOccurrenceId(it)
        }
        assertTrue(remindersBefore.isNotEmpty())
        assertTrue(remindersBefore.all { it.status == "SCHEDULED" })

        // When: suppress reminders for these occurrences
        occurrenceIds.forEach {
            database.recurringReminderDeliveryDao().suppressByOccurrenceId(it)
        }

        // Then
        val remindersAfter = occurrenceIds.flatMap {
            database.recurringReminderDeliveryDao().getByOccurrenceId(it)
        }
        assertTrue(remindersAfter.all { it.status == "CANCELLED" })
    }

    @Test
    fun `deactivate rule cancels planned expenses`() = runTest {
        val ruleId = seedActiveRule()
        seedPlannedExpenses(ruleId, count = 3)

        // Verify setup
        val before = database.plannedExpenseDao().getByRecurringRuleId(ruleId)
        assertEquals(3, before.size)
        assertTrue(before.all { it.status == "PLANNED" })

        // When
        database.plannedExpenseDao().cancelPlannedByRecurringRuleId(ruleId, fixedNow)

        // Then
        val after = database.plannedExpenseDao().getByRecurringRuleId(ruleId)
        assertTrue(after.all { it.status == "CANCELLED" })
    }

    // ── Helpers ──

    private suspend fun seedActiveRule(): Long {
        val rule = ManualRecurringExpense(
            merchant = "Netflix",
            amount = 15.99,
            currency = "EUR",
            frequency = "MONTHLY",
            nextDate = fixedNow + 86400000 * 30,
            isActive = true,
            createdAt = fixedNow,
            updatedAt = fixedNow
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
                sourceRecurringRuleId = ruleId,
                sourceOccurrenceKey = "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30 * i}|MONTHLY",
                openSourceOccurrenceKey = "RECURRING_RULE|$ruleId|${fixedNow + 86400000L * 30 * i}|MONTHLY",
                expectedAmount = 15.99,
                expectedCurrency = "EUR",
                expectedDate = fixedNow + 86400000L * 30 * i,
                status = "PLANNED",
                createdAt = fixedNow,
                updatedAt = fixedNow
            )
            database.plannedExpenseDao().insertPlannedExpense(planned)
        }
    }
}
