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

sealed interface TerminalWriteOutcome {
    data object Durable : TerminalWriteOutcome
    data class AlreadyTerminal(val status: String?) : TerminalWriteOutcome
    data class NotDurable(
        val intendedStatus: String,
        val reasonCode: String?,
        val failureCode: String,
        val errorClass: String?
    ) : TerminalWriteOutcome
}

interface WorkerRunHandle {
    val runId: Long
    val correlationId: String
    val workerName: String
    val workId: String?
    val runAttempt: Int?
    suspend fun success(rowsScanned: Int = 0, rowsUpdated: Int = 0, notificationsSent: Int = 0, message: String? = null, reasonCode: String? = null): TerminalWriteOutcome
    suspend fun skipped(reason: String): TerminalWriteOutcome
    suspend fun retry(reason: String, error: Throwable? = null): TerminalWriteOutcome
    suspend fun failure(reason: String, error: Throwable? = null): TerminalWriteOutcome
    suspend fun cancelled(reason: String): TerminalWriteOutcome
    suspend fun staleAborted(): TerminalWriteOutcome
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

        /**
         * PR12I-2: Classify a reason/error pair into a structured diagnostic code.
         * Never returns raw exception messages or stack traces.
         *
         * Known-safe message map for [RetryableWorkerException] so custom messages
         * containing paths/PII are never persisted verbatim.
         */
        fun classifyDiagnostic(reason: String, error: Throwable?): String = when {
            error is TimeoutCancellationException -> "TIMEOUT"
            error is RetryableWorkerException -> when (error.reasonCode) {
                "PIPELINE_TIMEOUT" -> "PIPELINE_TIMEOUT"
                else -> "RETRYABLE"
            }
            error is WorkerCheckpointBlockedException -> error.reasonCode
            error is SecurityException -> {
                if (reason.contains("notification", ignoreCase = true) ||
                    reason.contains("permission", ignoreCase = true)
                ) "NOTIFICATION_PERMISSION_DENIED"
                else "SECURITY_EXCEPTION"
            }
            reason.contains("TIMEOUT", ignoreCase = true) -> "TIMEOUT"
            reason.contains("BLOCKED", ignoreCase = true) -> "BLOCKED"
            reason.contains("PRIVACY", ignoreCase = true) -> "PRIVACY"
            reason.contains("RESTORE", ignoreCase = true) -> "RESTORE_BLOCKED"
            reason.contains("NETWORK", ignoreCase = true) -> "NETWORK_UNAVAILABLE"
            else -> WorkerReasonCodes.sanitizeReasonCode(reason)
        }
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
        return Handle(id, correlationId, workerName, workId, runAttempt, startedAt, timeProvider, sanitizer, dao)
    }

    private class Handle(
        override val runId: Long,
        override val correlationId: String,
        override val workerName: String,
        override val workId: String?,
        override val runAttempt: Int?,
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

        private fun TerminalResult.toOutcome(
            intendedStatus: String,
            reasonCode: String?,
            error: Throwable?
        ): TerminalWriteOutcome = when (this) {
            TerminalResult.Completed -> TerminalWriteOutcome.Durable
            is TerminalResult.AlreadyCompletedDb -> TerminalWriteOutcome.AlreadyTerminal(status)
            TerminalResult.AlreadyCompletedLocal -> TerminalWriteOutcome.AlreadyTerminal("LOCAL_COMPLETED")
            TerminalResult.NotDurableTimeout -> TerminalWriteOutcome.NotDurable(
                intendedStatus, reasonCode, "TERMINAL_WRITE_TIMEOUT", "TimeoutCancellationException"
            )
            TerminalResult.NotDurableStillRunning -> TerminalWriteOutcome.NotDurable(
                intendedStatus, reasonCode, "TERMINAL_WRITE_ZERO_AFFECTED", null
            )
            is TerminalResult.NotDurableFailure -> TerminalWriteOutcome.NotDurable(
                intendedStatus, reasonCode, "TERMINAL_WRITE_FAILED", error?.javaClass?.simpleName
            )
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

        override suspend fun success(rowsScanned: Int, rowsUpdated: Int, notificationsSent: Int, message: String?, reasonCode: String?): TerminalWriteOutcome {
            val code = reasonCode ?: com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.WORKER_SUCCESS.name
            val result = terminal("SUCCESS", TerminalArgs(
                rowsScanned = rowsScanned,
                rowsUpdated = rowsUpdated,
                notificationsSent = notificationsSent,
                statusReason = message,
                terminalReasonCode = code,
                terminalDiagnosticCode = code
            ))
            return result.toOutcome("SUCCESS", code, null)
        }

        override suspend fun skipped(reason: String): TerminalWriteOutcome {
            val result = terminal("SKIPPED", TerminalArgs(
                statusReason = reason,
                terminalReasonCode = reason,
                terminalDiagnosticCode = reason
            ))
            return result.toOutcome("SKIPPED", reason, null)
        }

        override suspend fun retry(reason: String, error: Throwable?): TerminalWriteOutcome {
            val result = terminal("RETRY", TerminalArgs(
                retryReason = reason,
                errorMessage = sanitizer.sanitizeExceptionMessage(error?.message),
                errorClass = error?.javaClass?.simpleName,
                terminalReasonCode = reason,
                terminalDiagnosticCode = classifyDiagnostic(reason, error)
            ))
            return result.toOutcome("RETRY", reason, error)
        }

        override suspend fun failure(reason: String, error: Throwable?): TerminalWriteOutcome {
            val result = terminal("FAILED", TerminalArgs(
                statusReason = reason,
                errorMessage = sanitizer.sanitizeExceptionMessage(error?.message),
                errorClass = error?.javaClass?.simpleName,
                terminalReasonCode = reason,
                terminalDiagnosticCode = classifyDiagnostic(reason, error)
            ))
            return result.toOutcome("FAILED", reason, error)
        }

        override suspend fun cancelled(reason: String): TerminalWriteOutcome {
            val result = terminal("CANCELLED", TerminalArgs(
                statusReason = reason,
                cancellationReason = reason,
                terminalReasonCode = reason,
                terminalDiagnosticCode = reason
            ))
            return result.toOutcome("CANCELLED", reason, null)
        }

        override suspend fun staleAborted(): TerminalWriteOutcome {
            val reason = "STALE_RUNNING_ABORTED"
            val result = terminal("STALE_ABORTED", TerminalArgs(
                statusReason = reason,
                terminalReasonCode = reason,
                terminalDiagnosticCode = reason
            ))
            return result.toOutcome("STALE_ABORTED", reason, null)
        }
    }
}
