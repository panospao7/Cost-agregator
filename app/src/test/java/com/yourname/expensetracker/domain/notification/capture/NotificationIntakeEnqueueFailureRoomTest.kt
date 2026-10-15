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
 * without the interplay that made [NotificationIntakeEnqueueFailureTest] hang.
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
            workManager.enqueueUniqueWork("${after.id}", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
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
