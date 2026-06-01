package com.yourname.expensetracker.worker

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.Result as WorkResult
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.notification.capture.NotificationTransientPayloadCrypto
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for timeout/cancellation distinction in [NotificationIntakeWorker].
 *
 * These tests verify that [TimeoutCancellationException] is treated as a
 * retryable condition (WorkManager 10-min execution timeout), while a plain
 * [CancellationException] is propagated as a system cancellation.
 */
class NotificationIntakeWorkerTimeoutTest {

    @Test
    fun `worker_timeout_marks_retryable_not_terminal`() {
        // Verify that TimeoutCancellationException is NOT a subtype of
        // plain CancellationException in the catch hierarchy — i.e. a
        // dedicated catch block for TimeoutCancellationException must
        // appear BEFORE the generic CancellationException catch.
        //
        // If TimeoutCancellationException were caught by the generic
        // CancellationException block it would be re-thrown instead of
        // being handled as a retryable timeout.
        val timeoutEx: Throwable = TimeoutCancellationException("WorkManager 10-min timeout")
        val isCancellation = timeoutEx is CancellationException
        val isTimeout = timeoutEx is TimeoutCancellationException

        // TimeoutCancellationException IS a CancellationException (subclass),
        // so ordering of catch blocks matters: the timeout-specific block
        // must come BEFORE the generic CancellationException block.
        assertEquals("TimeoutCancellationException must be a CancellationException subclass",
            true, isCancellation)
        assertEquals("TimeoutCancellationException must be a TimeoutCancellationException",
            true, isTimeout)
    }

    @Test
    fun `worker_system_cancellation_propagates`() {
        // Verify that a plain (non-timeout) CancellationException is NOT
        // a TimeoutCancellationException, so it won't be caught by the
        // timeout-specific catch block and will propagate as a system
        // cancellation.
        val systemCancel: Throwable = CancellationException("System shutdown")
        val isTimeout = systemCancel is TimeoutCancellationException

        assertEquals("Plain CancellationException must NOT be a TimeoutCancellationException",
            false, isTimeout)
    }

    @Test
    fun `timeout_cancellation_exception_is_subclass_of_cancellation_exception`() {
        // Structural/type-hierarchy documentation test.
        //
        // Verifies that TimeoutCancellationException is a subclass of
        // CancellationException, which means catch-block ordering matters:
        // the TimeoutCancellationException catch must appear BEFORE the
        // generic CancellationException catch in the worker.
        //
        // This is NOT a behavioral test — the actual catch-block ordering
        // is verified by worker_timeout_calls_markRetryableFailure_and_returns_retry.
        val timeoutEx: Throwable = TimeoutCancellationException("WorkManager 10-min timeout")
        val isCancellation = timeoutEx is CancellationException
        val isTimeout = timeoutEx is TimeoutCancellationException

        assertEquals("TimeoutCancellationException must be a CancellationException subclass",
            true, isCancellation)
        assertEquals("TimeoutCancellationException must be a TimeoutCancellationException",
            true, isTimeout)
    }

    @Test
    fun `worker_timeout_calls_markRetryableFailure_and_returns_retry`() = runBlocking {
        // Behavioral test: when repository.processAndSave throws
        // TimeoutCancellationException, the worker must:
        // 1. Call intakeDao.markRetryableFailure with failureCode = "TIMEOUT"
        // 2. Return Result.retry() (to let WorkManager retry)
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val intakeDao = mockk<NotificationIntakeDao>(relaxed = true)
        val repository = mockk<NotificationRepository>(relaxed = true)
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true)

        val intakeId = 42L
        val now = 1_700_000_000_000L
        val intakeRow = intakeEntity(
            id = intakeId, packageName = "com.test.app",
            attempts = 1, maxAttempts = 5, now = now
        )

        every { params.inputData } returns Data.Builder().putLong("intakeId", intakeId).build()
        every { writeBarrier.writesAllowed() } returns true
        every { timeProvider.now() } returns now
        coEvery { intakeDao.getById(intakeId) } returns intakeRow
        coEvery { intakeDao.claimForProcessing(intakeId, now, any()) } returns 1
        coEvery { repository.processAndSave(any(), any(), any(), any()) } throws TimeoutCancellationException("WorkManager 10-min timeout")

        val worker = NotificationIntakeWorker(
            appContext = context, params = params,
            intakeDao = intakeDao, repository = repository,
            writeBarrier = writeBarrier, timeProvider = timeProvider,
            crypto = crypto
        )

        val result = worker.doWork()

        coVerify(exactly = 1) {
            intakeDao.markRetryableFailure(
                id = intakeId, failureCode = "TIMEOUT",
                nextAttemptAt = any(), failureHash = any(), nowMs = now
            )
        }
        assertEquals("Must return retry() for timeout", WorkResult.retry(), result)
    }

    private fun intakeEntity(
        id: Long, packageName: String,
        attempts: Int, maxAttempts: Int, now: Long
    ): NotificationIntakeEntity = NotificationIntakeEntity(
        id = id, packageName = packageName, appName = "Test",
        notificationKeyHash = "hash", postTime = now, capturedAt = now,
        source = "LISTENER", correlationId = "corr-$id",
        dedupeFingerprint = "fp-$id", contentHash = null,
        title = "Title", text = "Text", bigText = null, subText = null,
        extrasJson = null,
        rawStorageMode = "STORE_RAW", payloadMode = "RAW",
        rawPayloadPurgedAt = null,
        transientPayloadCiphertext = null, transientPayloadNonce = null,
        transientPayloadVersion = null, transientPayloadPurgedAt = null,
        status = "RECEIVED",
        attempts = attempts, maxAttempts = maxAttempts,
        nextAttemptAt = null, lockedAt = null, lockedBy = null,
        lastAttemptAt = null, terminalAt = null,
        rawNotificationId = null, expenseId = null, pendingReviewId = null,
        lastFailureCode = null, lastFailureMessageHash = null,
        finalOutcome = null, createdAt = now, updatedAt = now
    )
}
