package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import timber.log.Timber
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
         * PR12B: Mutex-based terminal state machine replaces AtomicBoolean CAS-before-DB.
         * The in-memory [completed] flag is ONLY set AFTER a successful (affected==1)
         * DB write, or after confirming the DB row is already terminal. This prevents
         * the handle from being permanently marked complete when the DB update fails.
         */
        private val terminalMutex = Mutex()
        private var completed = false

        sealed class TerminalResult {
            /** DB write succeeded (affected==1). Handle is now durably terminal. */
            object Completed : TerminalResult()
            /** Another caller already won the local mutex race. No DB call was made. */
            object AlreadyCompletedLocal : TerminalResult()
            /** DB row was already terminal (affected==0 + getById != RUNNING). */
            data class AlreadyCompletedDb(val status: String?) : TerminalResult()
            /** DB write timed out. Handle is NOT marked completed — retryable. */
            object NotDurableTimeout : TerminalResult()
            /** DB write returned 0 affected but the row is still RUNNING. */
            object NotDurableStillRunning : TerminalResult()
            /** DB write failed with a non-cancellation exception. */
            data class NotDurableFailure(val error: Throwable) : TerminalResult()
        }

        private data class TerminalArgs(
            val rowsScanned: Int = 0,
            val rowsUpdated: Int = 0,
            val notificationsSent: Int = 0,
            val statusReason: String? = null,
            val retryReason: String? = null,
            val errorMessage: String? = null,
            val errorClass: String? = null,
            val cancellationReason: String? = null,
            val terminalReasonCode: String? = null,
            val terminalDiagnosticCode: String? = null,
            val partialFailureCount: Int? = null,
            val failedTargetCount: Int? = null
        )

        private suspend fun terminal(
            status: String,
            args: TerminalArgs
        ): TerminalResult {
            terminalMutex.lock()
            try {
                if (completed) return TerminalResult.AlreadyCompletedLocal

                val affected = try {
                    withTimeout(TERMINAL_WRITE_TIMEOUT_MS) {
                        dao.completeTerminal(
                            id = runId,
                            status = status,
                            finishedAt = timeProvider.now(),
                            rowsScanned = args.rowsScanned,
                            rowsUpdated = args.rowsUpdated,
                            notificationsSent = args.notificationsSent,
                            statusReason = args.statusReason,
                            retryReason = args.retryReason,
                            errorMessage = args.errorMessage,
                            errorClass = args.errorClass,
                            cancellationReason = args.cancellationReason,
                            terminalReasonCode = args.terminalReasonCode,
                            terminalDiagnosticCode = args.terminalDiagnosticCode,
                            partialFailureCount = args.partialFailureCount,
                            failedTargetCount = args.failedTargetCount
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    return TerminalResult.NotDurableTimeout
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return TerminalResult.NotDurableFailure(e)
                }

                if (affected == 1) {
                    completed = true
                    return TerminalResult.Completed
                }

                // affected == 0: row might already be terminal, or might still be RUNNING
                val current = dao.getById(runId)
                if (current?.status != "RUNNING") {
                    completed = true
                    return TerminalResult.AlreadyCompletedDb(current?.status)
                }

                return TerminalResult.NotDurableStillRunning
            } finally {
                terminalMutex.unlock()
            }
        }

        override suspend fun success(rowsScanned: Int, rowsUpdated: Int, notificationsSent: Int, message: String?) {
            when (val result = terminal("SUCCESS", TerminalArgs(
                rowsScanned = rowsScanned,
                rowsUpdated = rowsUpdated,
                notificationsSent = notificationsSent,
                statusReason = message
            ))) {
                is TerminalResult.Completed -> { /* normal */ }
                is TerminalResult.AlreadyCompletedLocal -> Timber.w("Handle $runId already completed locally — ignoring duplicate success")
                is TerminalResult.AlreadyCompletedDb -> Timber.w("Handle $runId already completed in DB as ${result.status} — ignoring duplicate success")
                is TerminalResult.NotDurableTimeout -> Timber.w("Handle $runId terminal SUCCESS timed out — DB state may be stale")
                is TerminalResult.NotDurableStillRunning -> Timber.w("Handle $runId terminal SUCCESS returned 0 affected but row is still RUNNING")
                is TerminalResult.NotDurableFailure -> Timber.w(result.error, "Handle $runId terminal SUCCESS failed")
            }
        }

        override suspend fun skipped(reason: String) {
            when (val result = terminal("SKIPPED", TerminalArgs(statusReason = reason))) {
                is TerminalResult.Completed -> { }
                is TerminalResult.AlreadyCompletedLocal -> Timber.w("Handle $runId already completed locally — ignoring duplicate skipped")
                is TerminalResult.AlreadyCompletedDb -> Timber.w("Handle $runId already completed in DB as ${result.status} — ignoring duplicate skipped")
                is TerminalResult.NotDurableTimeout -> Timber.w("Handle $runId terminal SKIPPED timed out — DB state may be stale")
                is TerminalResult.NotDurableStillRunning -> Timber.w("Handle $runId terminal SKIPPED returned 0 affected but row is still RUNNING")
                is TerminalResult.NotDurableFailure -> Timber.w(result.error, "Handle $runId terminal SKIPPED failed")
            }
        }

        override suspend fun retry(reason: String, error: Throwable?) {
            when (val result = terminal("RETRY", TerminalArgs(
                retryReason = reason,
                errorMessage = sanitizer.sanitizeExceptionMessage(error?.message),
                errorClass = error?.javaClass?.simpleName
            ))) {
                is TerminalResult.Completed -> { }
                is TerminalResult.AlreadyCompletedLocal -> Timber.w("Handle $runId already completed locally — ignoring duplicate retry")
                is TerminalResult.AlreadyCompletedDb -> Timber.w("Handle $runId already completed in DB as ${result.status} — ignoring duplicate retry")
                is TerminalResult.NotDurableTimeout -> Timber.w("Handle $runId terminal RETRY timed out — DB state may be stale")
                is TerminalResult.NotDurableStillRunning -> Timber.w("Handle $runId terminal RETRY returned 0 affected but row is still RUNNING")
                is TerminalResult.NotDurableFailure -> Timber.w(result.error, "Handle $runId terminal RETRY failed")
            }
        }

        override suspend fun failure(reason: String, error: Throwable?) {
            when (val result = terminal("FAILED", TerminalArgs(
                errorMessage = sanitizer.sanitizeExceptionMessage(error?.let { "$reason: ${it.message}" } ?: reason),
                errorClass = error?.javaClass?.simpleName
            ))) {
                is TerminalResult.Completed -> { }
                is TerminalResult.AlreadyCompletedLocal -> Timber.w("Handle $runId already completed locally — ignoring duplicate failure")
                is TerminalResult.AlreadyCompletedDb -> Timber.w("Handle $runId already completed in DB as ${result.status} — ignoring duplicate failure")
                is TerminalResult.NotDurableTimeout -> Timber.w("Handle $runId terminal FAILED timed out — DB state may be stale")
                is TerminalResult.NotDurableStillRunning -> Timber.w("Handle $runId terminal FAILED returned 0 affected but row is still RUNNING")
                is TerminalResult.NotDurableFailure -> Timber.w(result.error, "Handle $runId terminal FAILED failed")
            }
        }

        override suspend fun cancelled(reason: String) {
            when (val result = terminal("CANCELLED", TerminalArgs(
                statusReason = reason,
                cancellationReason = reason
            ))) {
                is TerminalResult.Completed -> { }
                is TerminalResult.AlreadyCompletedLocal -> Timber.w("Handle $runId already completed locally — ignoring duplicate cancelled")
                is TerminalResult.AlreadyCompletedDb -> Timber.w("Handle $runId already completed in DB as ${result.status} — ignoring duplicate cancelled")
                is TerminalResult.NotDurableTimeout -> Timber.w("Handle $runId terminal CANCELLED timed out — DB state may be stale")
                is TerminalResult.NotDurableStillRunning -> Timber.w("Handle $runId terminal CANCELLED returned 0 affected but row is still RUNNING")
                is TerminalResult.NotDurableFailure -> Timber.w(result.error, "Handle $runId terminal CANCELLED failed")
            }
        }

        override suspend fun staleAborted() {
            when (val result = terminal("STALE_ABORTED", TerminalArgs())) {
                is TerminalResult.Completed -> { }
                is TerminalResult.AlreadyCompletedLocal -> Timber.w("Handle $runId already completed locally — ignoring duplicate staleAborted")
                is TerminalResult.AlreadyCompletedDb -> Timber.w("Handle $runId already completed in DB as ${result.status} — ignoring duplicate staleAborted")
                is TerminalResult.NotDurableTimeout -> Timber.w("Handle $runId terminal STALE_ABORTED timed out — DB state may be stale")
                is TerminalResult.NotDurableStillRunning -> Timber.w("Handle $runId terminal STALE_ABORTED returned 0 affected but row is still RUNNING")
                is TerminalResult.NotDurableFailure -> Timber.w(result.error, "Handle $runId terminal STALE_ABORTED failed")
            }
        }
    }
}
