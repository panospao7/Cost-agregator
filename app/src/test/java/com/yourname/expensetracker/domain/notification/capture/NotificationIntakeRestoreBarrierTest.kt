package com.yourname.expensetracker.domain.notification.capture

import android.content.Context
import androidx.work.WorkManager
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GR-14u6: the notification-capture writers must be blocked by
 * [DatabaseWriteBarrier] during restore/maintenance mode, mirroring the
 * worker-path sibling (NotificationIntakeWorker's runGuardedWithContext).
 *
 * These writers are reached from framework callbacks with no enclosing
 * CoroutineExceptionHandler, so a blocked write must NOT escape as an
 * exception — each entry point skips (or returns Dropped) instead.
 */
class NotificationIntakeRestoreBarrierTest {

    private val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val timeProvider = FakeTimeProvider(1716163200000L)
    private val crypto =
        NotificationTransientPayloadCrypto(mockk<NotificationTransientKeyProvider>(relaxed = true))

    private fun maintenanceMode(modeValue: String): RestoreMaintenanceMode {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns modeValue
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return RestoreMaintenanceMode(context, timeProvider)
    }

    private fun coordinator(modeValue: String): NotificationIntakeCoordinator {
        val mode = maintenanceMode(modeValue)
        val emitter = NotificationDiagnosticEmitter(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mode,
            Dispatchers.Unconfined,
        )
        return NotificationIntakeCoordinator(
            intakeDao, workManager, emitter, timeProvider, crypto, DatabaseWriteBarrier(mode),
            transactionRunner = mockk<DomainTransactionRunner>(relaxed = true)
        )
    }

    private suspend fun capture(coordinator: NotificationIntakeCoordinator) =
        coordinator.capture(
            packageName = "com.example.app",
            appName = "App",
            notificationKey = "key",
            notificationKeyHash = "hash",
            postTime = 1L,
            title = "Title",
            text = "Text",
            combinedBody = null,
            subText = null,
            extrasJson = null,
            rawStorageMode = RawStorageMode.STORE_RAW,
            correlationId = "corr",
            source = "listener",
        )

    @Test
    fun `capture is dropped and writes nothing during restore`() = runTest {
        val result = capture(coordinator("RESTORE_PREPARING"))
        assertTrue(result is NotificationIntakeCaptureResult.Dropped)
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `capture writes in normal mode`() = runTest {
        val result = capture(coordinator("NORMAL"))
        assertTrue(result is NotificationIntakeCaptureResult.Enqueued)
        coVerify(exactly = 1) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `captureForRetry skips writes during restore`() = runTest {
        coordinator("RESTORE_SWAPPING").captureForRetry(
            packageName = "com.example.app",
            notificationKey = "key",
            postTime = 1L,
            correlationId = "corr",
            title = "Title",
            storage = DeferredCaptureStorageSnapshot(
                storageMode = RawStorageMode.STORE_METADATA_ONLY,
                appName = null,
                extrasJson = null
            )
        )
        coVerify(exactly = 0) { intakeDao.insertOrIgnore(any()) }
    }

    @Test
    fun `repairLegacyPlaintextTransientRows is skipped during restore`() = runTest {
        NotificationIntakePayloadRepairer(
            intakeDao, crypto, timeProvider, DatabaseWriteBarrier(maintenanceMode("RESTORE_PREPARING"))
        ).repairLegacyPlaintextTransientRows()
        coVerify(exactly = 0) { intakeDao.getLegacyPlaintextTransientRows(any()) }
    }

    @Test
    fun `recoverPending skips writes during restore`() = runTest {
        NotificationIntakeRecoveryScheduler(
            intakeDao, workManager, timeProvider, DatabaseWriteBarrier(maintenanceMode("RESTORE_PREPARING"))
        ).recoverPending()
        coVerify(exactly = 0) { intakeDao.releaseStaleProcessing(any(), any()) }
    }

    @Test
    fun `recoverPending writes in normal mode`() = runTest {
        NotificationIntakeRecoveryScheduler(
            intakeDao, workManager, timeProvider, DatabaseWriteBarrier(maintenanceMode("NORMAL"))
        ).recoverPending()
        coVerify(exactly = 1) { intakeDao.releaseStaleProcessing(any(), any()) }
    }
}
