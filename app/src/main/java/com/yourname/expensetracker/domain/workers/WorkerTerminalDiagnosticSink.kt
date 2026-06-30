package com.yourname.expensetracker.domain.workers

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR12H-3: Durable fallback diagnostic for terminal DB write failures.
 *
 * When a [WorkerRunHandle] terminal call cannot persist its status durably
 * (timeout, exception, or zero-affected), the guard records the failure via
 * this sink so the event is never silently lost. Implementations must not throw.
 */
interface WorkerTerminalDiagnosticSink {
    fun recordWorkerTerminalWriteFailure(
        workerName: String,
        runId: Long,
        correlationId: String?,
        workId: String?,
        runAttempt: Int?,
        intendedStatus: String,
        reasonCode: String?,
        failureCode: String,
        errorClass: String?,
        timestampMs: Long
    )
}

/**
 * Logging-only implementation of [WorkerTerminalDiagnosticSink].
 *
 * **IMPORTANT: This sink is NOT durable.** It emits [Timber.e] with all structured
 * context so non-durable terminal writes are visible in logcat (unlike the previous
 * Timber.w-only approach). However, logcat can be lost, release builds may not
 * preserve logs, and diagnostics emitted via this sink do NOT survive process death.
 *
 * Prefer [FileWorkerTerminalDiagnosticSink] for production use. This sink exists
 * primarily for debug builds via [CompositeWorkerTerminalDiagnosticSink] and for
 * environments where file I/O is not possible.
 */
@Singleton
class LoggingWorkerTerminalDiagnosticSink @Inject constructor() : WorkerTerminalDiagnosticSink {
    override fun recordWorkerTerminalWriteFailure(
        workerName: String,
        runId: Long,
        correlationId: String?,
        workId: String?,
        runAttempt: Int?,
        intendedStatus: String,
        reasonCode: String?,
        failureCode: String,
        errorClass: String?,
        timestampMs: Long
    ) {
        Timber.e(
            "TERMINAL_WRITE_NOT_DURABLE worker=%s runId=%d corrId=%s workId=%s attempt=%s " +
                "intendedStatus=%s reasonCode=%s failureCode=%s errorClass=%s ts=%d",
            workerName, runId, correlationId ?: "-", workId ?: "-",
            runAttempt?.toString() ?: "-",
            intendedStatus, reasonCode ?: "-", failureCode, errorClass ?: "-", timestampMs
        )
    }
}

/**
 * Retained for backward-compatibility with any code that still references
 * the old class name. Delegates to [LoggingWorkerTerminalDiagnosticSink].
 */
@Deprecated(
    message = "Renamed to LoggingWorkerTerminalDiagnosticSink for clarity",
    replaceWith = ReplaceWith("LoggingWorkerTerminalDiagnosticSink")
)
typealias TimberWorkerTerminalDiagnosticSink = LoggingWorkerTerminalDiagnosticSink
