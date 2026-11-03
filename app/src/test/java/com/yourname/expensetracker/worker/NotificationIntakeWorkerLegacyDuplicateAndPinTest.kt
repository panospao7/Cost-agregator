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
import com.yourname.expensetracker.data.database.dao.NotificationIntakePayloadForProcessing
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.dao.NotificationIntakeProcessingMetadata
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.notification.capture.NotificationTransientPayloadCrypto
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
import com.yourname.expensetracker.domain.workers.TerminalWriteOutcome
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerRunHandle
import com.yourname.expensetracker.domain.workers.WorkerRunLogger
import com.yourname.expensetracker.domain.workers.WorkerTerminalDiagnosticSink
import com.yourname.expensetracker.domain.workers.toWorkerResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fix-1.0.4 S2 worker-side pins (worker source is unchanged by the slice).
 *
 * 1. Legacy duplicate: after the P-01-1 rename a second worker can exist for a row that
 *    legacy work (bare `<id>` name) already owns. When `claimForProcessing` returns 0 the
 *    loser must do nothing: no payload load, no decrypt, no processing, no status change,
 *    no purge.
 * 2. P-01-2 pin: every failure-code literal the worker throws is NON-transient under
 *    `WorkerExecutionGuard.classifyTransient`'s rule, so the guard can never turn a
 *    terminal intake failure into a WorkManager retry. The real (private) classifier is
 *    exercised through a real [WorkerExecutionGuard]; the source scan makes sure a newly
 *    added thrown code cannot escape the pin.
 */
class NotificationIntakeWorkerLegacyDuplicateAndPinTest {

    private val now = 1_700_000_000_000L

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

    private fun metadata(
        id: Long,
        status: String,
        nextAttemptAt: Long? = null
    ) = NotificationIntakeProcessingMetadata(
        id = id,
        status = status,
        attempts = 1,
        maxAttempts = 5,
        nextAttemptAt = nextAttemptAt,
        payloadMode = "RAW",
        rawStorageMode = "STORE_RAW",
        packageName = "com.test.app",
        appName = "Test",
        postTime = now,
        capturedAt = now,
        source = "LISTENER",
        correlationId = "corr-$id",
        dedupeFingerprint = "fp-$id"
    )

    private class Harness(val worker: NotificationIntakeWorker) {
        lateinit var intakeDao: NotificationIntakeDao
        lateinit var repository: NotificationRepository
        lateinit var crypto: NotificationTransientPayloadCrypto
    }

