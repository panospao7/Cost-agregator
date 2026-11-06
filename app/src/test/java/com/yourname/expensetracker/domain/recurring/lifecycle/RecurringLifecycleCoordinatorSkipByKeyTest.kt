package com.yourname.expensetracker.domain.recurring.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.util.blockedWriteException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * P-04-2: [RecurringLifecycleCoordinator.skipOccurrenceByKey] resolves an occurrence by its key
 * and routes through the existing atomic status path: occurrence SKIPPED, open planned row
 * CANCELLED, open reminders suppressed, one OCCURRENCE_SKIPPED lifecycle event. An unknown key
 * returns false and writes nothing; an occurrence that is neither PLANNED nor SKIPPED (PAID, MISSED,
 * CANCELLED, IGNORED) also returns false with nothing written, so the caller deletes the row.
 */
class RecurringLifecycleCoordinatorSkipByKeyTest {

    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var reminderDeliveryDao: RecurringReminderDeliveryDao
    private lateinit var lifecycleEventDao: RecurringLifecycleEventDao
    private lateinit var plannedExpenseDao: PlannedExpenseDao
    private lateinit var database: AppDatabase
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var coordinator: RecurringLifecycleCoordinator

    private val now = 1_712_000_000_000L
    private val key = "RECURRING_RULE|1|$now|MONTHLY"

    @Before
    fun setup() {
        occurrenceDao = mockk(relaxed = true)
        reminderDeliveryDao = mockk(relaxed = true)
        lifecycleEventDao = mockk(relaxed = true)
        plannedExpenseDao = mockk(relaxed = true)
        database = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now

        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }

