package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticEvent(
    val eventId: String = CorrelationIds.newId(),
    val pipeline: AppPipeline,
    val stage: String,
    val outcome: EventOutcome,
    val severity: EventSeverity = EventSeverity.INFO,
    val reasonCode: DiagnosticReasonCode? = null,
    val entityType: String? = null,
    val entityId: Long? = null,
    val sourceType: String? = null,
    val sourceIdHash: String? = null,
    val correlationId: String = CorrelationIds.newId(),
    val causationId: String? = null,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    val exception: Throwable? = null,
    val elapsedMs: Long? = null,
    val isTerminal: Boolean = false
)

interface DiagnosticEventWriter {
    suspend fun emit(event: DiagnosticEvent)
}

@Singleton
class RoomDiagnosticEventWriter @Inject constructor(
    private val dao: PipelineDiagnosticEventDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier
) : DiagnosticEventWriter {

    override suspend fun emit(event: DiagnosticEvent) {
        // U-004: Room diagnostics are a regular DB write. When the barrier denies, this throws
        // DatabaseAccessBlockedException; CompositeDiagnosticEventWriter routes that to the safe sink.
        writeBarrier.checkWritesAllowed("DiagnosticEventWriter.emit")
        val safeMetadataJson = sanitizer.sanitizeJsonString(
            if (event.metadata.isEmpty()) null else event.metadata.toJson()
        )
        val row = PipelineDiagnosticEvent(
            pipeline = event.pipeline.name,
            stage = event.stage,
            outcome = event.outcome.name,
            timestamp = timeProvider.now(),
            eventId = event.eventId,
            correlationId = event.correlationId,
            causationId = event.causationId,
            severity = event.severity.name,
            reasonCode = event.reasonCode?.name,
            entityType = event.entityType,
            entityId = event.entityId,
            sourceType = event.sourceType,
            sourceIdHash = event.sourceIdHash,
            isTerminal = event.isTerminal,
            elapsedMs = event.elapsedMs,
            exceptionClass = event.exception?.javaClass?.simpleName,
            // DIAG-01: exception messages are never stored, only the class name
            exceptionMessage = event.exception?.let { DiagnosticThrowableSummary.className(it) },
            metadataJson = safeMetadataJson,
            metadataSchemaVersion = 1
        )
        writeBarrier.runWrite(DatabaseAccessOperation("DiagnosticEventWriter.emit")) {
            dao.insert(row)
        }
    }
}
