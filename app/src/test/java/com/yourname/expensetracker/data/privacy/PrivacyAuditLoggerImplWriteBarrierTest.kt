package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G3-S2: [PrivacyAuditLoggerImpl] is a Room writer and must honour the maintenance write barrier.
 * CompositePrivacyGate maps the resulting exception to FailClosed(PRIVACY_AUDIT_WRITE_FAILED).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyAuditLoggerImplWriteBarrierTest {

    private val dao = mockk<PrivacyAuditDao>(relaxed = true)
    private val barrier = mockk<DatabaseWriteBarrier>()

    private fun logger() = PrivacyAuditLoggerImpl(dao, FakeTimeProvider(1_000L), barrier)

    @Test
    fun `blocked barrier rejects the audit write without touching the dao`() = runTest {
        val blocked = DatabaseAccessBlockedException(
            DatabaseAccessType.WRITE,
            DatabaseAccessOperation("PrivacyAuditLoggerImpl.logDecision"),
            RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        )
        every { barrier.checkWritesAllowed(any<String>()) } throws blocked

        val thrown = runCatching {
            logger().logDecision(PrivacyCapability.RAWBACKUP_EXPORT, PrivacyDecision.Allowed, emptyMap())
        }.exceptionOrNull()

        assertSame(blocked, thrown)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `cancellation from the barrier propagates and nothing is inserted`() = runTest {
        val cancellation = CancellationException("audit cancelled")
        every { barrier.checkWritesAllowed(any<String>()) } throws cancellation

        val thrown = runCatching {
            logger().logDecision(PrivacyCapability.RAWBACKUP_EXPORT, PrivacyDecision.Allowed, emptyMap())
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `allowed barrier persists the audit event`() = runTest {
        every { barrier.checkWritesAllowed(any<String>()) } returns Unit
        val inserted = slot<PrivacyAuditEvent>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        logger().logDecision(PrivacyCapability.RAWBACKUP_EXPORT, PrivacyDecision.Allowed, emptyMap())

        coVerify(exactly = 1) { dao.insert(any()) }
        assertEquals("RAWBACKUP_EXPORT", inserted.captured.capability)
        assertEquals("ALLOWED", inserted.captured.decision)
    }
}
