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

/**
 * P-04-1: `releaseClaimedDelivery` gives a CLAIMED delivery back to SCHEDULED (clearing `claimedAt`)
 * only while `attemptCount < maxAttempts`. Real in-memory Room; synthetic rows only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringReminderDeliveryDaoReleaseClaimedTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RecurringReminderDeliveryDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private var occurrenceId = 0L

    private val seededAt = 1_710_000_000_000L
    private val claimedAt = seededAt + 1_000L
    private val releaseNow = seededAt + 60_000L

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext()).build()
        dao = database.recurringReminderDeliveryDao()
        occurrenceDao = database.recurringOccurrenceDao()
        occurrenceId = kotlinx.coroutines.runBlocking {
            occurrenceDao.insert(
                RecurringOccurrence(
                    sourceType = "RECURRING_RULE",
                    sourceId = 1L,
                    occurrenceKey = "RECURRING_RULE|release|MONTHLY",
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
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertDelivery(
        status: String,
        attemptCount: Int,
        window: String = "DUE_DAY",
        claimed: Long? = if (status == "CLAIMED") claimedAt else null
    ): Long = dao.insert(
        RecurringReminderDelivery(
            occurrenceId = occurrenceId,
            reminderWindow = window,
            scheduledAt = seededAt,
            status = status,
            claimedAt = claimed,
            lastAttemptAt = if (attemptCount > 0) claimedAt else null,
            attemptCount = attemptCount,
            failureReason = null,
            createdAt = seededAt,
            updatedAt = claimedAt
        )
    )

    @Test
    fun `INV P-04-1 releases a claimed delivery below the cap back to scheduled`() = runTest {
        val id = insertDelivery("CLAIMED", attemptCount = 1)

        assertEquals(1, dao.releaseClaimedDelivery(id, 3, releaseNow))

        val row = assertNotNull(dao.getById(id))
        assertEquals("SCHEDULED", row.status)
        assertNull(row.claimedAt)
        assertEquals(releaseNow, row.updatedAt)
        // The attempt counter is the retry budget: release must not reset or change it.
        assertEquals(1, row.attemptCount)
        assertEquals(claimedAt, row.lastAttemptAt)
        assertEquals(seededAt, row.scheduledAt)
        assertNull(row.failureReason)
    }

    @Test
    fun `INV P-04-1 released delivery is selectable and claimable again`() = runTest {
        val id = insertDelivery("CLAIMED", attemptCount = 1)
        assertEquals(1, dao.releaseClaimedDelivery(id, 3, releaseNow))

        assertEquals(listOf(id), dao.getPendingDeliveriesForPlannedOccurrences(releaseNow).map { it.id })
        assertEquals(1, dao.claimDelivery(id, releaseNow))
        assertEquals(2, assertNotNull(dao.getById(id)).attemptCount)
    }

    @Test
    fun `INV P-04-1 one attempt below the cap still releases`() = runTest {
        val id = insertDelivery("CLAIMED", attemptCount = 2)

        assertEquals(1, dao.releaseClaimedDelivery(id, 3, releaseNow))

        assertEquals("SCHEDULED", assertNotNull(dao.getById(id)).status)
    }

    @Test
    fun `INV P-04-1 at the cap returns zero and leaves the row unchanged`() = runTest {
        val id = insertDelivery("CLAIMED", attemptCount = 3)
        val before = assertNotNull(dao.getById(id))

        assertEquals(0, dao.releaseClaimedDelivery(id, 3, releaseNow))

        assertEquals(before, dao.getById(id))
        assertEquals("CLAIMED", before.status)
        assertEquals(claimedAt, before.claimedAt)
    }

    @Test
    fun `INV P-04-1 above the cap returns zero and leaves the row unchanged`() = runTest {
        val id = insertDelivery("CLAIMED", attemptCount = 7)
        val before = assertNotNull(dao.getById(id))

        assertEquals(0, dao.releaseClaimedDelivery(id, 3, releaseNow))

        assertEquals(before, dao.getById(id))
    }

    @Test
    fun `INV P-04-1 a non-claimed delivery returns zero and is never modified`() = runTest {
        val statuses = listOf("SCHEDULED", "SENT", "CANCELLED", "DISMISSED", "SNOOZED", "FAILED_TRANSIENT", "FAILED_FINAL")
        statuses.forEachIndexed { index, status ->
            val id = insertDelivery(status, attemptCount = 0, window = "W$index")
            val before = assertNotNull(dao.getById(id))

            assertEquals(0, dao.releaseClaimedDelivery(id, 3, releaseNow), "status=$status")

            assertEquals(before, dao.getById(id), "status=$status")
        }
    }

    @Test
    fun `INV P-04-1 an unknown delivery id returns zero`() = runTest {
        assertEquals(0, dao.releaseClaimedDelivery(9_999L, 3, releaseNow))
    }

    @Test
    fun `INV P-04-1 only the addressed delivery is released`() = runTest {
        val target = insertDelivery("CLAIMED", attemptCount = 1, window = "A")
        val other = insertDelivery("CLAIMED", attemptCount = 1, window = "B")
        val otherBefore = assertNotNull(dao.getById(other))

        assertEquals(1, dao.releaseClaimedDelivery(target, 3, releaseNow))

        assertEquals(otherBefore, dao.getById(other))
        assertEquals("SCHEDULED", assertNotNull(dao.getById(target)).status)
    }

    @Test
    fun `INV P-04-1 a second release of the same delivery is a no-op`() = runTest {
        val id = insertDelivery("CLAIMED", attemptCount = 1)
        assertEquals(1, dao.releaseClaimedDelivery(id, 3, releaseNow))
        val afterFirst = assertNotNull(dao.getById(id))

        assertEquals(0, dao.releaseClaimedDelivery(id, 3, releaseNow + 5_000L))

        assertEquals(afterFirst, dao.getById(id))
    }
}
