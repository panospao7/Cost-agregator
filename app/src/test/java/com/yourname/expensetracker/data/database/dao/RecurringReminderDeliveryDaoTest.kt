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
        failureReason: String? = null
    ) = RecurringReminderDelivery(
        occurrenceId = occurrenceId,
        reminderWindow = "DUE_DAY",
        scheduledAt = scheduledAt,
        status = status,
        attemptCount = attemptCount,
        failureReason = failureReason,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW
    )
}
