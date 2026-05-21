package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticSideEffectEventWriter @Inject constructor(
    private val writer: DiagnosticEventWriter,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider
) : SideEffectEventWriter {

    override suspend fun started(action: PostCommitAction) {
        emitEvent(
            action = action,
            outcome = EventOutcome.SIDE_EFFECT_STARTED,
            severity = EventSeverity.DEBUG,
            isTerminal = false
        )
    }

    override suspend fun completed(action: PostCommitAction) {
        emitEvent(
            action = action,
            outcome = EventOutcome.SIDE_EFFECT_COMPLETED,
            severity = EventSeverity.DEBUG,
            isTerminal = true
        )
    }

    override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) {
        val reasonCode = mapSkipReasonToDiagnosticReasonCode(reason)
        val metadata = SideEffectMetadataFactory.forAction(
            action = action,
            additional = SideEffectMetadataFactory.forSkip(reason)
        )
        emitEvent(
            action = action,
            outcome = EventOutcome.SKIPPED,
            severity = EventSeverity.INFO,
            reasonCode = reasonCode,
            isTerminal = true,
            metadataOverride = metadata
        )
    }

    override suspend fun failed(
        action: PostCommitAction,
        retryable: Boolean,
        reason: String,
        error: Throwable?
    ) {
        val outcome = if (retryable) EventOutcome.FAILED_RETRYABLE else EventOutcome.FAILED_FINAL
        val severity = if (retryable) EventSeverity.WARNING else EventSeverity.ERROR
        val reasonCode = when {
            error is kotlinx.coroutines.CancellationException -> DiagnosticReasonCode.CANCELLED_BY_SYSTEM
            else -> DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION
        }
        val exception = if (error is kotlinx.coroutines.CancellationException) null else error
        val metadata = SideEffectMetadataFactory.forAction(
            action = action,
            additional = SideEffectMetadataFactory.forFailure(reason, error?.javaClass?.name)
        )
        emitEvent(
            action = action,
            outcome = outcome,
            severity = severity,
            reasonCode = reasonCode,
            isTerminal = true,
            exception = exception,
            metadataOverride = metadata
        )
    }

    override suspend fun cancelled(action: PostCommitAction, reason: String?) {
        val additional = if (reason != null) mapOf("cancelReason" to reason) else emptyMap()
        val metadata = SideEffectMetadataFactory.forAction(
            action = action,
            additional = additional
        )
        emitEvent(
            action = action,
            outcome = EventOutcome.CANCELLED,
            severity = EventSeverity.WARNING,
            reasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM,
            isTerminal = true,
            metadataOverride = metadata
        )
    }

    private suspend fun emitEvent(
        action: PostCommitAction,
        outcome: EventOutcome,
        severity: EventSeverity,
        reasonCode: DiagnosticReasonCode? = null,
        isTerminal: Boolean,
        exception: Throwable? = null,
        metadataOverride: SafeEventMetadata? = null
    ) {
        val metadata = metadataOverride ?: SideEffectMetadataFactory.forAction(action)
        val event = DiagnosticEvent(
            pipeline = action.pipeline,
            stage = "SIDE_EFFECT",
            outcome = outcome,
            severity = severity,
            reasonCode = reasonCode,
            entityType = action.targetEntityType,
            entityId = action.targetEntityId,
            sourceType = action.source,
            correlationId = action.correlationId ?: CorrelationIds.newId(),
            causationId = action.causationId,
            metadata = metadata,
            exception = exception,
            isTerminal = isTerminal
        )
        writer.emit(event)
    }

    private fun mapSkipReasonToDiagnosticReasonCode(reason: SideEffectSkipReason): DiagnosticReasonCode? {
        return when (reason) {
            SideEffectSkipReason.PRIVACY_DENIED -> DiagnosticReasonCode.PRIVACY_DENIED
            SideEffectSkipReason.RESTORE_BLOCKED -> DiagnosticReasonCode.RESTORE_BLOCKED
            SideEffectSkipReason.DUPLICATE -> DiagnosticReasonCode.DUPLICATE
            SideEffectSkipReason.PERMISSION_DENIED -> DiagnosticReasonCode.PERMISSION_DENIED
            SideEffectSkipReason.MISSING_ENTITY,
            SideEffectSkipReason.ALREADY_PROCESSED,
            SideEffectSkipReason.DISABLED_BY_SETTINGS,
            SideEffectSkipReason.LOW_CONFIDENCE,
            SideEffectSkipReason.NO_WORK,
            SideEffectSkipReason.NOT_APPLICABLE -> null
        }
    }
}
