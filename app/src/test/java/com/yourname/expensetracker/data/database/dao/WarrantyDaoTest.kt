package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WarrantyDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var warrantyDao: WarrantyDao
    private lateinit var scannedReceiptDao: ScannedReceiptDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())

        warrantyDao = database.warrantyDao()
        scannedReceiptDao = database.scannedReceiptDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    private suspend fun insertReceipt(createdAt: Long = BASE_NOW): Long {
        return scannedReceiptDao.insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "raw",
                parsedTotal = 120.0,
                parsedMerchant = "Test Merchant",
                parsedDate = createdAt,
                parsedItems = null,
                parsedTaxAmount = null,
                confidence = 0.9f,
                createdAt = createdAt
            )
        )
    }

    private fun makeWarranty(
        receiptId: Long,
        warrantyEndDate: Long,
        status: WarrantyStatus = WarrantyStatus.ACTIVE,
        updatedAt: Long = BASE_NOW,
        needsReview: Boolean = false
    ) = Warranty(
        receiptId = receiptId,
        expenseId = null,
        productName = "Laptop",
        merchantName = "Tech Store",
        purchaseDate = 1_700_000_000_000L,
        warrantyDurationMonths = 24,
        warrantyEndDate = warrantyEndDate,
        status = status,
        createdAt = 1_700_000_000_000L,
        updatedAt = updatedAt,
        needsReview = needsReview
    )

    @Test
    fun insertWarranty_retrieveByReceiptId() = runTest {
        val receiptId = insertReceipt()
        warrantyDao.insertWarranty(makeWarranty(receiptId, warrantyEndDate = 1_800_000_000_000L))

        val fetched = warrantyDao.getWarrantyByReceiptId(receiptId)
        assertNotNull(fetched)
        assertEquals(receiptId, fetched!!.receiptId)
        assertEquals("Laptop", fetched.productName)
    }

    @Test
    fun queryActiveWarranties_returnsOnlyNotExpiredActive() = runTest {
        val now = 1_700_000_000_000L

        val activeReceipt = insertReceipt(now)
        val expiredReceipt = insertReceipt(now + 1)
        val claimedReceipt = insertReceipt(now + 2)

        warrantyDao.insertWarranty(makeWarranty(activeReceipt, warrantyEndDate = now + 86_400_000L, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(expiredReceipt, warrantyEndDate = now - 1, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(claimedReceipt, warrantyEndDate = now + 86_400_000L, status = WarrantyStatus.CLAIMED))

        val active = warrantyDao.getActiveWarranties(now).first()

        assertEquals(1, active.size)
        assertEquals(activeReceipt, active[0].receiptId)
    }

    @Test
    fun queryExpiredWarranties_returnsOnlyRecentlyExpiredActive() = runTest {
        val now = 1_700_000_000_000L

        val expiredActiveReceipt = insertReceipt(now)
        val futureActiveReceipt = insertReceipt(now + 1)
        val expiredAlreadyMarkedReceipt = insertReceipt(now + 2)

        warrantyDao.insertWarranty(makeWarranty(expiredActiveReceipt, warrantyEndDate = now - 10_000, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(futureActiveReceipt, warrantyEndDate = now + 10_000, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(expiredAlreadyMarkedReceipt, warrantyEndDate = now - 10_000, status = WarrantyStatus.EXPIRED))

        val expired = warrantyDao.getRecentlyExpiredWarranties(now)

        assertEquals(1, expired.size)
        assertEquals(expiredActiveReceipt, expired[0].receiptId)
    }

    @Test
    fun updateWarrantyStatus_changesStatusAndUpdatedAt() = runTest {
        val now = 1_700_000_000_000L
        val receiptId = insertReceipt(now)
        val warrantyId = warrantyDao.insertWarranty(
            makeWarranty(
                receiptId = receiptId,
                warrantyEndDate = now + 100_000,
                status = WarrantyStatus.ACTIVE,
                updatedAt = now
            )
        )

        val newUpdatedAt = now + 5_000
        warrantyDao.updateWarrantyStatus(warrantyId, WarrantyStatus.CLAIMED, claimedAt = null, updatedAt = newUpdatedAt)

        val updated = warrantyDao.getWarrantyByReceiptId(receiptId)
        assertNotNull(updated)
        assertEquals(WarrantyStatus.CLAIMED, updated!!.status)
        assertEquals(newUpdatedAt, updated.updatedAt)
        assertNull(updated.claimedAt) // query updates status/updatedAt only
    }

    // ------------------------------------------------------------------
    // ai-fixes B1a (INV-11, INV-12, INV-14): widened expiry, pending count, ACTIVE-only reminders.
    // ------------------------------------------------------------------

    private suspend fun byReceipt(receiptId: Long): Warranty =
        warrantyDao.getWarrantyByReceiptId(receiptId)!!

    @Test
    fun markExpiredWarranties_expiresActiveAndPendingPastEnd_keepsNeedsReviewAndLeavesOthers() = runTest {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val activePast = insertReceipt(now)
        val pendingPast = insertReceipt(now + 1)
        val pendingFuture = insertReceipt(now + 2)
        val claimedPast = insertReceipt(now + 3)
        val expiredPast = insertReceipt(now + 4)
        warrantyDao.insertWarranty(makeWarranty(activePast, now - 2 * day, WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(pendingPast, now - day, WarrantyStatus.PENDING_REVIEW, needsReview = true))
        warrantyDao.insertWarranty(makeWarranty(pendingFuture, now + 5 * day, WarrantyStatus.PENDING_REVIEW, needsReview = true))
        warrantyDao.insertWarranty(makeWarranty(claimedPast, now - day, WarrantyStatus.CLAIMED))
        warrantyDao.insertWarranty(makeWarranty(expiredPast, now - day, WarrantyStatus.EXPIRED, needsReview = true))

        val pendingCount = warrantyDao.getExpirablePendingWarrantyCount(now)
        val changed = warrantyDao.markExpiredWarranties(currentTime = now, updatedAt = now + 10)

        assertEquals(1, pendingCount)
        assertEquals(2, changed) // activePast + pendingPast only
        assertEquals(WarrantyStatus.EXPIRED, byReceipt(activePast).status)
        val expiredPending = byReceipt(pendingPast)
        assertEquals(WarrantyStatus.EXPIRED, expiredPending.status)
        assertEquals("expired pending stays unconfirmed", true, expiredPending.needsReview)
        assertEquals(now + 10, expiredPending.updatedAt)
        assertEquals(WarrantyStatus.PENDING_REVIEW, byReceipt(pendingFuture).status)
        assertEquals(WarrantyStatus.CLAIMED, byReceipt(claimedPast).status)
        // Already-EXPIRED row is not rewritten (updatedAt unchanged).
        assertEquals(BASE_NOW, byReceipt(expiredPast).updatedAt)

        // Idempotent: a second sweep changes nothing and the pending count is now zero.
        assertEquals(0, warrantyDao.markExpiredWarranties(currentTime = now, updatedAt = now + 20))
        assertEquals(0, warrantyDao.getExpirablePendingWarrantyCount(now))
    }

    @Test
    fun markExpiredWarranties_boundaryIsStrictlyLessThanNow() = runTest {
        val now = 1_700_000_000_000L
        val atBoundary = insertReceipt(now)
        val justBefore = insertReceipt(now + 1)
        warrantyDao.insertWarranty(makeWarranty(atBoundary, now, WarrantyStatus.PENDING_REVIEW, needsReview = true))
        warrantyDao.insertWarranty(makeWarranty(justBefore, now - 1, WarrantyStatus.PENDING_REVIEW, needsReview = true))

        assertEquals(1, warrantyDao.getExpirablePendingWarrantyCount(now))
        assertEquals(1, warrantyDao.markExpiredWarranties(now, now))
        assertEquals(WarrantyStatus.PENDING_REVIEW, warrantyDao.getWarrantyByReceiptId(atBoundary)!!.status)
        assertEquals(WarrantyStatus.EXPIRED, warrantyDao.getWarrantyByReceiptId(justBefore)!!.status)
    }

    @Test
    fun getWarrantyById_returnsRowOrNull() = runTest {
        val receiptId = insertReceipt()
        val id = warrantyDao.insertWarranty(makeWarranty(receiptId, 1_800_000_000_000L))

        assertEquals(receiptId, warrantyDao.getWarrantyById(id)!!.receiptId)
        assertNull(warrantyDao.getWarrantyById(id + 999))
    }

    @Test
    fun reminderQueries_considerOnlyActive_pendingAndExpiredUnconfirmedNeverSelected() = runTest {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val active = insertReceipt(now)
        val pendingSoon = insertReceipt(now + 1)
        val expiredUnconfirmed = insertReceipt(now + 2)
        val expiredConfirmed = insertReceipt(now + 3)
        warrantyDao.insertWarranty(makeWarranty(active, now + 3 * day, WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(pendingSoon, now + 3 * day, WarrantyStatus.PENDING_REVIEW, needsReview = true))
        warrantyDao.insertWarranty(makeWarranty(expiredUnconfirmed, now - day, WarrantyStatus.EXPIRED, needsReview = true))
        warrantyDao.insertWarranty(makeWarranty(expiredConfirmed, now - day, WarrantyStatus.EXPIRED))

        val expiringSoon = warrantyDao.getWarrantiesExpiringSoon(futureTime = now + 7 * day, currentTime = now)
        val activeList = warrantyDao.getActiveWarranties(now).first()
        val recentlyExpired = warrantyDao.getRecentlyExpiredWarranties(now)

        assertEquals(listOf(active), expiringSoon.map { it.receiptId })
        assertEquals(listOf(active), activeList.map { it.receiptId })
        assertEquals(emptyList<Long?>(), recentlyExpired.map { it.receiptId })
    }

    private companion object {
        /** Fixed base instant (2026-05-15 12:00 UTC); replaces the wall clock. */
        val BASE_NOW: Long = dateMs(2026, 5, 15, 12, 0)
    }
}
