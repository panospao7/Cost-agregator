package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

interface OperationRunHandle {
    val runId: Long
    val correlationId: String

    suspend fun event(
        stage: String,
        outcome: EventOutcome,
        reasonCode: DiagnosticReasonCode? = null,
        severity: EventSeverity = EventSeverity.INFO,
        metadata: SafeEventMetadata = SafeEventMetadata.empty()
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
    override suspend fun event(stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?, severity: EventSeverity, metadata: SafeEventMetadata) = Unit
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
}

@Singleton
class RoomOperationRunRecorder @Inject constructor(
    private val runDao: OperationRunDao,
    private val eventDao: OperationRunEventDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider
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
                metadataJson = if (metadata.isEmpty()) null else metadata.toJson()
            )
        )
        return Handle(id, correlationId, operationType, runDao, eventDao, sanitizer, timeProvider)
    }

    private class Handle(
        override val runId: Long,
        override val correlationId: String,
        private val operationType: String,
        private val runDao: OperationRunDao,
        private val eventDao: OperationRunEventDao,
        private val sanitizer: EventMetadataSanitizer,
        private val timeProvider: TimeProvider
    ) : OperationRunHandle {

        private var processed = 0
        private var succeeded = 0
        private var failed = 0
        private var skipped = 0
        private var warnings = 0
        private var errors = 0

        override suspend fun event(
            stage: String,
            outcome: EventOutcome,
            reasonCode: DiagnosticReasonCode?,
            severity: EventSeverity,
            metadata: SafeEventMetadata
        ) {
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
                    metadataJson = if (metadata.isEmpty()) null else metadata.toJson()
                )
            )
        }

        override suspend fun increment(
            processed: Int, succeeded: Int, failed: Int,
            skipped: Int, warnings: Int, errors: Int
        ) {
            this.processed += processed
            this.succeeded += succeeded
            this.failed += failed
            this.skipped += skipped
            this.warnings += warnings
            this.errors += errors
        }

        override suspend fun success() = finalize("SUCCESS", null, null)
        override suspend fun partialSuccess(summary: String?) = finalize("PARTIAL_SUCCESS", summary, null)
        override suspend fun failedFinal(reason: String, error: Throwable?) = finalize("FAILED_FINAL", reason, error)
        override suspend fun failedRetryable(reason: String, error: Throwable?) = finalize("FAILED_RETRYABLE", reason, error)
        override suspend fun cancelled(reason: String?) = finalize("CANCELLED", reason, null)

        private suspend fun finalize(status: String, summary: String?, error: Throwable?) {
            val current = runDao.getById(runId) ?: return
            runDao.update(
                current.copy(
                    status = status,
                    finishedAt = timeProvider.now(),
                    rowsProcessed = processed,
                    rowsSucceeded = succeeded,
                    rowsFailed = failed,
                    rowsSkipped = skipped,
                    warningCount = warnings,
                    errorCount = errors,
                    errorSummary = summary ?: sanitizer.sanitizeExceptionMessage(error?.message)
                )
            )
        }
    }
}
