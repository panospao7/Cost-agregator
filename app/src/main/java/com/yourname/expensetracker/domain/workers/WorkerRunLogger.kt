package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

interface WorkerRunLogger {
    suspend fun start(workerName: String): WorkerRunHandle
}

interface WorkerRunHandle {
    suspend fun success(rowsScanned: Int = 0, rowsUpdated: Int = 0, notificationsSent: Int = 0, message: String? = null)
    suspend fun skipped(reason: String)
    suspend fun retry(reason: String, error: Throwable? = null)
    suspend fun failure(reason: String, error: Throwable? = null)
}

@Singleton
class WorkerRunLoggerImpl @Inject constructor(
    private val dao: BackgroundJobRunDao,
    private val timeProvider: TimeProvider
) : WorkerRunLogger {

    override suspend fun start(workerName: String): WorkerRunHandle {
        val id = dao.insert(
            BackgroundJobRun(
                workerName = workerName,
                startedAt = timeProvider.now(),
                status = "RUNNING"
            )
        )
        return Handle(id, workerName, timeProvider, dao)
    }

    private class Handle(
        private val runId: Long,
        private val workerName: String,
        private val timeProvider: TimeProvider,
        private val dao: BackgroundJobRunDao
    ) : WorkerRunHandle {
        override suspend fun success(rowsScanned: Int, rowsUpdated: Int, notificationsSent: Int, message: String?) {
            dao.update(
                BackgroundJobRun(
                    id = runId,
                    workerName = workerName,
                    startedAt = 0L,
                    finishedAt = timeProvider.now(),
                    status = "SUCCESS",
                    rowsScanned = rowsScanned,
                    rowsUpdated = rowsUpdated,
                    notificationsSent = notificationsSent,
                    retryReason = null,
                    errorMessage = message
                )
            )
        }

        override suspend fun skipped(reason: String) {
            dao.update(
                BackgroundJobRun(
                    id = runId,
                    workerName = workerName,
                    startedAt = 0L,
                    finishedAt = timeProvider.now(),
                    status = "SKIPPED_$reason",
                    rowsScanned = 0,
                    rowsUpdated = 0,
                    notificationsSent = 0,
                    retryReason = reason,
                    errorMessage = null
                )
            )
        }

        override suspend fun retry(reason: String, error: Throwable?) {
            dao.update(
                BackgroundJobRun(
                    id = runId,
                    workerName = workerName,
                    startedAt = 0L,
                    finishedAt = timeProvider.now(),
                    status = "RETRY",
                    rowsScanned = 0,
                    rowsUpdated = 0,
                    notificationsSent = 0,
                    retryReason = reason,
                    errorMessage = error?.message
                )
            )
        }

        override suspend fun failure(reason: String, error: Throwable?) {
            dao.update(
                BackgroundJobRun(
                    id = runId,
                    workerName = workerName,
                    startedAt = 0L,
                    finishedAt = timeProvider.now(),
                    status = "FAILED",
                    rowsScanned = 0,
                    rowsUpdated = 0,
                    notificationsSent = 0,
                    retryReason = null,
                    errorMessage = error?.let { "$reason: ${it.message}" } ?: reason
                )
            )
        }
    }
}