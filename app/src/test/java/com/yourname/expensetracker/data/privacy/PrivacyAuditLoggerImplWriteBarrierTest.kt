package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.PrivacyAuditDao
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G3-S2: [PrivacyAuditLoggerImpl] is a Room writer and must honour the maintenance write barrier.
 * CompositePrivacyGate maps the resulting exception to FailClosed(PRIVACY_AUDIT_WRITE_FAILED).
 *
 * R27-S2e: the insert loop runs inside [DatabaseWriteBarrier.runWrite], so this suite uses a REAL
 * barrier over a mocked [RestoreMaintenanceMode] (a mocked barrier would never run the block).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyAuditLoggerImplWriteBarrierTest {

    private val dao = mockk<PrivacyAuditDao>(relaxed = true)
    private var mode = RestoreMaintenanceMode.Mode.NORMAL
    private var modeFailure: Throwable? = null
    private val maintenance = mockk<RestoreMaintenanceMode>().also { mocked ->
        every { mocked.currentMode() } answers {
            modeFailure?.let { throw it }
            mode
        }
    }
    private val barrier = DatabaseWriteBarrier(maintenance)

    private fun logger() = PrivacyAuditLoggerImpl(dao, FakeTimeProvider(1_000L), barrier)

    @Test
    fun `blocked barrier rejects the audit write without touching the dao`() = runTest {
        mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING

        val thrown = runCatching {
            logger().logDecision(PrivacyCapability.RAWBACKUP_EXPORT, PrivacyDecision.Allowed, emptyMap())
        }.exceptionOrNull()

        assertTrue(thrown is DatabaseAccessBlockedException)
        assertEquals(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING, (thrown as DatabaseAccessBlockedException).mode)
        coVerify(exactly = 0) { dao.insert(any()) }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `cancellation from the barrier propagates and nothing is inserted`() = runTest {
        val cancellation = CancellationException("audit cancelled")
        modeFailure = cancellation

        val thrown = runCatching {
            logger().logDecision(PrivacyCapability.RAWBACKUP_EXPORT, PrivacyDecision.Allowed, emptyMap())
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `allowed barrier persists the audit event`() = runTest {
        val inserted = slot<PrivacyAuditEvent>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        logger().logDecision(PrivacyCapability.RAWBACKUP_EXPORT, PrivacyDecision.Allowed, emptyMap())

        coVerify(exactly = 1) { dao.insert(any()) }
        assertEquals("RAWBACKUP_EXPORT", inserted.captured.capability)
        assertEquals("ALLOWED", inserted.captured.decision)
    }

    @Test
    fun `audit insert is counted as in-flight write until the insert completes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { dao.insert(any()) } coAnswers {
            gate.await()
            1L
        }

        val call = async {
            logger().logDecision(PrivacyCapability.RAWBACKUP_EXPORT, PrivacyDecision.Allowed, emptyMap())
        }
        runCurrent()

        assertEquals("insert is parked inside runWrite", 1, barrier.inFlightWriteCount())
        assertTrue("logDecision must not complete while the insert is gated", call.isActive)

        gate.complete(Unit)
        call.await()

        assertEquals(0, barrier.inFlightWriteCount())
        coVerify(exactly = 1) { dao.insert(any()) }
    }
}
