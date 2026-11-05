package com.yourname.expensetracker.domain.notification.capture

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.notification.RawNotificationFingerprint
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RG17B-1 / V17B-N1: enqueue-failure handling against a REAL in-memory Room,
 * without the interplay (MockK-intercepted Operation.await under runTest) that made
 * the former @Ignore'd NotificationIntakeEnqueueFailureTest hang; every case of that
 * class now lives here.
 *
 * Hang avoidance: the WorkManager Operation is a hand-written
 * [FailingOperation] whose getResult() is a REAL already-failed
 * ListenableFuture (never a MockK-intercepted `await()` extension), the
 * enqueue is stubbed with a plain `every`, Room runs on explicit single-thread
 * executors, the test body uses runBlocking (no virtual-time scheduler), and
 * every test carries a hard timeout.
 *
 * Pins the SQL semantics of NotificationIntakeDao.markEnqueueFailed:
 *  - retryable branch: attempts + 1, FAILED_RETRYABLE, shared backoff ladder,
 *    controlled failure code, locks cleared, terminalAt untouched;
 *  - final branch (attempts + 1 >= maxAttempts): FAILED_FINAL, nextAttemptAt
 *    cleared AND terminalAt stamped so the payload repairer treats the row as
 *    terminal (V17B-N1);
 *  - the conditional WHERE keeps the transition idempotent and never touches an
 *    actively claimed (PROCESSING) row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NotificationIntakeEnqueueFailureRoomTest {

    private lateinit var database: AppDatabase
    private lateinit var intakeDao: NotificationIntakeDao
    private lateinit var workManager: WorkManager
    private lateinit var queryExecutor: ExecutorService
    private lateinit var transactionExecutor: ExecutorService
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val timeProvider = FakeTimeProvider(NOW)

    private val fingerprint = RawNotificationFingerprint.compute(
        packageName = PACKAGE,
        title = "Title",
        text = "Text",
        bigText = null,
        timestamp = NOW
    )

    @Before
    fun setup() {
        queryExecutor = Executors.newSingleThreadExecutor(daemonFactory("intake-room-query"))
        transactionExecutor = Executors.newSingleThreadExecutor(daemonFactory("intake-room-tx"))
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .build()
        intakeDao = database.notificationIntakeDao()
        workManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        database.close()
        queryExecutor.shutdownNow()
        transactionExecutor.shutdownNow()
    }

    private fun daemonFactory(name: String) = java.util.concurrent.ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    private fun normalMaintenanceMode(): RestoreMaintenanceMode {
        val mode = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { mode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { mode.isWritesAllowed() } returns true
        return mode
    }

    private fun coordinator(): NotificationIntakeCoordinator {
        every {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } returns FailingOperation(IOException("wm down"))
        return NotificationIntakeCoordinator(
            intakeDao = intakeDao,
            workManager = workManager,
            diagnostics = mockk<NotificationDiagnosticEmitter>(relaxed = true),
            timeProvider = timeProvider,
            crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true),
            writeBarrier = DatabaseWriteBarrier(normalMaintenanceMode()),
            transactionRunner = mockk<DomainTransactionRunner>(relaxed = true)
        )
    }

    private fun intakeRow(
        fingerprint: String,
        status: String,
        attempts: Int,
        maxAttempts: Int = MAX_ATTEMPTS,
        payloadMode: String = "RAW",
        rawStorageMode: String = RawStorageMode.STORE_RAW.name,
        title: String? = null,
        text: String? = null,
        nextAttemptAt: Long? = null,
        lockedAt: Long? = null,
        lockedBy: String? = null,
        terminalAt: Long? = null
    ) = NotificationIntakeEntity(
        packageName = PACKAGE,
        appName = "App",
        notificationKeyHash = "key-hash",
        postTime = NOW,
        capturedAt = NOW,
        source = "listener",
        correlationId = "corr-direct",
        dedupeFingerprint = fingerprint,
        contentHash = null,
        title = title,
        text = text,
        bigText = null,
        subText = null,
        extrasJson = null,
        rawStorageMode = rawStorageMode,
        payloadMode = payloadMode,
        status = status,
        attempts = attempts,
        maxAttempts = maxAttempts,
        nextAttemptAt = nextAttemptAt,
        lockedAt = lockedAt,
        lockedBy = lockedBy,
        terminalAt = terminalAt,
        createdAt = NOW,
        updatedAt = NOW
    )

    private suspend fun insert(row: NotificationIntakeEntity): Long {
        val id = intakeDao.insertOrIgnore(row)
        assertTrue("row must insert", id > 0L)
        return id
    }

    private suspend fun markEnqueueFailed(id: Long, nowMs: Long = NOW): Int =
        intakeDao.markEnqueueFailed(
            id = id,
            nextAttemptAt = nowMs + NotificationIntakeRetryPolicy.backoffFor(1),
            failureCode = "ENQUEUE_FAILED",
            failureHash = null,
            nowMs = nowMs
        )

    // ── T1: first failure through the real coordinator ──────────────────────

    @Test(timeout = 60_000)
    fun `first enqueue failure reports EnqueueFailed and marks the row retryable`() = runBlocking {
        val result = coordinator().capture(
            packageName = PACKAGE,
            appName = "App",
            notificationKey = "key",
            notificationKeyHash = "hash",
            postTime = NOW,
            title = "Title",
            text = "Text",
            combinedBody = null,
            subText = null,
            extrasJson = null,
            rawStorageMode = RawStorageMode.STORE_RAW,
            correlationId = "corr-1",
            source = "listener"
        )

        val after = intakeDao.getByFingerprint(fingerprint)
        assertNotNull(after)
        after!!
        assertTrue(result is NotificationIntakeCaptureResult.EnqueueFailed)
        result as NotificationIntakeCaptureResult.EnqueueFailed
        assertEquals(after.id, result.intakeId)
        assertEquals("corr-1", result.correlationId)

        assertEquals(NotificationIntakeStatus.FAILED_RETRYABLE.name, after.status)
        assertEquals(1, after.attempts)
        assertEquals(NOW + NotificationIntakeRetryPolicy.backoffFor(1), after.nextAttemptAt)
        assertEquals("ENQUEUE_FAILED", after.lastFailureCode)
        assertNull(after.lastFailureMessageHash)
        assertNull(after.lockedAt)
        assertNull(after.lockedBy)
        assertNull(after.terminalAt)
        verify(exactly = 1) {
            workManager.enqueueUniqueWork("notification-intake-${after.id}", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
        }
    }

    // ── T2: final branch + idempotency ──────────────────────────────────────

    @Test(timeout = 60_000)
    fun `failure at maxAttempts minus one goes FAILED_FINAL and a second call is a no-op`() = runBlocking {
        val id = insert(intakeRow("fp-final", NotificationIntakeStatus.RECEIVED.name, attempts = MAX_ATTEMPTS - 1))

        assertEquals(1, markEnqueueFailed(id))
        val finalRow = intakeDao.getById(id)!!
        assertEquals(NotificationIntakeStatus.FAILED_FINAL.name, finalRow.status)
        assertEquals(MAX_ATTEMPTS, finalRow.attempts)
        assertNull(finalRow.nextAttemptAt)
        assertEquals("ENQUEUE_FAILED", finalRow.lastFailureCode)
        assertNull(finalRow.lockedAt)
        assertNull(finalRow.lockedBy)

        assertEquals(0, markEnqueueFailed(id, nowMs = NOW + 5_000L))
        assertEquals(finalRow, intakeDao.getById(id))
    }

    // ── T3: actively claimed row is never transitioned ──────────────────────

    @Test(timeout = 60_000)
    fun `PROCESSING row is left unchanged by markEnqueueFailed`() = runBlocking {
        val id = insert(
            intakeRow(
                "fp-processing",
                NotificationIntakeStatus.PROCESSING.name,
                attempts = 2,
                lockedAt = NOW - 1_000L,
                lockedBy = "worker-1"
            )
        )
        val before = intakeDao.getById(id)!!

        assertEquals(0, markEnqueueFailed(id))
        assertEquals(before, intakeDao.getById(id))
    }

    // ── T5 (V17B-N1): terminalAt on the final branch ────────────────────────

    @Test(timeout = 60_000)
    fun `final enqueue failure stamps terminalAt so the repairer purges a legacy plaintext payload`() = runBlocking {
        val id = insert(
            intakeRow(
                "fp-legacy-plaintext",
                NotificationIntakeStatus.RECEIVED.name,
                attempts = MAX_ATTEMPTS - 1,
                payloadMode = "TRANSIENT",
                rawStorageMode = RawStorageMode.STORE_REDACTED.name,
                title = "Legacy title",
                text = "Legacy text"
            )
        )
        val legacy = intakeDao.getById(id)!!
        assertNull(legacy.transientPayloadCiphertext)
        assertNull(legacy.rawPayloadPurgedAt)
        assertNull(legacy.terminalAt)

        assertEquals(1, markEnqueueFailed(id, nowMs = NOW))
        val finalRow = intakeDao.getById(id)!!
        assertEquals(NotificationIntakeStatus.FAILED_FINAL.name, finalRow.status)
        assertEquals(MAX_ATTEMPTS, finalRow.attempts)
        assertNull(finalRow.nextAttemptAt)
        assertEquals(NOW, finalRow.terminalAt)

        // The row is still a legacy plaintext candidate until the repairer runs.
        assertTrue(intakeDao.getLegacyPlaintextTransientRows(100).any { it.id == id })

        val repairTime = NOW + 60_000L
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)
        NotificationIntakePayloadRepairer(
            intakeDao,
            crypto,
            FakeTimeProvider(repairTime),
            DatabaseWriteBarrier(normalMaintenanceMode())
        ).repairLegacyPlaintextTransientRows()

        // Terminal => purge visible payload, never encrypt it.
        verify(exactly = 0) { crypto.encrypt(any()) }
        val repaired = intakeDao.getById(id)!!
        assertNull(repaired.title)
        assertNull(repaired.text)
        assertNull(repaired.bigText)
        assertNull(repaired.subText)
        assertNull(repaired.extrasJson)
        assertEquals(repairTime, repaired.rawPayloadPurgedAt)
        assertNull(repaired.transientPayloadCiphertext)
        assertEquals(NOW, repaired.terminalAt)
        assertTrue(intakeDao.getLegacyPlaintextTransientRows(100).none { it.id == id })
    }

    @Test(timeout = 60_000)
    fun `retryable enqueue failure leaves terminalAt null`() = runBlocking {
        val id = insert(intakeRow("fp-retryable", NotificationIntakeStatus.RECEIVED.name, attempts = 1))

        assertEquals(1, markEnqueueFailed(id))
        val row = intakeDao.getById(id)!!
        assertEquals(NotificationIntakeStatus.FAILED_RETRYABLE.name, row.status)
        assertEquals(2, row.attempts)
        assertEquals(NOW + NotificationIntakeRetryPolicy.backoffFor(1), row.nextAttemptAt)
        assertNull(row.terminalAt)
    }

    @Test(timeout = 60_000)
    fun `second markEnqueueFailed on a final row returns 0 and keeps terminalAt`() = runBlocking {
        val id = insert(intakeRow("fp-final-twice", NotificationIntakeStatus.RECEIVED.name, attempts = MAX_ATTEMPTS - 1))

        assertEquals(1, markEnqueueFailed(id, nowMs = NOW))
        assertEquals(NOW, intakeDao.getById(id)!!.terminalAt)

        assertEquals(0, markEnqueueFailed(id, nowMs = NOW + 5_000L))
        val after = intakeDao.getById(id)!!
        assertEquals(NOW, after.terminalAt)
        assertEquals(MAX_ATTEMPTS, after.attempts)
        assertEquals(NotificationIntakeStatus.FAILED_FINAL.name, after.status)
    }

    // ── T6: full attempt sequence up to the maxAttempts boundary ────────────

    @Test(timeout = 60_000)
    fun `repeated enqueue failures stay retryable until maxAttempts then FAILED_FINAL and stop transitioning`() = runBlocking {
        val id = insert(intakeRow("fp-sequence", NotificationIntakeStatus.RECEIVED.name, attempts = 0))

        // Attempts 1..MAX_ATTEMPTS-1 stay retryable on the first rung of the ladder.
        for (expectedAttempts in 1 until MAX_ATTEMPTS) {
            assertEquals(1, markEnqueueFailed(id))
            val row = intakeDao.getById(id)!!
            assertEquals(NotificationIntakeStatus.FAILED_RETRYABLE.name, row.status)
            assertEquals(expectedAttempts, row.attempts)
            assertEquals(NOW + 30_000L, row.nextAttemptAt)
            assertNull(row.terminalAt)
        }

        // The MAX_ATTEMPTS-th failure is the boundary: FAILED_FINAL, schedule cleared, terminalAt stamped.
        assertEquals(1, markEnqueueFailed(id))
        val finalRow = intakeDao.getById(id)!!
        assertEquals(NotificationIntakeStatus.FAILED_FINAL.name, finalRow.status)
        assertEquals(MAX_ATTEMPTS, finalRow.attempts)
        assertNull(finalRow.nextAttemptAt)
        assertEquals(NOW, finalRow.terminalAt)

        // Conditional WHERE: a final-failed row is never transitioned or counted again.
        assertEquals(0, markEnqueueFailed(id, nowMs = NOW + 5_000L))
        assertEquals(finalRow, intakeDao.getById(id))
    }

    // ── T7: retryable row is recovered only once nextAttemptAt passes ───────

    @Test(timeout = 60_000)
    fun `retryable row after enqueue failure surfaces in getReadyForProcessing only once nextAttemptAt passes`() = runBlocking {
        val result = coordinator().capture(
            packageName = PACKAGE,
            appName = "App",
            notificationKey = "key",
            notificationKeyHash = "hash",
            postTime = NOW,
            title = "Title",
            text = "Text",
            combinedBody = null,
            subText = null,
            extrasJson = null,
            rawStorageMode = RawStorageMode.STORE_RAW,
            correlationId = "corr-recovery",
            source = "listener"
        )
        assertTrue(result is NotificationIntakeCaptureResult.EnqueueFailed)
        val row = intakeDao.getByFingerprint(fingerprint)!!
        assertEquals(NotificationIntakeStatus.FAILED_RETRYABLE.name, row.status)
        assertEquals(NOW + 30_000L, row.nextAttemptAt)

        // Not due yet: immediately after the failure and one millisecond before nextAttemptAt.
        assertTrue(intakeDao.getReadyForProcessing(NOW, 100).none { it.id == row.id })
        assertTrue(intakeDao.getReadyForProcessing(NOW + 29_999L, 100).none { it.id == row.id })

        // Recovery scan (e.g. after process death) surfaces it exactly at and after nextAttemptAt.
        assertTrue(intakeDao.getReadyForProcessing(NOW + 30_000L, 100).any { it.id == row.id })
        assertTrue(intakeDao.getReadyForProcessing(NOW + 31_000L, 100).any { it.id == row.id })
    }

    @Test(timeout = 60_000)
    fun `FAILED_FINAL row after enqueue failure never surfaces in getReadyForProcessing`() = runBlocking {
        val id = insert(intakeRow("fp-final-not-ready", NotificationIntakeStatus.RECEIVED.name, attempts = MAX_ATTEMPTS - 1))

        assertEquals(1, markEnqueueFailed(id))
        assertEquals(NotificationIntakeStatus.FAILED_FINAL.name, intakeDao.getById(id)!!.status)

        assertTrue(intakeDao.getReadyForProcessing(NOW, 100).none { it.id == id })
        assertTrue(intakeDao.getReadyForProcessing(NOW + 7_200_000L, 100).none { it.id == id })
    }

    // ── S1 (fix-1.0.4): keyset-paged recoverPending against real Room ───────

    private fun recoveryScheduler(
        mode: RestoreMaintenanceMode = normalMaintenanceMode()
    ) = NotificationIntakeRecoveryScheduler(
        intakeDao = intakeDao,
        workManager = workManager,
        timeProvider = timeProvider,
        writeBarrier = DatabaseWriteBarrier(mode)
    )

    private fun captureEnqueuedNames(): MutableList<String> {
        val names = mutableListOf<String>()
        every {
            workManager.enqueueUniqueWork(
                capture(names),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        } returns mockk<Operation>(relaxed = true)
        return names
    }

    private suspend fun insertReadyRows(count: Int, capturedAtOf: (Int) -> Long, prefix: String = "rp"): List<Long> =
        (0 until count).map { i ->
            insert(
                intakeRow("$prefix-$i", NotificationIntakeStatus.RECEIVED.name, attempts = 0)
                    .copy(capturedAt = capturedAtOf(i))
            )
        }

    @Test(timeout = 120_000)
    fun `recoverPending enqueues 250 ready rows beyond one page exactly once each`() = runBlocking {
        val ids = insertReadyRows(250, { NOW - 1_000_000L + it })
        val names = captureEnqueuedNames()

        recoveryScheduler().recoverPending()

        assertEquals(250, names.size)
        assertEquals(ids.map { NotificationIntakeWorkNames.uniqueWorkName(it) }.toSet(), names.toSet())
        // oldest first (capturedAt ASC)
        assertEquals(ids.map { NotificationIntakeWorkNames.uniqueWorkName(it) }, names)
    }

    @Test(timeout = 120_000)
    fun `recoverPending with identical capturedAt across a page boundary loses and repeats nothing`() = runBlocking {
        // 150 rows share one capturedAt, so the 100/101 page boundary falls inside the tie group.
        val tied = insertReadyRows(150, { NOW - 5_000L }, prefix = "tie")
        val later = insertReadyRows(5, { NOW - 1_000L + it }, prefix = "later")
        val names = captureEnqueuedNames()

        recoveryScheduler().recoverPending()

        val expected = (tied + later).map { NotificationIntakeWorkNames.uniqueWorkName(it) }
        assertEquals(155, names.size)
        assertEquals(expected.toSet(), names.toSet())
        assertEquals("no id may be enqueued twice", names.size, names.toSet().size)
        // ties are ordered by id ascending
        assertEquals(expected, names)
    }

    @Test(timeout = 120_000)
    fun `recoverPending stops at the 20 page cap and leaves the remainder for the next run`() = runBlocking {
        val ids = insertReadyRows(250, { NOW - 1_000_000L + it })
        val names = captureEnqueuedNames()

        recoveryScheduler().recoverPending(limit = 10)

        // 20 pages x 10 rows = 200 oldest rows; the newest 50 are untouched this run.
        assertEquals(200, names.size)
        assertEquals(ids.take(200).map { NotificationIntakeWorkNames.uniqueWorkName(it) }, names)
        assertTrue(names.none { it == NotificationIntakeWorkNames.uniqueWorkName(ids[200]) })
    }

    @Test(timeout = 120_000)
    fun `recoverPending with a short final page ends the loop and enqueues only that page`() = runBlocking {
        val ids = insertReadyRows(30, { NOW - 100_000L + it })
        val names = captureEnqueuedNames()

        recoveryScheduler().recoverPending()

        assertEquals(ids.map { NotificationIntakeWorkNames.uniqueWorkName(it) }, names)
    }

    @Test(timeout = 120_000)
    fun `recoverPending with exactly one full page enqueues it once and terminates on the empty next page`() = runBlocking {
        val ids = insertReadyRows(100, { NOW - 100_000L + it })
        val names = captureEnqueuedNames()

        recoveryScheduler().recoverPending()

        assertEquals(ids.map { NotificationIntakeWorkNames.uniqueWorkName(it) }, names)
    }

    @Test(timeout = 120_000)
    fun `recoverPending with a blocked write barrier enqueues nothing and touches no rows`() = runBlocking {
        insertReadyRows(5, { NOW - 100_000L + it })
        val stale = insert(
            intakeRow("rp-stale-blocked", NotificationIntakeStatus.PROCESSING.name, attempts = 1, lockedAt = NOW - 3_600_000L, lockedBy = "w")
        )
        val blocked = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { blocked.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        every { blocked.isWritesAllowed() } returns false
        val names = captureEnqueuedNames()

        recoveryScheduler(blocked).recoverPending()

        assertTrue(names.isEmpty())
        assertEquals(NotificationIntakeStatus.PROCESSING.name, intakeDao.getById(stale)!!.status)
    }

    @Test(timeout = 120_000)
    fun `recoverPending releases stale PROCESSING rows and enqueues them but leaves fresh PROCESSING rows alone`() = runBlocking {
        val stale = insert(
            intakeRow("rp-stale", NotificationIntakeStatus.PROCESSING.name, attempts = 1, lockedAt = NOW - 11 * 60 * 1000L, lockedBy = "w")
        )
        val fresh = insert(
            intakeRow("rp-fresh", NotificationIntakeStatus.PROCESSING.name, attempts = 1, lockedAt = NOW - 60 * 1000L, lockedBy = "w")
        )
        val names = captureEnqueuedNames()

        recoveryScheduler().recoverPending()

        assertEquals(NotificationIntakeStatus.FAILED_RETRYABLE.name, intakeDao.getById(stale)!!.status)
        assertEquals(NotificationIntakeStatus.PROCESSING.name, intakeDao.getById(fresh)!!.status)
        assertEquals(listOf(NotificationIntakeWorkNames.uniqueWorkName(stale)), names)
    }

    @Test(timeout = 120_000)
    fun `recoverPending skips FAILED_FINAL and not-yet-due retryable rows`() = runBlocking {
        val ready = insert(intakeRow("rp-ready", NotificationIntakeStatus.RECEIVED.name, attempts = 0))
        insert(intakeRow("rp-final", NotificationIntakeStatus.FAILED_FINAL.name, attempts = MAX_ATTEMPTS, terminalAt = NOW))
        insert(
            intakeRow("rp-later", NotificationIntakeStatus.FAILED_RETRYABLE.name, attempts = 1, nextAttemptAt = NOW + 60_000L)
        )
        val names = captureEnqueuedNames()

        recoveryScheduler().recoverPending()

        assertEquals(listOf(NotificationIntakeWorkNames.uniqueWorkName(ready)), names)
    }

    // ── R6 (fix-1.0.4): cap warning precision and injected dispatcher ───────

    private val capWarning = "Intake recovery reached page cap; remaining rows deferred to next run"

    /** Runs [block] with a Timber tree planted and returns the warning-level messages it saw. */
    private inline fun collectWarnings(block: () -> Unit): List<String> {
        val warnings = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == android.util.Log.WARN) warnings += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            block()
        } finally {
            timber.log.Timber.uproot(tree)
        }
        return warnings
    }

    @Test(timeout = 120_000)
    fun `R6 cap warning fires when the 20th page was full`() = runBlocking {
        val ids = insertReadyRows(250, { NOW - 1_000_000L + it })
        val names = captureEnqueuedNames()

        val warnings = collectWarnings { runBlocking { recoveryScheduler().recoverPending(limit = 10) } }

        assertEquals(200, names.size)
        assertEquals(ids.take(200).map { NotificationIntakeWorkNames.uniqueWorkName(it) }, names)
        assertEquals(1, warnings.count { it == capWarning })
    }

    @Test(timeout = 120_000)
    fun `R6 cap warning does not fire when the 20th page was short`() = runBlocking {
        // 19 full pages of 10 plus a short 20th page of 5: every row is enqueued, nothing is deferred.
        insertReadyRows(195, { NOW - 1_000_000L + it })
        val names = captureEnqueuedNames()

        val warnings = collectWarnings { runBlocking { recoveryScheduler().recoverPending(limit = 10) } }

        assertEquals(195, names.size)
        assertTrue("no cap warning expected", warnings.none { it == capWarning })
    }

    @Test(timeout = 120_000)
    fun `R6 cap warning does not fire when the backlog ends before page 20`() = runBlocking {
        insertReadyRows(30, { NOW - 100_000L + it })
        captureEnqueuedNames()

        val warnings = collectWarnings { runBlocking { recoveryScheduler().recoverPending(limit = 10) } }

        assertTrue("no cap warning expected", warnings.none { it == capWarning })
    }

    @Test(timeout = 120_000)
    fun `R6 recoverPending builds and enqueues work on the injected dispatcher not the caller thread`() {
        val executor = Executors.newSingleThreadExecutor(daemonFactory("intake-recovery-injected-io"))
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking { insertReadyRows(3, { NOW - 100_000L + it }) }
            val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
            every {
                workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            } answers {
                threads += Thread.currentThread().name
                mockk<Operation>(relaxed = true)
            }
            val scheduler = NotificationIntakeRecoveryScheduler(
                intakeDao = intakeDao,
                workManager = workManager,
                timeProvider = timeProvider,
                writeBarrier = DatabaseWriteBarrier(normalMaintenanceMode()),
                ioDispatcher = dispatcher
            )
            val callerThread = Thread.currentThread().name

            runBlocking { scheduler.recoverPending() }

            assertEquals(3, threads.size)
            assertTrue(
                "work must run on the injected dispatcher, saw $threads",
                threads.all { it.startsWith("intake-recovery-injected-io") } // coroutine debug appends @coroutine#N
            )
            assertTrue(!callerThread.startsWith("intake-recovery-injected-io"))
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PACKAGE = "com.example.app"
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * A hand-written [Operation] whose result is a REAL, already-failed future.
 * `Operation.await()` therefore throws [cause] immediately, exactly like a
 * failed WorkManager enqueue, without any MockK interception of the suspend
 * extension (which is what hung the original test).
 */
private class FailingOperation(cause: Throwable) : Operation {
    private val liveState: MutableLiveData<Operation.State> =
        MutableLiveData(Operation.State.FAILURE(cause))
    private val future: ListenableFuture<Operation.State.SUCCESS> =
        Futures.immediateFailedFuture(cause)

    override fun getState(): LiveData<Operation.State> = liveState
    override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = future
}
