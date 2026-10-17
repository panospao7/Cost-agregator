package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.entity.RawNotification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R27-S2c: [NotificationRepository] DB writes run inside [DatabaseWriteBarrier.runWrite], so a
 * backup/restore drain (`awaitNoInFlightWrites`) can see and wait for them.
 */
class NotificationRepositoryWriteBarrierTest {

    private var mode = RestoreMaintenanceMode.Mode.NORMAL
    private val maintenanceMode = mockk<RestoreMaintenanceMode>().also {
        every { it.currentMode() } answers { mode }
    }
    private val barrier = DatabaseWriteBarrier(maintenanceMode)
    private val dao = mockk<RawNotificationDao>(relaxed = true)

    private fun repository() = NotificationRepository(
        database = mockk<AppDatabase>(relaxed = true),
        dao = dao,
        blockedPackageDao = mockk(relaxed = true),
        expenseDao = mockk(relaxed = true),
        pendingReviewDao = mockk(relaxed = true),
        userCorrectionDao = mockk(relaxed = true),
        sourceStatsDao = mockk(relaxed = true),
        transactionRunner = mockk(relaxed = true),
        transactionLifecycleEventWriter = mockk(relaxed = true),
        classifier = mockk(relaxed = true),
        pipeline = mockk(relaxed = true),
        writeBarrier = barrier,
        privacySettingsRepository = mockk(relaxed = true),
        diagnosticEmitter = mockk(relaxed = true)
    )

    private fun notification() = RawNotification(
        packageName = "com.test",
        appName = "Test App",
        title = "Test",
        text = "Amount",
        timestamp = 1_000L,
        capturedAt = 1_000L
    )

    @Test
    fun `save is counted as in-flight write until the insert completes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { dao.insert(any()) } coAnswers {
            gate.await()
            5L
        }

        val result = async { repository().save(notification()) }
        runCurrent()

        assertEquals("insert is parked inside runWrite", 1, barrier.inFlightWriteCount())
        assertTrue("save must not complete while the insert is gated", result.isActive)

        gate.complete(Unit)

        assertEquals(5L, result.await())
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `save is rejected and does not touch the dao when maintenance mode is active`() = runTest {
        mode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING

        try {
            repository().save(notification())
            fail("expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, e.mode)
        }

        coVerify(exactly = 0) { dao.insert(any()) }
        assertEquals(0, barrier.inFlightWriteCount())
    }
}