    private fun buildHarness(
        intakeId: Long,
        meta: NotificationIntakeProcessingMetadata,
        claimResult: Int
    ): Harness {
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
        coEvery { intakeDao.getProcessingMetadataById(intakeId) } returns meta
        coEvery { intakeDao.claimForProcessing(intakeId, now, any()) } returns claimResult

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            timeProvider = timeProvider, crypto = crypto,
            executionGuard = realGuard(timeProvider),
            privacyGate = privacyGate
        )
        return Harness(worker).also {
            it.intakeDao = intakeDao
            it.repository = repository
            it.crypto = crypto
        }
    }

    // ── Legacy duplicate worker ────────────────────────────────────────────────

    @Test
    fun `legacy duplicate worker for a row claimed elsewhere does nothing when claim returns 0`() = runBlocking {
        val intakeId = 42L
        val h = buildHarness(
            intakeId,
            metadata(intakeId, status = "PROCESSING"),
            claimResult = 0
        )

        val result = h.worker.doWork()

        assertEquals(WorkResult.success(), result)
        coVerify(exactly = 1) { h.intakeDao.claimForProcessing(intakeId, now, any()) }
        coVerify(exactly = 0) { h.intakeDao.getPayloadForProcessing(any()) }
        coVerify(exactly = 0) { h.crypto.decrypt(any(), any(), any()) }
        coVerify(exactly = 0) { h.repository.processAndSave(any(), any(), any(), any()) }
        // No status change of any kind.
        coVerify(exactly = 0) { h.intakeDao.markTerminal(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markFinalFailure(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markRetryableFailure(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markPrivacyDeniedAndPurgeAllPayload(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.purgeAllPayload(any(), any()) }
    }

    @Test
    fun `legacy duplicate worker for an already terminal row does nothing when claim returns 0`() = runBlocking {
        val intakeId = 43L
        val h = buildHarness(
            intakeId,
            metadata(intakeId, status = "PROCESSED"),
            claimResult = 0
        )

        assertEquals(WorkResult.success(), h.worker.doWork())

        coVerify(exactly = 0) { h.intakeDao.getPayloadForProcessing(any()) }
        coVerify(exactly = 0) { h.repository.processAndSave(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markTerminal(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markFinalFailure(any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.markRetryableFailure(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.intakeDao.purgeAllPayload(any(), any()) }
    }

    @Test
    fun `control - the winning worker whose claim returns 1 does process the row`() = runBlocking {
        val intakeId = 44L
        val h = buildHarness(
            intakeId,
            metadata(intakeId, status = "RECEIVED"),
            claimResult = 1
        )
        coEvery { h.intakeDao.getProcessingMetadataById(intakeId) } returns
            metadata(intakeId, status = "PROCESSING")
        coEvery { h.intakeDao.getPayloadForProcessing(intakeId) } returns
            NotificationIntakePayloadForProcessing(
                id = intakeId, payloadMode = "RAW",
                title = "Paid 50.00", text = "Card transaction",
                bigText = null, subText = null, extrasJson = null,
                transientPayloadCiphertext = null, transientPayloadNonce = null,
                transientPayloadVersion = null
            )
        coEvery { h.intakeDao.markTerminal(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { h.repository.processAndSave(any(), any(), any(), any()) } returns
            NotificationPipelineOutcome.AutoAccepted(
                packageName = "com.test.app",
                correlationId = "corr-44",
                rawId = 100L,
                expenseId = 200L
            )

        assertEquals(WorkResult.success(), h.worker.doWork())

        coVerify(exactly = 1) { h.intakeDao.getPayloadForProcessing(intakeId) }
        coVerify(exactly = 1) { h.repository.processAndSave(any(), any(), any(), any()) }
        coVerify(exactly = 1) { h.intakeDao.markTerminal(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── P-01-2 pin: thrown failure codes are non-transient ─────────────────────

    /** Mirror of the substring list the classifier documents; used only for literal pinning. */
    private val transientSubstrings =
        listOf("timeout", "interrupted", "deadlock", "SQLITE_BUSY", "database is locked")

    private fun matchesTransientSubstring(message: String): Boolean =
        transientSubstrings.any { message.contains(it, ignoreCase = true) }

    private fun workerSource(): String {
        val rel = "src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt"
        val file = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: throw AssertionError(
                "NotificationIntakeWorker.kt not found from working dir ${File(".").absolutePath}"
            )
        return file.readText()
    }

    private data class ScannedThrows(val literals: List<String>, val failureCodeLiterals: List<String>)

    private fun scanWorkerThrows(): ScannedThrows {
        val source = workerSource()
        val literals = mutableListOf<String>()
        val unrecognised = mutableListOf<String>()
        val throwRegex = Regex("""\bthrow\s+(.+)""")
        val literalCtor = Regex("""^(?:\w+\.)*\w+\(\s*"([^"]*)"\s*\)""")
        val retryableCtor = Regex("""^RetryableWorkerException\(DiagnosticReasonCode\.[A-Z_]+\.name\)""")
        source.lineSequence().forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEach
            val code = raw.substringBefore("//")
            val m = throwRegex.find(code) ?: return@forEach
            val expr = m.groupValues[1].trim()
            when {
                // Rethrow of a caught exception: not a new code.
                expr == "e" -> Unit
                // Intentionally retryable by type: classifyTransient is not consulted for it.
                retryableCtor.containsMatchIn(expr) -> Unit
                // Dynamic code taken from the failureCode variable: pinned via its literals below.
                expr.startsWith("RuntimeException(failureCode)") -> Unit
                literalCtor.containsMatchIn(expr) ->
                    literals += literalCtor.find(expr)!!.groupValues[1].replace("\$intakeId", "42")
                else -> unrecognised += raw.trim()
            }
        }
        if (unrecognised.isNotEmpty()) {
            throw AssertionError(
                "NotificationIntakeWorker has a throw form the P-01-2 pin does not understand; " +
                    "extend the pin so the new code is checked: $unrecognised"
            )
        }
        val failureCodeRegex = Regex(
            """val failureCode\s*=\s*if\s*\([^)]*\)\s*\{\s*"([A-Z_]+)"\s*\}\s*else\s*\{\s*"([A-Z_]+)"\s*\}"""
        )
        val fc = failureCodeRegex.find(source)
            ?: throw AssertionError(
                "failureCode if/else literal block not found in NotificationIntakeWorker; update the pin"
            )
        return ScannedThrows(literals, listOf(fc.groupValues[1], fc.groupValues[2]))
    }

    private fun failureResultFor(code: String): WorkResult = runBlocking {
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        val guard = realGuard(timeProvider)
        val result: WorkerGuardResult<Unit> = guard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = NotificationIntakeWorker.WORKER_NAME,
                requiresDatabaseWrite = true,
                requiredCapabilities = listOf(PrivacyCapability.NOTIFICATION_CAPTURE),
                blockedPolicy = BlockedPolicy.RETRY,
                workId = "pin-work-id",
                runAttemptCount = 0,
                specVersion = null
            )
        ) { _ -> throw RuntimeException(code) }
        result.toWorkerResult()
    }

    @Test
    fun `P-01-2 pin control - the real classifier does retry a transient message and an IOException`() {
        assertEquals(WorkResult.retry(), failureResultFor("timeout"))
        assertEquals(WorkResult.retry(), failureResultFor("database is locked"))
        assertTrue(matchesTransientSubstring("SQLITE_BUSY: db busy"))
        assertTrue(matchesTransientSubstring("Deadlock detected"))
        assertTrue(matchesTransientSubstring("Interrupted"))
        assertFalse(matchesTransientSubstring("DECRYPT_FAILED"))
    }

    @Test
    fun `P-01-2 pin - every failure code the worker throws is non-transient under the real classifier`() {
        val scanned = scanWorkerThrows()

        // Non-vacuity: the scan must see the codes known at pin time. A refactor that
        // hides them from the scan fails here instead of silently emptying the pin.
        val expectedLiterals = listOf(
            "Intake row 42 not found",
            "MAX_ATTEMPTS_EXCEEDED",
            "CLAIMED_METADATA_UNAVAILABLE",
            "FAILED_FINAL",
            "MAX_RETRIES_EXHAUSTED",
            "WORKER_EXCEPTION"
        )
        assertTrue(
            "scan lost known thrown literals; found=${scanned.literals}",
            scanned.literals.containsAll(expectedLiterals)
        )
        assertEquals(setOf("DECRYPT_FAILED", "WORKER_EXCEPTION"), scanned.failureCodeLiterals.toSet())

        val allCodes = (scanned.literals + scanned.failureCodeLiterals).distinct()
        allCodes.forEach { code ->
            assertFalse(
                "failure code '$code' matches a transient substring and would be retried by the guard",
                matchesTransientSubstring(code)
            )
            assertEquals(
                "real classifier must treat '$code' as terminal (Result.failure)",
                WorkResult.failure(),
                failureResultFor(code)
            )
        }
    }
}
