package com.yourname.expensetracker.domain.workers

import android.util.Log
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

interface WorkerRunLogger {
    suspend fun start(
        workerName: String,
        workId: String? = null,
        uniqueWorkName: String? = null,
        specVersion: Int? = null,
        runAttempt: Int? = null,
        leaseId: String? = null
    ): WorkerRunHandle
}

interface WorkerRunHandle {
    val runId: Long
    val correlationId: String
    suspend fun success(rowsScanned: Int = 0, rowsUpdated: Int = 0, notificationsSent: Int = 0, message: String? = null)
    suspend fun skipped(reason: String)
    suspend fun retry(reason: String, error: Throwable? = null)
    suspend fun failure(reason: String, error: Throwable? = null)
    suspend fun cancelled(reason: String)
    suspend fun staleAborted()
}

@Singleton
class WorkerRunLoggerImpl @Inject constructor(
    private val dao: BackgroundJobRunDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider
) : WorkerRunLogger {

    companion object {
        /** PR8: 5 seconds max for a terminal DB write — backstop inside the guard's own timeout. */
        private const val TERMINAL_WRITE_TIMEOUT_MS = 5_000L
    }

    override suspend fun start(
        workerName: String,
        workId: String?,
        uniqueWorkName: String?,
        specVersion: Int?,
        runAttempt: Int?,
        leaseId: String?
    ): WorkerRunHandle {
        val startedAt = timeProvider.now()
        val correlationId = CorrelationIds.newId()
        val id = dao.insert(
            BackgroundJobRun(
                workerName = workerName,
                startedAt = startedAt,
                status = "RUNNING",
                correlationId = correlationId,
                workId = workId,
                uniqueWorkName = uniqueWorkName,
                specVersion = specVersion,
                runAttempt = runAttempt,
                leaseId = leaseId
            )
        )
        return Handle(id, correlationId, workerName, startedAt, timeProvider, sanitizer, dao)
    }

    private class Handle(
        override val runId: Long,
        override val correlationId: String,
        private val workerName: String,
        private val startedAt: Long,
        private val timeProvider: TimeProvider,
        private val sanitizer: EventMetadataSanitizer,
        private val dao: BackgroundJobRunDao
    ) : WorkerRunHandle {

        /**
         * P9 (NEW-P9-015): Idempotency guard — once [complete] has been called
         * (via any terminal method), all subsequent invocations are no-ops.
         * This prevents duplicate database writes when the guard's catch blocks
         * race with a [NonCancellable] terminal update.
         */
        private val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        override suspend fun success(rowsScanned: Int, rowsUpdated: Int, notificationsSent: Int, message: String?) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate success"); return }
            val affected = update("SUCCESS", rowsScanned = rowsScanned, rowsUpdated = rowsUpdated, notificationsSent = notificationsSent, statusReason = message)
            if (affected == 0) Log.w("WorkerRunLogger", "Handle $runId DB-level duplicate terminal detected (success)")
        }

        override suspend fun skipped(reason: String) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate skipped"); return }
            val affected = update("SKIPPED", statusReason = reason)
            if (affected == 0) Log.w("WorkerRunLogger", "Handle $runId DB-level duplicate terminal detected (skipped)")
        }

        override suspend fun retry(reason: String, error: Throwable?) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate retry"); return }
            val affected = update("RETRY", retryReason = reason, errorMessage = sanitizer.sanitizeExceptionMessage(error?.message), errorClass = error?.javaClass?.simpleName)
            if (affected == 0) Log.w("WorkerRunLogger", "Handle $runId DB-level duplicate terminal detected (retry)")
        }

        override suspend fun failure(reason: String, error: Throwable?) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate failure"); return }
            val affected = update("FAILED", errorMessage = sanitizer.sanitizeExceptionMessage(error?.let { "$reason: ${it.message}" } ?: reason), errorClass = error?.javaClass?.simpleName)
            if (affected == 0) Log.w("WorkerRunLogger", "Handle $runId DB-level duplicate terminal detected (failure)")
        }

        override suspend fun cancelled(reason: String) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate cancelled"); return }
            val affected = update("CANCELLED", statusReason = reason, cancellationReason = reason)
            if (affected == 0) Log.w("WorkerRunLogger", "Handle $runId DB-level duplicate terminal detected (cancelled)")
        }

        override suspend fun staleAborted() {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate staleAborted"); return }
            val affected = update("STALE_ABORTED")
            if (affected == 0) Log.w("WorkerRunLogger", "Handle $runId DB-level duplicate terminal detected (staleAborted)")
        }

        private suspend fun update(
            status: String,
            rowsScanned: Int = 0,
            rowsUpdated: Int = 0,
            notificationsSent: Int = 0,
            statusReason: String? = null,
            retryReason: String? = null,
            errorMessage: String? = null,
            errorClass: String? = null,
            cancellationReason: String? = null,
            terminalReasonCode: String? = null,
            terminalDiagnosticCode: String? = null,
            partialFailureCount: Int? = null,
            failedTargetCount: Int? = null
        ): Int {
            return try {
                withTimeout(TERMINAL_WRITE_TIMEOUT_MS) {
                    dao.completeTerminal(
                        id = runId,
                        status = status,
                        finishedAt = timeProvider.now(),
                        rowsScanned = rowsScanned,
                        rowsUpdated = rowsUpdated,
                        notificationsSent = notificationsSent,
                        statusReason = statusReason,
                        retryReason = retryReason,
                        errorMessage = errorMessage,
                        errorClass = errorClass,
                        cancellationReason = cancellationReason,
                        terminalReasonCode = terminalReasonCode,
                        terminalDiagnosticCode = terminalDiagnosticCode,
                        partialFailureCount = partialFailureCount,
                        failedTargetCount = failedTargetCount
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w("WorkerRunLogger", "Terminal DB update timed out for run $runId")
                0
            }
        }
    }
}
