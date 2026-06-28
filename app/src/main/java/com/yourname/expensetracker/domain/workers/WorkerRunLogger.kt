package com.yourname.expensetracker.domain.workers

import android.util.Log
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

interface WorkerRunLogger {
    suspend fun start(workerName: String): WorkerRunHandle
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

    override suspend fun start(workerName: String): WorkerRunHandle {
        val startedAt = timeProvider.now()
        val correlationId = CorrelationIds.newId()
        val id = dao.insert(
            BackgroundJobRun(
                workerName = workerName,
                startedAt = startedAt,
                status = "RUNNING",
                correlationId = correlationId
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
            update("SUCCESS", rowsScanned = rowsScanned, rowsUpdated = rowsUpdated, notificationsSent = notificationsSent, statusReason = message)
        }

        override suspend fun skipped(reason: String) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate skipped"); return }
            update("SKIPPED", statusReason = reason)
        }

        override suspend fun retry(reason: String, error: Throwable?) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate retry"); return }
            update("RETRY", retryReason = reason, errorMessage = sanitizer.sanitizeExceptionMessage(error?.message), errorClass = error?.javaClass?.simpleName)
        }

        override suspend fun failure(reason: String, error: Throwable?) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate failure"); return }
            update("FAILED", errorMessage = sanitizer.sanitizeExceptionMessage(error?.let { "$reason: ${it.message}" } ?: reason), errorClass = error?.javaClass?.simpleName)
        }

        override suspend fun cancelled(reason: String) {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate cancelled"); return }
            update("CANCELLED", statusReason = reason, cancellationReason = reason)
        }

        override suspend fun staleAborted() {
            if (!completed.compareAndSet(false, true)) { Log.w("WorkerRunLogger", "Handle $runId already completed — ignoring duplicate staleAborted"); return }
            update("STALE_ABORTED")
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
            cancellationReason: String? = null
        ) {
            dao.update(
                BackgroundJobRun(
                    id = runId,
                    workerName = workerName,
                    startedAt = startedAt,
                    finishedAt = timeProvider.now(),
                    status = status,
                    rowsScanned = rowsScanned,
                    rowsUpdated = rowsUpdated,
                    notificationsSent = notificationsSent,
                    statusReason = statusReason,
                    retryReason = retryReason,
                    errorMessage = errorMessage,
                    correlationId = correlationId,
                    cancellationReason = cancellationReason,
                    errorClass = errorClass
                )
            )
        }
    }
}
