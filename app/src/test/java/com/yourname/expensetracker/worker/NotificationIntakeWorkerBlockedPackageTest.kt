package com.yourname.expensetracker.worker

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.dao.NotificationIntakePayloadForProcessing
import com.yourname.expensetracker.data.database.dao.NotificationIntakeProcessingMetadata
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.notification.capture.NotificationTransientPayloadCrypto
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
import com.yourname.expensetracker.domain.workers.TerminalWriteOutcome
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerRunHandle
import com.yourname.expensetracker.domain.workers.WorkerRunLogger
import com.yourname.expensetracker.domain.workers.WorkerTerminalDiagnosticSink
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P-01-3: queued intake rows are re-checked against the user's "apps to skip" list BEFORE any
 * payload is loaded. A blocked package is dropped as DROPPED_POLICY / BLOCKED_PACKAGE, its
 * payload is purged, and nothing reaches the pipeline (no review, no expense). A failing lookup
 * fails closed through the worker's existing handling; cancellation propagates.
 *
 * The real [WorkerExecutionGuard] is used; DAO, repository, crypto and clock are mocks.
 * Synthetic package names only.
 */
class NotificationIntakeWorkerBlockedPackageTest {

    private val now = 1_700_000_000_000L
    private val blockedPkg = "com.synthetic.blocked.bank"
    private val allowedPkg = "com.test.app"
    private val logLines = CopyOnWriteArrayList<String>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logLines += listOfNotNull(tag, message, t?.message, t?.stackTraceToString()).joinToString(" ")
        }
    }

    @Before
    fun plantTree() {
        Timber.plant(logTree)
    }

    @After
    fun uprootTree() {
        Timber.uproot(logTree)
    }

    private fun mockWorkerRunHandle(): WorkerRunHandle {
        val h = mockk<WorkerRunHandle>(relaxed = true)
        coEvery { h.success(any(), any(), any(), any(), any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { h.skipped(any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { h.retry(any(), any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { h.failure(any(), any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { h.cancelled(any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { h.staleAborted(any()) } returns TerminalWriteOutcome.Durable
        return h
    }

    private fun realGuard(timeProvider: TimeProvider): WorkerExecutionGuard {
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val runLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)
        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns mockk(relaxed = true)
        coEvery { runLogger.start(any()) } returns mockWorkerRunHandle()
        every { permissionChecker.areNotificationsEnabled() } returns true
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed
        return WorkerExecutionGuard(
            mockk<DatabaseWriteBarrier>(relaxed = true),
            mockk<DatabaseReadBarrier>(relaxed = true),
            restoreMode,
            runLogger,
            privacyGate,
            leaseRegistry,
            mockk<MaintenanceSafeDiagnosticSink>(relaxed = true),
            mockk<WorkerTerminalDiagnosticSink>(relaxed = true),
            mockk<BackgroundJobRunDao>(relaxed = true),
            permissionChecker,
            timeProvider,
            mockk<Context>(relaxed = true)
        )
    }

    private fun metadata(id: Long, packageName: String, attempts: Int = 1) =
        NotificationIntakeProcessingMetadata(
            id = id,
            status = "PROCESSING",
            attempts = attempts,
            maxAttempts = 5,
            nextAttemptAt = null,
            payloadMode = "RAW",
            rawStorageMode = "STORE_RAW",
            packageName = packageName,
            appName = "Synthetic",
            postTime = now,
            capturedAt = now,
            source = "LISTENER",
            correlationId = "corr-$id",
            dedupeFingerprint = "fp-$id"
        )

    private class Harness(
        val worker: NotificationIntakeWorker,
        val intakeDao: NotificationIntakeDao,
        val repository: NotificationRepository,
        val crypto: NotificationTransientPayloadCrypto
    )

    private fun buildHarness(intakeId: Long, packageName: String, attempts: Int = 1): Harness {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getProcessingMetadataById(intakeId) } returns metadata(intakeId, packageName, attempts)
        coEvery { intakeDao.claimForProcessing(intakeId, now, any()) } returns 1
        coEvery { intakeDao.getPayloadForProcessing(intakeId) } returns
            NotificationIntakePayloadForProcessing(
                id = intakeId, payloadMode = "RAW",
                title = "Paid 50.00", text = "Card transaction",
                bigText = null, subText = null, extrasJson = null,
                transientPayloadCiphertext = null, transientPayloadNonce = null,
                transientPayloadVersion = null
            )
        coEvery { intakeDao.markTerminal(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { repository.isPackageBlocked(blockedPkg) } returns true
        coEvery { repository.isPackageBlocked(allowedPkg) } returns false

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = realGuard(timeProvider),
            privacyGate = privacyGate
        )
        return Harness(worker, intakeDao, repository, crypto)
    }

    private fun assertNoProcessing(h: Harness) {
        coVerify(exactly = 0) { h.intakeDao.getPayloadForProcessing(any()) }
        coVerify(exactly = 0) { h.crypto.decrypt(any(), any(), any()) }
        coVerify(exactly = 0) { h.repository.processAndSave(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.repository.save(any()) }
    }

    private fun assertNoPackageNameLogged() {
        logLines.forEach { line ->
            assertFalse("a log line contains the package name: $line", line.contains(blockedPkg))
        }
    }

    // ── Blocked package ──────────────────────────────────────────────────────────

    @Test
    fun `blocked package - queued row is marked DROPPED_POLICY with BLOCKED_PACKAGE and nothing is processed`() = runBlocking {
        val intakeId = 501L
        val h = buildHarness(intakeId, blockedPkg)

        val result = h.worker.doWork()

        assertEquals(WorkResult.success(), result)
        coVerify(exactly = 1) { h.repository.isPackageBlocked(blockedPkg) }
        coVerify(exactly = 1) {
            h.intakeDao.markTerminal(
                id = intakeId,
                status = "DROPPED_POLICY",
                rawId = null,
                expenseId = null,
                reviewId = null,
                finalOutcome = "BLOCKED_PACKAGE",
                nowMs = now
            )
        }
        // The payload is never loaded, decrypted, parsed or persisted: no review, no expense.
        assertNoProcessing(h)
        coVerify(exactly = 0) { h.intakeDao.markFinalFailure(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markRetryableFailure(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `blocked package - the stored payload is purged`() = runBlocking {
        val intakeId = 502L
        val h = buildHarness(intakeId, blockedPkg)

        assertEquals(WorkResult.success(), h.worker.doWork())

        coVerify(exactly = 1) { h.intakeDao.purgeAllPayload(id = intakeId, nowMs = now) }
    }

    @Test
    fun `blocked package - the drop happens only after the claim and no package name is logged`() = runBlocking {
        val intakeId = 503L
        val h = buildHarness(intakeId, blockedPkg)

        h.worker.doWork()

        coVerify(exactly = 1) { h.intakeDao.claimForProcessing(intakeId, now, any()) }
        assertTrue("the drop is logged with the bounded reason code", logLines.any { it.contains("BLOCKED_PACKAGE") })
        assertNoPackageNameLogged()
    }

    @Test
    fun `control - an unblocked package continues through normal processing`() = runBlocking {
        val intakeId = 504L
        val h = buildHarness(intakeId, allowedPkg)
        coEvery { h.repository.processAndSave(any(), any(), any(), any()) } returns
            NotificationPipelineOutcome.AutoAccepted(
                packageName = allowedPkg,
                correlationId = "corr-$intakeId",
                rawId = 100L,
                expenseId = 200L
            )

        assertEquals(WorkResult.success(), h.worker.doWork())

        coVerify(exactly = 1) { h.repository.isPackageBlocked(allowedPkg) }
        coVerify(exactly = 1) { h.intakeDao.getPayloadForProcessing(intakeId) }
        coVerify(exactly = 1) { h.repository.processAndSave(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            h.intakeDao.markTerminal(any(), "DROPPED_POLICY", any(), any(), any(), "BLOCKED_PACKAGE", any())
        }
    }

    @Test
    fun `a blocked package does not drop rows of a different package`() = runBlocking {
        // The list is keyed by exact package name: a sibling name must not be treated as blocked.
        val intakeId = 505L
        val h = buildHarness(intakeId, "$blockedPkg.sibling")
        coEvery { h.repository.isPackageBlocked("$blockedPkg.sibling") } returns false
        coEvery { h.repository.processAndSave(any(), any(), any(), any()) } returns
            NotificationPipelineOutcome.Dropped(
                packageName = "$blockedPkg.sibling",
                correlationId = "corr-$intakeId",
                reason = "x"
            )

        h.worker.doWork()

        coVerify(exactly = 1) { h.intakeDao.getPayloadForProcessing(intakeId) }
        coVerify(exactly = 0) {
            h.intakeDao.markTerminal(any(), any(), any(), any(), any(), "BLOCKED_PACKAGE", any())
        }
    }

    // ── Lookup failure: fail closed ───────────────────────────────────────────────

    @Test
    fun `lookup failure fails closed - no payload load, no processing, existing final-failure handling`() = runBlocking {
        val intakeId = 506L
        val h = buildHarness(intakeId, blockedPkg)
        coEvery { h.repository.isPackageBlocked(blockedPkg) } throws IllegalStateException("lookup failed")

        val result = h.worker.doWork()

        assertEquals(WorkResult.failure(), result)
        assertNoProcessing(h)
        coVerify(exactly = 1) {
            h.intakeDao.markFinalFailure(id = intakeId, failureCode = "WORKER_EXCEPTION", failureHash = null, nowMs = now)
        }
        coVerify(exactly = 1) { h.intakeDao.purgeAllPayload(id = intakeId, nowMs = now) }
        // A failed lookup never marks the row as successfully processed or dropped by policy.
        coVerify(exactly = 0) {
            h.intakeDao.markTerminal(any(), any(), any(), any(), any(), any(), any())
        }
        assertNoPackageNameLogged()
    }

    @Test
    fun `lookup failure that is transient and attempts remain schedules a retry and still processes nothing`() = runBlocking {
        val intakeId = 507L
        val h = buildHarness(intakeId, blockedPkg, attempts = 1)
        coEvery { h.repository.isPackageBlocked(blockedPkg) } throws IOException("io")

        val result = h.worker.doWork()

        assertEquals(WorkResult.retry(), result)
        assertNoProcessing(h)
        coVerify(exactly = 1) {
            h.intakeDao.markRetryableFailure(id = intakeId, nextAttemptAt = any(), failureCode = "WORKER_EXCEPTION", failureHash = null, nowMs = now)
        }
        coVerify(exactly = 0) { h.intakeDao.markFinalFailure(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.purgeAllPayload(any(), any()) }
    }

    @Test
    fun `transient lookup failure on the last attempt ends in final failure and never processes`() = runBlocking {
        val intakeId = 508L
        val h = buildHarness(intakeId, blockedPkg, attempts = 4) // attempts + 1 == maxAttempts(5)
        coEvery { h.repository.isPackageBlocked(blockedPkg) } throws IOException("io")

        assertEquals(WorkResult.failure(), h.worker.doWork())

        assertNoProcessing(h)
        coVerify(exactly = 1) { h.intakeDao.markFinalFailure(id = intakeId, failureCode = "WORKER_EXCEPTION", failureHash = null, nowMs = now) }
        coVerify(exactly = 0) { h.intakeDao.markRetryableFailure(any(), any(), any(), any(), any()) }
    }

    // ── Cancellation ──────────────────────────────────────────────────────────────

    @Test
    fun `CancellationException from the lookup propagates and the row is not failed or dropped`() = runBlocking {
        val intakeId = 509L
        val h = buildHarness(intakeId, blockedPkg)
        coEvery { h.repository.isPackageBlocked(blockedPkg) } throws CancellationException("cancelled")

        try {
            h.worker.doWork()
            fail("CancellationException must propagate out of doWork")
        } catch (e: CancellationException) {
            // expected
        }

        assertNoProcessing(h)
        coVerify(exactly = 0) { h.intakeDao.markFinalFailure(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markRetryableFailure(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markTerminal(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.purgeAllPayload(any(), any()) }
    }
}
