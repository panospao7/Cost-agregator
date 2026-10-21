package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val FIXED_NOW = 1_710_000_000_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringReminderDeliveryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RecurringReminderDeliveryDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.recurringReminderDeliveryDao()
        occurrenceDao = database.recurringOccurrenceDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `failed transient delivery is selected and claimable when scheduled time is due`() = runTest {
        val dueOccurrenceId = occurrenceDao.insert(occurrence("due"))
        val futureOccurrenceId = occurrenceDao.insert(
            occurrence("future", dueDate = FIXED_NOW + 86_400_000L)
        )
        val dueId = dao.insert(
            delivery(
                occurrenceId = dueOccurrenceId,
                status = "FAILED_TRANSIENT",
                scheduledAt = FIXED_NOW - 1L,
                attemptCount = 2,
                failureReason = "notification_error"
            )
        )
        val futureId = dao.insert(
            delivery(
                occurrenceId = futureOccurrenceId,
                status = "FAILED_TRANSIENT",
                scheduledAt = FIXED_NOW + 86_400_000L
            )
        )

        assertEquals(listOf(dueId), dao.getPendingDeliveriesForPlannedOccurrences(FIXED_NOW).map { it.id })
        assertEquals(listOf(dueId), dao.getPendingDeliveries(FIXED_NOW).map { it.id })
        assertEquals(1, dao.claimDelivery(dueId, FIXED_NOW))
        assertEquals(0, dao.claimDelivery(futureId, FIXED_NOW))

        val claimed = dao.getById(dueId)
        assertNotNull(claimed)
        assertEquals("CLAIMED", claimed.status)
        assertEquals(3, claimed.attemptCount)
        assertEquals(FIXED_NOW, claimed.claimedAt)
        assertEquals(FIXED_NOW, claimed.lastAttemptAt)
        assertNull(claimed.failureReason)
    }

    @Test
    fun `inserted scheduled delivery round-trips every field exactly`() = runTest {
        val occurrenceId = occurrenceDao.insert(occurrence("roundtrip"))
        assertEquals(true, occurrenceId > 0L)

        val reminderId = dao.insert(
            delivery(
                occurrenceId = occurrenceId,
                status = "SCHEDULED",
                scheduledAt = FIXED_NOW
            )
        )
        assertEquals(true, reminderId > 0L)

        val loaded = dao.getById(reminderId)
        assertNotNull(loaded)
        assertEquals(reminderId, loaded.id)
        assertEquals(occurrenceId, loaded.occurrenceId)
        assertEquals("DUE_DAY", loaded.reminderWindow)
        assertEquals(1_710_000_000_000L, loaded.scheduledAt)
        assertEquals("SCHEDULED", loaded.status)
        assertNull(loaded.lastSentAt)
        assertNull(loaded.dismissedAt)
        assertNull(loaded.snoozedUntil)
        assertNull(loaded.notificationId)
        assertEquals(1_710_000_000_000L, loaded.createdAt)
        assertNull(loaded.claimedAt)
        assertNull(loaded.lastAttemptAt)
        assertEquals(0, loaded.attemptCount)
        assertNull(loaded.failureReason)
        assertEquals(1_710_000_000_000L, loaded.updatedAt)
    }

    @Test
    fun `cancelOpenDueDeliveriesByIds cancels only open due rows and never CLAIMED`() = runTest {
        val occId = occurrenceDao.insert(occurrence("collapse"))
        val scheduledId = dao.insert(delivery(occId, "SCHEDULED", FIXED_NOW - 10L, window = "W1"))
        val snoozedId = dao.insert(
            delivery(occId, "SNOOZED", FIXED_NOW - 10L, window = "W2", snoozedUntil = FIXED_NOW - 1L)
        )
        val transientId = dao.insert(delivery(occId, "FAILED_TRANSIENT", FIXED_NOW - 10L, window = "W3"))
        val claimedId = dao.insert(delivery(occId, "CLAIMED", FIXED_NOW - 10L, window = "W4"))
        val sentId = dao.insert(delivery(occId, "SENT", FIXED_NOW - 10L, window = "W5"))
        val futureId = dao.insert(delivery(occId, "SCHEDULED", FIXED_NOW + 10_000L, window = "W6"))

        val cancelled = dao.cancelOpenDueDeliveriesByIds(
            listOf(scheduledId, snoozedId, transientId, claimedId, sentId, futureId),
            "STALE_WINDOW_SUPERSEDED",
            FIXED_NOW
        )

        assertEquals(3, cancelled)
        for (id in listOf(scheduledId, snoozedId, transientId)) {
            val row = dao.getById(id)
            assertNotNull(row)
            assertEquals("CANCELLED", row.status)
            assertEquals("STALE_WINDOW_SUPERSEDED", row.failureReason)
            assertEquals(FIXED_NOW, row.updatedAt)
        }
        assertEquals("CLAIMED", dao.getById(claimedId)!!.status)
        assertEquals("SENT", dao.getById(sentId)!!.status)
        assertEquals("SCHEDULED", dao.getById(futureId)!!.status)
        // Idempotent: already cancelled rows are not touched again.
        assertEquals(
            0,
            dao.cancelOpenDueDeliveriesByIds(listOf(scheduledId, snoozedId, transientId), "OTHER", FIXED_NOW)
        )
    }

    private fun occurrence(key: String, dueDate: Long = FIXED_NOW) = RecurringOccurrence(
        sourceType = "RECURRING_RULE",
        sourceId = key.hashCode().toLong(),
        occurrenceKey = "RECURRING_RULE|$key|MONTHLY",
        dueDate = dueDate,
        status = "PLANNED",
        expectedAmount = 25.0,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = "Test Merchant",
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW
    )

    private fun delivery(
        occurrenceId: Long,
        status: String,
        scheduledAt: Long,
        attemptCount: Int = 0,
        failureReason: String? = null,
        window: String = "DUE_DAY",
        snoozedUntil: Long? = null
    ) = RecurringReminderDelivery(
        occurrenceId = occurrenceId,
        reminderWindow = window,
        scheduledAt = scheduledAt,
        snoozedUntil = snoozedUntil,
        status = status,
        attemptCount = attemptCount,
        failureReason = failureReason,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW
    )
}
