package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface OperationRunHandle {
    val runId: Long
    val correlationId: String
    val isTerminal: Boolean get() = false

    suspend fun event(
        stage: String,
        outcome: EventOutcome,
        reasonCode: DiagnosticReasonCode? = null,
        severity: EventSeverity = EventSeverity.INFO,
        metadata: SafeEventMetadata = SafeEventMetadata.empty(),
        entityType: String? = null,
        entityId: Long? = null,
        exception: Throwable? = null,
        isTerminal: Boolean = false
    )

    suspend fun increment(
        processed: Int = 0,
        succeeded: Int = 0,
        failed: Int = 0,
        skipped: Int = 0,
        warnings: Int = 0,
        errors: Int = 0
    )

    suspend fun success()
    suspend fun partialSuccess(summary: String?)
    suspend fun failedFinal(reason: String, error: Throwable? = null)
    suspend fun failedRetryable(reason: String, error: Throwable? = null)
    suspend fun cancelled(reason: String? = null)
}

/** No-op handle for tests and secondary constructors that don't need recording. */
object NoOpOperationRunHandle : OperationRunHandle {
    override val runId: Long = -1L
    override val correlationId: String = "noop"
    override suspend fun event(stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?, severity: EventSeverity, metadata: SafeEventMetadata, entityType: String?, entityId: Long?, exception: Throwable?, isTerminal: Boolean) = Unit
    override suspend fun increment(processed: Int, succeeded: Int, failed: Int, skipped: Int, warnings: Int, errors: Int) = Unit
    override suspend fun success() = Unit
    override suspend fun partialSuccess(summary: String?) = Unit
    override suspend fun failedFinal(reason: String, error: Throwable?) = Unit
    override suspend fun failedRetryable(reason: String, error: Throwable?) = Unit
    override suspend fun cancelled(reason: String?) = Unit
}

interface OperationRunRecorder {
    suspend fun start(
        operationType: String,
        actor: String? = null,
        metadata: SafeEventMetadata = SafeEventMetadata.empty()
    ): OperationRunHandle

    /** Convenience: start, run block, guarantee terminal status in finally. */
    suspend fun <T> runOperation(
        operationType: String,
        actor: String? = null,
        metadata: SafeEventMetadata = SafeEventMetadata.empty(),
        block: suspend (OperationRunHandle) -> T
    ): T

    /** Mark stale RUNNING operation runs as STALE_ABORTED. Call on app startup. */
    suspend fun recoverStaleRunningOperationRuns(staleAgeMs: Long = DEFAULT_STALE_OPERATION_AGE_MS)

    companion object {
        const val DEFAULT_STALE_OPERATION_AGE_MS = 6 * 60 * 60 * 1000L // 6 hours
    }
}

