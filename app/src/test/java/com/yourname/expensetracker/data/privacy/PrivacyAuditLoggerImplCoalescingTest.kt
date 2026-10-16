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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Release 1.0 R26-D: only repeated ALLOWED NOTIFICATION_CAPTURE decisions are coalesced
 * (one row per 60-minute window carrying coalescedCount); everything else is written individually.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyAuditLoggerImplCoalescingTest {

    private val windowMs = 60L * 60L * 1000L
    private val clock = FakeTimeProvider(1_000_000L)
    private val rows = mutableListOf<PrivacyAuditEvent>()
    private var daoFailure: Throwable? = null
    private var mode = RestoreMaintenanceMode.Mode.NORMAL

    private var insertGate: CompletableDeferred<Unit>? = null
    private var inFlight = 0
    private var maxInFlight = 0

    // The DAO suspends (gate / yield) so concurrent callers can interleave if the Mutex is missing.
    private val dao = mockk<PrivacyAuditDao>().also { mocked ->
        coEvery { mocked.insert(any()) } coAnswers {
            val event = firstArg<PrivacyAuditEvent>()
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                insertGate?.await()
                yield()
                daoFailure?.let { throw it }
                rows += event
                rows.size.toLong()
            } finally {
                inFlight--
            }
        }
    }
    private val maintenance = mockk<RestoreMaintenanceMode>().also { mocked ->
        every { mocked.currentMode() } answers { mode }
    }
    private val logger = PrivacyAuditLoggerImpl(dao, clock, DatabaseWriteBarrier(maintenance))

    private suspend fun allowedCapture(context: Map<String, String> = emptyMap()) =
        logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.Allowed, context)

    private fun coalescedCount(row: PrivacyAuditEvent): String? =
        contextValue(row, "coalescedCount")

    private fun contextValue(row: PrivacyAuditEvent, key: String): String? =
        JSONObject(row.context!!).let { if (it.has(key)) it.getString(key) else null }

    @Test
    fun `D1 first allowed notification capture is written`() = runTest {
        allowedCapture()

        assertEquals(1, rows.size)
        assertEquals("NOTIFICATION_CAPTURE", rows[0].capability)
        assertEquals("ALLOWED", rows[0].decision)
        assertEquals("privacy-gate", rows[0].caller)
        assertNull(coalescedCount(rows[0]))
    }

    @Test
    fun `D2 identical allowed calls inside the window are not written`() = runTest {
        allowedCapture()
        clock.advanceTime(windowMs - 1)
        repeat(5) { allowedCapture() }

        assertEquals(1, rows.size)
    }

    @Test
    fun `D3 first call after the window writes a row carrying coalescedCount`() = runTest {
        allowedCapture()
        repeat(4) { allowedCapture() }
        clock.advanceTime(windowMs)
        allowedCapture()

        assertEquals(2, rows.size)
        assertEquals("4", coalescedCount(rows[1]))
        // Window-opening row is a real decision (not a flush) and names the window the count belongs to.
        assertNull(contextValue(rows[1], "coalescedFlush"))
        assertEquals("1000000", contextValue(rows[1], "coalescedSinceMs"))
        // Window was re-opened with a zero count: the next window row has no coalescedCount.
        clock.advanceTime(windowMs)
        allowedCapture()
        assertEquals(3, rows.size)
        assertNull(coalescedCount(rows[2]))
    }

    @Test
    fun `D4 denied notification capture flushes pending count before its own row`() = runTest {
        allowedCapture()
        allowedCapture()
        allowedCapture()
        logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.Denied("DISABLED"), emptyMap())

        assertEquals(3, rows.size)
        assertEquals("ALLOWED", rows[1].decision)
        assertEquals("2", coalescedCount(rows[1]))
        assertEquals("true", contextValue(rows[1], "coalescedFlush"))
        assertEquals("1000000", contextValue(rows[1], "coalescedSinceMs"))
        assertNull(contextValue(rows[2], "coalescedFlush"))
        assertEquals("DENIED", rows[2].decision)
        assertEquals("DISABLED", rows[2].reason)

        // The capture windows were closed by the DENIED row: the next ALLOWED is written again.
        allowedCapture()
        assertEquals(4, rows.size)
        assertEquals("ALLOWED", rows[3].decision)
        assertNull(coalescedCount(rows[3]))
    }

    @Test
    fun `D4c allowed after a denied decision is written as a new row`() = runTest {
        allowedCapture()
        allowedCapture() // suppressed
        logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.Denied("DISABLED"), emptyMap())
        allowedCapture()

        assertEquals(listOf("ALLOWED", "ALLOWED", "DENIED", "ALLOWED"), rows.map { it.decision })
        assertEquals("1", coalescedCount(rows[1]))
        assertEquals("true", contextValue(rows[1], "coalescedFlush"))
        assertNull(coalescedCount(rows[0]))
        assertNull(coalescedCount(rows[2]))
        assertNull(coalescedCount(rows[3]))
        assertNull(contextValue(rows[3], "coalescedFlush"))
    }

    @Test
    fun `D4d other capability decisions leave capture windows open`() = runTest {
        allowedCapture()
        logger.logDecision(PrivacyCapability.CLOUD_AI_GENERAL, PrivacyDecision.Denied("OFF"), emptyMap())
        allowedCapture() // still suppressed

        assertEquals(listOf("ALLOWED", "DENIED"), rows.map { it.decision })
    }

    @Test
    fun `D4b fail closed and not applicable are always individual rows`() = runTest {
        repeat(2) {
            logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.FailClosed("X"), emptyMap())
            logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.NotApplicable, emptyMap())
        }

        assertEquals(listOf("DENIED_FAIL_CLOSED", "NOT_APPLICABLE", "DENIED_FAIL_CLOSED", "NOT_APPLICABLE"), rows.map { it.decision })
    }

    @Test
    fun `D5 other capabilities are never coalesced`() = runTest {
        repeat(3) {
            logger.logDecision(PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST, PrivacyDecision.Allowed, emptyMap())
            logger.logDecision(PrivacyCapability.CLOUD_AI_GENERAL, PrivacyDecision.Allowed, emptyMap())
        }

        assertEquals(6, rows.size)
        assertTrue(rows.all { coalescedCount(it) == null })
    }

    @Test
    fun `D6 caller supplied coalescedCount is dropped`() = runTest {
        allowedCapture(mapOf("coalescedCount" to "999", "coalescedFlush" to "true", "coalescedSinceMs" to "1"))
        assertNull(coalescedCount(rows[0]))
        assertNull(contextValue(rows[0], "coalescedFlush"))
        assertNull(contextValue(rows[0], "coalescedSinceMs"))

        allowedCapture(mapOf("coalescedCount" to "999"))
        clock.advanceTime(windowMs)
        allowedCapture(mapOf("coalescedCount" to "999"))

        assertEquals(2, rows.size)
        assertEquals("1", coalescedCount(rows[1]))

        // Other capabilities cannot spoof it either.
        logger.logDecision(PrivacyCapability.CLOUD_AI_GENERAL, PrivacyDecision.Allowed, mapOf("coalescedCount" to "5"))
        assertNull(coalescedCount(rows[2]))
    }

    @Test
    fun `D7a failed window opening insert does not open a window`() = runTest {
        daoFailure = IOException("disk")
        try {
            allowedCapture()
            fail("expected IOException")
        } catch (expected: IOException) {
            // propagated
        }
        assertTrue(rows.isEmpty())

        daoFailure = null
        allowedCapture()
        assertEquals(1, rows.size)
    }

    @Test
    fun `D7b failed flush insert keeps the pending count`() = runTest {
        allowedCapture()
        allowedCapture()
        allowedCapture()

        daoFailure = IOException("disk")
        try {
            logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.Denied("D"), emptyMap())
            fail("expected IOException")
        } catch (expected: IOException) {
            // propagated
        }
        assertEquals(1, rows.size)

        daoFailure = null
        logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.Denied("D"), emptyMap())
        assertEquals(3, rows.size)
        assertEquals("2", coalescedCount(rows[1]))
        assertEquals("DENIED", rows[2].decision)
    }

    @Test
    fun `D7c failed window rollover insert keeps previous pending count`() = runTest {
        allowedCapture()
        allowedCapture()
        allowedCapture()
        clock.advanceTime(windowMs)

        daoFailure = IOException("disk")
        try {
            allowedCapture()
            fail("expected IOException")
        } catch (expected: IOException) {
            // propagated
        }
        daoFailure = null
        allowedCapture()

        assertEquals(2, rows.size)
        assertEquals("2", coalescedCount(rows[1]))
    }

    @Test
    fun `D8 blocked barrier throws without insert and leaves state unchanged`() = runTest {
        allowedCapture()
        allowedCapture() // pending = 1

        mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        val thrown = runCatching { allowedCapture() }.exceptionOrNull()
        val thrownDenied = runCatching {
            logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.Denied("D"), emptyMap())
        }.exceptionOrNull()
        assertTrue(thrown is DatabaseAccessBlockedException)
        assertTrue(thrownDenied is DatabaseAccessBlockedException)
        assertEquals(1, rows.size)

        mode = RestoreMaintenanceMode.Mode.NORMAL
        clock.advanceTime(windowMs)
        allowedCapture()
        assertEquals(2, rows.size)
        assertEquals("1", coalescedCount(rows[1]))
    }

    @Test
    fun `D9 thirty third key evicts and flushes the eldest pending count`() = runTest {
        allowedCapture(mapOf("operation" to "op0"))
        allowedCapture(mapOf("operation" to "op0")) // op0 pending = 1
        for (i in 1..31) allowedCapture(mapOf("operation" to "op$i"))
        assertEquals(32, rows.size)

        allowedCapture(mapOf("operation" to "op32"))

        assertEquals(34, rows.size)
        val flush = rows[32]
        assertEquals("1", coalescedCount(flush))
        assertEquals("op0", JSONObject(flush.context!!).getString("operation"))
        assertEquals("ALLOWED", flush.decision)
        assertEquals("true", contextValue(flush, "coalescedFlush"))
        assertEquals("1000000", contextValue(flush, "coalescedSinceMs"))
        assertNull(contextValue(rows[33], "coalescedFlush"))
        assertEquals("op32", JSONObject(rows[33].context!!).getString("operation"))
        assertNull(coalescedCount(rows[33]))

        // op0 was evicted: its next call opens a fresh window and is written again.
        allowedCapture(mapOf("operation" to "op0"))
        assertEquals(35, rows.size)
    }

    @Test
    fun `D10 concurrent calls produce one row and the next window reports 49`() = runTest {
        // The DAO suspends (yield) inside insert, so without the Mutex several callers would
        // all see "no window" and write their own row.
        (1..50).map { async { allowedCapture() } }.awaitAll()
        assertEquals(1, rows.size)
        assertEquals(1, maxInFlight)

        clock.advanceTime(windowMs)
        allowedCapture()

        assertEquals(2, rows.size)
        assertEquals("49", coalescedCount(rows[1]))
    }

    @Test
    fun `D12 clock moving backwards expires the window and carries the count`() = runTest {
        allowedCapture()
        allowedCapture() // pending = 1
        clock.setTime(1_000_000L - 1)
        allowedCapture() // now < windowStartMs: treated as expired

        assertEquals(2, rows.size)
        assertEquals("1", coalescedCount(rows[1]))
        assertEquals("1000000", contextValue(rows[1], "coalescedSinceMs"))
        assertEquals(1_000_000L - 1, rows[1].timestampMs)

        // The new window starts at the (earlier) current time and suppresses again.
        allowedCapture()
        assertEquals(2, rows.size)
    }

    @Test
    fun `D13 waiter queued before maintenance does not insert after it started`() = runTest {
        val gate = CompletableDeferred<Unit>()
        insertGate = gate
        val holder = async { allowedCapture() }
        val waiter = async {
            runCatching {
                logger.logDecision(PrivacyCapability.NOTIFICATION_CAPTURE, PrivacyDecision.Denied("D"), emptyMap())
            }.exceptionOrNull()
        }
        runCurrent() // holder owns the lock and is inside insert; waiter passed the pre-lock check

        mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        gate.complete(Unit)
        holder.await()
        val thrown = waiter.await()

        assertTrue(thrown is DatabaseAccessBlockedException)
        assertEquals(1, rows.size)
        assertEquals("ALLOWED", rows[0].decision)
    }

    @Test
    fun `D11 cancellation from the dao propagates and opens no window`() = runTest {
        val cancellation = CancellationException("audit cancelled")
        daoFailure = cancellation

        val thrown = runCatching { allowedCapture() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertFalse(rows.isNotEmpty())

        daoFailure = null
        allowedCapture()
        assertEquals(1, rows.size)
    }
}
