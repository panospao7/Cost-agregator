package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.domain.recurring.lifecycle.RoomRecurringLifecycleEventWriter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden tests verifying Pipeline 4 recurring lifecycle state in the database
 * after operations through the real recurring rule lifecycle coordinator.
 */
class Pipeline4LifecycleGoldenTest : GoldenTestBase() {

    private val ruleCoordinator by lazy { buildRuleCoordinator() }

    @Test
    fun `create rule through coordinator generates occurrences reminders and planned rows`() = runTest {
        val ruleId = seedRuleViaCoordinator("Netflix", 15.99, "EUR", RecurrenceFrequency.MONTHLY)

        val occurrences = database.recurringOccurrenceDao()
            .getBySource("RECURRING_RULE", ruleId)
        assertTrue("Must create future occurrences", occurrences.isNotEmpty())

        val plannedOccs = occurrences.filter { it.status == "PLANNED" }
        assertTrue("Must have PLANNED occurrences", plannedOccs.isNotEmpty())

        val hasReminders = plannedOccs.any { occ ->
            database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occ.id, "DUE_DAY") != null
        }
        assertTrue("Must create reminder deliveries", hasReminders)

        val plannedRows = plannedOccs.mapNotNull {
            database.plannedExpenseDao().getBySourceOccurrenceKey(it.occurrenceKey)
        }
        assertTrue("Must create planned expense rows", plannedRows.isNotEmpty())
        plannedRows.forEach { assertEquals("PLANNED", it.status) }
    }

    @Test
    fun `deactivate and reactivate restores occurrences reminders and planned rows`() = runTest {
        val ruleId = seedRuleViaCoordinator("Spotify", 9.99, "EUR", RecurrenceFrequency.MONTHLY)

        val beforeDeactivate = database.recurringOccurrenceDao()
            .getBySource("RECURRING_RULE", ruleId).filter { it.status == "PLANNED" }
        assertTrue("Setup must generate state before cleanup is tested", beforeDeactivate.isNotEmpty())
        beforeDeactivate.forEach {
            assertNotNull(database.plannedExpenseDao().getBySourceOccurrenceKey(it.occurrenceKey))
            assertNotNull(database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(it.id, "DUE_DAY"))
        }
        ruleCoordinator.deactivateRule(ruleId)
        assertEquals(false, database.manualRecurringExpenseDao().getById(ruleId)?.isActive)
        beforeDeactivate.forEach {
            assertNull(database.plannedExpenseDao().getBySourceOccurrenceKey(it.occurrenceKey))
            assertNull(database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(it.id, "DUE_DAY"))
        }

        // Assert: PLANNED occurrences removed (deleted, not cancelled)
        val afterDeactivate = database.recurringOccurrenceDao()
            .getBySource("RECURRING_RULE", ruleId)
        val plannedAfterDeactivate = afterDeactivate.filter { it.status == "PLANNED" }
        assertEquals("Deactivation must delete open PLANNED occurrences", 0, plannedAfterDeactivate.size)

        ruleCoordinator.activateRule(ruleId)
        assertEquals(true, database.manualRecurringExpenseDao().getById(ruleId)?.isActive)

        // Assert: PLANNED occurrences regenerated
        val afterActivate = database.recurringOccurrenceDao()
            .getBySource("RECURRING_RULE", ruleId)
        val planned = afterActivate.filter { it.status == "PLANNED" }
        assertTrue("Reactivation must regenerate PLANNED occurrences", planned.isNotEmpty())

        val hasReminders = planned.any { occ ->
            database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(occ.id, "DUE_DAY") != null
        }
        assertTrue("Reactivation must recreate reminder deliveries", hasReminders)

        // Assert: planned rows regenerated (not stuck as CANCELLED)
        val plannedRows = planned.mapNotNull {
            database.plannedExpenseDao().getBySourceOccurrenceKey(it.occurrenceKey)
        }
        val openPlanned = plannedRows.filter { it.status == "PLANNED" }
        assertTrue("Reactivation must restore open PLANNED planned rows", openPlanned.isNotEmpty())
    }

    @Test
    fun `delete rule cleans generated future state`() = runTest {
        val ruleId = seedRuleViaCoordinator("Prime", 5.99, "EUR", RecurrenceFrequency.MONTHLY)

        val beforeDelete = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        assertTrue("Deletion must start with generated state", beforeDelete.isNotEmpty())
        beforeDelete.forEach {
            assertNotNull(database.plannedExpenseDao().getBySourceOccurrenceKey(it.occurrenceKey))
            assertNotNull(database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(it.id, "DUE_DAY"))
        }
        ruleCoordinator.deleteRule(ruleId)

        val rule = database.manualRecurringExpenseDao().getById(ruleId)
        assertNull("Rule must be deleted", rule)

        val occurrences = database.recurringOccurrenceDao()
            .getBySource("RECURRING_RULE", ruleId)
        assertEquals("Generated occurrences must be cleaned", 0, occurrences.size)

        beforeDelete.forEach {
            assertNull("Generated planned row must be removed",
                database.plannedExpenseDao().getBySourceOccurrenceKey(it.occurrenceKey))
            assertNull("Generated reminder must be removed",
                database.recurringReminderDeliveryDao().getByOccurrenceAndWindow(it.id, "DUE_DAY"))
        }
    }

    private suspend fun seedRuleViaCoordinator(
        merchant: String, amount: Double, currency: String, frequency: RecurrenceFrequency
    ): Long {
        // Exercise the legal writer, not a direct DAO insert that cannot generate lifecycle state.
        val rule = ManualRecurringExpense(
            merchant = merchant, amount = amount, currency = currency,
            frequency = frequency,
            nextDate = TimePeriodUtils.addDays(TimePeriodUtils.getStartOfDay(fixedNow), 1),
            isActive = true, createdAt = fixedNow
        )
        return ruleCoordinator.createRule(rule)
    }

    private fun buildRuleCoordinator(): com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator {
        val occurrenceDao = database.recurringOccurrenceDao()
        val deliveryDao = database.recurringReminderDeliveryDao()
        val plannedDao = database.plannedExpenseDao()
        val eventDao = database.recurringLifecycleEventDao()
        val expenseDao = database.expenseDao()
        val ruleDao = database.manualRecurringExpenseDao()

        val eventWriter = RoomRecurringLifecycleEventWriter(eventDao, timeProvider, writeBarrier)

        val lifecycleCoordinator = com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator(
            database = database,
            expander = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander(),
            resolver = com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver(),
            materializer = com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = writeBarrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = plannedDao
            ),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            reminderDeliveryDao = deliveryDao,
            lifecycleEventDao = eventDao,
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = writeBarrier,
            plannedExpenseDao = plannedDao,
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider),
            eventWriter = eventWriter
        )

        val projectionService = com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService(
            plannedExpenseDao = plannedDao,
            occurrenceDao = occurrenceDao,
            writeBarrier = writeBarrier
        )

        return com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator(
            database = database,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = deliveryDao,
            plannedExpenseDao = plannedDao,
            lifecycleEventDao = eventDao,
            lifecycleCoordinator = dagger.Lazy { lifecycleCoordinator },
            expander = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander(),
            resolver = com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver(),
            materializer = com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = writeBarrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = plannedDao
            ),
            expenseDao = expenseDao,
            eventWriter = eventWriter,
            planProjectionService = dagger.Lazy { projectionService }
        )
    }

}
