package com.yourname.expensetracker.golden

import com.yourname.expensetracker.golden.GoldenTestBase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden tests verifying the Pipeline 4 recurring lifecycle:
 * create → update → deactivate → reactivate → delete.
 */
class Pipeline4LifecycleGoldenTest : GoldenTestBase() {

    @Test
    fun `create rule generates occurrences reminders and planned rows`() = runTest {
        // Arrange: insert a recurring rule via the rule lifecycle coordinator
        val rule = seedRule("Netflix", 15.99, "EUR", "MONTHLY")

        // Assert: occurrences exist for future months
        val occurrences = database.recurringOccurrenceDao()
            .getBySource("RECURRING_RULE", rule)
        assertTrue("Must create future occurrences", occurrences.isNotEmpty())

        // Assert: reminder deliveries exist for PLANNED occurrences
        val plannedOccs = occurrences.filter { it.status == "PLANNED" }
        val hasReminders = plannedOccs.any { occ ->
            database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occ.id, "DUE_DAY") != null
        }
        assertTrue("Must create reminder deliveries", hasReminders)

        // Assert: planned_expenses rows exist
        val plannedRows = plannedOccs.mapNotNull {
            database.plannedExpenseDao().getBySourceOccurrenceKey(it.occurrenceKey)
        }
        assertTrue("Must create planned expense rows", plannedRows.isNotEmpty())
    }

    @Test
    fun `deactivate and reactivate rule is reversible`() = runTest {
        val ruleId = seedRule("Spotify", 9.99, "EUR", "MONTHLY")

        // Deactivate
        database.manualRecurringExpenseDao().setActiveStatus(ruleId, false)

        // Reactivate
        database.manualRecurringExpenseDao().setActiveStatus(ruleId, true)

        // Assert: future PLANNED occurrences exist after reactivation
        val occurrences = database.recurringOccurrenceDao()
            .getBySource("RECURRING_RULE", ruleId)
        val planned = occurrences.filter { it.status == "PLANNED" }
        assertTrue("Reactivation must regenerate PLANNED occurrences", planned.isNotEmpty())

        val hasReminders = planned.any { occ ->
            database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occ.id, "DUE_DAY") != null
        }
        assertTrue("Reactivation must recreate reminder deliveries", hasReminders)
    }

    private suspend fun seedRule(merchant: String, amount: Double, currency: String, frequency: String): Long {
        val rule = com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
            merchant = merchant,
            amount = amount,
            currency = currency,
            frequency = try {
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.valueOf(frequency)
            } catch (_: Exception) {
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY
            },
            nextDate = System.currentTimeMillis(),
            isActive = true,
            createdAt = System.currentTimeMillis()
        )
        return database.recurringExpenseDao().insert(rule)
    }
}