@Singleton
class RoomOperationRunRecorder @Inject constructor(
    private val runDao: OperationRunDao,
    private val eventDao: OperationRunEventDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) : OperationRunRecorder {

    override suspend fun start(
        operationType: String,
        actor: String?,
        metadata: SafeEventMetadata
    ): OperationRunHandle {
        val correlationId = CorrelationIds.newId()
        val now = timeProvider.now()
        val id = runDao.insert(
            OperationRun(
                correlationId = correlationId,
                operationType = operationType,
                status = "RUNNING",
                startedAt = now,
                actor = actor,
                metadataJson = sanitizer.sanitizeJsonString(if (metadata.isEmpty()) null else metadata.toJson())
            )
        )
        val handle = Handle(id, correlationId, operationType, runDao, eventDao, sanitizer, timeProvider, safeSink, restoreMaintenanceMode)
        // DDL-016-03: STARTED failure must not orphan the RUNNING row — best-effort only
        runCatching {
            handle.event(stage = "STARTED", outcome = EventOutcome.ATTEMPTED, severity = EventSeverity.INFO)
        }.onFailure { Timber.w(it, "Failed to write STARTED event for operation run $id") }
        return handle
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
            // DDL-A8-16: skip success if block already finalized the handle (bank blocked, etc.)
            if (!run.isTerminal) {
                withContext(NonCancellable) { runCatching { run.success() } }
            }
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { runCatching { run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) } }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { run.failedFinal(e.message ?: "Exception", e) } }
            throw e
        }
    }

    /** Recover stale RUNNING operation runs after process death. */
    override suspend fun recoverStaleRunningOperationRuns(staleAgeMs: Long) {
        val cutoff = timeProvider.now() - staleAgeMs
        val stale = runDao.getStaleRunning(cutoff)
        for (run in stale) {
            val updated = runDao.finalizeIfRunning(
                id = run.id,
                status = "STALE_ABORTED",
                finishedAt = timeProvider.now(),
                errorSummary = "Recovered stale RUNNING operation after process death"
            )
            if (updated > 0) {
                // DDL-A8-11: best-effort — event insert failure must not abort startup recovery
                runCatching {
                    eventDao.insert(OperationRunEvent(
                        operationRunId = run.id,
                        correlationId = run.correlationId,
                        operationType = run.operationType,
                        stage = "STALE_RECOVERY",
                        eventType = "${run.operationType}_STALE_RECOVERY",
                        outcome = EventOutcome.CANCELLED.name,
                        severity = EventSeverity.WARNING.name,
                        reasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name,
                        occurredAt = timeProvider.now(),
                        isTerminal = true
                    ))
                }.onFailure { Timber.w(it, "Failed to write stale recovery event for run ${run.id}") }
            }
        }
        if (stale.isNotEmpty()) Timber.w("Recovered ${stale.size} stale RUNNING operation run(s) as STALE_ABORTED")
    }

    private class Handle(
        override val runId: Long,
        override val correlationId: String,
        private val operationType: String,
        private val runDao: OperationRunDao,
        private val eventDao: OperationRunEventDao,
        private val sanitizer: EventMetadataSanitizer,
        private val timeProvider: TimeProvider,
        private val safeSink: MaintenanceSafeDiagnosticSink,
        private val restoreMaintenanceMode: RestoreMaintenanceMode
    ) : OperationRunHandle {

        override suspend fun event(
            stage: String,
            outcome: EventOutcome,
            reasonCode: DiagnosticReasonCode?,
            severity: EventSeverity,
            metadata: SafeEventMetadata,
            entityType: String?,
            entityId: Long?,
            exception: Throwable?,
            isTerminal: Boolean
        ) {
            // DDL-016-02: event() is best-effort — failure must not fail business operation
            runCatching {
                eventDao.insert(
                    OperationRunEvent(
                        operationRunId = runId,
                        correlationId = correlationId,
                        operationType = operationType,
                        stage = stage,
                        eventType = "${operationType}_${stage}".uppercase(),
                        outcome = outcome.name,
                        severity = severity.name,
                        reasonCode = reasonCode?.name,
                        occurredAt = timeProvider.now(),
                        entityType = entityType,
                        entityId = entityId,
                        metadataJson = sanitizer.sanitizeJsonString(if (metadata.isEmpty()) null else metadata.toJson()),
                        exceptionClass = exception?.javaClass?.simpleName,
                        exceptionMessage = sanitizer.sanitizeExceptionMessage(exception?.message),
                        isTerminal = isTerminal
                    )
                )
            }.onFailure { error ->
                Timber.w(error, "Failed to write operation event (stage=$stage, operation=$operationType)")
                runCatching {
                    safeSink.recordDiagnosticEvent(
                        event = DiagnosticEvent(
                            pipeline = AppPipeline.BACKUP_RESTORE,
                            stage = "operation_event_write_failed",
                            outcome = EventOutcome.SIDE_EFFECT_FAILED,
                            severity = EventSeverity.WARNING,
                            correlationId = correlationId,
                            metadata = SafeEventMetadata.builder()
                                .put("operationType", operationType)
                                .put("failedStage", stage)
                                .build(),
                            exception = error,
                            isTerminal = false
                        ),
                        mode = restoreMaintenanceMode.currentMode(),
                        writeFailure = error
                    )
                }
            }
        }

        override suspend fun increment(
            processed: Int, succeeded: Int, failed: Int,
            skipped: Int, warnings: Int, errors: Int
        ) {
            // DDL-A8-10: best-effort — increment failure must not fail bank sync or other operations
            runCatching {
                runDao.incrementCounters(runId, processed, succeeded, failed, skipped, warnings, errors)
            }.onFailure { Timber.w(it, "Failed to persist operation counters for run $runId") }
        }

        override suspend fun success() = finalizeNonCancellable("SUCCESS", null, null)
        override suspend fun partialSuccess(summary: String?) = finalizeNonCancellable("PARTIAL_SUCCESS", summary, null)
        override suspend fun failedFinal(reason: String, error: Throwable?) = finalizeNonCancellable("FAILED_FINAL", reason, error)
        override suspend fun failedRetryable(reason: String, error: Throwable?) = finalizeNonCancellable("FAILED_RETRYABLE", reason, error)
        override suspend fun cancelled(reason: String?) = finalizeNonCancellable("CANCELLED", reason, null)

        private suspend fun finalizeNonCancellable(status: String, summary: String?, error: Throwable?) {
            withContext(NonCancellable) {
                // DDL-81-07: best-effort — diagnostic finalization must not fail business success
                runCatching {
                    val updated = runDao.finalizeIfRunning(
                        id = runId,
                        status = status,
                        finishedAt = timeProvider.now(),
                        errorSummary = summary ?: sanitizer.sanitizeExceptionMessage(error?.message)
                    )
                    if (updated > 0) {
                        runCatching {
                            event(
                                stage = status,
                                outcome = statusToOutcome(status),
                                severity = if (status.startsWith("FAILED")) EventSeverity.ERROR else EventSeverity.INFO,
                                isTerminal = true
                            )
                        }.onFailure { Timber.w(it, "Failed to write terminal operation event for run $runId") }
                    }
                }.onFailure { Timber.w(it, "Failed to finalize operation run $runId") }
            }
        }

        private fun statusToOutcome(status: String): EventOutcome = when (status) {
            "SUCCESS" -> EventOutcome.COMPLETED
            "PARTIAL_SUCCESS" -> EventOutcome.COMPLETED
            "FAILED_FINAL" -> EventOutcome.FAILED_FINAL
            "FAILED_RETRYABLE" -> EventOutcome.FAILED_RETRYABLE
            "CANCELLED" -> EventOutcome.CANCELLED
            else -> EventOutcome.FAILED_FINAL
        }
    }

    companion object {
        const val STALE_THRESHOLD_MS = 4 * 60 * 60 * 1000L
    }
}
