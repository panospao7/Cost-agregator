package com.yourname.expensetracker.domain.diagnostics

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class SideEffectContext(
    val pipeline: AppPipeline,
    val correlationId: String,
    val causationId: String? = null,
    val entityType: String,
    val entityId: Long?,
    val source: String? = null,
    val actor: String = "system"
)

@Singleton
class SideEffectDiagnosticRecorder @Inject constructor(
    private val writer: DiagnosticEventWriter
) {
    /**
     * Runs [block] wrapped with SIDE_EFFECT_STARTED / SIDE_EFFECT_COMPLETED / SIDE_EFFECT_FAILED.
     * Cancellation is finalized NonCancellable before rethrowing.
     * Non-cancellation exceptions are swallowed (best-effort side effects).
     * Returns null on non-cancellation failure.
     */
    suspend fun <T> runSideEffect(
        context: SideEffectContext,
        name: String,
        metadata: SafeEventMetadata = SafeEventMetadata.empty(),
        block: suspend () -> T
    ): T? {
        emit(context, name, EventOutcome.SIDE_EFFECT_STARTED, EventSeverity.DEBUG, metadata)
        return try {
            val result = block()
            emit(context, name, EventOutcome.SIDE_EFFECT_COMPLETED, EventSeverity.DEBUG, metadata)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                emit(context, name, EventOutcome.CANCELLED, EventSeverity.WARNING,
                    metadata, reasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM, isTerminal = true)
            }
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side effect '$name' failed for ${context.entityType}:${context.entityId}")
            withContext(NonCancellable) {
                emit(context, name, EventOutcome.SIDE_EFFECT_FAILED, EventSeverity.WARNING,
                    metadata, reasonCode = DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
                    exception = e, isTerminal = false)
            }
            null
        }
    }

    private suspend fun emit(
        context: SideEffectContext,
        name: String,
        outcome: EventOutcome,
        severity: EventSeverity,
        metadata: SafeEventMetadata,
        reasonCode: DiagnosticReasonCode? = null,
        exception: Throwable? = null,
        isTerminal: Boolean = false
    ) {
        try {
            writer.emit(DiagnosticEvent(
                pipeline = context.pipeline,
                stage = "side_effect",
                outcome = outcome,
                severity = severity,
                reasonCode = reasonCode,
                entityType = context.entityType,
                entityId = context.entityId,
                correlationId = context.correlationId,
                causationId = context.causationId,
                metadata = SafeEventMetadata.builder()
                    .put("sideEffect", name)
                    .put("source", context.source ?: "")
                    .build(),
                exception = exception,
                isTerminal = isTerminal
            ))
        } catch (_: Exception) {}
    }
}
