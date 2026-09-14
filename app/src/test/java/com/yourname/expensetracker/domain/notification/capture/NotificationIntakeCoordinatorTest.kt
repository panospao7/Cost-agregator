package com.yourname.expensetracker.domain.notification.capture

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-02 U-004: barrier ownership tests for [NotificationIntakeCoordinator].
 *
 * The coordinator owns a [DatabaseWriteBarrier] and must check it immediately
 * before each durable intake write ([NotificationIntakeDao.insertOrIgnore]) in
 * both [NotificationIntakeCoordinator.capture] and
 * [NotificationIntakeCoordinator.captureForRetry]. During restore/maintenance
 * the typed [DatabaseAccessBlockedException] must surface before any DAO
 * mutation occurs.
 */
class NotificationIntakeCoordinatorTest {

    private lateinit var intakeDao: NotificationIntakeDao
    private lateinit var workManager: WorkManager
    private lateinit var diagnostics: NotificationDiagnosticEmitter
    private lateinit var crypto: NotificationTransientPayloadCrypto
    private lateinit var writeBarrier: DatabaseWriteBarrier

    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1_700_000_000_000L }

    private lateinit var coordinator: NotificationIntakeCoordinator

    @Before
    fun setup() {
        intakeDao = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        diagnostics = mockk(relaxed = true)
        crypto = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)

        coordinator = NotificationIntakeCoordinator(
            intakeDao = intakeDao,
            workManager = workManager,
            diagnostics = diagnostics,
            timeProvider = timeProvider,
            crypto = crypto,
            writeBarrier = writeBarrier
        )
    }

    private fun blockBarrier() {
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.WRITE,
                operation = DatabaseAccessOperation("blocked"),
                mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
            )
    }

    private suspend fun capture(): NotificationIntakeCaptureResult =
        coordinator.capture(
            packageName = "com.bank.app",
            appName = "Bank",
            notificationKey = "key-1",
            notificationKeyHash = "hash-1",
            postTime = 1_700_000_000_000L,
            title = "Payment alert",
            text = "You paid 50 EUR",
            combinedBody = null,
            subText = null,
            extrasJson = null,
            rawStorageMode = RawStorageMode.STORE_RAW,
            correlationId = "corr-1",
            source = "listener"
        )

    @Test
    fun `capture checks barrier before the intake insert`() = runTest {
        coEvery { intakeDao.insertOrIgnore(any()) } returns 42L

        val result = capture()

        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        assertEquals(42L, (result as NotificationIntakeCaptureResult.Enqueued).intakeId)

        // The barrier check must happen BEFORE the DAO write (order-sensitive).
        coVerifyOrder {
            writeBarrier.checkWritesAllowed("notification.intake.capture")
            intakeDao.insertOrIgnore(any())
        }
    }

    @Test
    fun `capture during restore throws barrier exception before any insert`() = runTest {
        blockBarrier()

        try {
            capture()
            throw AssertionError("Expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            // typed restore-block surfaced as today
        }

        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `captureForRetry checks barrier before the deferred intake insert`() = runTest {
        coEvery { intakeDao.insertOrIgnore(any()) } returns 7L

        coordinator.captureForRetry(
            packageName = "com.bank.app",
            notificationKey = "key-1",
            postTime = 1_700_000_000_000L,
            correlationId = "corr-1",
            title = "Payment alert"
        )

        coVerifyOrder {
            writeBarrier.checkWritesAllowed("notification.intake.capture.deferred")
            intakeDao.insertOrIgnore(any())
        }
    }

    @Test
    fun `captureForRetry during restore throws barrier exception before any insert`() = runTest {
        blockBarrier()

        try {
            coordinator.captureForRetry(
                packageName = "com.bank.app",
                notificationKey = "key-1",
                postTime = 1_700_000_000_000L,
                correlationId = "corr-1",
                title = "Payment alert"
            )
            throw AssertionError("Expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            // typed restore-block surfaced as today
        }

        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }
}
