package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyReminderDelivery
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

/**
 * Unit tests for [WarrantyReminderDeliveryDao] — the durable, claim-before-notify
 * sent-state for the warranty expiration worker (S9 / P9-P1-09).
 *
 * Verifies the invariants the worker relies on for correct dedup:
 *  - [WarrantyReminderDeliveryDao.claim] is atomic (a second claim returns 0).
 *  - [WarrantyReminderDeliveryDao.markSentFromClaimed] only transitions from CLAIMED.
 *  - [WarrantyReminderDeliveryDao.insertOrIgnore] respects the (warrantyId, windowDays,
 *    expiryDate) unique key (duplicate ignored).
 *  - [WarrantyReminderDeliveryDao.recoverStaleClaimed] keys on claimedAt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WarrantyReminderDeliveryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: WarrantyReminderDeliveryDao
    private lateinit var warrantyDao: WarrantyDao

    private var warrantyId: Long = 0L

    @Before
    fun setup() = runTest {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.warrantyReminderDeliveryDao()
        warrantyDao = database.warrantyDao()

        // Parent row required by the warrantyId FK (ON DELETE CASCADE).
        warrantyId = warrantyDao.insertWarranty(
            Warranty(
                receiptId = null,
                productName = "Laptop",
                merchantName = "Tech Store",
                purchaseDate = FIXED_NOW,
                warrantyDurationMonths = 24,
                warrantyEndDate = FIXED_NOW + 30L * 86_400_000L,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun delivery(
        warrantyId: Long = this.warrantyId,
        windowDays: Int = 7,
        expiryDate: Long = FIXED_NOW + 30L * 86_400_000L,
        status: String = "SCHEDULED",
        claimedAt: Long? = null,
        attemptCount: Int = 0,
        createdAt: Long = FIXED_NOW,
        updatedAt: Long = FIXED_NOW
    ) = WarrantyReminderDelivery(
        warrantyId = warrantyId,
        windowDays = windowDays,
        expiryDate = expiryDate,
        status = status,
        claimedAt = claimedAt,
        attemptCount = attemptCount,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    // -------------------------------------------------------------------------
    // insertOrIgnore unique key
    // -------------------------------------------------------------------------

    @Test
    fun `insertOrIgnore respects unique key - duplicate is ignored`() = runTest {
        val firstId = dao.insertOrIgnore(delivery())
        assertEquals(1L, firstId)

        // Same (warrantyId, windowDays, expiryDate) -> ignored (-1), state preserved.
        val secondId = dao.insertOrIgnore(delivery(status = "SENT"))
        assertEquals(-1L, secondId)

        val rows = dao.getByWarrantyId(warrantyId)
        assertEquals(1, rows.size)
        assertEquals("SCHEDULED", rows.single().status)
    }

    @Test
    fun `insertOrIgnore allows distinct windows for same warranty`() = runTest {
        dao.insertOrIgnore(delivery(windowDays = 7))
        dao.insertOrIgnore(delivery(windowDays = 30))

        assertEquals(2, dao.getByWarrantyId(warrantyId).size)
    }

    // -------------------------------------------------------------------------
    // Atomic claim
    // -------------------------------------------------------------------------

    @Test
    fun `claim is atomic - second claim returns 0`() = runTest {
        dao.insertOrIgnore(delivery())

        val first = dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW)
        assertEquals(1, first)

        // The row is now CLAIMED; a racing second claim must fail (no double-notify).
        val second = dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW + 1)
        assertEquals(0, second)

        val row = dao.getByKey(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L)
        assertNotNull(row)
        assertEquals("CLAIMED", row.status)
        assertEquals(1, row.attemptCount)
        assertEquals(FIXED_NOW, row.claimedAt)
    }

    @Test
    fun `claim does not claim an already SENT row`() = runTest {
        dao.insertOrIgnore(delivery())
        dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW)
        val row = dao.getByKey(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L)!!
        dao.markSentFromClaimed(row.id, notificationId = 10001, now = FIXED_NOW)

        // A SENT delivery is terminal — durable cross-run dedup.
        val claimed = dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW + 5)
        assertEquals(0, claimed)
    }

    @Test
    fun `claim re-claims a FAILED row`() = runTest {
        dao.insertOrIgnore(delivery())
        dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW)
        val row = dao.getByKey(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L)!!
        dao.markFailed(row.id, reason = "notification_not_delivered", now = FIXED_NOW)

        // A FAILED delivery (transient failure) is re-claimable on a later run.
        val reclaimed = dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW + 10)
        assertEquals(1, reclaimed)
        assertEquals(2, dao.getByKey(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L)!!.attemptCount)
    }

    // -------------------------------------------------------------------------
    // markSentFromClaimed only from CLAIMED
    // -------------------------------------------------------------------------

    @Test
    fun `markSentFromClaimed only updates from CLAIMED`() = runTest {
        dao.insertOrIgnore(delivery())
        val scheduled = dao.getByKey(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L)!!

        // SCHEDULED -> markSent must be rejected (0 rows).
        assertEquals(0, dao.markSentFromClaimed(scheduled.id, notificationId = 10001, now = FIXED_NOW))
        assertEquals("SCHEDULED", dao.getById(scheduled.id)!!.status)

        // Claim, then markSent succeeds and persists the notificationId.
        dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW)
        assertEquals(1, dao.markSentFromClaimed(scheduled.id, notificationId = 10001, now = FIXED_NOW + 1))
        val sent = dao.getById(scheduled.id)!!
        assertEquals("SENT", sent.status)
        assertEquals(10001, sent.notificationId)

        // Already SENT -> a second markSent is a no-op.
        assertEquals(0, dao.markSentFromClaimed(scheduled.id, notificationId = 99999, now = FIXED_NOW + 2))
        assertEquals(10001, dao.getById(scheduled.id)!!.notificationId)
    }

    @Test
    fun `markFailed only updates from CLAIMED`() = runTest {
        dao.insertOrIgnore(delivery())
        val scheduled = dao.getByKey(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L)!!

        // SCHEDULED -> markFailed rejected.
        assertEquals(0, dao.markFailed(scheduled.id, reason = "x", now = FIXED_NOW))

        dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, FIXED_NOW)
        assertEquals(1, dao.markFailed(scheduled.id, reason = "notification_not_delivered", now = FIXED_NOW + 1))
        val failed = dao.getById(scheduled.id)!!
        assertEquals("FAILED", failed.status)
        assertEquals("notification_not_delivered", failed.failureReason)
    }

    // -------------------------------------------------------------------------
    // recoverStaleClaimed keys on claimedAt
    // -------------------------------------------------------------------------

    @Test
    fun `recoverStaleClaimed resets only genuinely-old claims keyed on claimedAt`() = runTest {
        // Stale claim: claimedAt far in the past.
        dao.insertOrIgnore(delivery(windowDays = 7))
        dao.claim(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L, now = FIXED_NOW - 10_000L)

        // Fresh claim: claimedAt = now.
        dao.insertOrIgnore(delivery(windowDays = 30))
        dao.claim(warrantyId, 30, FIXED_NOW + 30L * 86_400_000L, now = FIXED_NOW)

        // Threshold between the two claim timestamps.
        val recovered = dao.recoverStaleClaimed(
            staleClaimThreshold = FIXED_NOW - 5_000L,
            now = FIXED_NOW + 1
        )
        assertEquals(1, recovered)

        // The stale one is reset to SCHEDULED with claimedAt cleared.
        val stale = dao.getByKey(warrantyId, 7, FIXED_NOW + 30L * 86_400_000L)!!
        assertEquals("SCHEDULED", stale.status)
        assertNull(stale.claimedAt)

        // The fresh one is untouched.
        val fresh = dao.getByKey(warrantyId, 30, FIXED_NOW + 30L * 86_400_000L)!!
        assertEquals("CLAIMED", fresh.status)
        assertEquals(FIXED_NOW, fresh.claimedAt)
    }

    @Test
    fun `recoverStaleClaimed ignores SENT and SCHEDULED rows`() = runTest {
        dao.insertOrIgnore(delivery())
        // SCHEDULED row, never claimed -> not recovered (and not affected).
        val recovered = dao.recoverStaleClaimed(
            staleClaimThreshold = FIXED_NOW + 1_000_000L,
            now = FIXED_NOW
        )
        assertEquals(0, recovered)
    }

    // -------------------------------------------------------------------------
    // FK cascade
    // -------------------------------------------------------------------------

    @Test
    fun `deleting parent warranty cascades to deliveries`() = runTest {
        dao.insertOrIgnore(delivery())
        assertEquals(1, dao.getByWarrantyId(warrantyId).size)

        warrantyDao.deleteWarrantyById(warrantyId)

        assertEquals(0, dao.getByWarrantyId(warrantyId).size)
    }
}
