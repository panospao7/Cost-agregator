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
            return safeHandle(operationType)
        }
        return try {
            writeBarrier.checkWritesAllowed("OperationRunRecorder.start")
            roomRecorder.start(operationType, actor, metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "OperationRunRecorder: falling back to safe handle for $operationType")
            safeHandle(operationType)
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

    private fun safeHandle(operationType: String): OperationRunHandle =
        SafeSinkOperationRunHandle(
            correlationId = CorrelationIds.newId(),
            operationType = operationType,
            safeSink = safeSink,
            restoreMaintenanceMode = restoreMaintenanceMode,
            timeProvider = timeProvider
        )
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

    override suspend fun event(
        stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?,
        severity: EventSeverity, metadata: SafeEventMetadata,
        entityType: String?, entityId: Long?, exception: Throwable?, isTerminal: Boolean
    ) {
        try {
            safeSink.recordDiagnosticEvent(
                event = DiagnosticEvent(
                    pipeline = operationTypeToPipeline(operationType),
                    stage = stage,
                    outcome = outcome,
                    severity = severity,
                    reasonCode = reasonCode,
                    entityType = entityType,
                    entityId = entityId,
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
    override suspend fun success() = event("SUCCESS", EventOutcome.COMPLETED, isTerminal = true)
    override suspend fun partialSuccess(summary: String?) = event("PARTIAL_SUCCESS", EventOutcome.COMPLETED, isTerminal = true)
    override suspend fun failedFinal(reason: String, error: Throwable?) = event("FAILED_FINAL", EventOutcome.FAILED_FINAL, severity = EventSeverity.ERROR, exception = error, isTerminal = true)
    override suspend fun failedRetryable(reason: String, error: Throwable?) = event("FAILED_RETRYABLE", EventOutcome.FAILED_RETRYABLE, severity = EventSeverity.WARNING, exception = error, isTerminal = true)
    override suspend fun cancelled(reason: String?) = event("CANCELLED", EventOutcome.CANCELLED, reasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM, isTerminal = true)

    private fun operationTypeToPipeline(type: String): AppPipeline = when {
        type.contains("BACKUP") || type.contains("RESTORE") -> AppPipeline.BACKUP_RESTORE
        type.contains("BANK") -> AppPipeline.BANK
        type.contains("EMAIL") -> AppPipeline.EMAIL
        type.contains("EXPORT") || type.contains("IMPORT") -> AppPipeline.EXPORT_IMPORT
        else -> AppPipeline.BACKUP_RESTORE
    }
}
