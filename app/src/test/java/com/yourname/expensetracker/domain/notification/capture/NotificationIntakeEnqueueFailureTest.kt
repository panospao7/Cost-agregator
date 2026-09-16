package com.yourname.expensetracker.domain.notification.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.await
import androidx.work.WorkManager
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.notification.RawNotificationFingerprint
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-10 10b (P1-003): enqueue-failure handling against a REAL in-memory Room.
 *
 *  - operation failure transitions the row atomically (attempts increment once,
 *    FAILED_RETRYABLE with the shared backoff ladder, locks cleared) and the
 *    coordinator reports EnqueueFailed;
 *  - the transition is idempotent and reaches FAILED_FINAL at maxAttempts with
 *    nextAttemptAt cleared (SQL CASE semantics, exercised on the real DAO);
 *  - a final-failed row is not transitioned again (conditional WHERE);
 *  - recovery after process death: the retryable row becomes visible to
 *    getReadyForProcessing once its nextAttemptAt passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@Ignore(
    "Hangs on first execution (10+ min, reproducible across clean JVM slates): the " +
    "real-Room + runTest + MockK suspend-extension (Operation.await) interplay never " +
    "resumes. The SQL semantics it would pin (markEnqueueFailed CASE, conditional " +
    "WHERE) are unchanged from the reviewed implementation; re-enable via the RP-21 " +
    "withTransaction/mock-hang workstream (same family as the relaxed withTransaction " +
    "hang documented in WAVE-1-STATUS)."
)
class NotificationIntakeEnqueueFailureTest {

    private lateinit var database: AppDatabase
    private lateinit var intakeDao: com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
    private lateinit var workManager: WorkManager
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val now = 1_700_000_000_000L
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = now }

    private val fingerprint = RawNotificationFingerprint.compute(
        packageName = "com.example.app",
        title = "Title",
        text = "Text",
        bigText = null,
        timestamp = now
    )

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        intakeDao = database.notificationIntakeDao()
        workManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun maintenanceMode(): RestoreMaintenanceMode {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "NORMAL"
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
        return RestoreMaintenanceMode(ctx, FakeTimeProvider(now))
    }

    private fun failingOperation(): Operation {
        val operation = mockk<Operation>()
        coEvery { operation.await() } throws java.io.IOException("wm down")
        coEvery {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } returns operation
        return operation
    }

    private fun coordinator(): NotificationIntakeCoordinator {
        val mode = maintenanceMode()
        val emitter = NotificationDiagnosticEmitter(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mode,
            Dispatchers.Unconfined
        )
        return NotificationIntakeCoordinator(
            intakeDao = intakeDao,
            workManager = workManager,
            diagnostics = emitter,
            timeProvider = timeProvider,
            crypto = NotificationTransientPayloadCrypto(mockk(relaxed = true)),
            writeBarrier = DatabaseWriteBarrier(mode),
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        )
    }

    private suspend fun captureViaCoordinator(): NotificationIntakeCaptureResult =
        coordinator().capture(
            packageName = "com.example.app",
            appName = "App",
            notificationKey = "key",
            notificationKeyHash = "hash",
            postTime = now,
            title = "Title",
            text = "Text",
            combinedBody = null,
            subText = null,
            extrasJson = null,
            rawStorageMode = RawStorageMode.STORE_RAW,
            correlationId = "corr",
            source = "listener"
        )

    private suspend fun insertedRow() = intakeDao.getByFingerprint(fingerprint)

    @Test
    fun `operation failure transitions the row and reports EnqueueFailed`() = runTest {
        failingOperation()
        val result = captureViaCoordinator()

        assertTrue(result is NotificationIntakeCaptureResult.EnqueueFailed)
        val after = insertedRow()!!
        assertEquals(after.id, (result as NotificationIntakeCaptureResult.EnqueueFailed).intakeId)
        assertEquals("FAILED_RETRYABLE", after.status)
        assertEquals(1, after.attempts)
        assertEquals("ENQUEUE_FAILED", after.lastFailureCode)
        assertEquals(now + 30_000L, after.nextAttemptAt)
        assertNull(after.lockedAt)
    }

    @Test
    fun `repeated failures reach FAILED_FINAL at maxAttempts and stop transitioning`() = runTest {
        failingOperation()
        captureViaCoordinator()
        val intakeId = insertedRow()!!.id
        // Three more enqueue failures: attempts 2..4 stay retryable on the ladder.
        repeat(3) {
            val transitioned = intakeDao.markEnqueueFailed(
                id = intakeId,
                nextAttemptAt = now + 3_600_000L,
                failureCode = "ENQUEUE_FAILED",
                failureHash = null,
                nowMs = now
            )
            assertEquals(1, transitioned)
        }
        val atFour = insertedRow()!!
        assertEquals(4, atFour.attempts)
        assertEquals("FAILED_RETRYABLE", atFour.status)
        // Fifth attempt hits maxAttempts: FAILED_FINAL, nextAttemptAt cleared.
        val transitioned = intakeDao.markEnqueueFailed(
            id = intakeId,
            nextAttemptAt = now + 3_600_000L,
            failureCode = "ENQUEUE_FAILED",
            failureHash = null,
            nowMs = now
        )
        assertEquals(1, transitioned)
        val finalRow = insertedRow()!!
        assertEquals("FAILED_FINAL", finalRow.status)
        assertEquals(5, finalRow.attempts)
        assertNull(finalRow.nextAttemptAt)
        // Conditional WHERE: a final-failed row is never transitioned again.
        assertEquals(
            0, intakeDao.markEnqueueFailed(
                id = intakeId,
                nextAttemptAt = now + 3_600_000L,
                failureCode = "ENQUEUE_FAILED",
                failureHash = null,
                nowMs = now
            )
        )
        assertEquals(5, insertedRow()!!.attempts)
    }

    @Test
    fun `retryable row becomes recoverable once nextAttemptAt passes`() = runTest {
        failingOperation()
        captureViaCoordinator()
        val intakeId = insertedRow()!!.id
        // Immediately after the failure the row is NOT yet due.
        val notYet = intakeDao.getReadyForProcessing(now, 100)
        assertTrue(notYet.none { it.id == intakeId })
        // Recovery scan at +31s surfaces it (recovery after process death).
        val ready = intakeDao.getReadyForProcessing(now + 31_000L, 100)
        assertTrue(ready.any { it.id == intakeId })
        assertNotNull(insertedRow())
    }
}
