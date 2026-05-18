package com.yourname.expensetracker.domain.workers

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

        override suspend fun success(rowsScanned: Int, rowsUpdated: Int, notificationsSent: Int, message: String?) {
            update("SUCCESS", rowsScanned = rowsScanned, rowsUpdated = rowsUpdated, notificationsSent = notificationsSent)
        }

        override suspend fun skipped(reason: String) {
            update("SKIPPED", statusReason = reason)
        }

        override suspend fun retry(reason: String, error: Throwable?) {
            update("RETRY", retryReason = reason, errorMessage = sanitizer.sanitizeExceptionMessage(error?.message), errorClass = error?.javaClass?.simpleName)
        }

        override suspend fun failure(reason: String, error: Throwable?) {
            update("FAILED", errorMessage = sanitizer.sanitizeExceptionMessage(error?.let { "$reason: ${it.message}" } ?: reason), errorClass = error?.javaClass?.simpleName)
        }

        override suspend fun cancelled(reason: String) {
            update("CANCELLED", statusReason = reason, cancellationReason = reason)
        }

        override suspend fun staleAborted() {
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
