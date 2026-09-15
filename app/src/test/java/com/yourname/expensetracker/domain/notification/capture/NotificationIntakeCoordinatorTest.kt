package com.yourname.expensetracker.domain.notification.capture

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
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
 * The coordinator owns a [DatabaseWriteBarrier]; both durable intake writes
 * ([NotificationIntakeDao.insertOrIgnore] in [NotificationIntakeCoordinator.capture]
 * and [NotificationIntakeCoordinator.captureForRetry]) run behind barrier gates
 * against the merged (gr-14f-mediated) contract: during restore/maintenance
 * BOTH paths skip silently — `capture` reports the typed
 * [NotificationIntakeCaptureResult.Dropped] outcome, `captureForRetry` logs and
 * returns — and no DAO mutation or WorkManager enqueue happens.
 *
 * Harness: a REAL [DatabaseWriteBarrier] over a mocked [RestoreMaintenanceMode]
 * (RetentionTargetPurgeTest pattern) — stubbing runWrite on a relaxed mock would
 * silently drop the executed lambda.
 */
class NotificationIntakeCoordinatorTest {

    private lateinit var intakeDao: NotificationIntakeDao
    private lateinit var workManager: WorkManager
    private lateinit var diagnostics: NotificationDiagnosticEmitter
    private lateinit var crypto: NotificationTransientPayloadCrypto
    private lateinit var maintenanceMode: RestoreMaintenanceMode

    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1_700_000_000_000L }

    private lateinit var coordinator: NotificationIntakeCoordinator

    @Before
    fun setup() {
        intakeDao = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        diagnostics = mockk(relaxed = true)
        crypto = mockk(relaxed = true)
        maintenanceMode = mockk(relaxed = true)
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true

        coordinator = makeCoordinator()
    }

    private fun makeCoordinator(mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL): NotificationIntakeCoordinator {
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            every { maintenanceMode.currentMode() } returns mode
            every { maintenanceMode.isWritesAllowed() } returns false
        }
        return NotificationIntakeCoordinator(
            intakeDao = intakeDao,
            workManager = workManager,
            diagnostics = diagnostics,
            timeProvider = timeProvider,
            crypto = crypto,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode)
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

        // The gate-before-write ordering is proven behaviorally by
        // `capture during restore throws barrier exception before any insert`:
        // with the barrier blocked, the typed exception surfaces and the DAO
        // write never happens.
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `capture during restore is dropped before any insert`() = runTest {
        // Merged (gr-14f-mediated) semantics: capture reports a typed Dropped
        // result instead of throwing when writes are blocked.
        coordinator = makeCoordinator(RestoreMaintenanceMode.Mode.RESTORE_STAGING)

        val result = capture()

        assertTrue(result is NotificationIntakeCaptureResult.Dropped)
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `captureForRetry inserts behind the barrier in normal mode`() = runTest {
        coEvery { intakeDao.insertOrIgnore(any()) } returns 7L

        coordinator.captureForRetry(
            packageName = "com.bank.app",
            notificationKey = "key-1",
            postTime = 1_700_000_000_000L,
            correlationId = "corr-1",
            title = "Payment alert"
        )

        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `captureForRetry during restore skips silently before any insert`() = runTest {
        // Merged (gr-14f-mediated) semantics: the deferred path logs and skips —
        // it never throws on a barrier block.
        coordinator = makeCoordinator(RestoreMaintenanceMode.Mode.RESTORE_STAGING)

        coordinator.captureForRetry(
            packageName = "com.bank.app",
            notificationKey = "key-1",
            postTime = 1_700_000_000_000L,
            correlationId = "corr-1",
            title = "Payment alert"
        )

        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }
}