        coordinator = RecurringLifecycleCoordinator(
            database = database,
            expander = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            materializer = mockk(relaxed = true),
            occurrenceDao = occurrenceDao,
            expenseDao = mockk(relaxed = true),
            timeProvider = timeProvider,
            manualRecurringExpenseDao = mockk(relaxed = true),
            reminderDeliveryDao = reminderDeliveryDao,
            lifecycleEventDao = lifecycleEventDao,
            restoreMaintenanceMode = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            readBarrier = io.mockk.mockk<com.yourname.expensetracker.data.backup.DatabaseReadBarrier>(relaxed = true),
            plannedExpenseDao = plannedExpenseDao,
            transactionRunner = mockk(relaxed = true),
            eventWriter = mockk(relaxed = true),
            installmentEligibility = mockk(relaxed = true),
            ruleCoordinator = dagger.Lazy { mockk<RecurringRuleLifecycleCoordinator>(relaxed = true) }
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun occurrence(status: String) = RecurringOccurrence(
        id = 10L,
        sourceType = "RECURRING_RULE",
        sourceId = 1L,
        occurrenceKey = key,
        dueDate = now,
        status = status,
        expectedAmount = 15.0,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = "Synthetic Streaming"
    )

    private fun planned(status: String = "PLANNED") = PlannedExpense(
        id = 77L,
        description = "Synthetic Streaming",
        amount = 15.0,
        currency = "EUR",
        date = now,
        sourceOccurrenceKey = key,
        status = status
    )

    private fun verifyNothingWritten() {
        coVerify(exactly = 0) { occurrenceDao.updateStatus(any(), any(), any()) }
        coVerify(exactly = 0) { plannedExpenseDao.updateStatus(any(), any(), any()) }
        coVerify(exactly = 0) { reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(any(), any(), any()) }
        coVerify(exactly = 0) { lifecycleEventDao.insert(any()) }
    }

    @Test
    fun `P-04-2 skipOccurrenceByKey marks the occurrence SKIPPED, cancels the planned row and suppresses reminders`() =
        runTest(timeout = 60.seconds) {
            coEvery { occurrenceDao.getByKey(key) } returns occurrence("PLANNED")
            coEvery { occurrenceDao.getById(10L) } returns occurrence("PLANNED")
            coEvery { plannedExpenseDao.getBySourceOccurrenceKey(key) } returns planned()

            val skipped = coordinator.skipOccurrenceByKey(key)

            assertTrue(skipped)
            coVerify(exactly = 1) { occurrenceDao.updateStatus(listOf(10L), "SKIPPED", now) }
            coVerify(exactly = 1) { plannedExpenseDao.updateStatus(77L, "CANCELLED", now) }
            coVerify(exactly = 1) {
                reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(10L, now, "occurrence_skipped")
            }
            coVerify(exactly = 1) {
                lifecycleEventDao.insert(
                    match {
                        it.occurrenceId == 10L &&
                            it.eventType == "OCCURRENCE_SKIPPED" &&
                            it.oldStatus == "PLANNED" &&
                            it.newStatus == "SKIPPED"
                    }
                )
            }
        }

    @Test
    fun `P-04-2 skipOccurrenceByKey with an unknown key returns false and writes nothing`() =
        runTest(timeout = 60.seconds) {
            coEvery { occurrenceDao.getByKey("no-such-key") } returns null

            val skipped = coordinator.skipOccurrenceByKey("no-such-key")

            assertFalse(skipped)
            verifyNothingWritten()
        }

    private suspend fun assertNonSkippableReturnsFalseAndWritesNothing(
        occurrenceStatus: String,
        plannedStatus: String
    ) {
        coEvery { occurrenceDao.getByKey(key) } returns occurrence(occurrenceStatus)
        coEvery { occurrenceDao.getById(10L) } returns occurrence(occurrenceStatus)
        coEvery { plannedExpenseDao.getBySourceOccurrenceKey(key) } returns planned(plannedStatus)

        val skipped = coordinator.skipOccurrenceByKey(key)

        assertFalse(skipped)
        verifyNothingWritten()
    }

    @Test
    fun `P-04-2 skipOccurrenceByKey returns false for a PAID occurrence and writes nothing`() =
        runTest(timeout = 60.seconds) {
            assertNonSkippableReturnsFalseAndWritesNothing("PAID", "FULFILLED")
        }

    @Test
    fun `P-04-2 skipOccurrenceByKey returns false for a MISSED occurrence and writes nothing`() =
        runTest(timeout = 60.seconds) {
            assertNonSkippableReturnsFalseAndWritesNothing("MISSED", "PLANNED")
        }

    @Test
    fun `P-04-2 skipOccurrenceByKey returns false for a CANCELLED occurrence and writes nothing`() =
        runTest(timeout = 60.seconds) {
            assertNonSkippableReturnsFalseAndWritesNothing("CANCELLED", "CANCELLED")
        }

    @Test
    fun `P-04-2 skipOccurrenceByKey returns false for an IGNORED occurrence and writes nothing`() =
        runTest(timeout = 60.seconds) {
            assertNonSkippableReturnsFalseAndWritesNothing("IGNORED", "PLANNED")
        }

    @Test
    fun `P-04-2 skipOccurrenceByKey on an already SKIPPED occurrence is idempotent and returns true`() =
        runTest(timeout = 60.seconds) {
            coEvery { occurrenceDao.getByKey(key) } returns occurrence("SKIPPED")
            coEvery { occurrenceDao.getById(10L) } returns occurrence("SKIPPED")
            coEvery { plannedExpenseDao.getBySourceOccurrenceKey(key) } returns planned("CANCELLED")

            val skipped = coordinator.skipOccurrenceByKey(key)

            assertTrue(skipped)
            coVerify(exactly = 1) { occurrenceDao.updateStatus(listOf(10L), "SKIPPED", now) }
            coVerify(exactly = 0) { plannedExpenseDao.updateStatus(any(), any(), any()) }
        }

    @Test
    fun `P-04-2 skipOccurrenceByKey under a raised write barrier throws before any read or write`() =
        runTest(timeout = 60.seconds) {
            every { writeBarrier.checkWritesAllowed(any<String>()) } throws blockedWriteException()

            assertFailsWith<DatabaseAccessBlockedException> { coordinator.skipOccurrenceByKey(key) }

            coVerify(exactly = 0) { occurrenceDao.getByKey(any()) }
            verifyNothingWritten()
        }

    @Test
    fun `P-04-2 skipOccurrenceByKey leaves a non-open planned row untouched`() =
        runTest(timeout = 60.seconds) {
            coEvery { occurrenceDao.getByKey(key) } returns occurrence("PLANNED")
            coEvery { occurrenceDao.getById(10L) } returns occurrence("PLANNED")
            coEvery { plannedExpenseDao.getBySourceOccurrenceKey(key) } returns planned("CANCELLED")

            val skipped = coordinator.skipOccurrenceByKey(key)

            assertEquals(true, skipped)
            coVerify(exactly = 1) { occurrenceDao.updateStatus(listOf(10L), "SKIPPED", now) }
            coVerify(exactly = 0) { plannedExpenseDao.updateStatus(any(), any(), any()) }
        }
}
