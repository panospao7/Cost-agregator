package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.WarrantyReminderDelivery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-16 16-D: bounded claim-repair DAO semantics.
 *
 * 1. [NotificationIntakeDao.claimForProcessing] honours the nextAttemptAt
 *    backoff predicate: a FAILED_RETRYABLE row whose next attempt is scheduled
 *    in the future must NOT be claimable (an idempotent-claim replay or a
 *    concurrent worker must not jump the backoff).
 * 2. [WarrantyReminderDeliveryDao.markFailedByKey] returns an unexpectedly
 *    absent-after-claim delivery to a retryable FAILED state by unique key,
 *    and never regresses a SENT row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkerClaimRepairDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var intakeDao: NotificationIntakeDao
    private lateinit var warrantyDeliveryDao: WarrantyReminderDeliveryDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        intakeDao = database.notificationIntakeDao()
        warrantyDeliveryDao = database.warrantyReminderDeliveryDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ── 16-D: intake claim nextAttemptAt predicate ─────────────────────

    private suspend fun seedIntake(
        id: Long,
        status: String,
        nextAttemptAt: Long? = null
    ): NotificationIntakeEntity {
        val entity = NotificationIntakeEntity(
            id = id,
            packageName = "com.example.app",
            appName = "Example",
            notificationKeyHash = null,
            postTime = 1_700_000_000_000L,
            capturedAt = 1_700_000_000_000L,
            source = "LISTENER",
            correlationId = "corr-$id",
            dedupeFingerprint = "fp-$id",
            contentHash = null,
            title = null,
            text = null,
            bigText = null,
            subText = null,
            extrasJson = null,
            rawStorageMode = "METADATA_ONLY",
            payloadMode = "TRANSIENT",
            status = status,
            attempts = 1,
            maxAttempts = 3,
            nextAttemptAt = nextAttemptAt,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        intakeDao.insertOrIgnore(entity)
        return entity
    }

    @Test
    fun `claim is refused while backoff nextAttemptAt is in the future`() = runTest {
        val now = 1_700_000_000_000L
        seedIntake(id = 1L, status = "FAILED_RETRYABLE", nextAttemptAt = now + 60_000L)

        val claimed = intakeDao.claimForProcessing(id = 1L, nowMs = now, workerId = "worker-a")

        assertEquals("future nextAttemptAt must block the claim (backoff predicate)", 0, claimed)
    }

    @Test
    fun `claim succeeds once the backoff has elapsed`() = runTest {
        val now = 1_700_000_000_000L
        seedIntake(id = 2L, status = "FAILED_RETRYABLE", nextAttemptAt = now - 1L)

        val claimed = intakeDao.claimForProcessing(id = 2L, nowMs = now, workerId = "worker-a")

        assertEquals(1, claimed)
    }

    @Test
    fun `claim succeeds when nextAttemptAt is cleared`() = runTest {
        val now = 1_700_000_000_000L
        seedIntake(id = 3L, status = "RECEIVED", nextAttemptAt = null)

        val claimed = intakeDao.claimForProcessing(id = 3L, nowMs = now, workerId = "worker-a")

        assertEquals(1, claimed)
    }

    @Test
    fun `claim keeps idempotent no-claim for terminal rows regardless of backoff`() = runTest {
        val now = 1_700_000_000_000L
        seedIntake(id = 4L, status = "PROCESSED", nextAttemptAt = null)

        val claimed = intakeDao.claimForProcessing(id = 4L, nowMs = now, workerId = "worker-a")

        assertEquals(0, claimed)
    }

    // ── 16-D: warranty absent-after-claim repair ───────────────────────

    private suspend fun seedWarrantyForFk(): Long {
        database.warrantyDao().insertWarranty(
            com.yourname.expensetracker.data.database.entity.Warranty(
                receiptId = null,
                productName = "Laptop",
                merchantName = "Tech Store",
                purchaseDate = 1_700_000_000_000L,
                warrantyDurationMonths = 24,
                warrantyEndDate = 1_800_000_000_000L,
                warrantyType = com.yourname.expensetracker.data.database.entity.WarrantyType.MANUFACTURER,
                status = com.yourname.expensetracker.data.database.entity.WarrantyStatus.ACTIVE,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L
            )
        )
        return 1L
    }

    private suspend fun seedDelivery(warrantyId: Long, status: String): WarrantyReminderDelivery {
        val delivery = WarrantyReminderDelivery(
            warrantyId = warrantyId,
            windowDays = 7,
            expiryDate = 1_800_000_000_000L,
            status = status,
            attemptCount = 0,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        warrantyDeliveryDao.insertOrIgnore(delivery)
        return warrantyDeliveryDao.getByKey(warrantyId, 7, 1_800_000_000_000L)!!
    }

    @Test
    fun `markFailedByKey returns an unexpectedly absent claimed row to retryable FAILED`() = runTest {
        val warrantyId = seedWarrantyForFk()
        seedDelivery(warrantyId, status = "SCHEDULED")

        // Simulate the claim winning but the re-read unexpectedly missing the row.
        assertEquals(1, warrantyDeliveryDao.claim(warrantyId, 7, 1_800_000_000_000L, now = 1_700_000_100_000L))
        assertNotNull(warrantyDeliveryDao.getByKey(warrantyId, 7, 1_800_000_000_000L))

        val repaired = warrantyDeliveryDao.markFailedByKey(
            warrantyId = warrantyId,
            windowDays = 7,
            expiryDate = 1_800_000_000_000L,
            reason = "claimed_row_unexpectedly_absent",
            now = 1_700_000_200_000L
        )
        assertEquals(1, repaired)

        val row = warrantyDeliveryDao.getByKey(warrantyId, 7, 1_800_000_000_000L)!!
        assertEquals("FAILED", row.status)
        assertEquals("claimed_row_unexpectedly_absent", row.failureReason)
    }

    @Test
    fun `markFailedByKey never regresses a SENT row`() = runTest {
        val warrantyId = seedWarrantyForFk()
        val row = seedDelivery(warrantyId, status = "SCHEDULED")

        assertEquals(1, warrantyDeliveryDao.claim(warrantyId, 7, 1_800_000_000_000L, now = 1_700_000_100_000L))
        assertEquals(1, warrantyDeliveryDao.markSentFromClaimed(row.id, notificationId = 30123, now = 1_700_000_150_000L))

        val repaired = warrantyDeliveryDao.markFailedByKey(
            warrantyId = warrantyId,
            windowDays = 7,
            expiryDate = 1_800_000_000_000L,
            reason = "claimed_row_unexpectedly_absent",
            now = 1_700_000_200_000L
        )
        assertEquals(0, repaired)

        val sent = warrantyDeliveryDao.getByKey(warrantyId, 7, 1_800_000_000_000L)!!
        assertEquals("SENT", sent.status)
    }
}
