package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintenance-safe operation run recorder.
 *
 * - Normal DB writable: delegates to [RoomOperationRunRecorder].
 * - Maintenance/restore/write-barrier blocked: returns [SafeSinkOperationRunHandle].
 * - Room insert failure: returns [SafeSinkOperationRunHandle].
 */
@Singleton
class CompositeOperationRunRecorder @Inject constructor(
    private val roomRecorder: RoomOperationRunRecorder,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
) : OperationRunRecorder {

    override suspend fun start(
        operationType: String,
        actor: String?,
        metadata: SafeEventMetadata
    ): OperationRunHandle {
        if (restoreMaintenanceMode.currentMode() != RestoreMaintenanceMode.Mode.NORMAL) {
            return safeHandle(operationType, metadata)
        }
        return try {
            writeBarrier.checkWritesAllowed("OperationRunRecorder.start")
            roomRecorder.start(operationType, actor, metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "OperationRunRecorder: falling back to safe handle for $operationType")
            safeHandle(operationType, metadata)
        }
    }

    override suspend fun <T> runOperation(
        operationType: String,
        actor: String?,
        metadata: SafeEventMetadata,
        block: suspend (OperationRunHandle) -> T
    ): T {
        val run = start(operationType, actor, metadata)
        try {
            val result = block(run)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { run.success() }
            }
            return result
        } catch (e: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) }
            }
            throw e
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { run.failedFinal(e.message ?: "Exception", e) }
            }
            throw e
        }
    }

    override suspend fun recoverStaleRunningOperationRuns(staleAgeMs: Long) {
        try {
            roomRecorder.recoverStaleRunningOperationRuns(staleAgeMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "CompositeOperationRunRecorder: stale recovery failed")
        }
    }

    private suspend fun safeHandle(operationType: String, metadata: SafeEventMetadata = SafeEventMetadata.empty()): OperationRunHandle {
        val handle = SafeSinkOperationRunHandle(
            correlationId = CorrelationIds.newId(),
            operationType = operationType,
            safeSink = safeSink,
            restoreMaintenanceMode = restoreMaintenanceMode,
            timeProvider = timeProvider
        )
        // DDL-016-04: safe handle must emit STARTED to satisfy STARTED -> terminal contract
        runCatching {
            handle.event(
                stage = "STARTED",
                outcome = EventOutcome.ATTEMPTED,
                severity = EventSeverity.INFO,
                metadata = metadata,
                isTerminal = false
            )
        }
        return handle
    }
}

/** No-op handle that records terminal status to safe sink. */
class SafeSinkOperationRunHandle(
    override val runId: Long = 0L,
    override val correlationId: String,
    private val operationType: String,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val timeProvider: TimeProvider
) : OperationRunHandle {

    // DDL-A8-09: terminal-once — prevents multiple terminal events
    private val _isTerminal = java.util.concurrent.atomic.AtomicBoolean(false)
    override val isTerminal: Boolean get() = _isTerminal.get()

    override suspend fun event(
        stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?,
        severity: EventSeverity, metadata: SafeEventMetadata,
        entityType: String?, entityId: Long?, exception: Throwable?, isTerminal: Boolean
    ) {
        // DDL-C67-01/DDL-F876-04: direct terminal event marks handle terminal; skip if already terminal
        if (isTerminal && !_isTerminal.compareAndSet(false, true)) return
        emitSafeEvent(stage, outcome, reasonCode, severity, metadata, exception, isTerminal)
    }

    // DDL-C67-01: terminalOnce must NOT call event() — that would double-CAS and skip emission.
    // Instead it sets terminal state then calls emitSafeEvent directly.
    private suspend fun terminalOnce(
        stage: String, outcome: EventOutcome, severity: EventSeverity = EventSeverity.INFO,
        reasonCode: DiagnosticReasonCode? = null, exception: Throwable? = null
    ) {
        if (!_isTerminal.compareAndSet(false, true)) return
        emitSafeEvent(stage, outcome, reasonCode, severity, SafeEventMetadata.empty(), exception, isTerminal = true)
    }

    /** Lower-level emission that does NOT touch _isTerminal state. */
    private suspend fun emitSafeEvent(
        stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?,
        severity: EventSeverity, metadata: SafeEventMetadata, exception: Throwable?,
        isTerminal: Boolean
    ) {
        try {
            safeSink.recordDiagnosticEvent(
                event = DiagnosticEvent(
                    pipeline = operationTypeToPipeline(operationType),
                    stage = stage,
                    outcome = outcome,
                    severity = severity,
                    reasonCode = reasonCode,
                    correlationId = correlationId,
                    metadata = metadata,
                    exception = exception,
                    isTerminal = isTerminal
                ),
                mode = restoreMaintenanceMode.currentMode()
            )
        } catch (_: Exception) {}
    }

    override suspend fun increment(processed: Int, succeeded: Int, failed: Int, skipped: Int, warnings: Int, errors: Int) = Unit

    override suspend fun success() = terminalOnce("SUCCESS", EventOutcome.COMPLETED)
    override suspend fun partialSuccess(summary: String?) = terminalOnce("PARTIAL_SUCCESS", EventOutcome.COMPLETED)
    override suspend fun failedFinal(reason: String, error: Throwable?) = terminalOnce("FAILED_FINAL", EventOutcome.FAILED_FINAL, EventSeverity.ERROR, exception = error)
    override suspend fun failedRetryable(reason: String, error: Throwable?) = terminalOnce("FAILED_RETRYABLE", EventOutcome.FAILED_RETRYABLE, EventSeverity.WARNING, exception = error)
    // DDL-F876-05: preserve caller-supplied reason code instead of always using CANCELLED_BY_SYSTEM
    override suspend fun cancelled(reason: String?) = terminalOnce(
        stage = "CANCELLED",
        outcome = EventOutcome.CANCELLED,
        reasonCode = reason?.let { runCatching { DiagnosticReasonCode.valueOf(it) }.getOrNull() }
            ?: DiagnosticReasonCode.CANCELLED_BY_SYSTEM
    )

    private fun operationTypeToPipeline(type: String): AppPipeline = when {
        type.contains("BACKUP") || type.contains("RESTORE") -> AppPipeline.BACKUP_RESTORE
        type.contains("BANK") -> AppPipeline.BANK
        type.contains("EMAIL") -> AppPipeline.EMAIL
        type.contains("EXPORT") || type.contains("IMPORT") -> AppPipeline.EXPORT_IMPORT
        else -> AppPipeline.BACKUP_RESTORE
    }
}
