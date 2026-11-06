package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

private const val RELEASED_EVENT = "REMINDER_RELEASED_PERMISSION_RETRY"

/**
 * P-04-1: `releaseClaimedReminderDelivery` checks the write barrier, then in ONE transaction moves a
 * CLAIMED delivery back to SCHEDULED and writes the `REMINDER_RELEASED_PERMISSION_RETRY` event. At the
 * attempt cap, or for a delivery that is not CLAIMED, it writes nothing and returns false.
 *
 * Harness: REAL in-memory Room [AppDatabase] (Robolectric) and REAL barrier over a mocked
 * [RestoreMaintenanceMode]. The rollback case swaps in an event DAO that fails on insert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringLifecycleCoordinatorReleaseDeliveryTest {

    private lateinit var database: AppDatabase
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var deliveryDao: RecurringReminderDeliveryDao
    private lateinit var eventDao: RecurringLifecycleEventDao
    private lateinit var maintenance: RestoreMaintenanceMode
    private lateinit var lifecycle: RecurringLifecycleCoordinator

    private val seededAt = 1_790_000_000_000L
    private val claimedAt = seededAt + 1_000L
    private val clockNow = seededAt + 60_000L
    private val timeProvider = object : TimeProvider {
        override fun now() = clockNow
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceDao = database.recurringOccurrenceDao()
        deliveryDao = database.recurringReminderDeliveryDao()
        eventDao = database.recurringLifecycleEventDao()
        maintenance = mockk<RestoreMaintenanceMode>(relaxed = true).apply {
            every { currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            every { isWritesAllowed() } returns true
        }
        lifecycle = buildCoordinator(eventDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun buildCoordinator(coordinatorEventDao: RecurringLifecycleEventDao): RecurringLifecycleCoordinator {
        val barrier = DatabaseWriteBarrier(maintenance)
        val plannedDao = database.plannedExpenseDao()
        val eventWriter = object : RecurringLifecycleEventWriter {
            override suspend fun writeCritical(
                occurrenceId: Long?,
                eventType: String,
                oldStatus: String?,
                newStatus: String?,
                metadata: String?,
                occurredAt: Long
            ): Long = eventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = if (occurredAt == 0L) clockNow else occurredAt,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        }
        val installmentEligibility = RecurringInstallmentEligibility(
            dagger.Lazy { InstallmentEntryLinkReaderImpl(database.installmentPlanDao()) }
        )
        return RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = barrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = plannedDao
            ),
            occurrenceDao = occurrenceDao,
            expenseDao = database.expenseDao(),
            timeProvider = timeProvider,
            manualRecurringExpenseDao = database.manualRecurringExpenseDao(),
            reminderDeliveryDao = deliveryDao,
            lifecycleEventDao = coordinatorEventDao,
            restoreMaintenanceMode = maintenance,
            writeBarrier = barrier,
            readBarrier = io.mockk.mockk<com.yourname.expensetracker.data.backup.DatabaseReadBarrier>(relaxed = true),
            plannedExpenseDao = plannedDao,
            transactionRunner = mockk(relaxed = true),
            eventWriter = eventWriter,
            installmentEligibility = installmentEligibility,
            ruleCoordinator = dagger.Lazy { mockk<RecurringRuleLifecycleCoordinator>(relaxed = true) }
        )
    }

    private suspend fun seedOccurrence(): Long = occurrenceDao.insert(
        RecurringOccurrence(
            sourceType = "RECURRING_RULE",
            sourceId = 1L,
            occurrenceKey = "RECURRING_RULE|1|$seededAt|MONTHLY",
            dueDate = seededAt,
            status = "PLANNED",
            expectedAmount = 25.0,
            expectedCurrency = "EUR",
            frequency = "MONTHLY",
            merchant = "Synthetic Merchant",
            createdAt = seededAt,
            updatedAt = seededAt
        )
    )

    private suspend fun seedDelivery(occurrenceId: Long, status: String, attemptCount: Int): Long =
        deliveryDao.insert(
            RecurringReminderDelivery(
                occurrenceId = occurrenceId,
                reminderWindow = "DUE_DAY",
                scheduledAt = seededAt,
                status = status,
                claimedAt = if (status == "CLAIMED") claimedAt else null,
                lastAttemptAt = if (attemptCount > 0) claimedAt else null,
                attemptCount = attemptCount,
                createdAt = seededAt,
                updatedAt = claimedAt
            )
        )

    private suspend fun releaseEvents() = eventDao.getEventsByType(RELEASED_EVENT)

    @Test
    fun `P-04-1 release below the cap returns true and writes the delivery and the event together`() =
        runTest(timeout = 60.seconds) {
            val occurrenceId = seedOccurrence()
            val deliveryId = seedDelivery(occurrenceId, "CLAIMED", attemptCount = 1)

            assertTrue(lifecycle.releaseClaimedReminderDelivery(deliveryId, 3))

            val row = deliveryDao.getById(deliveryId)!!
            assertEquals("SCHEDULED", row.status)
            assertNull(row.claimedAt)
            assertEquals(clockNow, row.updatedAt)
            assertEquals(1, row.attemptCount)

            val event = releaseEvents().single()
            assertEquals(occurrenceId, event.occurrenceId)
            assertEquals("CLAIMED", event.oldStatus)
            assertEquals("SCHEDULED", event.newStatus)
            assertEquals(clockNow, event.occurredAt)
            val metadata = event.metadata!!.replace(" ", "")
            assertTrue(metadata.contains("\"deliveryId\":$deliveryId"))
            assertTrue(metadata.contains("\"reason\":\"notification_permission_denied\""))
        }

    @Test
    fun `P-04-1 release at the cap returns false and writes no event`() = runTest(timeout = 60.seconds) {
        val occurrenceId = seedOccurrence()
        val deliveryId = seedDelivery(occurrenceId, "CLAIMED", attemptCount = 3)
        val before = deliveryDao.getById(deliveryId)!!

        assertFalse(lifecycle.releaseClaimedReminderDelivery(deliveryId, 3))

        assertEquals(before, deliveryDao.getById(deliveryId))
        assertTrue(releaseEvents().isEmpty())
    }

    @Test
    fun `P-04-1 release one attempt below the cap is the last allowed release`() = runTest(timeout = 60.seconds) {
        val occurrenceId = seedOccurrence()
        val deliveryId = seedDelivery(occurrenceId, "CLAIMED", attemptCount = 2)

        assertTrue(lifecycle.releaseClaimedReminderDelivery(deliveryId, 3))

        assertEquals(1, releaseEvents().size)
    }

    @Test
    fun `P-04-1 a delivery that is not claimed is not released and writes no event`() =
        runTest(timeout = 60.seconds) {
            val occurrenceId = seedOccurrence()
            val deliveryId = seedDelivery(occurrenceId, "SCHEDULED", attemptCount = 0)
            val before = deliveryDao.getById(deliveryId)!!

            assertFalse(lifecycle.releaseClaimedReminderDelivery(deliveryId, 3))

            assertEquals(before, deliveryDao.getById(deliveryId))
            assertTrue(releaseEvents().isEmpty())
        }

    @Test
    fun `P-04-1 an unknown delivery returns false and writes no event`() = runTest(timeout = 60.seconds) {
        assertFalse(lifecycle.releaseClaimedReminderDelivery(9_999L, 3))

        assertTrue(releaseEvents().isEmpty())
    }

    @Test
    fun `P-04-1 a second release of the same delivery is a no-op with a single event`() =
        runTest(timeout = 60.seconds) {
            val occurrenceId = seedOccurrence()
            val deliveryId = seedDelivery(occurrenceId, "CLAIMED", attemptCount = 1)

            assertTrue(lifecycle.releaseClaimedReminderDelivery(deliveryId, 3))
            assertFalse(lifecycle.releaseClaimedReminderDelivery(deliveryId, 3))

            assertEquals(1, releaseEvents().size)
        }

    @Test
    fun `P-04-1 a blocked write barrier throws and writes nothing`() = runTest(timeout = 60.seconds) {
        val occurrenceId = seedOccurrence()
        val deliveryId = seedDelivery(occurrenceId, "CLAIMED", attemptCount = 1)
        val before = deliveryDao.getById(deliveryId)!!
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_STAGING

        assertFailsWith<DatabaseAccessBlockedException> {
            lifecycle.releaseClaimedReminderDelivery(deliveryId, 3)
        }

        assertEquals(before, deliveryDao.getById(deliveryId))
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        assertTrue(releaseEvents().isEmpty())
    }

    @Test
    fun `P-04-1 a failing event insert rolls the delivery release back`() = runTest(timeout = 60.seconds) {
        val occurrenceId = seedOccurrence()
        val deliveryId = seedDelivery(occurrenceId, "CLAIMED", attemptCount = 1)
        val before = deliveryDao.getById(deliveryId)!!
        val failingEventDao = mockk<RecurringLifecycleEventDao>()
        coEvery { failingEventDao.insert(any()) } throws IllegalStateException("synthetic insert failure")
        val failing = buildCoordinator(failingEventDao)

        assertFailsWith<IllegalStateException> {
            failing.releaseClaimedReminderDelivery(deliveryId, 3)
        }

        // The delivery update and the event share one transaction: the failed insert undoes the update.
        assertEquals(before, deliveryDao.getById(deliveryId))
        assertEquals("CLAIMED", deliveryDao.getById(deliveryId)!!.status)
        assertTrue(releaseEvents().isEmpty())
    }
}
